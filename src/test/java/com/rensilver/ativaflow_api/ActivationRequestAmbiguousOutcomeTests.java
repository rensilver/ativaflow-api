package com.rensilver.ativaflow_api;

import java.util.UUID;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestLeaseRecoveryService;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
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

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class,
		ActivationRequestClaimService.class, ActivationRequestLeaseRecoveryService.class,
		ActivationRequestAttemptService.class})
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestAmbiguousOutcomeTests {

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

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void ambiguousOutcomeRemainsBlockedEvenOnTheLastAttempt(int previousAttempts) {
		var attempt = prepare(previousAttempts);
		var beforeUpdatedAt = repository.findById(attempt.id()).orElseThrow().getUpdatedAt();
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
		assertThat(attempts.recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT")).isTrue();

		var stored = repository.findById(attempt.id()).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getAttemptCount()).isEqualTo(previousAttempts + 1);
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.getVersion()).isEqualTo(attempt.version() + 1);
		assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(beforeUpdatedAt);
		var after = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
		for (String changed : new String[]{"claimed_by", "lease_until", "last_error_code", "version", "updated_at"}) {
			before.remove(changed);
			after.remove(changed);
		}
		assertThat(after).isEqualTo(before);
		var snapshot = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());
		assertThat(attempts.recordAmbiguousOutcome(attempt, "OTHER_UNKNOWN_RESULT")).isFalse();
		assertThat(recovery.recoverExpiredLeases(10)).isZero();
		assertThat(claims.claimNext("worker-a")).isEmpty();
		assertThat(claims.claimNext("replacement")).isEmpty();
		assertThat(attempts.prepareAttempt(new ClaimedActivationRequest(
				attempt.id(), stored.getVersion(), "worker-a", attempt.leaseUntil()))).isEmpty();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(snapshot);
	}

	@ParameterizedTest
	@CsvSource({
			"PROCESSING, worker-a, 60, 99, true",
			"PROCESSING, worker-b, 60,  2, true",
			"PROCESSING, worker-a, -1,  2, true",
			"PROCESSING, worker-a,   ,  2, true",
			"PROCESSING, worker-a, 60,  2, false",
			"PENDING,    worker-a, 60,  2, true",
			"COMPLETED,  worker-a, 60,  2, true",
			"FAILED,     worker-a, 60,  2, true"
	})
	void rejectsLostOwnershipOrUnpreparedWorkWithoutChangingAnyColumn(String status, String worker,
			Integer leaseDelay, long version, boolean reconciliation) {
		var attempt = prepare(0);
		jdbc.update("""
				UPDATE activation_requests SET status = ?, claimed_by = ?,
				    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
				    version = ?, reconciliation_required = ? WHERE id = ?
				""", status, worker, leaseDelay, version, reconciliation, attempt.id());
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());

		assertThat(attempts.recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT")).isFalse();

		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
	}

	@Test
	void lateResponseAfterLeaseRecoveryKeepsTheExistingReconciliationBlock() {
		var attempt = prepare(0);
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
				attempt.id());
		assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);
		var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());

		assertThat(attempts.recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT")).isFalse();

		assertThat(claims.claimNext("worker-a")).isEmpty();
		assertThat(claims.claimNext("worker-b")).isEmpty();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
	}

	@Test
	void commitsTheUnknownResultIndependentlyOfTheCallerTransaction() {
		var attempt = prepare(0);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			assertThat(attempts.recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT")).isTrue();
			transaction.setRollbackOnly();
		});

		var stored = repository.findById(attempt.id()).orElseThrow();
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(claims.claimNext("replacement")).isEmpty();
	}

	@Test
	void rejectsLeaseExactlyAtDatabaseTime() {
		var attempt = prepare(0);
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP WHERE id = ?", attempt.id());
			var before = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id());

			assertThat(repository.tryRecordAmbiguousOutcome(attempt.id(), attempt.version(), "worker-a", "PROVIDER_TIMEOUT"))
					.isZero();

			assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", attempt.id())).isEqualTo(before);
		});
	}

	@Test
	void blockedRequestDoesNotPreventProcessingOtherPendingWork() {
		var ambiguous = prepare(0);
		assertThat(attempts.recordAmbiguousOutcome(ambiguous, "PROVIDER_TIMEOUT")).isTrue();
		UUID pendingId = insertPending();

		var next = claims.claimNext("worker-b").orElseThrow();

		assertThat(next.id()).isEqualTo(pendingId);
		assertThat(attempts.prepareAttempt(next)).isPresent();
		assertThat(repository.findById(ambiguous.id()).orElseThrow().isReconciliationRequired()).isTrue();
	}

	private PreparedActivationAttempt prepare(int previousAttempts) {
		UUID id = insertPending();
		if (previousAttempts > 0) {
			jdbc.update("""
					UPDATE activation_requests SET status = 'PROCESSING', attempt_count = ?,
					    next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?
					""", previousAttempts, id);
		}
		return attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
	}

	private UUID insertPending() {
		return repository.saveAndFlush(ActivationRequest.create("EXT-" + UUID.randomUUID(),
				"CUSTOMER-1", "PRODUCT-1", "consumer-a")).getId();
	}
}
