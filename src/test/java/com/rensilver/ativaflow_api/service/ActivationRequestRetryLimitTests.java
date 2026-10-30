package com.rensilver.ativaflow_api.service;

import java.util.UUID;

import com.rensilver.ativaflow_api.support.TestcontainersConfiguration;
import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
		ActivationRequestClaimService.class, ActivationRequestAttemptService.class,
		ActivationRequestOutcomeService.class, ActivationRetryPolicy.class})
@DataJpaTest(showSql = false, properties = {"app.worker.retry.max-attempts=2", "app.worker.retry.jitter-ratio=0"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestRetryLimitTests {

	@Autowired
	private ActivationRequestRepository repository;
	@Autowired
	private ActivationRequestClaimService claims;
	@Autowired
	private ActivationRequestAttemptService attempts;
	@Autowired
	private ActivationRequestOutcomeService outcomes;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@Test
	void stopsAfterTwoTotalAttemptsWhenConfigured() {
		UUID id = insertPending();
		var first = attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
		assertThat(outcomes.recordSafeTransientFailure(first, "PROVIDER_BUSY", null)).isTrue();
		assertThat(repository.findById(id).orElseThrow().getNextAttemptAt()).isNotNull();
		jdbc.update("UPDATE activation_requests SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", id);
		var second = attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
		assertThat(outcomes.recordSafeTransientFailure(second, "PROVIDER_BUSY", null)).isTrue();
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getAttemptCount()).isEqualTo(2);
		assertThat(stored.getStatus().name()).isEqualTo("FAILED");
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	@Test
	void excludesRequestsAtTheConfiguredLimitFromClaim() {
		UUID exhausted = insertPending();
		jdbc.update("""
				UPDATE activation_requests SET status = 'PROCESSING', attempt_count = 2,
				    next_attempt_at = CURRENT_TIMESTAMP, created_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
				WHERE id = ?
				""", exhausted);
		UUID pending = insertPending();
		assertThat(claims.claimNext("worker-a").orElseThrow().id()).isEqualTo(pending);
		assertThat(repository.findById(exhausted).orElseThrow().getClaimedBy()).isNull();
	}

	@Test
	void conditionalClaimAlsoRefusesRequestsAtTheConfiguredLimit() {
		UUID id = insertPending();
		jdbc.update("""
				UPDATE activation_requests SET status = 'PROCESSING', attempt_count = 2,
				    next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?
				""", id);
		long version = repository.findById(id).orElseThrow().getVersion();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
				assertThat(repository.tryClaim(id, version, "worker-a", 30_000, 2)).isZero());
		assertThat(repository.findById(id).orElseThrow().getClaimedBy()).isNull();
	}

	@Test
	void preparationRefusesAnotherAttemptAtTheConfiguredLimit() {
		UUID id = insertPending();
		var claim = claims.claimNext("worker-a").orElseThrow();
		jdbc.update("""
				UPDATE activation_requests SET attempt_count = 2, next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?
				""", id);
		assertThat(attempts.prepareAttempt(claim)).isEmpty();
		assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(2);
	}

	@Test
	void ambiguousResultOnTheLastConfiguredAttemptStillRequiresReconciliation() {
		UUID id = insertPending();
		jdbc.update("""
				UPDATE activation_requests SET status = 'PROCESSING', attempt_count = 1,
				    next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?
				""", id);
		var attempt = attempts.prepareAttempt(claims.claimNext("worker-a").orElseThrow()).orElseThrow();
		assertThat(attempts.recordAmbiguousOutcome(attempt, "PROVIDER_COMMUNICATION_FAILURE")).isTrue();
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.getStatus().name()).isEqualTo("PROCESSING");
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(claims.claimNext("worker-b")).isEmpty();
	}

	private UUID insertPending() {
		return repository.saveAndFlush(ActivationRequest.create("EXT-" + UUID.randomUUID(),
				"CUSTOMER-1", "PRODUCT-1", "consumer-a")).getId();
	}
}
