package com.rensilver.ativaflow_api.service;

import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestPageResponse;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.exception.ActivationRequestConflictException;
import com.rensilver.ativaflow_api.exception.ActivationRequestNotFoundException;
import com.rensilver.ativaflow_api.exception.InvalidPaginationException;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivationRequestService {

	public static final int MAX_PAGE_SIZE = 100;

	private final ActivationRequestRepository repository;
	private final ActivationRequestMapper mapper;
	private final ActivationRequestCreationTransaction creationTransaction;

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

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public CreateActivationRequestResult createActivationRequest(CreateActivationRequest request, String trustedConsumerId) {
		var existing = repository.findByExternalId(request.externalId());
		if (existing.isPresent()) {
			return reuseExisting(existing.get(), request, trustedConsumerId);
		}
		try {
			return new CreateActivationRequestResult(creationTransaction.insert(request, trustedConsumerId), true);
		}
		catch (DataIntegrityViolationException exception) {
			if (!isExternalIdConflict(exception)) {
				throw exception;
			}
			// A transação de inserção já terminou com rollback; a consulta usa uma nova transação.
			return repository.findByExternalId(request.externalId())
					.map(entity -> reuseExisting(entity, request, trustedConsumerId))
					.orElseThrow(() -> exception);
		}
	}

	private CreateActivationRequestResult reuseExisting(ActivationRequest existing, CreateActivationRequest request,
			String trustedConsumerId) {
		if (!existing.getConsumerId().equals(trustedConsumerId)
				|| !existing.getCustomerId().equals(request.customerId())
				|| !existing.getProductCode().equals(request.productCode())) {
			throw new ActivationRequestConflictException();
		}
		return new CreateActivationRequestResult(mapper.toResponse(existing), false);
	}

	private boolean isExternalIdConflict(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& "uk_activation_requests_external_id".equals(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}
}
