package com.rensilver.ativaflow_api.client.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Testes da fronteira HTTP, sem contexto Spring, banco de dados ou containers. */
@WireMockTest
class HttpActivationProviderWireMockTests {

	private static final String PATH = "/provider/v1/activate";
	private static final ActivationProviderRequest REQUEST =
			new ActivationProviderRequest(" EXT-12345 ", "Cliente-ação", " INTERNET-500 ");

	private WireMock wireMock;
	private HttpActivationProviderClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo runtime) {
		wireMock = runtime.getWireMock();
		client = newClient(runtime.getHttpBaseUrl(), Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 201, 204})
	void confirmsSuccessWithOnePostContainingOnlyTheBusinessFields(int status) {
		wireMock.register(post(PATH).willReturn(aResponse().withStatus(status)));

		assertThat(client.activate(REQUEST)).isEqualTo(new ProviderOutcome.Success());

		verifySingleAttempt();
	}

	@ParameterizedTest
	@MethodSource("providerFailures")
	void classifiesProviderFailureWithoutRetryingOrExposingItsBody(int status, ProviderOutcome expected) {
		wireMock.register(post(PATH).willReturn(aResponse().withStatus(status)
				.withHeader("Retry-After", "120")
				.withBody("{\"detail\":\"private-provider-details\"}")));

		assertThat(client.activate(REQUEST)).isEqualTo(expected);

		verifySingleAttempt();
	}

	@ParameterizedTest
	@EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
	void preservesUnknownOutcomeWhenTheConnectionIsInterruptedWithoutRetrying(Fault fault) {
		wireMock.register(post(PATH).willReturn(aResponse().withFault(fault)));

		assertThat(client.activate(REQUEST))
				.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_COMMUNICATION_FAILURE"));

		verifySingleAttempt();
	}

	@Test
	void preservesUnknownOutcomeWhenAReceivedRequestTimesOutWithoutRetrying(WireMockRuntimeInfo runtime) {
		wireMock.register(post(PATH).willReturn(aResponse().withStatus(204).withFixedDelay(3000)));
		var impatientClient = newClient(runtime.getHttpBaseUrl(), Duration.ofMillis(500));

		assertThat(impatientClient.activate(REQUEST))
				.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_TIMEOUT"));

		verifySingleAttempt();
	}

	@Test
	void canActivateAnotherRequestWithTheSameClientAfterAProviderFailure() {
		wireMock.register(post(PATH).willReturn(aResponse().withStatus(500)));
		assertThat(client.activate(REQUEST))
				.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN"));
		verifySingleAttempt();

		wireMock.register(post(PATH).willReturn(aResponse().withStatus(204)));
		var nextRequest = new ActivationProviderRequest("EXT-NEXT", "CUSTOMER-NEXT", "PRODUCT-NEXT");

		assertThat(client.activate(nextRequest)).isEqualTo(new ProviderOutcome.Success());

		wireMock.verifyThat(2, postRequestedFor(urlEqualTo(PATH)));
		wireMock.verifyThat(1, postRequestedFor(urlEqualTo(PATH))
				.withRequestBody(matchingJsonPath("$.externalId", WireMock.equalTo(" EXT-12345 "))));
		wireMock.verifyThat(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
				{"externalId":"EXT-NEXT","customerId":"CUSTOMER-NEXT","productCode":"PRODUCT-NEXT"}
				""")));
	}

	private void verifySingleAttempt() {
		wireMock.verifyThat(1, postRequestedFor(urlEqualTo(PATH)));
		wireMock.verifyThat(1, postRequestedFor(urlEqualTo(PATH))
				.withHeader("Content-Type", containing("application/json"))
				.withRequestBody(equalToJson("""
						{"externalId":" EXT-12345 ","customerId":"Cliente-ação","productCode":" INTERNET-500 "}
						""")));
	}

	private static Stream<Arguments> providerFailures() {
		return Stream.of(
				Arguments.of(400, new ProviderOutcome.PermanentFailure("PROVIDER_REJECTED")),
				Arguments.of(500, new ProviderOutcome.Indeterminate("PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN")),
				Arguments.of(429, new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE")),
				Arguments.of(503, new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE")));
	}

	private static HttpActivationProviderClient newClient(String baseUrl, Duration readTimeout) {
		var httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(2))
				.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		return new HttpActivationProviderClient(RestClient.builder()
				.baseUrl(baseUrl).requestFactory(requestFactory).build());
	}
}
