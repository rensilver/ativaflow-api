package com.rensilver.ativaflow_api.service;

import java.util.Map;

import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.observability.AfterCommitLog;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isola a inserção para que uma colisão seja revertida antes da releitura pelo serviço. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivationRequestCreationTransaction {

	private final ActivationRequestRepository repository;
	private final ActivationRequestMapper mapper;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ActivationRequestResponse insert(CreateActivationRequest request, String trustedConsumerId) {
		ActivationRequest entity = ActivationRequest.create(
				request.externalId(), request.customerId(), request.productCode(), trustedConsumerId, CorrelationId.current());
		var saved = repository.saveAndFlush(entity);
		AfterCommitLog.info(log, "activation_request_created", Map.of(
				"requestId", saved.getId(), "status", saved.getStatus().name(),
				"correlationId", AfterCommitLog.correlationId(saved.getId(), saved.getCorrelationId())));
		return mapper.toResponse(saved);
	}
}
