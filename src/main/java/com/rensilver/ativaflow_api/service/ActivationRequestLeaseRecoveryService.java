package com.rensilver.ativaflow_api.service;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Remove a posse vencida sem alterar a evidência de segurança do próximo envio. */
@Service
public class ActivationRequestLeaseRecoveryService {

	private final ActivationRequestRepository repository;

	public ActivationRequestLeaseRecoveryService(ActivationRequestRepository repository) {
		this.repository = repository;
	}

	/** Retorna a quantidade liberada em um lote curto, confirmado independentemente do chamador. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recoverExpiredLeases(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("Recovery batch size must be positive");
		}
		return repository.releaseExpiredLeases(batchSize);
	}
}
