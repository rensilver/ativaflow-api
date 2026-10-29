package com.rensilver.ativaflow_api.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Adquire uma solicitação por transação; o retorno só é entregue após o commit pelo proxy Spring. */
@Service
@EnableConfigurationProperties(ActivationRetryProperties.class)
public class ActivationRequestClaimService {

	private final ActivationRequestRepository repository;
	private final long leaseMillis;
	private final int maxAttempts;

	public ActivationRequestClaimService(ActivationRequestRepository repository,
			@Value("${app.worker.lease-duration:30s}") Duration leaseDuration, ActivationRetryProperties retryProperties) {
		this.repository = repository;
		this.maxAttempts = retryProperties.maxAttempts();
		this.leaseMillis = leaseDuration.toMillis();
		if (leaseMillis < 1) {
			throw new IllegalArgumentException("Lease duration must be at least one millisecond");
		}
	}

	/** O claim não autoriza HTTP: é necessário confirmar a preparação via ActivationRequestAttemptService. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<ClaimedActivationRequest> claimNext(@Nullable String workerId) {
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("Worker id must contain non-blank text of at most 100 characters");
		}
		var candidate = repository.findNextClaimCandidate(maxAttempts);
		if (candidate.isEmpty()) {
			return Optional.empty();
		}
		var request = candidate.orElseThrow();
		int affectedRows = repository.tryClaim(request.getId(), Objects.requireNonNull(request.getVersion()),
				workerId, leaseMillis, maxAttempts);
		if (affectedRows == 0) {
			return Optional.empty();
		}
		if (affectedRows != 1) {
			throw new IllegalStateException("Claim must update exactly one activation request");
		}
		// O update nativo limpou o contexto JPA: a releitura contém a versão e o lease persistidos.
		var claimed = repository.findById(request.getId()).orElseThrow();
		return Optional.of(new ClaimedActivationRequest(claimed.getId(),
				Objects.requireNonNull(claimed.getVersion()), workerId, Objects.requireNonNull(claimed.getLeaseUntil())));
	}
}
