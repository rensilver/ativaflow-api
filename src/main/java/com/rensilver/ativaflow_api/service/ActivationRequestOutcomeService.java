package com.rensilver.ativaflow_api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.rensilver.ativaflow_api.observability.AfterCommitLog;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persiste resultados conhecidos em transações curtas; não classifica HTTP nem chama o provedor. */
@Service
@Slf4j
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
	 * retryAfter é o instante mínimo já interpretado pela integração HTTP.
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
				attempt.attemptCount(), success, retryable, errorCode, delayMillis, retryAfter, retryPolicy.maxAttempts());
		if (affectedRows == 0) {
			return false;
		}
		if (affectedRows != 1) {
			throw new IllegalStateException("Confirmed outcome must update exactly one activation request");
		}
		if (retryable && !success && attempt.attemptCount() < retryPolicy.maxAttempts()) {
			// Relê o prazo calculado pelo PostgreSQL, incluindo backoff/jitter e Retry-After.
			repository.findById(attempt.id()).ifPresent(request -> AfterCommitLog.info(log,
					"activation_retry_scheduled", Map.of(
							"requestId", request.getId(), "attempt", request.getAttemptCount(),
							"nextAttemptAt", Objects.requireNonNull(request.getNextAttemptAt()),
							"correlationId", AfterCommitLog.correlationId(request.getId(), request.getCorrelationId()))));
		}
		return true;
	}

	private static void requireErrorCode(@Nullable String errorCode) {
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
			throw new IllegalArgumentException("Error code must contain non-blank text of at most 100 characters");
		}
	}
}
