package com.rensilver.ativaflow_api.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.rensilver.ativaflow_api.support.TestcontainersConfiguration;
import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.worker.enabled=false",
		"app.activation-provider.retry-contract=rejected-before-activation",
		"app.activation-provider.read-timeout=500ms",
		"app.worker.retry.initial-delay=200ms", "app.worker.retry.max-delay=400ms",
		"app.worker.retry.jitter-ratio=0"})
@ActiveProfiles("test")
class ActivationRequestHttpRetryTests {

	private static final String PATH = "/provider/v1/activate";

	@RegisterExtension
	static final WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.activation-provider.base-url", provider::baseUrl);
	}

	@Autowired private ActivationRequestClaimService claims;
	@Autowired private ActivationRequestAttemptService attempts;
	@Autowired private ActivationRequestOutcomeHandler outcomes;
	@Autowired private ActivationProviderClient client;
	@Autowired private ActivationRequestRepository repository;
	@Autowired private JdbcTemplate jdbc;

	private ActivationRequestWorker worker;
	private UUID id;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM activation_requests");
		id = repository.saveAndFlush(ActivationRequest.create(
				"EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a")).getId();
		worker = newWorker();
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void delaySecondsArePersistedAndPreventAnotherWorkerFromSendingEarly(int status) throws IOException {
		// O cenário opt-in fica fora dos mappings padrão de sucesso do Compose.
		var rejection = StubMapping.buildFrom(Files.readString(
				Path.of("infra/wiremock/scenarios/activation-rejected-before-activation.json")));
		rejection.setResponse(ResponseDefinitionBuilder.like(rejection.getResponse()).withStatus(status).build());
		provider.addStubMapping(rejection);
		Instant minimum = Instant.now().plusSeconds(120);

		assertThat(worker.processNext()).isTrue();

		var stored = stored();
		assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(stored.getNextAttemptAt()).isAfterOrEqualTo(minimum);
		assertThat(stored.getAttemptCount()).isEqualTo(1);
		assertThat(stored.isReconciliationRequired()).isFalse();
		assertThat(newWorker().processNext()).isFalse();
		assertThat(stored().getNextAttemptAt()).isEqualTo(stored.getNextAttemptAt());
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void httpDateIsPersistedWithoutClampingToMaximumBackoff(int status) {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(status)
				.withHeader("Retry-After", "Fri, 31 Oct 2098 12:00:00 GMT")));

		assertThat(worker.processNext()).isTrue();

		assertThat(stored().getNextAttemptAt()).isEqualTo(Instant.parse("2098-10-31T12:00:00Z"));
		assertThat(newWorker().processNext()).isFalse();
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "invalid", "Thu, 01 Jan 1970 00:00:00 GMT"})
	void invalidZeroOrPastDeadlineKeepsPositiveBackoff(String header) {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(429).withHeader("Retry-After", header)));

		assertThat(worker.processNext()).isTrue();

		var stored = stored();
		assertThat(stored.getNextAttemptAt()).isEqualTo(stored.getUpdatedAt().plusMillis(200));
		assertThat(stored.isReconciliationRequired()).isFalse();
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void realHttpAttemptsStopAtThreeConfirmedRejections(int status) {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(status)));

		for (int attempt = 1; attempt <= 3; attempt++) {
			await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).until(worker::processNext);
			var stored = stored();
			assertThat(stored.getAttemptCount()).isEqualTo(attempt);
			assertThat(stored.isReconciliationRequired()).isFalse();
			if (attempt < 3) {
				assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
				assertThat(stored.getNextAttemptAt()).isEqualTo(stored.getUpdatedAt().plusMillis(attempt * 200L));
			}
		}

		assertThat(stored().getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(stored().getNextAttemptAt()).isNull();
		assertThat(newWorker().processNext()).isFalse();
		provider.verify(3, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
				{"externalId":"EXT-1","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
				""")));
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 503})
	void successAfterSafeRejectionCompletesAndStopsSending(int status) {
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(status)));
		assertThat(worker.processNext()).isTrue();
		provider.stubFor(post(PATH).willReturn(aResponse().withStatus(204)));

		await().atMost(Duration.ofSeconds(5)).until(worker::processNext);

		assertThat(stored().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		assertThat(stored().getAttemptCount()).isEqualTo(2);
		assertThat(stored().getNextAttemptAt()).isNull();
		assertThat(newWorker().processNext()).isFalse();
		provider.verify(2, postRequestedFor(urlEqualTo(PATH)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"timeout", "overflow"})
	void selectedContractStillBlocksAmbiguousOrUnrepresentableResults(String scenario) {
		var response = aResponse().withStatus(503);
		if (scenario.equals("timeout")) {
			response.withFixedDelay(1500).withHeader("Retry-After", "0");
		}
		else {
			response.withHeader("Retry-After", "9999999999999999999999999999999999999999");
		}
		provider.stubFor(post(PATH).willReturn(response));

		assertThat(worker.processNext()).isTrue();

		assertThat(stored().getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(stored().isReconciliationRequired()).isTrue();
		assertThat(stored().getNextAttemptAt()).isNull();
		assertThat(stored().getLastErrorCode()).isEqualTo(scenario.equals("timeout")
				? "PROVIDER_TIMEOUT" : "PROVIDER_RETRY_AFTER_UNSUPPORTED");
		assertThat(newWorker().processNext()).isFalse();
		provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	private ActivationRequest stored() {
		return repository.findById(id).orElseThrow();
	}

	private ActivationRequestWorker newWorker() {
		return new ActivationRequestWorker(claims, attempts, outcomes, client, repository);
	}
}
