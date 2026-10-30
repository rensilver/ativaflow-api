package com.rensilver.ativaflow_api.service;

import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import org.springframework.stereotype.Service;

/** Encaminha o resultado do provedor à transição persistida correspondente. */
@Service
public class ActivationRequestOutcomeHandler {

	private final ActivationRequestAttemptService attemptService;
	private final ActivationRequestOutcomeService outcomeService;

	public ActivationRequestOutcomeHandler(ActivationRequestAttemptService attemptService,
			ActivationRequestOutcomeService outcomeService) {
		this.attemptService = attemptService;
		this.outcomeService = outcomeService;
	}

	/**
	 * Persiste a transição indicada pelo resultado. Retorna false quando a posse ou versão
	 * da tentativa já não permite gravar o resultado.
	 */
	public boolean record(PreparedActivationAttempt attempt, ProviderOutcome outcome) {
		return switch (outcome) {
			case ProviderOutcome.Success success -> outcomeService.recordSuccess(attempt);
			case ProviderOutcome.PermanentFailure failure ->
					outcomeService.recordPermanentFailure(attempt, failure.errorCode());
			case ProviderOutcome.TransientFailure failure -> outcomeService.recordSafeTransientFailure(
					attempt, failure.errorCode(), failure.retryAfter().orElse(null));
			case ProviderOutcome.Indeterminate indeterminate ->
					attemptService.recordAmbiguousOutcome(attempt, indeterminate.errorCode());
		};
	}
}
