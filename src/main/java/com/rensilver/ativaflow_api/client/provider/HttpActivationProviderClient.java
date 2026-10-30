package com.rensilver.ativaflow_api.client.provider;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.config.ActivationProviderProperties.RetryContract;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Uma única tentativa HTTP; respostas sem garantia contratual de efeito são indeterminadas. */
public class HttpActivationProviderClient implements ActivationProviderClient {

	private static final Logger log = LoggerFactory.getLogger(HttpActivationProviderClient.class);
	// Faixa operacional deliberadamente menor que Instant/PostgreSQL, sem truncar prazos maiores.
	private static final Instant MAX_RETRY_AT = Instant.parse("9999-12-31T23:59:59Z");

	private final RestClient restClient;
	private final RetryContract retryContract;
	private final MeterRegistry registry;

	public HttpActivationProviderClient(RestClient restClient) {
		this(restClient, RetryContract.NONE);
	}

	public HttpActivationProviderClient(RestClient restClient, RetryContract retryContract) {
		this(restClient, retryContract, new CompositeMeterRegistry());
	}

	public HttpActivationProviderClient(RestClient restClient, RetryContract retryContract, MeterRegistry registry) {
		this.restClient = restClient;
		this.retryContract = Objects.requireNonNull(retryContract);
		this.registry = Objects.requireNonNull(registry);
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
							case 429, 503 -> classifyRetryableResponse(status, response.getHeaders());
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
			Timer.builder("activation.provider.attempts")
					.description("Duration and outcome of each provider HTTP attempt")
					.tags("category", responseCategory, "result", result)
					.serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500),
							Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10))
					.register(registry).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
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

	private ProviderOutcome classifyRetryableResponse(int status, HttpHeaders headers) {
		if (retryContract != RetryContract.REJECTED_BEFORE_ACTIVATION) {
			return new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE");
		}
		String code = status == 429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_TEMPORARILY_UNAVAILABLE";
		var values = headers.get(HttpHeaders.RETRY_AFTER);
		if (values == null || values.isEmpty()) {
			return new ProviderOutcome.TransientFailure(code, Optional.empty());
		}
		if (values.size() != 1) {
			return new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED");
		}
		String value = values.getFirst().strip();
		Instant deadline = null;
		if (value.matches("[0-9]+")) {
			try {
				deadline = Instant.now().plusSeconds(Long.parseLong(value));
			}
			catch (NumberFormatException | ArithmeticException | DateTimeException exception) {
				// Um prazo numérico enorme não pode virar ausência de prazo e antecipar o envio.
				return new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED");
			}
		}
		else {
			try {
				var date = headers.getFirstZonedDateTime(HttpHeaders.RETRY_AFTER);
				deadline = date == null ? null : date.toInstant();
			}
			catch (IllegalArgumentException exception) {
				// Header malformado não elimina o backoff positivo controlado pela aplicação.
			}
		}
		if (deadline != null && deadline.isAfter(MAX_RETRY_AT)) {
			return new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED");
		}
		return new ProviderOutcome.TransientFailure(code, Optional.ofNullable(deadline));
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
