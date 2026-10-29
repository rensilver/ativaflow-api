package com.rensilver.ativaflow_api.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Evidência do efeito externo de uma tentativa, sem detalhes do transporte.
 * Códigos de falha devem ser categorias internas não nulas, não vazias e com até
 * 100 caracteres; nunca payloads, credenciais, mensagens ou respostas brutas.
 */
public sealed interface ProviderOutcome {

	/** Ativação concluída e confirmada pelo contrato do provedor. */
	record Success() implements ProviderOutcome {
	}

	/** Rejeição definitiva, com garantia contratual de não ativação; não autoriza retry. */
	record PermanentFailure(String errorCode) implements ProviderOutcome {
	}

	/**
	 * Falha transitória com garantia contratual de não ativação, permitindo retry seguro.
	 * O transporte sozinho não comprova essa garantia. O retry continua sujeito ao
	 * limite de tentativas e ao backoff da aplicação.
	 *
	 * @param errorCode categoria interna da falha
	 * @param retryAfter instante mínimo para novo envio, já interpretado pela integração;
	 *                   Optional.empty() quando ausente ou inválido, nunca null.
	 *                   Prazo não representável com segurança exige Indeterminate,
	 *                   preservando a pendência em vez de autorizar envio antecipado.
	 */
	record TransientFailure(String errorCode, Optional<Instant> retryAfter) implements ProviderOutcome {
	}

	/**
	 * Efeito externo desconhecido ou conclusão ainda não confirmada; exige reconciliação
	 * e não autoriza reenvio automático, inclusive na última tentativa permitida.
	 */
	record Indeterminate(String errorCode) implements ProviderOutcome {
	}
}
