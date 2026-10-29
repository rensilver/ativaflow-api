package com.rensilver.ativaflow_api.service;

import java.util.Objects;
import java.util.Optional;

import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registra tentativas e resultados ambíguos em transações curtas, sem realizar HTTP. */
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

	/**
	 * Mantém o envio bloqueado para reconciliação, inclusive na última tentativa, e libera a posse.
	 * Recebe somente código categorizado, nunca payload ou resposta bruta do provedor.
	 * Retorna false se a posse foi perdida; a proteção persistida na preparação permanece ativa.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean recordAmbiguousOutcome(PreparedActivationAttempt attempt, @Nullable String errorCode) {
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
			throw new IllegalArgumentException("Error code must contain non-blank text of at most 100 characters");
		}
		int affectedRows = repository.tryRecordAmbiguousOutcome(attempt.id(), attempt.version(),
				attempt.claimedBy(), errorCode);
		if (affectedRows == 0) {
			return false;
		}
		if (affectedRows != 1) {
			throw new IllegalStateException("Ambiguous outcome must update exactly one activation request");
		}
		return true;
	}
}
