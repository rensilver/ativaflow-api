package com.rensilver.ativaflow_api.service;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.support.TestcontainersConfiguration;
import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class,
		ActivationRequestClaimService.class, ActivationRequestLeaseRecoveryService.class,
		ActivationRequestAttemptService.class})
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestAttemptTests {

	@Autowired
	private ActivationRequestAttemptService attempts;

	@Autowired
	private ActivationRequestClaimService claims;

	@Autowired
	private ActivationRequestLeaseRecoveryService recovery;

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@Test
	void preparesFirstAttemptAtomicallyAndDoesNotAuthorizeItTwice() {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();
		jdbc.update("UPDATE activation_requests SET last_error_code = 'PREVIOUS_ERROR' WHERE id = ?", id);
		var before = repository.findById(id).orElseThrow();

		var preparation = attempts.prepareAttempt(claim);
		assertThat(preparation).isPresent();
		var prepared = preparation.orElseThrow();

		assertThat(prepared.id()).isEqualTo(id);
		assertThat(prepared.version()).isEqualTo(claim.version() + 1);
		assertThat(prepared.claimedBy()).isEqualTo("worker-a");
		assertThat(prepared.leaseUntil()).isEqualTo(claim.leaseUntil());
		assertThat(prepared.attemptCount()).isEqualTo(1);
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getLastErrorCode()).isEqualTo("PREVIOUS_ERROR");
		assertThat(stored.getCreatedAt()).isEqualTo(before.getCreatedAt());
		assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(before.getUpdatedAt());
		assertThat(stored.getVersion()).isEqualTo(prepared.version());
		assertThat(stored.getClaimedBy()).isEqualTo(prepared.claimedBy());
		assertThat(stored.getLeaseUntil()).isEqualTo(prepared.leaseUntil());
		var snapshot = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		assertThat(attempts.prepareAttempt(claim)).isEmpty();
		var current = new ClaimedActivationRequest(id, prepared.version(), prepared.claimedBy(), prepared.leaseUntil());
		assertThat(attempts.prepareAttempt(current)).isEmpty();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(snapshot);
	}

	@Test
	void restartBeforePreparationAllowsResumeButRestartAfterPreparationRequiresReconciliation() {
		UUID id = insertPending();
		var abandoned = claims.claimNext("worker-a").orElseThrow();
		expire(id);
		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);
		var resumed = claims.claimNext("worker-a").orElseThrow();
		assertThat(attempts.prepareAttempt(abandoned)).isEmpty();
		assertThat(attempts.prepareAttempt(resumed)).isPresent();

		// Mesmo sem HTTP, uma queda após o commit não prova ausência de ativação externa.
		expire(id);
		assertThat(recovery.recoverExpiredLeases(10)).isZero();

		assertThat(claims.claimNext("worker-b")).isEmpty();
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.getLeaseUntil()).isNotNull();
		assertThat(stored.getClaimedBy()).isEqualTo("worker-a");
	}

	@ParameterizedTest
	@CsvSource({"1, 2", "2, 3"})
	void preparationConsumesDueSafeRetryAndMarksItIndeterminate(int previousAttempts, int expectedAttempts) {
		UUID id = insertPending();
		jdbc.update("""
				UPDATE activation_requests SET status = 'PROCESSING', attempt_count = ?,
				    next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute', last_error_code = 'PROVIDER_UNAVAILABLE'
				WHERE id = ?
				""", previousAttempts, id);
		var claim = claims.claimNext("worker-a").orElseThrow();

		var preparation = attempts.prepareAttempt(claim);
		assertThat(preparation).isPresent();
		assertThat(preparation.orElseThrow().attemptCount()).isEqualTo(expectedAttempts);

		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		expire(id);
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({
			"PROCESSING, 0, false,     , -1, worker-a, 1",
			"PROCESSING, 0, false,     ,   , worker-a, 1",
			"PROCESSING, 0, false,     , 60, worker-b, 1",
			"PROCESSING, 0, false,     , 60, worker-a, 2",
			"PROCESSING, 0, true,      , 60, worker-a, 1",
			"PROCESSING, 1, false,     , 60, worker-a, 1",
			"PROCESSING, 1, false,   60, 60, worker-a, 1",
			"PROCESSING, 3, false,  -60, 60, worker-a, 1",
			"PENDING,    0, false,     , 60, worker-a, 1",
			"COMPLETED,  0, false,     , 60, worker-a, 1",
			"FAILED,     0, false,     , 60, worker-a, 1"
	})
	void rejectsInvalidOwnershipOrUnsafeWorkWithoutChangingAnyColumn(String status, int count, boolean reconciliation,
			Integer nextDelay, Integer leaseDelay, String owner, long version) {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();
		jdbc.update("""
				UPDATE activation_requests SET status = ?, attempt_count = ?, reconciliation_required = ?,
				    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
				    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), claimed_by = ?, version = ?
				WHERE id = ?
				""", status, count, reconciliation, nextDelay, leaseDelay, owner, version, id);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

		assertThat(attempts.prepareAttempt(claim)).isEmpty();

		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
	}

	@Test
	void commitsPreparationEvenIfCallerRollsBack() {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(attempts.prepareAttempt(claim)).isPresent();
			transaction.setRollbackOnly();
		});

		assertThat(repository.findById(id).orElseThrow().isReconciliationRequired()).isTrue();
		assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
	}

	@Test
	void concurrentPreparationsWithTheSameClaimAuthorizeOnlyOneAttempt() throws Exception {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();
		var ready = new CountDownLatch(4);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var results = new ArrayList<Future<Boolean>>();
			for (int i = 0; i < 4; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return attempts.prepareAttempt(claim).isPresent();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int authorized = 0;
			for (var result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					authorized++;
				}
			}
			assertThat(authorized).isEqualTo(1);
		} finally {
			start.countDown();
		}
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getVersion()).isEqualTo(claim.version() + 1);
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.isReconciliationRequired()).isTrue();
	}

	@Test
	void rejectsLeaseExactlyAtDatabaseTimeButAcceptsRetryDueExactlyNow() {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP WHERE id = ?", id);
			assertThat(repository.tryPrepareAttempt(id, claim.version(), "worker-a", 3)).isZero();
			jdbc.update("""
					UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 minute',
					    next_attempt_at = CURRENT_TIMESTAMP, attempt_count = 2 WHERE id = ?
					""", id);
			var stale = repository.findById(id).orElseThrow();

			assertThat(repository.tryPrepareAttempt(id, claim.version(), "worker-a", 3)).isEqualTo(1);
			stale.complete();
			repository.flush();

			var stored = repository.findById(id).orElseThrow();
			assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
			assertThat(stored.getAttemptCount()).isEqualTo(3);
			assertThat(stored.getNextAttemptAt()).isNull();
			assertThat(stored.isReconciliationRequired()).isTrue();
		});
	}

	private UUID insertPending() {
		return repository.saveAndFlush(ActivationRequest.create("EXT-" + UUID.randomUUID(),
				"CUSTOMER-1", "PRODUCT-1", "consumer-a")).getId();
	}

	private void expire(UUID id) {
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
	}
}
