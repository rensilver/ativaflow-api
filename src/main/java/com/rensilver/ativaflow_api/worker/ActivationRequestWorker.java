package com.rensilver.ativaflow_api.worker;

import java.util.UUID;

import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Polls durable pending requests; each database step commits before the next one runs. */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationRequestWorker {

	private final ActivationRequestClaimService claims;
	private final ActivationRequestAttemptService attempts;
	private final ActivationRequestOutcomeHandler outcomes;
	private final ActivationProviderClient provider;
	private final ActivationRequestRepository repository;
	private final String workerId = UUID.randomUUID().toString();

	public ActivationRequestWorker(ActivationRequestClaimService claims, ActivationRequestAttemptService attempts,
			ActivationRequestOutcomeHandler outcomes, ActivationProviderClient provider,
			ActivationRequestRepository repository) {
		this.claims = claims;
		this.attempts = attempts;
		this.outcomes = outcomes;
		this.provider = provider;
		this.repository = repository;
	}

	/** Runs after the configured delay and drains all currently available work. */
	@Scheduled(fixedDelayString = "${app.worker.poll-interval:250ms}")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void poll() {
		while (processNext()) {
			// Keep claiming until no eligible item remains.
		}
	}

	/** Returns true when a candidate was claimed, even if its preparation became stale. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public boolean processNext() {
		var claim = claims.claimNext(workerId);
		if (claim.isEmpty()) {
			return false;
		}

		var attempt = attempts.prepareAttempt(claim.orElseThrow());
		if (attempt.isEmpty()) {
			return true;
		}

		var request = repository.findById(attempt.orElseThrow().id()).orElseThrow(
				() -> new IllegalStateException("Prepared activation request no longer exists"));
		// O worker pode executar em outra thread/instância: a fonte é o registro persistido, nunca o MDC herdado.
		String correlationId = CorrelationId.isValid(request.getCorrelationId())
				? request.getCorrelationId() : request.getId().toString();
		try (var ignored = CorrelationId.withId(correlationId)) {
			var outcome = provider.activate(new ActivationProviderRequest(
					request.getExternalId(), request.getCustomerId(), request.getProductCode()));
			outcomes.record(attempt.orElseThrow(), outcome);
		}
		return true;
	}
}
