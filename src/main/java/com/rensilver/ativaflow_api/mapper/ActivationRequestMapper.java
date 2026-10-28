package com.rensilver.ativaflow_api.mapper;

import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Converts entities to the public response contract without internal processing metadata. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
		unmappedTargetPolicy = ReportingPolicy.ERROR,
		unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface ActivationRequestMapper {

	// Every omitted source property must be explicitly reviewed as internal metadata.
	@BeanMapping(ignoreUnmappedSourceProperties = {
			"consumerId", "version", "attemptCount", "nextAttemptAt",
			"leaseUntil", "claimedBy", "lastErrorCode", "reconciliationRequired"
	})
	ActivationRequestResponse toResponse(ActivationRequest entity);
}
