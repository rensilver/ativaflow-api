package com.rensilver.ativaflow_api.client.provider;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Classificação sem socket, contexto Spring ou espera real pelo timeout. */
class HttpActivationProviderResilienceUnitTests {

	private static final ActivationProviderRequest REQUEST =
			new ActivationProviderRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1");

	private MockRestServiceServer server;
	private HttpActivationProviderClient client;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder().baseUrl("http://provider.example");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpActivationProviderClient(builder.build());
	}

	@ParameterizedTest(name = "HTTP {0}, Retry-After={1}")
	@MethodSource("unconfirmedResponses")
	void retryAfterNeverAuthorizesAnotherHttpAttemptWithoutANonActivationGuarantee(int status, String retryAfter) {
		var response = withStatus(HttpStatusCode.valueOf(status)).body("private-provider-details");
		if (retryAfter != null) {
			response.header(HttpHeaders.RETRY_AFTER, retryAfter);
		}
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andExpect(method(HttpMethod.POST)).andRespond(response);

		assertThat(client.activate(REQUEST))
				.isEqualTo(new ProviderOutcome.Indeterminate("PROVIDER_UNCONFIRMED_RESPONSE"));
		// A segunda chamada falharia: o transporte permite exatamente uma requisição.
		server.verify();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("communicationFailures")
	void classifiesTransportFailureWithoutRetrying(String scenario, IOException failure, String expectedCode) {
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andExpect(method(HttpMethod.POST)).andRespond(withException(failure));

		assertThat(client.activate(REQUEST)).isEqualTo(new ProviderOutcome.Indeterminate(expectedCode));
		server.verify();
	}

	private static Stream<Arguments> unconfirmedResponses() {
		return Stream.of(429, 503).flatMap(status -> Stream.of(
				null, "0", "120", "Wed, 01 Jan 2098 00:00:00 GMT", "Thu, 01 Jan 1970 00:00:00 GMT",
				"invalid", "-1", "9999999999999999999999999999999999999999")
				.map(header -> Arguments.of(status, header)));
	}

	private static Stream<Arguments> communicationFailures() {
		return Stream.of(
				Arguments.of("connection timeout", new HttpConnectTimeoutException("private endpoint"), "PROVIDER_TIMEOUT"),
				Arguments.of("HTTP read timeout", new HttpTimeoutException("private endpoint"), "PROVIDER_TIMEOUT"),
				Arguments.of("socket read timeout", new SocketTimeoutException("private endpoint"), "PROVIDER_TIMEOUT"),
				Arguments.of("nested timeout", new IOException("private endpoint",
						new IOException("transport wrapper", new SocketTimeoutException("read timed out"))), "PROVIDER_TIMEOUT"),
				Arguments.of("other I/O failure", new IOException("private endpoint"), "PROVIDER_COMMUNICATION_FAILURE"));
	}
}
