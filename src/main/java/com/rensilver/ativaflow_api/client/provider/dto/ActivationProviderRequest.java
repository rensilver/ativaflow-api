package com.rensilver.ativaflow_api.client.provider.dto;

/**
 * Dados de negócio da ativação, separados dos DTOs públicos e da entidade persistida.
 * Os valores devem ser fornecidos já validados, sem normalização, conforme a ADR-0002:
 * texto não nulo, não vazio segundo String.isBlank() e com até 100 unidades UTF-16.
 * Correlação e credenciais são metadados da integração e não fazem parte deste payload.
 */
public record ActivationProviderRequest(String externalId, String customerId, String productCode) {
}
