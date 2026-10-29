package com.rensilver.ativaflow_api.client.provider;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;

/**
 * Porta de saída para ativação, independente do transporte utilizado pelo provedor.
 * A implementação traduz o protocolo externo para resultados de negócio.
 */
@FunctionalInterface
public interface ActivationProviderClient {

	/**
	 * Executa uma única tentativa, sem retry implícito e fora de transação de banco.
	 * Falhas esperadas da integração devem ser traduzidas para {@link ProviderOutcome};
	 * ausência de confirmação confiável deve produzir {@link ProviderOutcome.Indeterminate}.
	 * Persistência, transições de estado e agendamento de retries pertencem à aplicação.
	 *
	 * @param request dados persistidos válidos, não nulos, preservados sem normalização
	 * @return resultado não nulo da tentativa, sem status, headers ou corpo bruto do transporte
	 */
	ProviderOutcome activate(ActivationProviderRequest request);
}
