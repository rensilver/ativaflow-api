package com.rensilver.ativaflow_api.client.provider;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Uma única tentativa HTTP; respostas sem garantia contratual de efeito são indeterminadas. */
public class HttpActivationProviderClient implements ActivationProviderClient {

	private final RestClient restClient;

	public HttpActivationProviderClient(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public ProviderOutcome activate(ActivationProviderRequest request) {
		try {
			return restClient.post()
					.uri("/provider/v1/activate")
					.contentType(MediaType.APPLICATION_JSON)
					.headers(headers -> {
						String correlationId = CorrelationId.current();
						if (correlationId != null) {
							headers.set(CorrelationId.HEADER, correlationId);
						}
					})
					.body(request)
					.exchange((httpRequest, response) -> {
						return switch (response.getStatusCode().value()) {
							// Somente estes status confirmam a conclusão conforme o contrato conhecido.
							case 200, 201, 204 -> new ProviderOutcome.Success();
							// Rejeição contratual definitiva: código interno fixo, sem corpo ou Retry-After.
							case 400 -> new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED");
							// Um 500 pode ocorrer depois de o provedor efetivar a ativação; não reenvia automaticamente.
							case 500 -> new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN");
							// Status e Retry-After não provam que o provedor deixou de ativar o produto.
							default -> new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE");
						};
					});
		}
		catch (RestClientException exception) {
			// Timeout não prova que o provedor deixou de efetivar a ativação.
			return new ProviderOutcome.Indeterminate(isTimeout(exception)
					? "PROVIDER_TIMEOUT" : "PROVIDER_COMMUNICATION_FAILURE");
		}
	}

	private static boolean isTimeout(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}
}
