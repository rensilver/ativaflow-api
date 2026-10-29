package com.rensilver.ativaflow_api.service;

import java.time.Instant;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persiste resultados conhecidos em transações curtas; não classifica HTTP nem chama o provedor. */
@Service
public class ActivationRequestOutcomeService {

	private final ActivationRequestRepository repository;
	private final ActivationRetryPolicy retryPolicy;

	public ActivationRequestOutcomeService(ActivationRequestRepository repository, ActivationRetryPolicy retryPolicy) {
		this.repository = repository;
		this.retryPolicy = retryPolicy;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean recordSuccess(PreparedActivationAttempt attempt) {
		return record(attempt, true, false, null, 0, null);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean recordPermanentFailure(PreparedActivationAttempt attempt, @Nullable String errorCode) {
		requireErrorCode(errorCode);
		return record(attempt, false, false, errorCode, 0, null);
	}

	/**
	 * Exige garantia de não ativação externa; HTTP 500/503, timeout e Retry-After sozinhos não a comprovam.
	 * retryAfter é o instante mínimo já interpretado pela futura integração HTTP.
	 * Retorna false quando a posse/versão não permite gravar; não autoriza reenviar nesse caso.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean recordSafeTransientFailure(PreparedActivationAttempt attempt, @Nullable String errorCode,
			@Nullable Instant retryAfter) {
		requireErrorCode(errorCode);
		return record(attempt, false, true, errorCode, retryPolicy.delayMillis(attempt.attemptCount()), retryAfter);
	}

	private boolean record(PreparedActivationAttempt attempt, boolean success, boolean retryable,
			@Nullable String errorCode, long delayMillis, @Nullable Instant retryAfter) {
		int affectedRows = repository.tryRecordConfirmedOutcome(attempt.id(), attempt.version(), attempt.claimedBy(),
				attempt.attemptCount(), success, retryable, errorCode, delayMillis, retryAfter);
		if (affectedRows == 0) {
			return false;
		}
		if (affectedRows != 1) {
			throw new IllegalStateException("Confirmed outcome must update exactly one activation request");
		}
		return true;
	}

	private static void requireErrorCode(@Nullable String errorCode) {
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
			throw new IllegalArgumentException("Error code must contain non-blank text of at most 100 characters");
		}
	}
}
