package com.rensilver.ativaflow_api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class, ActivationRequestClaimService.class})
@DataJpaTest(properties = {"app.worker.lease-duration=45s",
		"spring.datasource.hikari.connection-init-sql=SET statement_timeout = '5s'"}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestClaimTests {

	@Autowired
	private ActivationRequestClaimService service;

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@Test
	void acquiresPendingRequestWithDatabaseTimeAndReturnsCommittedOwnership() {
		UUID id = insert("PENDING", 0, null, false, null);
		Instant before = databaseTime();

		var result = service.claimNext("worker-a");
		assertThat(result).isPresent();
		var claim = result.orElseThrow();

		Instant after = databaseTime();
		assertThat(claim.id()).isEqualTo(id);
		assertThat(claim.version()).isEqualTo(1);
		assertThat(claim.claimedBy()).isEqualTo("worker-a");
		assertThat(claim.leaseUntil()).isBetween(before.plusSeconds(45), after.plusSeconds(45));
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.getVersion()).isEqualTo(claim.version());
		assertThat(stored.getClaimedBy()).isEqualTo(claim.claimedBy());
		assertThat(stored.getLeaseUntil()).isEqualTo(claim.leaseUntil());
		assertThat(stored.getUpdatedAt()).isBetween(before, after);
		assertThat(stored.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
		assertThat(stored.getExternalId()).isEqualTo("EXT-" + id);
		assertThat(stored.getConsumerId()).isEqualTo("consumer-a");
		assertThat(stored.getAttemptCount()).isZero();
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.isReconciliationRequired()).isFalse();
		assertThat(service.claimNext("worker-b")).isEmpty();
	}

	@Test
	void preservesSafeRetryScheduleCounterAndLastErrorWhenReclaimingExpiredLease() {
		UUID id = insert("PROCESSING", 2, -60, false, -1);
		jdbc.update("UPDATE activation_requests SET version = 7, last_error_code = 'PROVIDER_UNAVAILABLE' WHERE id = ?", id);
		Instant scheduledAt = repository.findById(id).orElseThrow().getNextAttemptAt();

		var claim = service.claimNext("replacement-worker").orElseThrow();

		var stored = repository.findById(id).orElseThrow();
		assertThat(claim.id()).isEqualTo(id);
		assertThat(claim.version()).isEqualTo(8);
		assertThat(stored.getAttemptCount()).isEqualTo(2);
		assertThat(stored.getNextAttemptAt()).isEqualTo(scheduledAt);
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(stored.isReconciliationRequired()).isFalse();
		assertThat(stored.getClaimedBy()).isEqualTo("replacement-worker");
	}

	@ParameterizedTest
	@CsvSource({
			"PENDING,    0,    , false,    , true",
			"PROCESSING, 0,    , false, -60, true",
			"PROCESSING, 0,    , false,    , true",
			"PROCESSING, 1, -60, false,    , true",
			"PROCESSING, 2, -60, false, -60, true",
			"PROCESSING, 1,  60, false,    , false",
			"PROCESSING, 1,    , false, -60, false",
			"PROCESSING, 3, -60, false, -60, false",
			"PROCESSING, 0,    , true,  -60, false",
			"PROCESSING, 2, -60, true,  -60, false",
			"PROCESSING, 0,    , false,  60, false",
			"PROCESSING, 2, -60, false,  60, false",
			"PENDING,    0,    , false,  60, false",
			"PENDING,    1,    , false,    , false",
			"PENDING,    0, -60, false,    , false",
			"PENDING,    0,    , true,     , false",
			"COMPLETED,  0,    , false, -60, false",
			"FAILED,     0,    , false, -60, false"
	})
	void onlyClaimsEligibleWork(String status, int attempts, Integer nextDelay, boolean reconciliation,
			Integer leaseDelay, boolean eligible) {
		UUID id = insert(status, attempts, nextDelay, reconciliation, leaseDelay);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

		var claim = service.claimNext("worker-a");

		assertThat(claim.isPresent()).isEqualTo(eligible);
		if (eligible) {
			assertThat(claim.orElseThrow().id()).isEqualTo(id);
		} else {
			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
		}
	}

	@Test
	void returnsEmptyWhenThereIsNoWork() {
		assertThat(service.claimNext("worker-a")).isEmpty();
	}

	@Test
	void concurrentWorkersAcquireTheSameRequestOnlyOnce() throws Exception {
		UUID id = insert("PENDING", 0, null, false, null);
		int workers = 8;
		var ready = new CountDownLatch(workers);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var results = new ArrayList<Future<Optional<ClaimedActivationRequest>>>();
			for (int i = 0; i < workers; i++) {
				String workerId = "worker-" + i;
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return service.claimNext(workerId);
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var winners = new ArrayList<ClaimedActivationRequest>();
			for (var result : results) {
				result.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
			}
			assertThat(winners).hasSize(1);
			var stored = repository.findById(id).orElseThrow();
			assertThat(stored.getClaimedBy()).isEqualTo(winners.getFirst().claimedBy());
			assertThat(stored.getVersion()).isEqualTo(1);
			assertThat(stored.getAttemptCount()).isZero();
		} finally {
			start.countDown();
		}
	}

	@Test
	void skipsLockedRequestAndAcquiresAnotherWithoutWaitingForItsRelease() {
		UUID lockedId = insert("PENDING", 0, null, false, null);
		UUID availableId = insert("PENDING", 0, null, false, null);
		jdbc.update("UPDATE activation_requests SET created_at = '2025-01-01T00:00:00Z' WHERE id = ?", lockedId);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.queryForObject("SELECT id FROM activation_requests WHERE id = ? FOR UPDATE", UUID.class, lockedId);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				var result = executor.submit(() -> service.claimNext("worker-b"));
				assertThat(result.get(5, TimeUnit.SECONDS).orElseThrow().id()).isEqualTo(availableId);
				assertThat(executor.submit(() -> service.claimNext("worker-c")).get(5, TimeUnit.SECONDS)).isEmpty();
			} catch (Exception exception) {
				throw new AssertionError("Claim must finish while the other row is still locked", exception);
			}
		});
		assertThat(service.claimNext("worker-a").orElseThrow().id()).isEqualTo(lockedId);
	}

	@Test
	void commitsClaimBeforeReturningEvenIfCallerRollsBack() {
		UUID id = insert("PENDING", 0, null, false, null);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(service.claimNext("worker-a").orElseThrow().id()).isEqualTo(id);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				assertThat(executor.submit(() -> jdbc.queryForObject(
						"SELECT claimed_by FROM activation_requests WHERE id = ?", String.class, id))
						.get(5, TimeUnit.SECONDS)).isEqualTo("worker-a");
			} catch (Exception exception) {
				throw new AssertionError("Ownership must already be committed when claim returns", exception);
			}
			transaction.setRollbackOnly();
		});
		assertThat(service.claimNext("worker-b")).isEmpty();
		assertThat(repository.findById(id).orElseThrow().getClaimedBy()).isEqualTo("worker-a");
	}

	@Test
	void acceptsLeaseAndRetryExactlyAtDatabaseTransactionTime() {
		UUID id = insert("PROCESSING", 2, null, false, null);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.update("""
					UPDATE activation_requests SET next_attempt_at = CURRENT_TIMESTAMP,
					    lease_until = CURRENT_TIMESTAMP, claimed_by = 'old-worker' WHERE id = ?
					""", id);
			assertThat(repository.findNextClaimCandidate()).isPresent();
			assertThat(repository.tryClaim(id, 0, "worker-a", 45000)).isEqualTo(1);
		});
	}

	@Test
	void conditionalUpdateReturnsZeroAndPreservesRowForStaleVersion() {
		UUID id = insert("PENDING", 0, null, false, null);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
				assertThat(repository.tryClaim(id, 1, "worker-a", 45000)).isZero());

		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
	}

	@Test
	void conditionalUpdateReturnsZeroForWorkThatIsNoLongerEligible() {
		UUID id = insert("PROCESSING", 2, -60, true, -60);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
				assertThat(repository.tryClaim(id, 0, "worker-a", 45000)).isZero());
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
	}

	@Test
	void rejectsManagedUpdateLoadedBeforeNativeClaim() {
		UUID id = insert("PENDING", 0, null, false, null);
		try (var staleContext = entityManagerFactory.createEntityManager()) {
			staleContext.getTransaction().begin();
			try {
				var stale = staleContext.find(ActivationRequest.class, id);
				assertThat(service.claimNext("worker-a")).isPresent();
				var claimedRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

				stale.startProcessing();

				assertThatThrownBy(staleContext::flush).isInstanceOf(OptimisticLockException.class);
				staleContext.getTransaction().rollback();
				assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id))
						.isEqualTo(claimedRow);
			} finally {
				if (staleContext.getTransaction().isActive()) {
					staleContext.getTransaction().rollback();
				}
			}
		}
	}

	@Test
	void nativeClaimDiscardsManagedCandidateBeforeLaterFlush() {
		UUID id = insert("PENDING", 0, null, false, null);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			var candidate = repository.findNextClaimCandidate().orElseThrow();
			assertThat(repository.tryClaim(id, candidate.getVersion(), "worker-a", 45000)).isEqualTo(1);
			var claimedRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

			candidate.startProcessing();
			repository.flush();

			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(claimedRow);
			var reloaded = repository.findById(id).orElseThrow();
			assertThat(reloaded.getVersion()).isEqualTo(1);
			assertThat(reloaded.getClaimedBy()).isEqualTo("worker-a");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"worker-a", "worker-b"})
	void rejectsDetachedResultAfterReclaimEvenWhenWorkerIdIsReused(String replacementWorker) {
		UUID id = insert("PENDING", 0, null, false, null);
		var original = service.claimNext("worker-a").orElseThrow();
		var stale = repository.findById(id).orElseThrow();
		// Simula a passagem do tempo sem mudar a versão: a retomada deve invalidar a posse anterior.
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
		var replacement = service.claimNext(replacementWorker).orElseThrow();
		var claimedRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		assertThat(replacement.version()).isEqualTo(original.version() + 1);

		stale.complete();

		assertThatThrownBy(() -> repository.saveAndFlush(stale))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(claimedRow);
	}

	@Test
	void staleClaimCannotAcquireAnEligibleRowAfterLeaseExpiresAgain() {
		UUID id = insert("PENDING", 0, null, false, null);
		var original = service.claimNext("worker-a").orElseThrow();
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
		var replacement = service.claimNext("worker-a").orElseThrow();
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
				assertThat(repository.tryClaim(id, original.version(), "worker-a", 45000)).isZero());

		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
				assertThat(repository.tryClaim(id, replacement.version(), "worker-a", 45000)).isEqualTo(1));
		assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(replacement.version() + 1);
	}

	private UUID insert(String status, int attempts, Integer nextDelay, boolean reconciliation, Integer leaseDelay) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, attempt_count,
				     next_attempt_at, reconciliation_required, lease_until, claimed_by, created_at, updated_at)
				VALUES (?, ?, 'CUSTOMER-1', 'PRODUCT-1', 'consumer-a', ?, ?,
				        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), ?,
				        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), ?,
				        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
				""", id, "EXT-" + id, status, attempts, nextDelay, reconciliation, leaseDelay,
				leaseDelay == null ? null : "old-worker");
		return id;
	}

	private Instant databaseTime() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class).toInstant();
	}
}
