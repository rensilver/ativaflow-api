package com.rensilver.ativaflow_api.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestWorkerLoggingTests {

	private static final UUID ID = UUID.fromString("245ac80e-51ab-4e8c-a95f-23792b9d7b84");
	private static final ClaimedActivationRequest CLAIM =
			new ClaimedActivationRequest(ID, 1, "worker-a", Instant.parse("2026-10-29T12:00:30Z"));
	@Mock private ActivationRequestClaimService claims;
	@Mock private ActivationRequestAttemptService attempts;
	@Mock private ActivationRequestOutcomeHandler outcomes;
	@Mock private ActivationProviderClient provider;
	@Mock private ActivationRequestRepository repository;
	private ActivationRequestWorker worker;
	private LogCapture logs;

	@BeforeEach
	void setUp() {
		worker = new ActivationRequestWorker(claims, attempts, outcomes, provider, repository);
		logs = new LogCapture(ActivationRequestWorker.class);
	}

	@AfterEach
	void tearDown() {
		logs.close();
	}

	@ParameterizedTest
	@CsvSource({"1, SUCCESS", "2, PERMANENT_FAILURE", "3, TRANSIENT_FAILURE", "3, INDETERMINATE"})
	void identifiesThePersistedAttemptAndItsResult(int number, String result) {
		var attempt = prepare(number);
		ProviderOutcome outcome = switch (result) {
			case "SUCCESS" -> new ProviderOutcome.Success();
			case "PERMANENT_FAILURE" -> new ProviderOutcome.PermanentFailure("private-error-code");
			case "TRANSIENT_FAILURE" -> new ProviderOutcome.TransientFailure("private-error-code", Optional.empty());
			default -> new ProviderOutcome.Indeterminate("private-error-code");
		};
		when(provider.activate(any())).thenAnswer(invocation -> {
			assertThat(logs.events()).hasSize(1);
			assertThat(LogCapture.fields(logs.events().getFirst()))
					.containsEntry("event", "activation_attempt_started");
			return outcome;
		});
		when(outcomes.record(attempt, outcome)).thenReturn(true);

		assertThat(worker.processNext()).isTrue();

		assertAttemptEvents(number, result, "RECORDED");
	}

	@Test
	void distinguishesAStaleResultFromAPersistedResult() {
		prepare(2);
		when(provider.activate(any())).thenReturn(new ProviderOutcome.Success());
		when(outcomes.record(any(), any())).thenReturn(false);

		worker.processNext();

		assertAttemptEvents(2, "SUCCESS", "STALE");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void reportsUnexpectedFailuresWithoutLeakingTheException(boolean providerFails) {
		prepare(1);
		var failure = new IllegalStateException("private-exception-secret");
		if (providerFails) {
			when(provider.activate(any())).thenThrow(failure);
		}
		else {
			when(provider.activate(any())).thenReturn(new ProviderOutcome.Success());
			when(outcomes.record(any(), any())).thenThrow(failure);
		}

		try (var ignored = CorrelationId.withId("outer-correlation")) {
			assertThatThrownBy(worker::processNext).isSameAs(failure);
			assertThat(CorrelationId.current()).isEqualTo("outer-correlation");
		}

		assertAttemptEvents(1, providerFails ? "UNEXPECTED_ERROR" : "SUCCESS",
				providerFails ? "NOT_RECORDED" : "ERROR");
	}

	@Test
	void doesNotLogAttemptsWhenThereIsNoWork() {
		when(claims.claimNext(anyString())).thenReturn(Optional.empty());

		assertThat(worker.processNext()).isFalse();
		assertThat(logs.events()).isEmpty();
	}

	@Test
	void doesNotLogAnHttpAttemptWhenPreparationIsStale() {
		when(claims.claimNext(anyString())).thenReturn(Optional.of(CLAIM));
		when(attempts.prepareAttempt(CLAIM)).thenReturn(Optional.empty());

		assertThat(worker.processNext()).isTrue();
		assertThat(logs.events()).isEmpty();
	}

	private PreparedActivationAttempt prepare(int number) {
		var attempt = new PreparedActivationAttempt(ID, 2, "worker-a", CLAIM.leaseUntil(), number);
		var request = ActivationRequest.create("private-external-id", "private-customer-id", "private-product-code",
				"private-consumer", "logging-worker-1");
		when(claims.claimNext(anyString())).thenReturn(Optional.of(CLAIM));
		when(attempts.prepareAttempt(CLAIM)).thenReturn(Optional.of(attempt));
		when(repository.findById(ID)).thenReturn(Optional.of(request));
		return attempt;
	}

	private void assertAttemptEvents(int number, String result, String persistence) {
		assertThat(logs.events()).hasSize(2);
		assertThat(LogCapture.fields(logs.events().getFirst()))
				.containsEntry("event", "activation_attempt_started");
		assertThat(LogCapture.fields(logs.events().getLast()))
				.containsEntry("event", "activation_attempt_finished")
				.containsEntry("result", result).containsEntry("persistence", persistence);
		for (var event : logs.events()) {
			assertThat(LogCapture.fields(event)).containsEntry("requestId", ID)
					.containsEntry("attempt", number).containsEntry("correlationId", "logging-worker-1");
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(event.getFormattedMessage() + event.getKeyValuePairs()).doesNotContain("private-");
		}
	}
}
