package com.rensilver.ativaflow_api.client.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.config.ActivationProviderConfiguration;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class HttpActivationProviderClientTests {

	private static final String PATH = "/provider/v1/activate";
	private static final ActivationProviderRequest REQUEST =
			new ActivationProviderRequest(" EXT-12345 ", "Cliente-ação", " INTERNET-500 ");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					JacksonAutoConfiguration.class, RestClientAutoConfiguration.class))
			.withUserConfiguration(ActivationProviderConfiguration.class);

	@Test
	void sendsOnlyThePersistedBusinessFieldsToTheConfiguredBaseUrl(WireMockRuntimeInfo wireMock) {
		stubFor(post("/gateway" + PATH).willReturn(aResponse().withStatus(204)));

		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl() + "/gateway/")
				.run(context -> {
					assertThat(context).hasSingleBean(ActivationProviderClient.class);
					assertThat(context.getBean(ActivationProviderClient.class).activate(REQUEST))
							.isEqualTo(new ProviderOutcome.Success());
				});

		verify(1, postRequestedFor(urlEqualTo("/gateway" + PATH))
				.withHeader("Content-Type", containing("application/json"))
				.withoutHeader("Authorization")
				.withoutHeader("Idempotency-Key")
				.withRequestBody(equalToJson("""
						{"externalId":" EXT-12345 ","customerId":"Cliente-ação","productCode":" INTERNET-500 "}
						""")));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 201, 204})
	void treatsContractedSuccessStatusesAsCompletedWithoutParsingABody(int status, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(status).withBody("not a JSON response")));
		assertOutcome(wireMock, new ProviderOutcome.Success());
	}

	@Test
	void mapsContractedRejectionWithoutExposingTheResponseBody(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(400).withBody("sensitive provider details")));
		assertOutcome(wireMock, new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"120", "invalid", "9999999999999999999999999999999999999999"})
	void badRequestRemainsPermanentRegardlessOfRetryAfter(String retryAfter, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(400)
				.withHeader("Retry-After", retryAfter)
				.withBody("{\"detail\":\"customer data and provider credentials\"}")));
		assertOutcome(wireMock, new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED"));
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void treatsUncontractedRateLimitAndUnavailableResponsesAsIndeterminate(int status, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(status)));
		assertOutcome(wireMock, new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE"));
	}

	@Test
	void treatsInternalServerErrorAsIndeterminateEvenWhenRetryAfterIsPresent(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(500)
				.withHeader("Retry-After", "120")
				.withBody("provider may have activated the product")));
		assertOutcome(wireMock, new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN"));
	}

	@ParameterizedTest
	@ValueSource(ints = {202, 205, 301, 302, 303, 307, 308, 401, 403, 404, 409, 429, 502, 503, 504})
	void preservesUncontractedOutcomesAndDoesNotFollowRedirects(int status, WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(status)
				.withHeader("Location", wireMock.getHttpBaseUrl() + "/redirect-target")));
		stubFor(any(urlEqualTo("/redirect-target")).willReturn(aResponse().withStatus(204)));
		assertOutcome(wireMock, new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE"));
		verify(0, anyRequestedFor(urlEqualTo("/redirect-target")));
	}

	@Test
	void retryAfterDoesNotMakeAnUncontractedUnavailableResponseSafe(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "120")));
		assertOutcome(wireMock, new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE"));
	}

	@Test
	void configuredReadTimeoutProducesAnIndeterminateResultWithoutRetry(WireMockRuntimeInfo wireMock) {
		stubFor(post(PATH).willReturn(aResponse().withStatus(204).withFixedDelay(1000)));
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl(),
				"app.activation-provider.connect-timeout=200ms", "app.activation-provider.read-timeout=100ms")
				.run(context -> assertThat(context.getBean(ActivationProviderClient.class).activate(REQUEST))
						.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_TIMEOUT")));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void connectionFailureProducesAnIndeterminateResult() {
		contextRunner.withPropertyValues("app.activation-provider.base-url=http://localhost:1")
				.run(context -> assertThat(context.getBean(ActivationProviderClient.class).activate(REQUEST))
						.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_COMMUNICATION_FAILURE")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/relative", "ftp://localhost", "http://", "http://user:secret@localhost",
			"http://localhost?secret=value", "http://localhost#fragment"})
	void rejectsInvalidBaseUrlsAtStartup(String baseUrl) {
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + baseUrl)
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void requiresAnExplicitBaseUrl() {
		contextRunner.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"app.activation-provider.connect-timeout=0ms", "app.activation-provider.connect-timeout=-1ms",
			"app.activation-provider.read-timeout=0ms", "app.activation-provider.read-timeout=-1ms",
			"app.activation-provider.connect-timeout=999999ns", "app.activation-provider.read-timeout=2147483648ms",
			"app.activation-provider.read-timeout=invalid"})
	void rejectsUnboundedOrNegativeTimeouts(String property, WireMockRuntimeInfo wireMock) {
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl(), property)
				.run(context -> assertThat(context).hasFailed());
	}

	private void assertOutcome(WireMockRuntimeInfo wireMock, ProviderOutcome expected) {
		contextRunner.withPropertyValues("app.activation-provider.base-url=" + wireMock.getHttpBaseUrl())
				.run(context -> assertThat(context.getBean(ActivationProviderClient.class).activate(REQUEST)).isEqualTo(expected));
		verify(1, postRequestedFor(urlEqualTo(PATH)));
	}
}
