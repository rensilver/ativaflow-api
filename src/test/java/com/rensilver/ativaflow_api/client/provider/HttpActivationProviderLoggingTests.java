package com.rensilver.ativaflow_api.client.provider;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.locks.LockSupport;

import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import com.rensilver.ativaflow_api.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpActivationProviderLoggingTests {

	private static final ActivationProviderRequest REQUEST = new ActivationProviderRequest(
			"private-external-id", "private-customer-id", "private-product-code");
	private MockRestServiceServer server;
	private HttpActivationProviderClient client;
	private LogCapture logs;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder().baseUrl("http://provider.example")
				.defaultHeader("Authorization", "Bearer private-token")
				.defaultHeader("X-API-Key", "private-api-key");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpActivationProviderClient(builder.build());
		logs = new LogCapture(HttpActivationProviderClient.class);
	}

	@AfterEach
	void tearDown() {
		logs.close();
	}

	@ParameterizedTest
	@CsvSource({
			"200, HTTP_2XX, SUCCESS", "201, HTTP_2XX, SUCCESS", "204, HTTP_2XX, SUCCESS",
			"202, HTTP_2XX, INDETERMINATE", "302, HTTP_3XX, INDETERMINATE",
			"400, HTTP_4XX, PERMANENT_FAILURE", "401, HTTP_4XX, INDETERMINATE",
			"429, HTTP_4XX, INDETERMINATE", "500, HTTP_5XX, INDETERMINATE",
			"503, HTTP_5XX, INDETERMINATE"
	})
	void logsOneCategorizedResultWithoutRequestResponseOrCredentials(int status, String category, String result) {
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andRespond(withStatus(HttpStatusCode.valueOf(status))
						.contentType(MediaType.APPLICATION_JSON).body("{\"secret\":\"private-response\"}")
						.header("Set-Cookie", "private-cookie"));

		try (var ignored = CorrelationId.withId("logging-request-1")) {
			client.activate(REQUEST);
		}

		assertEvent(category, status, result);
		assertThat(LogCapture.fields(logs.events().getFirst())).containsEntry("correlationId", "logging-request-1");
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"socket", "http", "communication"})
	void categorizesTransportFailuresWithoutExceptionDetails(String kind) {
		IOException failure = switch (kind) {
			case "socket" -> new SocketTimeoutException("private-exception-secret");
			case "http" -> new HttpTimeoutException("private-exception-secret");
			default -> new IOException("private-exception-secret");
		};
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andRespond(request -> { throw failure; });

		assertThat(client.activate(REQUEST)).isInstanceOf(ProviderOutcome.Indeterminate.class);

		assertEvent(kind.equals("communication") ? "COMMUNICATION_FAILURE" : "TIMEOUT", 0, "INDETERMINATE");
		server.verify();
	}

	@Test
	void measuresTimeSpentWaitingForTheProvider() {
		server.expect(requestTo("http://provider.example/provider/v1/activate")).andRespond(request -> {
			long end = System.nanoTime() + 20_000_000L;
			while (System.nanoTime() < end) {
				LockSupport.parkNanos(end - System.nanoTime());
			}
			return withStatus(HttpStatusCode.valueOf(204)).createResponse(request);
		});

		client.activate(REQUEST);

		assertEvent("HTTP_2XX", 204, "SUCCESS");
		assertThat((Long) LogCapture.fields(logs.events().getFirst()).get("durationMs")).isGreaterThanOrEqualTo(20);
		server.verify();
	}

	@Test
	void logsUnexpectedFailureAndPreservesTheException() {
		var failure = new IllegalStateException("private-exception-secret");
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andRespond(request -> { throw failure; });

		assertThatThrownBy(() -> client.activate(REQUEST)).isSameAs(failure);

		assertEvent("UNEXPECTED_ERROR", 0, "UNEXPECTED_ERROR");
		server.verify();
	}

	private void assertEvent(String category, int status, String result) {
		assertThat(logs.events()).hasSize(1);
		var event = logs.events().getFirst();
		assertThat(LogCapture.fields(event))
				.containsEntry("event", "activation_provider_attempt")
				.containsEntry("responseCategory", category)
				.containsEntry("httpStatus", status)
				.containsEntry("result", result);
		assertThat((Long) LogCapture.fields(event).get("durationMs")).isNotNegative();
		assertThat(event.getThrowableProxy()).isNull();
		assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
				.doesNotContain("private-", "provider.example", "Authorization", "X-API-Key", "Set-Cookie");
	}
}
