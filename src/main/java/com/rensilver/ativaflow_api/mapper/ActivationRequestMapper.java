package com.rensilver.ativaflow_api.mapper;

import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Converte entidades em respostas públicas sem metadados internos de processamento.
 * A criação de entidades cabe à fábrica de domínio, com um proprietário fornecido pela aplicação.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
		unmappedTargetPolicy = ReportingPolicy.ERROR,
		unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface ActivationRequestMapper {

	// Cada propriedade de origem omitida deve ser explicitamente revisada como metadado interno.
	@BeanMapping(ignoreUnmappedSourceProperties = {
			"consumerId", "version", "attemptCount", "nextAttemptAt",
			"leaseUntil", "claimedBy", "lastErrorCode", "reconciliationRequired"
	})
	ActivationRequestResponse toResponse(ActivationRequest entity);
}
