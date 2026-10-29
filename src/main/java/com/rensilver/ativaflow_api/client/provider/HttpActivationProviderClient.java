package com.rensilver.ativaflow_api.client.provider;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Uma única tentativa HTTP; respostas sem garantia contratual de efeito são indeterminadas. */
public class HttpActivationProviderClient implements ActivationProviderClient {

	private static final Logger log = LoggerFactory.getLogger(HttpActivationProviderClient.class);

	private final RestClient restClient;

	public HttpActivationProviderClient(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public ProviderOutcome activate(ActivationProviderRequest request) {
		long startedAt = System.nanoTime();
		int httpStatus = 0;
		String responseCategory = "UNEXPECTED_ERROR";
		String result = "UNEXPECTED_ERROR";
		try {
			var responseResult = restClient.post()
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
						int status = response.getStatusCode().value();
						ProviderOutcome outcome = switch (status) {
							// Somente estes status confirmam a conclusão conforme o contrato conhecido.
							case 200, 201, 204 -> new ProviderOutcome.Success();
							// Rejeição contratual definitiva: código interno fixo, sem corpo ou Retry-After.
							case 400 -> new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED");
							// Um 500 pode ocorrer depois de o provedor efetivar a ativação; não reenvia automaticamente.
							case 500 -> new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN");
							// Status e Retry-After não provam que o provedor deixou de ativar o produto.
							default -> new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE");
						};
						return new ResponseResult(status, outcome);
					});
			httpStatus = responseResult.status();
			responseCategory = "HTTP_" + httpStatus / 100 + "XX";
			result = switch (responseResult.outcome()) {
				case ProviderOutcome.Success success -> "SUCCESS";
				case ProviderOutcome.PermanentFailure failure -> "PERMANENT_FAILURE";
				case ProviderOutcome.TransientFailure failure -> "TRANSIENT_FAILURE";
				case ProviderOutcome.Indeterminate indeterminate -> "INDETERMINATE";
			};
			return responseResult.outcome();
		}
		catch (RestClientException exception) {
			boolean timeout = isTimeout(exception);
			responseCategory = timeout ? "TIMEOUT" : "COMMUNICATION_FAILURE";
			result = "INDETERMINATE";
			// Timeout não prova que o provedor deixou de efetivar a ativação.
			return new ProviderOutcome.Indeterminate(timeout
					? "PROVIDER_TIMEOUT" : "PROVIDER_COMMUNICATION_FAILURE");
		}
		finally {
			// Lista explícita de campos técnicos: nunca incluir request, response, URL ou exception.
			log.atInfo().addKeyValue("event", "activation_provider_attempt")
					.addKeyValue("correlationId", CorrelationId.current())
					.addKeyValue("httpStatus", httpStatus)
					.addKeyValue("responseCategory", responseCategory)
					.addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
					.addKeyValue("result", result)
					.log("Activation provider attempt finished");
		}
	}

	private record ResponseResult(int status, ProviderOutcome outcome) {
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
