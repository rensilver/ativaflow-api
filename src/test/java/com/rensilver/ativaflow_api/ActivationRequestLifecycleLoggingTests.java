package com.rensilver.ativaflow_api;

import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.exception.ActivationRequestConflictException;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestCreationTransaction;
import com.rensilver.ativaflow_api.service.ActivationRequestLeaseRecoveryService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeService;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import com.rensilver.ativaflow_api.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class,
		ActivationRequestCreationTransaction.class, ActivationRequestService.class,
		ActivationRequestClaimService.class, ActivationRequestAttemptService.class,
		ActivationRequestOutcomeService.class, ActivationRequestLeaseRecoveryService.class,
		ActivationRetryPolicy.class, ActivationRequestLifecycleLoggingTests.MapperConfiguration.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationRequestLifecycleLoggingTests {

	private static final CreateActivationRequest INPUT = new CreateActivationRequest(
			"private-external", "private-customer", "private-product");
	@Autowired private ActivationRequestService service;
	@Autowired private ActivationRequestClaimService claims;
	@Autowired private ActivationRequestAttemptService attempts;
	@Autowired private ActivationRequestOutcomeService outcomes;
	@Autowired private ActivationRequestLeaseRecoveryService recovery;
	@Autowired private ActivationRequestRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ActivationRetryPolicy retryPolicy;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@Test
	void logsOnlyTheCommittedCreationWithoutBusinessDataOrDuplicateEventOnReuse() {
		try (var logs = new LogCapture(ActivationRequestCreationTransaction.class)) {
			UUID id = create();
			assertEvent(logs, "activation_request_created", id);
			assertThat(LogCapture.fields(logs.events().getFirst())).containsEntry("status", "PENDING");
			assertThat(service.createActivationRequest(INPUT, "private-consumer").created()).isFalse();
			assertThatThrownBy(() -> service.createActivationRequest(
					new CreateActivationRequest(INPUT.externalId(), "other-customer", INPUT.productCode()),
					"private-consumer")).isInstanceOf(ActivationRequestConflictException.class);
			assertThat(logs.events()).hasSize(1);
		}
	}

	@Test
	void logsClaimWithPersistedCorrelationAndDoesNotLogEmptyPolls() {
		UUID id = create();
		try (var logs = new LogCapture(ActivationRequestClaimService.class);
				var ignored = CorrelationId.withId("unrelated-thread")) {
			var claim = claims.claimNext("private-worker").orElseThrow();
			assertEvent(logs, "activation_request_claimed", id);
			assertThat(LogCapture.fields(logs.events().getFirst()))
					.containsEntry("attempt", 0).containsEntry("recovered", false)
					.containsEntry("leaseUntil", claim.leaseUntil());
			assertThat(CorrelationId.current()).isEqualTo("unrelated-thread");
			assertThat(claims.claimNext("private-worker")).isEmpty();
			assertThat(logs.events()).hasSize(1);
		}
	}

	@Test
	void identifiesDirectRecoveryOfAnExpiredClaimAndFallsBackToInternalIdForLegacyRows() {
		UUID id = create();
		claims.claimNext("old-worker").orElseThrow();
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute', correlation_id = NULL WHERE id = ?", id);
		try (var logs = new LogCapture(ActivationRequestClaimService.class)) {
			claims.claimNext("replacement").orElseThrow();
			assertThat(logs.events()).hasSize(1);
			assertThat(LogCapture.fields(logs.events().getFirst()))
					.containsEntry("event", "activation_request_claimed")
					.containsEntry("requestId", id).containsEntry("recovered", true)
					.containsEntry("correlationId", id.toString());
		}
	}

	@Test
	void logsOnlyNonemptyRecoveryBatchesWithoutBorrowingThreadCorrelation() {
		UUID id = create();
		claims.claimNext("old-worker").orElseThrow();
		jdbc.update("UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?", id);
		try (var logs = new LogCapture(ActivationRequestLeaseRecoveryService.class);
				var ignored = CorrelationId.withId("unrelated-thread")) {
			assertThat(recovery.recoverExpiredLeases(10)).isEqualTo(1);
			assertThat(recovery.recoverExpiredLeases(10)).isZero();
			assertThat(logs.events()).hasSize(1);
			assertThat(LogCapture.fields(logs.events().getFirst())).containsOnly(
					entry("event", "activation_leases_recovered"), entry("recoveredCount", 1));
			assertThat(logs.events().getFirst().getMDCPropertyMap()).doesNotContainKey("correlationId");
			assertThat(CorrelationId.current()).isEqualTo("unrelated-thread");
		}
	}

	@Test
	void logsThePersistedRetryDeadlineAndDoesNotLogStaleOutcomes() {
		var attempt = prepare();
		Instant minimum = Instant.now().plusSeconds(60);
		try (var logs = new LogCapture(ActivationRequestOutcomeService.class);
				var ignored = CorrelationId.withId("unrelated-thread")) {
			assertThat(outcomes.recordSafeTransientFailure(attempt, "private-error", minimum)).isTrue();
			var stored = repository.findById(attempt.id()).orElseThrow();
			assertEvent(logs, "activation_retry_scheduled", attempt.id());
			assertThat(LogCapture.fields(logs.events().getFirst()))
					.containsEntry("attempt", 1).containsEntry("nextAttemptAt", stored.getNextAttemptAt());
			assertThat(stored.getNextAttemptAt()).isAfterOrEqualTo(minimum.minusMillis(1));
			assertThat(outcomes.recordSafeTransientFailure(attempt, "private-error", minimum)).isFalse();
			assertThat(logs.events()).hasSize(1);
			assertThat(CorrelationId.current()).isEqualTo("unrelated-thread");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"success", "permanent", "exhausted", "ambiguous"})
	void doesNotAnnounceRetryForTerminalOrAmbiguousOutcomes(String result) {
		var attempt = prepare();
		if (result.equals("exhausted")) {
			jdbc.update("UPDATE activation_requests SET attempt_count = 3 WHERE id = ?", attempt.id());
			attempt = new PreparedActivationAttempt(attempt.id(), attempt.version(), attempt.claimedBy(), attempt.leaseUntil(), 3);
		}
		try (var logs = new LogCapture(ActivationRequestOutcomeService.class)) {
			boolean recorded = switch (result) {
				case "success" -> outcomes.recordSuccess(attempt);
				case "permanent" -> outcomes.recordPermanentFailure(attempt, "private-error");
				case "ambiguous" -> attempts.recordAmbiguousOutcome(attempt, "private-error");
				default -> outcomes.recordSafeTransientFailure(attempt, "private-error", null);
			};
			assertThat(recorded).isTrue();
			assertThat(logs.events()).isEmpty();
		}
	}

	@Test
	void defersCreationEventUntilCommitAndDiscardsItOnRollback() {
		var transaction = new TransactionTemplate(transactionManager);
		var creation = new ActivationRequestCreationTransaction(repository, Mappers.getMapper(ActivationRequestMapper.class));
		try (var logs = new LogCapture(ActivationRequestCreationTransaction.class);
				var ignored = CorrelationId.withId("lifecycle-1")) {
			transaction.executeWithoutResult(status -> {
				creation.insert(INPUT, "private-consumer");
				assertThat(logs.events()).isEmpty();
				status.setRollbackOnly();
			});
			assertThat(repository.count()).isZero();
			assertThat(logs.events()).isEmpty();
			UUID id = transaction.execute(status -> {
				UUID inserted = creation.insert(INPUT, "private-consumer").id();
				assertThat(logs.events()).isEmpty();
				return inserted;
			});
			assertEvent(logs, "activation_request_created", id);
		}
	}

	@Test
	void doesNotAnnounceRetryWhenItsTransactionRollsBack() {
		var attempt = prepare();
		var transaction = new TransactionTemplate(transactionManager);
		var target = new ActivationRequestOutcomeService(repository, retryPolicy);
		try (var logs = new LogCapture(ActivationRequestOutcomeService.class)) {
			transaction.executeWithoutResult(status -> {
				assertThat(target.recordSafeTransientFailure(attempt, "private-error", null)).isTrue();
				assertThat(logs.events()).isEmpty();
				status.setRollbackOnly();
			});
			assertThat(logs.events()).isEmpty();
			assertThat(repository.findById(attempt.id()).orElseThrow().getNextAttemptAt()).isNull();
		}
	}

	private UUID create() {
		try (var ignored = CorrelationId.withId("lifecycle-1")) {
			return service.createActivationRequest(INPUT, "private-consumer").response().id();
		}
	}

	private PreparedActivationAttempt prepare() {
		create();
		return attempts.prepareAttempt(claims.claimNext("private-worker").orElseThrow()).orElseThrow();
	}

	private void assertEvent(LogCapture logs, String event, UUID id) {
		assertThat(logs.events()).hasSize(1);
		var entry = logs.events().getFirst();
		assertThat(LogCapture.fields(entry)).containsEntry("event", event).containsEntry("requestId", id)
				.containsEntry("correlationId", "lifecycle-1");
		assertThat(entry.getMDCPropertyMap()).containsEntry("correlationId", "lifecycle-1");
		assertThat(entry.getThrowableProxy()).isNull();
		assertThat(entry.getFormattedMessage() + entry.getKeyValuePairs() + entry.getMDCPropertyMap())
				.doesNotContain("private-", "Authorization", "Bearer", "unrelated-thread");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MapperConfiguration {
		@Bean
		ActivationRequestMapper mapper() {
			return Mappers.getMapper(ActivationRequestMapper.class);
		}
	}
}
