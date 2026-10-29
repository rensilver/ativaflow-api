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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestWorkerTests {

	private static final UUID ID = UUID.fromString("245ac80e-51ab-4e8c-a95f-23792b9d7b84");

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

	@Test
	void sendsRequestOnlyAfterClaimAndAttemptPreparationSucceed() {
		var claim = new ClaimedActivationRequest(ID, 1, "worker-a", Instant.parse("2026-10-29T12:00:30Z"));
		var attempt = new PreparedActivationAttempt(ID, 2, "worker-a", claim.leaseUntil(), 1);
		var request = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		var success = new ProviderOutcome.Success();
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim));
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.of(attempt));
		when(repository.findById(ID)).thenReturn(Optional.of(request));
		when(provider.activate(new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1")))
				.thenReturn(success);

		var worker = new ActivationRequestWorker(claims, attempts, outcomes, provider, repository);

		assertThat(worker.processNext()).isTrue();

		InOrder order = inOrder(claims, attempts, repository, provider, outcomes);
		order.verify(claims).claimNext(anyString());
		order.verify(attempts).prepareAttempt(claim);
		order.verify(repository).findById(ID);
		order.verify(provider).activate(new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1"));
		order.verify(outcomes).record(attempt, success);
	}

	@Test
	void doesNotCallProviderWhenPreparingTheAttemptFails() {
		var claim = new ClaimedActivationRequest(ID, 1, "worker-a", Instant.parse("2026-10-29T12:00:30Z"));
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim));
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.empty());

		var worker = new ActivationRequestWorker(claims, attempts, outcomes, provider, repository);

		assertThat(worker.processNext()).isTrue();
		verify(provider, never()).activate(org.mockito.ArgumentMatchers.any());
		verify(repository, org.mockito.Mockito.never()).findById(ID);
	}
}
