package com.rensilver.ativaflow_api.service;

import java.util.Objects;
import java.util.Optional;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persiste a preparação em transação curta; o chamador só pode iniciar HTTP após o retorno com sucesso. */
@Service
public class ActivationRequestAttemptService {

	private final ActivationRequestRepository repository;

	public ActivationRequestAttemptService(ActivationRequestRepository repository) {
		this.repository = repository;
	}

	/** Vazio significa perda de posse ou envio inseguro: não autoriza chamar o provedor. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<PreparedActivationAttempt> prepareAttempt(ClaimedActivationRequest claim) {
		int affectedRows = repository.tryPrepareAttempt(claim.id(), claim.version(), claim.claimedBy());
		if (affectedRows == 0) {
			return Optional.empty();
		}
		if (affectedRows != 1) {
			throw new IllegalStateException("Preparation must update exactly one activation request");
		}
		var prepared = repository.findById(claim.id()).orElseThrow();
		return Optional.of(new PreparedActivationAttempt(prepared.getId(),
				Objects.requireNonNull(prepared.getVersion()), Objects.requireNonNull(prepared.getClaimedBy()),
				Objects.requireNonNull(prepared.getLeaseUntil()), prepared.getAttemptCount()));
	}
}
