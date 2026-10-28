package com.rensilver.ativaflow_api.dto.request;

import com.rensilver.ativaflow_api.validation.NotBlankText;
import jakarta.validation.constraints.Size;

/** Dados fornecidos pelo consumidor para criar uma solicitação de ativação. */
public record CreateActivationRequest(
		@NotBlankText(message = "externalId é obrigatório")
		@Size(max = 100, message = "externalId deve ter no máximo 100 caracteres")
		String externalId,
		@NotBlankText(message = "customerId é obrigatório")
		@Size(max = 100, message = "customerId deve ter no máximo 100 caracteres")
		String customerId,
		@NotBlankText(message = "productCode é obrigatório")
		@Size(max = 100, message = "productCode deve ter no máximo 100 caracteres")
		String productCode
) {
}
