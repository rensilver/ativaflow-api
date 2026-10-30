package com.rensilver.ativaflow_api.service;

import java.util.Map;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import com.rensilver.ativaflow_api.observability.AfterCommitLog;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Remove a posse vencida sem alterar a evidência de segurança do próximo envio. */
@Service
@Slf4j
public class ActivationRequestLeaseRecoveryService {

	private final ActivationRequestRepository repository;
	private final int maxAttempts;

	public ActivationRequestLeaseRecoveryService(ActivationRequestRepository repository,
			ActivationRetryProperties retryProperties) {
		this.repository = repository;
		this.maxAttempts = retryProperties.maxAttempts();
	}

	/** Retorna a quantidade liberada em um lote curto, confirmado independentemente do chamador. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recoverExpiredLeases(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("Recovery batch size must be positive");
		}
		int recovered = repository.releaseExpiredLeases(batchSize, maxAttempts);
		if (recovered > 0) {
			AfterCommitLog.info(log, "activation_leases_recovered", Map.of("recoveredCount", recovered));
		}
		return recovered;
	}
}
