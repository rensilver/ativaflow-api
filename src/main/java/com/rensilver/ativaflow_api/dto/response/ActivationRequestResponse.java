package com.rensilver.ativaflow_api.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;

/** Representação pública de uma solicitação, com datas em UTC. */
public record ActivationRequestResponse(
		UUID id,
		String externalId,
		String customerId,
		String productCode,
		ActivationStatus status,
		Instant createdAt,
		Instant updatedAt
) {
}
