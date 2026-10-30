package com.rensilver.ativaflow_api.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Exercita handler e serviços reais até a fronteira da persistência, sem simular o SQL. */
@ExtendWith(MockitoExtension.class)
class ActivationRequestOutcomeResilienceUnitTests {

	private static final UUID ID = UUID.fromString("245ac80e-51ab-4e8c-a95f-23792b9d7b84");
	private static final Instant LEASE_UNTIL = Instant.parse("2026-10-29T12:00:30Z");
	private static final Instant RETRY_AFTER = Instant.parse("2098-01-01T00:00:00Z");

	@Mock
	private ActivationRequestRepository repository;

	@ParameterizedTest(name = "attempt={0}, error={1}, updatedRows={2}")
	@MethodSource("ambiguousOutcomes")
	void ambiguousOutcomeUsesOnlyReconciliationEvenOnTheLastAttempt(int attemptCount, String code, int updatedRows) {
		var attempt = attempt(attemptCount);
		when(repository.tryRecordAmbiguousOutcome(ID, attempt.version(), "worker-a", code)).thenReturn(updatedRows);

		assertThat(handler(3).record(attempt, new ProviderOutcome.Indeterminate(code)))
				.isEqualTo(updatedRows == 1);
		// A escrita confirmada liberaria a proteção; nenhum agendamento deve ser solicitado aqui.
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest(name = "maxAttempts={0}, attempt={1}, backoffMs={2}, Retry-After={3}")
	@MethodSource("safeTransientOutcomes")
	void safeRetryPassesTheTotalAttemptBudgetBackoffAndUnshortenedProviderDeadlineToPersistence(
			int maxAttempts, int attemptCount, long expectedDelayMillis, Instant retryAfter) {
		var attempt = attempt(attemptCount);
		when(repository.tryRecordConfirmedOutcome(ID, attempt.version(), "worker-a", attemptCount,
				false, true, "PROVIDER_BUSY", expectedDelayMillis, retryAfter, maxAttempts)).thenReturn(1);
		if (attemptCount < maxAttempts) {
			var stored = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a", "retry-1");
			ReflectionTestUtils.setField(stored, "id", ID);
			ReflectionTestUtils.setField(stored, "attemptCount", attemptCount);
			ReflectionTestUtils.setField(stored, "nextAttemptAt", RETRY_AFTER);
			when(repository.findById(ID)).thenReturn(Optional.of(stored));
		}

		assertThat(handler(maxAttempts).record(attempt,
				new ProviderOutcome.TransientFailure("PROVIDER_BUSY", Optional.ofNullable(retryAfter)))).isTrue();
		if (attemptCount < maxAttempts) {
			verify(repository).findById(ID);
		}
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@CsvSource({"1, 1000", "2, 2000", "3, 4000"})
	void staleSafeFailureIsNotReportedAsRecorded(int attemptCount, long expectedDelayMillis) {
		assertThat(handler(3).record(attempt(attemptCount),
				new ProviderOutcome.TransientFailure("PROVIDER_BUSY", Optional.of(RETRY_AFTER)))).isFalse();
		verify(repository).tryRecordConfirmedOutcome(ID, attempt(attemptCount).version(),
				"worker-a", attemptCount, false, true, "PROVIDER_BUSY", expectedDelayMillis, RETRY_AFTER, 3);
		verifyNoMoreInteractions(repository);
	}

	private ActivationRequestOutcomeHandler handler(int maxAttempts) {
		var properties = new ActivationRetryProperties(maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(1), 0);
		return new ActivationRequestOutcomeHandler(new ActivationRequestAttemptService(repository, properties),
				new ActivationRequestOutcomeService(repository, new ActivationRetryPolicy(properties)));
	}

	private static PreparedActivationAttempt attempt(int attemptCount) {
		return new PreparedActivationAttempt(ID, attemptCount * 2L, "worker-a", LEASE_UNTIL, attemptCount);
	}

	private static Stream<Arguments> ambiguousOutcomes() {
		return IntStream.rangeClosed(1, 3).boxed().flatMap(attempt ->
				Stream.of("PROVIDER_TIMEOUT", "PROVIDER_UNCONFIRMED_RESPONSE", "PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN")
						.flatMap(code -> Stream.of(0, 1).map(rows -> Arguments.of(attempt, code, rows))));
	}

	private static Stream<Arguments> safeTransientOutcomes() {
		return Stream.of(
				Arguments.of(1, 1, 1_000L, null),
				Arguments.of(1, 1, 1_000L, RETRY_AFTER),
				Arguments.of(2, 1, 1_000L, null),
				Arguments.of(2, 1, 1_000L, RETRY_AFTER),
				Arguments.of(2, 2, 2_000L, null),
				Arguments.of(2, 2, 2_000L, RETRY_AFTER),
				Arguments.of(3, 1, 1_000L, null),
				Arguments.of(3, 1, 1_000L, RETRY_AFTER),
				Arguments.of(3, 2, 2_000L, null),
				Arguments.of(3, 2, 2_000L, RETRY_AFTER),
				Arguments.of(3, 3, 4_000L, null),
				Arguments.of(3, 3, 4_000L, RETRY_AFTER));
	}
}
