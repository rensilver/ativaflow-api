package com.rensilver.ativaflow_api;

import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.rensilver.ativaflow_api.support.RequestTestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class ActivationRequestSchedulerRestartTests {

	private static final String PROVIDER_PATH = "/provider/v1/activate";

	// The database outlives each application context, as it does during an API restart.
	@Container
	static final PostgreSQLContainer<?> postgres = new TestcontainersConfiguration().postgresContainer();

	@RegisterExtension
	static final WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@Test
	void newApplicationAutomaticallyResumesSafeWorkAndPreservesTheReconciliationBlock() throws Exception {
		provider.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PROVIDER_PATH)
				.willReturn(aResponse().withStatus(204)));
		UUID expiredClaim;
		UUID ambiguous;
		UUID pending;
		try (var original = startApplication(false)) {
			assertThat(original.getBeansOfType(ActivationRequestWorker.class)).isEmpty();
			var mvc = mvc(original);
			expiredClaim = create(mvc, original, "EXT-EXPIRED");
			var claims = original.getBean(ActivationRequestClaimService.class);
			assertThat(claims.claimNext("stopped-worker").orElseThrow().id()).isEqualTo(expiredClaim);
			ambiguous = create(mvc, original, "EXT-AMBIGUOUS");
			var claim = claims.claimNext("stopped-worker").orElseThrow();
			assertThat(claim.id()).isEqualTo(ambiguous);
			assertThat(original.getBean(ActivationRequestAttemptService.class).prepareAttempt(claim)).isPresent();
			pending = create(mvc, original, "EXT-PENDING");
			var jdbc = original.getBean(JdbcTemplate.class);
			assertThat(jdbc.update("""
					UPDATE activation_requests SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
					WHERE id IN (?, ?)
					""", expiredClaim, ambiguous)).isEqualTo(2);
			provider.verify(0, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
		}

		try (var replacement = startApplication(true)) {
			assertThat(replacement.getBeansOfType(ActivationRequestWorker.class)).hasSize(1);
			var jdbc = replacement.getBean(JdbcTemplate.class);
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertCompleted(jdbc, expiredClaim);
				assertCompleted(jdbc, pending);
			});
			var mvc = mvc(replacement);
			assertVisibleStatus(mvc, expiredClaim, "COMPLETED");
			assertVisibleStatus(mvc, pending, "COMPLETED");
			assertVisibleStatus(mvc, ambiguous, "PROCESSING");
			assertBlocked(jdbc, ambiguous);
			assertOnlySafeRequestsWereSent();
		}

		// A further restart cannot resend completed or ambiguously prepared work.
		try (var restarted = startApplication(true)) {
			var jdbc = restarted.getBean(JdbcTemplate.class);
			await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertCompleted(jdbc, expiredClaim);
				assertCompleted(jdbc, pending);
				assertBlocked(jdbc, ambiguous);
				assertOnlySafeRequestsWereSent();
			});
		}
	}

	private ConfigurableApplicationContext startApplication(boolean workerEnabled) {
		return new SpringApplicationBuilder(AtivaflowApiApplication.class).run(
				"--spring.profiles.active=test", "--server.port=0", "--spring.main.banner-mode=off",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--app.activation-provider.base-url=" + provider.baseUrl(),
				"--app.worker.enabled=" + workerEnabled, "--app.worker.poll-interval=50ms");
	}

	private MockMvc mvc(ConfigurableApplicationContext context) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
				.apply(springSecurity()).build();
	}

	private UUID create(MockMvc mvc, ConfigurableApplicationContext context, String externalId) throws Exception {
		var response = mvc.perform(post("/api/v1/requests").with(user("consumer-a"))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"externalId":"%s","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
						""".formatted(externalId)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
				.andReturn().getResponse();
		return UUID.fromString(context.getBean(ObjectMapper.class)
				.readTree(response.getContentAsString()).get("id").asText());
	}

	private void assertVisibleStatus(MockMvc mvc, UUID id, String expectedStatus) throws Exception {
		mvc.perform(get("/api/v1/requests/{id}", id).with(user("consumer-a")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expectedStatus));
	}

	private void assertCompleted(JdbcTemplate jdbc, UUID id) {
		var row = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		assertThat(row).containsEntry("status", "COMPLETED").containsEntry("attempt_count", 1)
				.containsEntry("reconciliation_required", false);
		assertThat(row.get("claimed_by")).isNull();
		assertThat(row.get("lease_until")).isNull();
		assertThat(row.get("next_attempt_at")).isNull();
	}

	private void assertBlocked(JdbcTemplate jdbc, UUID id) {
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id))
				.containsEntry("status", "PROCESSING").containsEntry("attempt_count", 1)
				.containsEntry("reconciliation_required", true).containsEntry("claimed_by", "stopped-worker");
	}

	private void assertOnlySafeRequestsWereSent() {
		provider.verify(2, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
		for (String externalId : new String[]{"EXT-EXPIRED", "EXT-PENDING"}) {
			provider.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH))
					.withRequestBody(matchingJsonPath("$.externalId", equalTo(externalId))));
		}
		provider.verify(0, postRequestedFor(urlEqualTo(PROVIDER_PATH))
				.withRequestBody(matchingJsonPath("$.externalId", equalTo("EXT-AMBIGUOUS"))));
	}
}
