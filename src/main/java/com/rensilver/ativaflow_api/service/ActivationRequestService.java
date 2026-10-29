package com.rensilver.ativaflow_api.service;

import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivationRequestService {

	private final ActivationRequestRepository repository;
	private final ActivationRequestMapper mapper;

	@Transactional
	public ActivationRequestResponse create(CreateActivationRequest request, String trustedConsumerId) {
		ActivationRequest entity = ActivationRequest.create(
				request.externalId(), request.customerId(), request.productCode(), trustedConsumerId);
		ActivationRequest saved = repository.save(entity);
		return mapper.toResponse(saved);
	}
}
