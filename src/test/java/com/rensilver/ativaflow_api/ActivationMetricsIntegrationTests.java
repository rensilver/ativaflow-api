package com.rensilver.ativaflow_api;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.http.Fault;
import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.client.provider.dto.ActivationProviderRequest;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.domain.ProviderOutcome;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.activation-provider.read-timeout=300ms",
		"app.worker.enabled=true", "app.worker.poll-interval=1h",
		"app.worker.retry.initial-delay=100ms", "app.worker.retry.max-delay=100ms",
		"app.worker.retry.jitter-ratio=0",
		"app.activation-provider.retry-contract=rejected-before-activation",
		"app.metrics.state-refresh-interval=100ms"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivationMetricsIntegrationTests {

	@RegisterExtension
	static final WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry properties) {
		properties.add("app.activation-provider.base-url", provider::baseUrl);
	}

	@Autowired private ActivationProviderClient client;
	@Autowired private MeterRegistry registry;
	@Autowired private ActivationRequestRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MockMvc mvc;
	@Autowired private ActivationRequestWorker worker;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@ParameterizedTest
	@CsvSource({"204,0,HTTP_2XX,SUCCESS", "400,0,HTTP_4XX,PERMANENT_FAILURE",
			"503,0,HTTP_5XX,TRANSIENT_FAILURE", "500,0,HTTP_5XX,INDETERMINATE",
			"204,1500,TIMEOUT,INDETERMINATE"})
	void exportsDurationAndBoundedOutcomeForEachRealHttpAttempt(int statusCode, int delay,
			String category, String result) throws Exception {
		provider.stubFor(post("/provider/v1/activate").willReturn(aResponse()
				.withStatus(statusCode).withFixedDelay(delay)));
		var previous = registry.find("activation.provider.attempts")
				.tags("category", category, "result", result).timer();
		long before = previous == null ? 0 : previous.count();

		client.activate(new ActivationProviderRequest("private-" + UUID.randomUUID(), "customer-secret", "product"));

		var timer = registry.find("activation.provider.attempts")
				.tags("category", category, "result", result).timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(before + 1);
		assertThat(timer.totalTime(TimeUnit.SECONDS)).isPositive();
		assertThat(timer.getId().getTags()).hasSize(2);
		assertThat(scrape()).contains("activation_provider_attempts_seconds_count",
				"category=\"" + category + "\"", "result=\"" + result + "\"")
				.doesNotContain("customer-secret", "private-");
	}

	@Test
	void refreshesDatabaseTotalsIncludingMissingStatesAndExportsAutomaticMetrics() throws Exception {
		var first = repository.saveAndFlush(ActivationRequest.create("metric-a", "customer", "product", "a"));
		repository.saveAndFlush(ActivationRequest.create("metric-b", "customer", "product", "b"));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(scrape()).contains("activation_requests{state=\"PENDING\"} 2.0"));
		jdbc.update("UPDATE activation_requests SET status='COMPLETED' WHERE id=?", first.getId());
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			String body = scrape();
			assertThat(body).contains("activation_requests{state=\"PENDING\"} 1.0",
					"activation_requests{state=\"COMPLETED\"} 1.0",
					"activation_requests{state=\"PROCESSING\"} 0.0",
					"activation_requests{state=\"FAILED\"} 0.0");
		});
		assertThat(registry.get("activation.requests.snapshot.timestamp").gauge().value()).isPositive();
		String body = scrape();
		assertThat(body).contains("http_server_requests_seconds_count", "jvm_memory_used_bytes",
				"jvm_threads_live_threads", "hikaricp_connections_active", "hikaricp_connections_idle",
				"hikaricp_connections_pending", "hikaricp_connections_timeout_total");
		jdbc.update("DELETE FROM activation_requests");
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(scrape()).contains("activation_requests{state=\"COMPLETED\"} 0.0"));
	}

	@Test
	void connectionFailureIsMeasuredAsIndeterminateWithoutRetrying() throws Exception {
		provider.stubFor(post("/provider/v1/activate").willReturn(aResponse()
				.withFault(Fault.EMPTY_RESPONSE)));
		var previous = registry.find("activation.provider.attempts")
				.tags("category", "COMMUNICATION_FAILURE", "result", "INDETERMINATE").timer();
		long before = previous == null ? 0 : previous.count();
		assertThat(client.activate(new ActivationProviderRequest("communication", "customer", "product")))
				.isEqualTo(new ProviderOutcome.Indeterminate(
						"PROVIDER_COMMUNICATION_FAILURE"));
		assertThat(registry.get("activation.provider.attempts")
				.tags("category", "COMMUNICATION_FAILURE", "result", "INDETERMINATE").timer().count())
				.isEqualTo(before + 1);
		assertThat(scrape()).contains("category=\"COMMUNICATION_FAILURE\"");
		provider.verify(1, postRequestedFor(
				urlEqualTo("/provider/v1/activate")));
	}

	@Test
	void countsOnlyRetriesActuallyDispatchedAfterThePersistedDelay() {
		provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(503)));
		repository.saveAndFlush(ActivationRequest.create("retry-metric", "customer", "product", "a"));
		assertThat(worker.processNext()).isTrue();
		var retries = registry.find("activation.provider.retries").counter();
		assertThat(retries).isNotNull();
		assertThat(retries.count()).isZero();
		await().atMost(Duration.ofSeconds(5)).until(worker::processNext);
		assertThat(retries.count()).isEqualTo(1);
		await().atMost(Duration.ofSeconds(5)).until(worker::processNext);
		assertThat(retries.count()).isEqualTo(2);
		assertThat(worker.processNext()).isFalse();
		assertThat(retries.count()).isEqualTo(2);
		provider.verify(3, postRequestedFor(
				urlEqualTo("/provider/v1/activate")));
	}

	private String scrape() throws Exception {
		return mvc.perform(get("/actuator/prometheus").with(jwt().authorities(
				new SimpleGrantedAuthority("SCOPE_monitoring/read"))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}
}
