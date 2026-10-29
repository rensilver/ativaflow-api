package com.rensilver.ativaflow_api.client.provider;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Uma única tentativa HTTP, conforme o contrato simulado da ADR-0002. */
public class HttpActivationProviderClient implements ActivationProviderClient {

	private final RestClient restClient;
	private final Clock clock;

	public HttpActivationProviderClient(RestClient restClient, Clock clock) {
		this.restClient = restClient;
		this.clock = clock;
	}

	@Override
	public ProviderOutcome activate(ActivationProviderRequest request) {
		try {
			return restClient.post()
					.uri("/provider/v1/activate")
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.exchange((httpRequest, response) -> switch (response.getStatusCode().value()) {
						case 200, 201, 204 -> new ProviderOutcome.Success();
						case 400 -> new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED");
						// A garantia de não ativação pertence ao contrato simulado, não ao status HTTP.
						case 429, 500, 503 -> safeTransientFailure(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
						default -> new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE");
					});
		}
		catch (RestClientException exception) {
			// Nunca propagar corpo bruto, credenciais ou mensagens do transporte ao domínio.
			return new ProviderOutcome.Indeterminate("PROVIDER_COMMUNICATION_FAILURE");
		}
	}

	private ProviderOutcome safeTransientFailure(@Nullable String retryAfter) {
		try {
			return new ProviderOutcome.TransientFailure("PROVIDER_TEMPORARILY_UNAVAILABLE", parseRetryAfter(retryAfter));
		}
		catch (ArithmeticException | DateTimeException | NumberFormatException exception) {
			return new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_OUT_OF_RANGE");
		}
	}

	private Optional<Instant> parseRetryAfter(@Nullable String header) {
		if (header == null || header.isBlank()) {
			return Optional.empty();
		}
		String value = header.strip();
		Instant now = clock.instant();
		Instant deadline;
		if (value.chars().allMatch(character -> character >= '0' && character <= '9')) {
			deadline = now.plusSeconds(Long.parseLong(value));
		}
		else {
			try {
				deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			}
			catch (DateTimeParseException exception) {
				return Optional.empty();
			}
		}
		// O agendamento persistido usa precisão de milissegundos; overflow não autoriza retry antecipado.
		deadline.toEpochMilli();
		return deadline.isBefore(now) ? Optional.empty() : Optional.of(deadline);
	}
}
