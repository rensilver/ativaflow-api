package com.rensilver.ativaflow_api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestLeaseRecoveryService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeService;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class, ActivationRequestClaimService.class,
		ActivationRequestAttemptService.class, ActivationRequestLeaseRecoveryService.class,
		ActivationRequestOutcomeService.class, ActivationRetryPolicy.class})
@DataJpaTest(showSql = false, properties = {"app.worker.retry.initial-delay=10s", "app.worker.retry.jitter-ratio=0"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestOutcomeTests {

	@Autowired
	private ActivationRequestOutcomeService outcomes;

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

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@Test
	void safeRetriesArePersistedInTheFutureAndStopAfterThreeTotalAttempts() {
		var attempt = prepare(0);
		for (int count = 1; count <= 3; count++) {
			var before = repository.findById(attempt.id()).orElseThrow();
			Instant databaseBefore = databaseNow();
			assertThat(outcomes.recordSafeTransientFailure(attempt, "PROVIDER_UNAVAILABLE", null)).isTrue();
			var stored = repository.findById(attempt.id()).orElseThrow();
			assertThat(stored.getAttemptCount()).isEqualTo(count);
			assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
			assertThat(stored.isReconciliationRequired()).isFalse();
			assertThat(stored.getLeaseUntil()).isNull();
			assertThat(stored.getClaimedBy()).isNull();
			assertThat(stored.getVersion()).isEqualTo(attempt.version() + 1);
			assertThat(stored.getCreatedAt()).isEqualTo(before.getCreatedAt());
			assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(before.getUpdatedAt());
			assertThat(stored.getStatus().name()).isEqualTo(count < 3 ? "PROCESSING" : "FAILED");
			assertThat(claims.claimNext("another-worker")).isEmpty();
			var snapshot = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
			assertThat(outcomes.recordSafeTransientFailure(attempt, "DUPLICATE_RESULT", null)).isFalse();
			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(snapshot);
			if (count < 3) {
				long expectedDelay = count == 1 ? 10 : 20;
				assertThat(stored.getNextAttemptAt()).isBetween(databaseBefore.plusSeconds(expectedDelay),
						databaseNow().plusSeconds(expectedDelay));
				jdbc.update("UPDATE activation_requests SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", attempt.id());
				attempt = attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
				assertThat(repository.findById(attempt.id()).orElseThrow().getLastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
			} else {
				assertThat(stored.getNextAttemptAt()).isNull();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void retryAfterIsAMinimumAndCannotRemoveTheBackoff(boolean future) {
		var attempt = prepare(0);
		Instant now = databaseNow();
		Instant retryAfter = now.plusSeconds(future ? 120 : -120);
		assertThat(outcomes.recordSafeTransientFailure(attempt, "PROVIDER_BUSY", retryAfter)).isTrue();
		Instant next = repository.findById(attempt.id()).orElseThrow().getNextAttemptAt();
		if (future) {
			assertThat(next).isEqualTo(retryAfter);
		} else {
			assertThat(next).isAfterOrEqualTo(now.plusSeconds(10));
		}
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"0, true", "2, true", "0, false", "2, false"})
	void confirmedTerminalResultsClearSchedulingAndNeverBecomeEligibleAgain(int previousAttempts, boolean success) {
		var attempt = prepare(previousAttempts);
		jdbc.update("UPDATE activation_requests SET last_error_code = 'PREVIOUS_ERROR' WHERE id = ?", attempt.id());
		assertThat(success ? outcomes.recordSuccess(attempt) : outcomes.recordPermanentFailure(attempt, "PROVIDER_REJECTED"))
				.isTrue();
		var stored = repository.findById(attempt.id()).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo(success ? "COMPLETED" : "FAILED");
		assertThat(stored.getLastErrorCode()).isEqualTo(success ? null : "PROVIDER_REJECTED");
		assertThat(stored.getAttemptCount()).isEqualTo(previousAttempts + 1);
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.isReconciliationRequired()).isFalse();
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.getVersion()).isEqualTo(attempt.version() + 1);
		var terminalRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());

		assertThat(outcomes.recordSuccess(attempt)).isFalse();
		assertThat(outcomes.recordPermanentFailure(attempt, "LATE_REJECTION")).isFalse();
		assertThat(outcomes.recordSafeTransientFailure(attempt, "LATE_TRANSIENT_FAILURE", null)).isFalse();
		assertThat(attempts.recordAmbiguousOutcome(attempt, "LATE_TIMEOUT")).isFalse();
		assertThat(recovery.recoverExpiredLeases(10)).isZero();
		assertThat(claims.claimNext("replacement")).isEmpty();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(terminalRow);
	}

	@ParameterizedTest
	@CsvSource({"PENDING, true", "PENDING, false", "PROCESSING, true", "PROCESSING, false"})
	void staleEntityCannotOverwriteCommittedTerminalState(String snapshotStatus, boolean success) {
		UUID id = insertPending();
		try (var staleContext = entityManagerFactory.createEntityManager()) {
			staleContext.getTransaction().begin();
			try {
				var stale = staleContext.find(ActivationRequest.class, id);
				var claim = claims.claimNext("worker-a").orElseThrow();
				var attempt = attempts.prepareAttempt(claim).orElseThrow();
				if (snapshotStatus.equals("PROCESSING")) {
					staleContext.refresh(stale);
				}
				assertThat(success ? outcomes.recordSuccess(attempt)
						: outcomes.recordPermanentFailure(attempt, "PROVIDER_REJECTED")).isTrue();
				var terminalRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
				assertThat(terminalRow).containsEntry("status", success ? "COMPLETED" : "FAILED");

				if (snapshotStatus.equals("PENDING")) {
					stale.startProcessing();
				} else if (success) {
					stale.fail("LATE_REJECTION");
				} else {
					stale.complete();
				}

				assertThatThrownBy(staleContext::flush).isInstanceOf(OptimisticLockException.class);
				staleContext.getTransaction().rollback();
				assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(terminalRow);
			} finally {
				if (staleContext.getTransaction().isActive()) {
					staleContext.getTransaction().rollback();
				}
			}
		}
	}

	@ParameterizedTest
	@CsvSource({"PROCESSING, worker-a, 60, 99, true, 1", "PROCESSING, worker-b, 60, 2, true, 1",
			"PROCESSING, worker-a, -1, 2, true, 1", "PROCESSING, worker-a, , 2, true, 1",
			"PROCESSING, worker-a, 60, 2, false, 1", "PROCESSING, worker-a, 60, 2, true, 0",
			"PROCESSING, worker-a, 60, 2, true, 2", "PENDING, worker-a, 60, 2, true, 1",
			"COMPLETED, worker-a, 60, 2, true, 1", "FAILED, worker-a, 60, 2, true, 1"})
	void rejectsLostOwnershipAndUnpreparedResultsWithoutMutatingAnyColumn(String status, String worker,
			Integer leaseDelay, long version, boolean reconciliation, int count) {
		var attempt = prepare(0);
		jdbc.update("""
				UPDATE activation_requests SET status = ?, claimed_by = ?,
				    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
				    version = ?, reconciliation_required = ?, attempt_count = ? WHERE id = ?
				""", status, worker, leaseDelay, version, reconciliation, count, attempt.id());
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
		assertThat(outcomes.recordSuccess(attempt)).isFalse();
		assertThat(outcomes.recordPermanentFailure(attempt, "PROVIDER_REJECTED")).isFalse();
		assertThat(outcomes.recordSafeTransientFailure(attempt, "PROVIDER_BUSY", null)).isFalse();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
	}

	@Test
	void responseAfterLeaseRecoveryCannotClearTheReconciliationProtection() {
		var attempt = prepare(0);
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", attempt.id());
		assertThat(recovery.recoverExpiredLeases(1)).isEqualTo(1);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
		assertThat(outcomes.recordSuccess(attempt)).isFalse();
		assertThat(outcomes.recordSafeTransientFailure(attempt, "PROVIDER_BUSY", null)).isFalse();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	@Test
	void futureRetryDoesNotBlockOtherPendingWorkAndSurvivesCallerRollback() {
		var attempt = prepare(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(outcomes.recordSafeTransientFailure(attempt, "PROVIDER_BUSY", null)).isTrue();
			transaction.setRollbackOnly();
		});
		UUID pending = insertPending();
		assertThat(claims.claimNext("worker-b").orElseThrow().id()).isEqualTo(pending);
		assertThat(repository.findById(attempt.id()).orElseThrow().getNextAttemptAt()).isAfter(databaseNow());
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void databaseRejectsRetryDelaysThatWouldAllowImmediateReprocessing(long delayMillis) {
		var attempt = prepare(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
			assertThat(repository.tryRecordConfirmedOutcome(attempt.id(), attempt.version(), attempt.claimedBy(),
					1, false, true, "PROVIDER_BUSY", delayMillis, null)).isZero();
			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
		});
	}

	@Test
	void leaseExpiringExactlyAtDatabaseTimeRejectsEveryConfirmedResult() {
		var attempt = prepare(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP WHERE id = ?", attempt.id());
			var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
			for (boolean success : new boolean[]{true, false}) {
				assertThat(repository.tryRecordConfirmedOutcome(attempt.id(), attempt.version(), attempt.claimedBy(),
						1, success, false, success ? null : "PROVIDER_REJECTED", 0, null)).isZero();
			}
			assertThat(repository.tryRecordConfirmedOutcome(attempt.id(), attempt.version(), attempt.claimedBy(),
					1, false, true, "PROVIDER_BUSY", 1000, null)).isZero();
			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void terminalResultCommitsIndependentlyOfTheCaller(boolean success) {
		var attempt = prepare(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(success ? outcomes.recordSuccess(attempt) : outcomes.recordPermanentFailure(attempt, "PROVIDER_REJECTED"))
					.isTrue();
			transaction.setRollbackOnly();
		});
		assertThat(repository.findById(attempt.id()).orElseThrow().getStatus().name()).isEqualTo(success ? "COMPLETED" : "FAILED");
	}

	@Test
	void concurrentResultsForOnePreparedAttemptOnlyPersistOneDecision() throws Exception {
		var attempt = prepare(0);
		var ready = new CountDownLatch(3);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var results = new ArrayList<Future<Boolean>>();
			for (int i = 0; i < 3; i++) {
				int outcome = i;
				results.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return switch (outcome) {
						case 0 -> outcomes.recordSuccess(attempt);
						case 1 -> outcomes.recordPermanentFailure(attempt, "PROVIDER_REJECTED");
						default -> outcomes.recordSafeTransientFailure(attempt, "PROVIDER_BUSY", null);
					};
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int recorded = 0;
			for (var result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					recorded++;
				}
			}
			assertThat(recorded).isEqualTo(1);
		} finally {
			start.countDown();
		}
		var stored = repository.findById(attempt.id()).orElseThrow();
		assertThat(stored.getVersion()).isEqualTo(attempt.version() + 1);
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.isReconciliationRequired()).isFalse();
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	private PreparedActivationAttempt prepare(int previousAttempts) {
		UUID id = insertPending();
		if (previousAttempts > 0) {
			jdbc.update("""
					UPDATE activation_requests SET status = 'PROCESSING', attempt_count = ?,
					    next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?
					""", previousAttempts, id);
		}
		return attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
	}

	private UUID insertPending() {
		return repository.saveAndFlush(ActivationRequest.create("EXT-" + UUID.randomUUID(), "CUSTOMER-1", "PRODUCT-1", "consumer-a")).getId();
	}

	private Instant databaseNow() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
	}
}
