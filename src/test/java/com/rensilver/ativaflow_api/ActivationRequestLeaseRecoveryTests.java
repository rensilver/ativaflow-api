package com.rensilver.ativaflow_api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestLeaseRecoveryService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class,
		ActivationRequestClaimService.class, ActivationRequestLeaseRecoveryService.class})
@DataJpaTest(properties = "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '5s'", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestLeaseRecoveryTests {

	@Autowired
	private ActivationRequestLeaseRecoveryService recovery;

	@Autowired
	private ActivationRequestClaimService claims;

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
	void releasesAbandonedClaimWithoutConsumingAttemptAndAllowsAnotherWorkerToResume() {
		UUID id = insert("PROCESSING", 0, false, -1);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		Instant start = databaseTime();

		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);

		var released = repository.findById(id).orElseThrow();
		assertThat(released.getClaimedBy()).isNull();
		assertThat(released.getLeaseUntil()).isNull();
		assertThat(released.getVersion()).isEqualTo(1);
		assertThat(released.getUpdatedAt()).isBetween(start, databaseTime());
		var after = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		for (String changed : new String[]{"claimed_by", "lease_until", "version", "updated_at"}) {
			before.remove(changed);
			after.remove(changed);
		}
		assertThat(after).isEqualTo(before);
		assertThat(recovery.recoverExpiredLeases(10)).isZero();
		assertThat(claims.claimNext("replacement").orElseThrow().id()).isEqualTo(id);
		assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isZero();
	}

	@ParameterizedTest
	@CsvSource({"1, true", "3, true", "1, false"})
	void releasingLeaseNeverAuthorizesRetryOfUnknownExternalResult(int attempts, boolean reconciliation) {
		UUID id = insert("PROCESSING", attempts, reconciliation, -1);

		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);

		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.getAttemptCount()).isEqualTo(attempts);
		assertThat(stored.isReconciliationRequired()).isEqualTo(reconciliation);
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(claims.claimNext("replacement")).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"PROCESSING, 60", "PROCESSING,", "PENDING, -1", "COMPLETED, -1", "FAILED, -1"})
	void doesNotReleaseLiveLeasesUnleasedWorkOrOtherStatuses(String status, Integer leaseDelay) {
		UUID id = insert(status, 0, false, leaseDelay);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);

		assertThat(recovery.recoverExpiredLeases(10)).isZero();

		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(before);
	}

	@Test
	void preservesSafeRetryScheduleWhenReleasingAbandonedRetryClaim() {
		UUID id = insert("PROCESSING", 2, false, -1);
		jdbc.update("UPDATE activation_requests SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?", id);
		Instant scheduledAt = repository.findById(id).orElseThrow().getNextAttemptAt();

		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);

		assertThat(claims.claimNext("replacement").orElseThrow().id()).isEqualTo(id);
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getAttemptCount()).isEqualTo(2);
		assertThat(stored.getNextAttemptAt()).isEqualTo(scheduledAt);
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
	}

	@Test
	void rejectsNonPositiveBatchSize() {
		assertThatThrownBy(() -> recovery.recoverExpiredLeases(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> recovery.recoverExpiredLeases(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recoversAtMostTheRequestedBatchStartingWithTheOldestLease() {
		UUID first = insert("PROCESSING", 0, false, -30);
		UUID second = insert("PROCESSING", 1, true, -20);
		UUID third = insert("PROCESSING", 0, false, -10);

		assertThat(recovery.recoverExpiredLeases(2)).isEqualTo(2);

		assertThat(repository.findById(first).orElseThrow().getClaimedBy()).isNull();
		assertThat(repository.findById(second).orElseThrow().getClaimedBy()).isNull();
		assertThat(repository.findById(third).orElseThrow().getClaimedBy()).isEqualTo("old-worker");
		assertThat(recovery.recoverExpiredLeases(2)).isEqualTo(1);
		assertThat(recovery.recoverExpiredLeases(2)).isZero();
	}

	@Test
	void skipsLockedLeaseWhileAnotherRecovererStillOwnsItsTransaction() {
		UUID locked = insert("PROCESSING", 0, false, -30);
		UUID available = insert("PROCESSING", 1, true, -20);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.queryForObject("SELECT id FROM activation_requests WHERE id = ? FOR UPDATE", UUID.class, locked);
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				assertThat(executor.submit(() -> recovery.recoverExpiredLeases(10)).get(5, TimeUnit.SECONDS)).isEqualTo(1);
				assertThat(repository.findById(available).orElseThrow().getLeaseUntil()).isNull();
				assertThat(executor.submit(() -> recovery.recoverExpiredLeases(10)).get(5, TimeUnit.SECONDS)).isZero();
			} catch (Exception exception) {
				throw new AssertionError("Recovery must skip a locked lease without waiting", exception);
			}
		});
		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);
	}

	@Test
	void concurrentRecoverersReleaseEachLeaseOnlyOnce() throws Exception {
		var ids = new ArrayList<UUID>();
		for (int i = 0; i < 12; i++) {
			ids.add(insert("PROCESSING", i % 2, i % 2 == 1, -1));
		}
		var ready = new CountDownLatch(4);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var results = new ArrayList<Future<Integer>>();
			for (int i = 0; i < 4; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return recovery.recoverExpiredLeases(12);
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int released = 0;
			for (var result : results) {
				released += result.get(10, TimeUnit.SECONDS);
			}
			assertThat(released).isEqualTo(12);
		} finally {
			start.countDown();
		}
		for (UUID id : ids) {
			var stored = repository.findById(id).orElseThrow();
			assertThat(stored.getVersion()).isEqualTo(1);
			assertThat(stored.getClaimedBy()).isNull();
			assertThat(stored.getLeaseUntil()).isNull();
		}
	}

	@Test
	void includesLeaseExactlyAtDatabaseTimeAndDiscardsManagedSnapshot() {
		UUID id = insert("PROCESSING", 0, false, -1);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP WHERE id = ?", id);
			var stale = repository.findById(id).orElseThrow();

			assertThat(repository.releaseExpiredLeases(10)).isEqualTo(1);
			stale.complete();
			repository.flush();

			var reloaded = repository.findById(id).orElseThrow();
			assertThat(reloaded.getStatus().name()).isEqualTo("PROCESSING");
			assertThat(reloaded.getVersion()).isEqualTo(1);
			assertThat(reloaded.getLeaseUntil()).isNull();
		});
	}

	@Test
	void recoveryIsCommittedEvenWhenCallerRollsBack() {
		UUID id = insert("PROCESSING", 0, false, -1);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);
			transaction.setRollbackOnly();
		});

		assertThat(repository.findById(id).orElseThrow().getLeaseUntil()).isNull();
		assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1);
	}

	private UUID insert(String status, int attempts, boolean reconciliation, Integer leaseDelay) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, attempt_count,
				     reconciliation_required, lease_until, claimed_by, last_error_code, created_at, updated_at)
				VALUES (?, ?, 'CUSTOMER-1', 'PRODUCT-1', 'consumer-a', ?, ?, ?,
				        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), ?, 'PROVIDER_TIMEOUT',
				        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
				""", id, "EXT-" + id, status, attempts, reconciliation, leaseDelay,
				leaseDelay == null ? null : "old-worker");
		return id;
	}

	private Instant databaseTime() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class).toInstant();
	}
}
