package com.rensilver.ativaflow_api.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Retomada na fronteira de orquestração; elegibilidade e durabilidade do claim pertencem aos testes de banco. */
@ExtendWith(MockitoExtension.class)
class ActivationRequestWorkerRecoveryUnitTests {

	private static final Instant LEASE_UNTIL = Instant.parse("2026-10-29T12:00:30Z");

	@Mock
	private ActivationRequestClaimService claims;
	@Mock
	private ActivationRequestAttemptService attempts;
	@Mock
	private ActivationRequestOutcomeHandler outcomes;
	@Mock
	private ActivationProviderClient provider;
	@Mock
	private ActivationRequestRepository repository;

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3})
	void newWorkerResumesEligibleWorkUsingThePreparedAttemptNumberAndPersistedPayload(int attemptCount) {
		var request = ActivationRequest.create(" EXT-1 ", "Cliente-ação", " PRODUCT-1 ", "consumer-a");
		if (attemptCount > 1) {
			request.startProcessing();
		}
		when(claims.claimNext(anyString())).thenReturn(Optional.empty())
				.thenAnswer(invocation -> Optional.of(new ClaimedActivationRequest(
						request.getId(), 7, invocation.getArgument(0), LEASE_UNTIL)))
				.thenReturn(Optional.empty());
		when(attempts.prepareAttempt(any())).thenAnswer(invocation -> {
			ClaimedActivationRequest claim = invocation.getArgument(0);
			return Optional.of(new PreparedActivationAttempt(claim.id(), 8, claim.claimedBy(), LEASE_UNTIL, attemptCount));
		});
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));
		var payload = new ActivationProviderRequest(" EXT-1 ", "Cliente-ação", " PRODUCT-1 ");
		var success = new ProviderOutcome.Success();
		when(provider.activate(payload)).thenReturn(success);

		assertThat(worker().processNext()).isFalse();
		verifyNoInteractions(attempts, provider, outcomes, repository);
		worker().poll();

		var workerIds = ArgumentCaptor.forClass(String.class);
		verify(claims, times(3)).claimNext(workerIds.capture());
		assertThat(workerIds.getAllValues().get(1)).isNotEqualTo(workerIds.getAllValues().getFirst());
		assertThat(workerIds.getAllValues().get(2)).isEqualTo(workerIds.getAllValues().get(1));
		var prepared = new PreparedActivationAttempt(request.getId(), 8, workerIds.getAllValues().get(1),
				LEASE_UNTIL, attemptCount);
		var order = inOrder(attempts, repository, provider, outcomes);
		order.verify(attempts).prepareAttempt(new ClaimedActivationRequest(request.getId(), 7,
				prepared.claimedBy(), LEASE_UNTIL));
		order.verify(repository).findById(request.getId());
		order.verify(provider).activate(payload);
		order.verify(outcomes).record(prepared, success);
		verifyNoMoreInteractions(provider, outcomes);
	}

	@Test
	void pollingContinuesWithAnotherRequestAfterAnAmbiguousResult() {
		var ambiguous = ActivationRequest.create("EXT-TIMEOUT", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		var next = ActivationRequest.create("EXT-NEXT", "CUSTOMER-2", "PRODUCT-2", "consumer-a");
		var firstClaim = claim(ambiguous.getId());
		var nextClaim = claim(next.getId());
		var firstAttempt = new PreparedActivationAttempt(ambiguous.getId(), 8, "worker-a", LEASE_UNTIL, 3);
		var nextAttempt = new PreparedActivationAttempt(next.getId(), 8, "worker-a", LEASE_UNTIL, 1);
		when(claims.claimNext(anyString())).thenReturn(Optional.of(firstClaim))
				.thenReturn(Optional.of(nextClaim)).thenReturn(Optional.empty());
		when(attempts.prepareAttempt(firstClaim)).thenReturn(Optional.of(firstAttempt));
		when(attempts.prepareAttempt(nextClaim)).thenReturn(Optional.of(nextAttempt));
		when(repository.findById(ambiguous.getId())).thenReturn(Optional.of(ambiguous));
		when(repository.findById(next.getId())).thenReturn(Optional.of(next));
		var firstPayload = new ActivationProviderRequest("EXT-TIMEOUT", "CUSTOMER-1", "PRODUCT-1");
		var nextPayload = new ActivationProviderRequest("EXT-NEXT", "CUSTOMER-2", "PRODUCT-2");
		var timeout = new ProviderOutcome.Indeterminate("PROVIDER_TIMEOUT");
		var success = new ProviderOutcome.Success();
		when(provider.activate(firstPayload)).thenReturn(timeout);
		when(provider.activate(nextPayload)).thenReturn(success);

		worker().poll();

		var order = inOrder(provider, outcomes);
		order.verify(provider).activate(firstPayload);
		order.verify(outcomes).record(firstAttempt, timeout);
		order.verify(provider).activate(nextPayload);
		order.verify(outcomes).record(nextAttempt, success);
		verifyNoMoreInteractions(provider, outcomes);
	}

	@Test
	void lostOwnershipWhileRecordingAnOutcomeDoesNotTriggerAnImmediateResend() {
		var request = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		var claim = claim(request.getId());
		var attempt = new PreparedActivationAttempt(request.getId(), 8, "worker-a", LEASE_UNTIL, 2);
		var success = new ProviderOutcome.Success();
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim)).thenReturn(Optional.empty());
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.of(attempt));
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));
		when(provider.activate(new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1"))).thenReturn(success);
		when(outcomes.record(attempt, success)).thenReturn(false);

		worker().poll();

		verify(provider).activate(new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1"));
		verify(outcomes).record(attempt, success);
		verifyNoMoreInteractions(provider, outcomes);
	}

	@Test
	void failedPreparationAfterRecoveryDoesNotSendAndPollingStillContinues() {
		var claim = claim(UUID.randomUUID());
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim)).thenReturn(Optional.empty());
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.empty());

		worker().poll();

		verify(claims, times(2)).claimNext(anyString());
		verifyNoInteractions(provider, outcomes, repository);
		verify(attempts, never()).recordAmbiguousOutcome(any(), anyString());
	}

	private ClaimedActivationRequest claim(UUID id) {
		return new ClaimedActivationRequest(id, 7, "worker-a", LEASE_UNTIL);
	}

	private ActivationRequestWorker worker() {
		return new ActivationRequestWorker(claims, attempts, outcomes, provider, repository);
	}
}
