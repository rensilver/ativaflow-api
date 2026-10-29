package com.rensilver.ativaflow_api.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestOutcomeServiceTests {

	@Mock
	private ActivationRequestRepository repository;

	@Test
	void zeroUpdatedRowsNeverReportsARecordedResult() {
		assertThat(service().recordSuccess(attempt())).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 2})
	void unexpectedUpdatedRowsFailsInsteadOfReportingSuccess(int count) {
		var attempt = attempt();
		when(repository.tryRecordConfirmedOutcome(attempt.id(), 2, "worker-a", 1,
				true, false, null, 0, null)).thenReturn(count);
		assertThatThrownBy(() -> service().recordSuccess(attempt)).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void rejectsMissingFailureCodesBeforeAccessingTheDatabase(String code) {
		assertThatThrownBy(() -> service().recordPermanentFailure(attempt(), code)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().recordSafeTransientFailure(attempt(), code, null)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsFailureCodesThatExceedTheDatabaseLimit() {
		assertThatThrownBy(() -> service().recordPermanentFailure(attempt(), "E".repeat(101))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().recordSafeTransientFailure(attempt(), "E".repeat(101), null)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	private ActivationRequestOutcomeService service() {
		return new ActivationRequestOutcomeService(repository,
				new ActivationRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0));
	}

	private PreparedActivationAttempt attempt() {
		return new PreparedActivationAttempt(UUID.randomUUID(), 2, "worker-a", Instant.parse("2026-10-29T12:00:00Z"), 1);
	}
}
