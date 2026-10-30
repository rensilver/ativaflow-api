package com.rensilver.ativaflow_api;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.rensilver.ativaflow_api.support.RequestTestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, ActivationRequestProcessingIntegrationTests.HttpObservation.class})
@SpringBootTest(properties = {"app.worker.enabled=true", "app.worker.poll-interval=50ms",
		"app.activation-provider.read-timeout=1s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
// This context owns an active scheduler and must stop it before WireMock is shut down.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivationRequestProcessingIntegrationTests {

	private static final String PROVIDER_PATH = "/provider/v1/activate";

	@RegisterExtension
	static final WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.activation-provider.base-url", provider::baseUrl);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private HttpObservation observation;

	@BeforeEach
	void prepare() {
		jdbc.update("DELETE FROM activation_requests");
		observation.entered = new CountDownLatch(1);
		observation.release = new CountDownLatch(1);
	}

	@AfterEach
	void releaseHttp() {
		observation.release.countDown();
	}

	@ParameterizedTest
	@CsvSource({
			"204, 0, COMPLETED, false,",
			"500, 0, PROCESSING, true, PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN",
			"204, 3000, PROCESSING, true, PROVIDER_TIMEOUT"
	})
	void commitsCreationBeforeHttpAndPersistsTheProviderOutcomeWithoutResending(
			int httpStatus, int delayMillis, String expectedStatus, boolean reconciliation, String errorCode)
			throws Exception {
		String externalId = "EXT-" + UUID.randomUUID();
		String payload = """
				{"externalId":"%s","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
				""".formatted(externalId);
		provider.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PROVIDER_PATH)
				.willReturn(aResponse().withStatus(httpStatus).withFixedDelay(delayMillis)));

		// The HTTP interceptor holds the worker until POST has returned. A synchronous
		// provider call would block this response and fail the bounded Future.get.
		try (var executor = Executors.newSingleThreadExecutor()) {
			var response = executor.submit(() -> mvc.perform(post("/api/v1/requests")
					.with(user("consumer-a")).header("X-Correlation-ID", "integration-flow")
					.contentType(MediaType.APPLICATION_JSON).content(payload))
					.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
					.andReturn().getResponse()).get(5, TimeUnit.SECONDS);
			UUID id = UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
			assertThat(response.getHeader("Location")).isEqualTo("/api/v1/requests/" + id);
			assertThat(observation.entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(observation.transactionActive).isFalse();
			// A separate connection sees both commits while the external call is blocked.
			var prepared = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
			assertThat(prepared).containsEntry("status", "PROCESSING")
					.containsEntry("attempt_count", 1).containsEntry("reconciliation_required", true);
			assertThat(prepared.get("claimed_by")).isNotNull();
			assertThat(prepared.get("lease_until")).isNotNull();
			assertThat(prepared.get("next_attempt_at")).isNull();
			observation.release.countDown();

			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				var stored = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
				assertThat(stored).containsEntry("status", expectedStatus)
						.containsEntry("attempt_count", 1).containsEntry("reconciliation_required", reconciliation)
						.containsEntry("last_error_code", errorCode);
				assertThat(stored.get("claimed_by")).isNull();
				assertThat(stored.get("lease_until")).isNull();
				assertThat(stored.get("next_attempt_at")).isNull();
			});
			mvc.perform(get(response.getHeader("Location")).with(user("consumer-a")))
					.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expectedStatus))
					.andExpect(jsonPath("$.externalId").value(externalId))
					.andExpect(jsonPath("$.length()").value(7));

			var finalRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
			await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id)).isEqualTo(finalRow);
				provider.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH))
						.withHeader("X-Correlation-ID", equalTo("integration-flow"))
						.withRequestBody(equalToJson(payload)));
			});
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class HttpObservation {
		volatile CountDownLatch entered = new CountDownLatch(1);
		volatile CountDownLatch release = new CountDownLatch(1);
		volatile boolean transactionActive;

		@Bean
		RestClientCustomizer observeProviderHttp() {
			return builder -> builder.requestInterceptor((request, body, execution) -> {
				transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
				entered.countDown();
				try {
					if (!release.await(15, TimeUnit.SECONDS)) {
						throw new IOException("Test did not release the provider call");
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException(exception);
				}
				return execution.execute(request, body);
			});
		}
	}
}
