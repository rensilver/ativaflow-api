package com.rensilver.ativaflow_api.service;

import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
}
