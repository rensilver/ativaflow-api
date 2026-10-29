package com.rensilver.ativaflow_api;

import java.util.UUID;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.worker.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivationRequestProviderRejectionTests {

	private static final String PATH = "/provider/v1/activate";

	@RegisterExtension
	static final WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.activation-provider.base-url", provider::baseUrl);
	}

	@Autowired
	private ActivationRequestClaimService claims;

	@Autowired
	private ActivationRequestAttemptService attempts;

	@Autowired
	private ActivationRequestOutcomeHandler outcomes;

	@Autowired
	private ActivationProviderClient client;

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("DELETE FROM activation_requests");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void badRequestPersistsFailedWithASafeInternalReasonAndNeverRetries(int previousAttempts) throws Exception {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(400)
				.withHeader("Retry-After", "120")
				.withHeader("Content-Type", "application/json")
				.withBody("{\"detail\":\"sensitive customer data; token=provider-secret\"}")));
		var request = repository.saveAndFlush(ActivationRequest.create(
				"EXT-" + UUID.randomUUID(), "CUSTOMER-1", "PRODUCT-1", "consumer-a"));
		if (previousAttempts > 0) {
			jdbc.update("""
					UPDATE activation_requests SET status = 'PROCESSING', attempt_count = ?,
					    next_attempt_at = CURRENT_TIMESTAMP, last_error_code = 'PROVIDER_TEMPORARILY_UNAVAILABLE'
					WHERE id = ?
					""", previousAttempts, request.getId());
		}
		// Scheduling is disabled; use the real worker and transactional service beans synchronously.
		var worker = new ActivationRequestWorker(claims, attempts, outcomes, client, repository);

		assertThat(worker.processNext()).isTrue();

		var stored = repository.findById(request.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(stored.getAttemptCount()).isEqualTo(previousAttempts + 1);
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_REJECTED");
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.isReconciliationRequired()).isFalse();
		var terminalRow = jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", request.getId());

		assertThat(worker.processNext()).isFalse();
		var anotherWorker = new ActivationRequestWorker(claims, attempts, outcomes, client, repository);
		assertThat(anotherWorker.processNext()).isFalse();
		assertThat(jdbc.queryForMap("SELECT * FROM activation_requests WHERE id = ?", request.getId()))
				.isEqualTo(terminalRow);
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));

		mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(user("consumer-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$.lastErrorCode").doesNotExist());
	}

	@Test
	void unavailableResponseRemainsIndeterminateAndIsNeverRetried() {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(503)));
		var request = repository.saveAndFlush(ActivationRequest.create(
				"EXT-" + UUID.randomUUID(), "CUSTOMER-1", "PRODUCT-1", "consumer-a"));
		var worker = new ActivationRequestWorker(claims, attempts, outcomes, client, repository);

		assertThat(worker.processNext()).isTrue();
		var stored = repository.findById(request.getId()).orElseThrow();
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_UNCONFIRMED_RESPONSE");
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(worker.processNext()).isFalse();
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}
}
