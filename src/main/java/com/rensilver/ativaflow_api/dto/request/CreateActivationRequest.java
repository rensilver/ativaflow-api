package com.rensilver.ativaflow_api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Dados fornecidos pelo consumidor para criar uma solicitação de ativação. */
public record CreateActivationRequest(
		@NotBlank(message = "externalId é obrigatório")
		@Size(max = 100, message = "externalId deve ter no máximo 100 caracteres")
		String externalId,
		@NotBlank(message = "customerId é obrigatório")
		@Size(max = 100, message = "customerId deve ter no máximo 100 caracteres")
		String customerId,
		@NotBlank(message = "productCode é obrigatório")
		@Size(max = 100, message = "productCode deve ter no máximo 100 caracteres")
		String productCode
) {
}
