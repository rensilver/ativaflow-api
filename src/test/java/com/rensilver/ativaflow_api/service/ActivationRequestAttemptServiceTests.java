package com.rensilver.ativaflow_api.service;

import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ActivationRequestAttemptServiceTests {

	@Mock
	private ActivationRequestRepository repository;

	@Test
	void zeroRowsNeverAuthorizesAnAttempt() {
		var claim = new ClaimedActivationRequest(UUID.randomUUID(), 7, "worker-a", Instant.parse("2026-10-29T12:00:00Z"));
		when(repository.tryPrepareAttempt(claim.id(), 7, "worker-a")).thenReturn(0);

		assertThat(new ActivationRequestAttemptService(repository).prepareAttempt(claim)).isEmpty();
	}

	@Test
	void unexpectedAffectedRowCountNeverAuthorizesAnAttempt() {
		var claim = new ClaimedActivationRequest(UUID.randomUUID(), 7, "worker-a", Instant.parse("2026-10-29T12:00:00Z"));
		when(repository.tryPrepareAttempt(claim.id(), 7, "worker-a")).thenReturn(2);

		assertThatThrownBy(() -> new ActivationRequestAttemptService(repository).prepareAttempt(claim))
				.isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void ambiguousOutcomeIsRecordedOnlyWhenExactlyOneRowIsUpdated(int affectedRows) {
		var attempt = preparedAttempt();
		when(repository.tryRecordAmbiguousOutcome(attempt.id(), 8, "worker-a", "PROVIDER_TIMEOUT"))
				.thenReturn(affectedRows);

		assertThat(new ActivationRequestAttemptService(repository).recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT"))
				.isEqualTo(affectedRows == 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 2})
	void unexpectedOutcomeRowCountFailsInsteadOfReportingSuccess(int affectedRows) {
		var attempt = preparedAttempt();
		when(repository.tryRecordAmbiguousOutcome(attempt.id(), 8, "worker-a", "PROVIDER_TIMEOUT"))
				.thenReturn(affectedRows);

		assertThatThrownBy(() -> new ActivationRequestAttemptService(repository)
				.recordAmbiguousOutcome(attempt, "PROVIDER_TIMEOUT"))
				.isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void rejectsMissingOutcomeErrorCodeBeforeAccessingTheDatabase(String errorCode) {
		assertThatThrownBy(() -> new ActivationRequestAttemptService(repository)
				.recordAmbiguousOutcome(preparedAttempt(), errorCode)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsOutcomeErrorCodeLongerThanThePersistedLimit() {
		assertThatThrownBy(() -> new ActivationRequestAttemptService(repository)
				.recordAmbiguousOutcome(preparedAttempt(), "E".repeat(101))).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	private PreparedActivationAttempt preparedAttempt() {
		return new PreparedActivationAttempt(UUID.randomUUID(), 8, "worker-a",
				Instant.parse("2026-10-29T12:00:00Z"), 1);
	}
}
