package com.rensilver.ativaflow_api.client.provider;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class HttpActivationProviderCorrelationTests {

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

	@AfterEach
	void clearContext() {
		MDC.clear();
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void propagatesOnlyCorrelationEvenWhenConsumerJwtAndRequestHeadersAreAvailable() {
		var jwt = Jwt.withTokenValue("consumer.jwt.signature").header("alg", "RS256")
				.subject("consumer-a").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		var incoming = new MockHttpServletRequest();
		incoming.addHeader(HttpHeaders.AUTHORIZATION, "Bearer consumer.jwt.signature");
		incoming.addHeader(HttpHeaders.COOKIE, "session=consumer-secret");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));
		MDC.put("correlationId", "consumer-request-123");

		expectActivation().andExpect(header("X-Correlation-ID", "consumer-request-123"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
				.andExpect(content().json("""
						{"externalId":"EXT-1","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
						""", JsonCompareMode.STRICT))
				.andRespond(withNoContent());

		assertThat(client.activate(REQUEST)).isEqualTo(new ProviderOutcome.Success());
		server.verify();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "bad\r\nAuthorization: secret", "Bearer consumer.jwt.signature"})
	void omitsAbsentOrUnsafeCorrelation(String correlationId) {
		if (correlationId != null) {
			MDC.put("correlationId", correlationId);
		}
		expectActivation().andExpect(headerDoesNotExist("X-Correlation-ID"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION)).andRespond(withNoContent());

		assertThat(client.activate(REQUEST)).isEqualTo(new ProviderOutcome.Success());
		server.verify();
	}

	@Test
	void readsCorrelationForEachAttemptWithoutLeakingItToTheNextRequest() {
		expectActivation().andExpect(header("X-Correlation-ID", "first-request")).andRespond(withNoContent());
		expectActivation().andExpect(header("X-Correlation-ID", "second-request")).andRespond(withNoContent());
		expectActivation().andExpect(headerDoesNotExist("X-Correlation-ID")).andRespond(withNoContent());

		MDC.put("correlationId", "first-request");
		client.activate(REQUEST);
		MDC.put("correlationId", "second-request");
		client.activate(REQUEST);
		MDC.remove("correlationId");
		client.activate(REQUEST);

		server.verify();
	}

	private org.springframework.test.web.client.ResponseActions expectActivation() {
		return server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andExpect(method(HttpMethod.POST));
	}
}
