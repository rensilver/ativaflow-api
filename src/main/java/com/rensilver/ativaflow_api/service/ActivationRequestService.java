package com.rensilver.ativaflow_api.service;

import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestPageResponse;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.exception.ActivationRequestNotFoundException;
import com.rensilver.ativaflow_api.exception.InvalidPaginationException;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivationRequestService {

	public static final int MAX_PAGE_SIZE = 100;

	private final ActivationRequestRepository repository;
	private final ActivationRequestMapper mapper;

	@Transactional(readOnly = true)
	public ActivationRequestPageResponse listActivationRequests(@Nullable ActivationStatus status, @Nullable String customerId,
			int page, int size, String trustedConsumerId) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
			throw new InvalidPaginationException();
		}
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
		Page<ActivationRequestResponse> result = repository.findAllByFilters(trustedConsumerId, status, customerId, pageable)
				.map(mapper::toResponse);
		return new ActivationRequestPageResponse(result.getContent(), result.getNumber(), result.getSize(),
				result.getTotalElements(), result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public ActivationRequestResponse getActivationRequestById(UUID id, String trustedConsumerId) {
		ActivationRequest entity = repository.findByIdAndConsumerId(id, trustedConsumerId)
				.orElseThrow(ActivationRequestNotFoundException::new);
		return mapper.toResponse(entity);
	}

	@Transactional
	public ActivationRequestResponse createActivationRequest(CreateActivationRequest request, String trustedConsumerId) {
		ActivationRequest entity = ActivationRequest.create(
				request.externalId(), request.customerId(), request.productCode(), trustedConsumerId);
		ActivationRequest saved = repository.save(entity);
		return mapper.toResponse(saved);
	}
}
