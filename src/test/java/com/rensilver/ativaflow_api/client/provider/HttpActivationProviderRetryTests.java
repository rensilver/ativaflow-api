package com.rensilver.ativaflow_api.client.provider;

import java.time.Instant;
import java.util.Optional;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.config.ActivationProviderConfiguration;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/** Contrato opt-in: 429/503 rejeitam antes da ativação e não deixam trabalho remoto pendente. */
@WireMockTest
class HttpActivationProviderRetryTests {

	private static final String PATH = "/provider/v1/activate";
	private static final ActivationProviderRequest REQUEST =
			new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, ValidationAutoConfiguration.class))
			.withUserConfiguration(ActivationProviderConfiguration.class)
			.withPropertyValues("app.activation-provider.retry-contract=rejected-before-activation");

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void selectedContractProducesSafeFailureAndInterpretsDelaySeconds(int status, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(status).withHeader("Retry-After", "120")
				.withBody("private provider details")));
		withClient(wireMock, client -> {
			Instant minimum = Instant.now().plusSeconds(120);
			var outcome = client.activate(REQUEST);
			Instant maximum = Instant.now().plusSeconds(120);
			assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.TransientFailure.class, failure -> {
				assertThat(failure.errorCode()).isEqualTo(status == 429
						? "PROVIDER_RATE_LIMITED" : "PROVIDER_TEMPORARILY_UNAVAILABLE");
				assertThat(failure.retryAfter()).hasValueSatisfying(deadline ->
						assertThat(deadline).isBetween(minimum, maximum));
			});
		});
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"Fri, 31 Oct 2098 12:00:00 GMT", "Thu, 01 Jan 1970 00:00:00 GMT"})
	void interpretsHttpDateWithoutClampingToBackoff(String header, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", header)));
		Instant expected = header.startsWith("Fri") ? Instant.parse("2098-10-31T12:00:00Z") : Instant.EPOCH;
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(
				new ProviderOutcome.TransientFailure("PROVIDER_TEMPORARILY_UNAVAILABLE", Optional.of(expected))));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"invalid", "-1", "+120", "1.5"})
	void absentOrInvalidDeadlineLeavesSafeFailureToPositiveBackoff(String header, WireMockRuntimeInfo wireMock) {
		var response = aResponse().withStatus(429);
		if (header != null) {
			response.withHeader("Retry-After", header);
		}
		stubFor(post(PATH).willReturn(response));
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(
				new ProviderOutcome.TransientFailure("PROVIDER_RATE_LIMITED", Optional.empty())));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "000", " 0 "})
	void acceptsZeroDelayWithoutRemovingApplicationBackoff(String header, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(429).withHeader("Retry-After", header)));
		withClient(wireMock, client -> {
			Instant minimum = Instant.now();
			assertThat(client.activate(REQUEST)).isInstanceOfSatisfying(ProviderOutcome.TransientFailure.class,
					failure -> assertThat(failure.retryAfter()).hasValueSatisfying(deadline ->
							assertThat(deadline).isBetween(minimum, Instant.now())));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"9999999999999999999999999999999999999999", "9223372036854775807", "315537897600"})
	void unrepresentableDeadlineBlocksRetryInsteadOfShorteningIt(String header, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", header)));
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(
				new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED")));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void largestSupportedHttpDateRemainsADeadline(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(503)
				.withHeader("Retry-After", "Fri, 31 Dec 9999 23:59:59 GMT")));
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(
				new ProviderOutcome.TransientFailure("PROVIDER_TEMPORARILY_UNAVAILABLE",
						Optional.of(Instant.parse("9999-12-31T23:59:59Z")))));
	}

	@Test
	void repeatedDeadlinesBlockRetryInsteadOfChoosingOne(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "120", "240")));
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(
				new ProviderOutcome.Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED")));
	}

	@ParameterizedTest
	@ValueSource(ints = {202, 400, 500, 502, 504})
	void selectedContractDoesNotChangeOtherStatuses(int status, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(status).withHeader("Retry-After", "120")));
		ProviderOutcome expected = switch (status) {
			case 400 -> new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED");
			case 500 -> new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN");
			default -> new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE");
		};
		withClient(wireMock, client -> assertThat(client.activate(REQUEST)).isEqualTo(expected));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void invalidContractPreventsStartup(WireMockRuntimeInfo wireMock) {
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl(),
				"app.activation-provider.retry-contract=always-retry")
				.run(context -> assertThat(context).hasFailed());
	}

	private void withClient(WireMockRuntimeInfo wireMock, java.util.function.Consumer<ActivationProviderClient> assertion) {
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl())
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertion.accept(context.getBean(ActivationProviderClient.class));
				});
	}
}
