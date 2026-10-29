package com.rensilver.ativaflow_api;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, CreateActivationRequestEndpointTests.TimeConfiguration.class,
		CreateActivationRequestEndpointTests.ProcessingBeforeSerialization.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreateActivationRequestEndpointTests {

	private static final String CONSUMER = "consumer-post-tests";
	private static final String PATH = "/api/v1/requests";
	private static final Instant CREATED_AT = Instant.parse("2026-10-28T15:00:00.123456Z");
	private static final Instant PROCESSED_AT = Instant.parse("2026-10-28T15:01:00.123456Z");
	private static final String SNAPSHOT_EXTERNAL_ID = "EXT-SNAPSHOT";
	private static final String PAYLOAD = """
			{"externalId":" EXT-12345 ","customerId":"CUST-10001","productCode":"INTERNET-500"}
			""";

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.activation-provider.base-url", provider::baseUrl);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void removeCreatedRequests() {
		jdbcTemplate.update("DELETE FROM activation_requests WHERE consumer_id = ?", CONSUMER);
	}

	@Test
	void createsCommittedPendingRequestAndReturnsItsPublicRepresentationAndLocation() throws Exception {
		var result = mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.externalId").value(" EXT-12345 "))
				.andExpect(jsonPath("$.customerId").value("CUST-10001"))
				.andExpect(jsonPath("$.productCode").value("INTERNET-500"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(CREATED_AT.toString()))
				.andReturn();

		var body = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID id = UUID.fromString(body.get("id").asText());
		assertThat(body.size()).isEqualTo(7);
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(PATH + "/" + id);
		assertThat(storedRequestCount()).isEqualTo(1);
		jdbcTemplate.queryForObject("SELECT * FROM activation_requests WHERE id = ?", (rs, rowNum) -> {
			assertThat(rs.getString("external_id")).isEqualTo(" EXT-12345 ");
			assertThat(rs.getString("customer_id")).isEqualTo("CUST-10001");
			assertThat(rs.getString("product_code")).isEqualTo("INTERNET-500");
			assertThat(rs.getString("consumer_id")).isEqualTo(CONSUMER);
			assertThat(rs.getString("status")).isEqualTo("PENDING");
			assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
			assertThat(rs.getObject("updated_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
			assertThat(rs.getLong("version")).isZero();
			assertThat(rs.getInt("attempt_count")).isZero();
			return id;
		}, id);

		// A URI publicada deve permitir ler o registro confirmado em outra transação.
		var fetched = mockMvc.perform(get(result.getResponse().getHeader("Location")).with(user(CONSUMER)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(fetched)).isEqualTo(body);

		var listed = mockMvc.perform(get(PATH).with(user(CONSUMER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(listed).get("content").get(0)).isEqualTo(body);
	}

	@Test
	void createsWithoutCallingSlowAndUnavailableProvider() throws Exception {
		provider.stubFor(request("POST", urlEqualTo("/provider/v1/activate"))
				.willReturn(aResponse().withStatus(503).withFixedDelay(10_000)));

		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"));

		assertThat(storedRequestCount()).isEqualTo(1);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void identicalRepetitionReturnsCurrentRecordWithoutChangingOrReactivatingIt(ActivationStatus activationStatus)
			throws Exception {
		var created = mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());
		jdbcTemplate.update("""
				UPDATE activation_requests SET status = ?, updated_at = ?, version = 4,
				attempt_count = 2, next_attempt_at = ?, lease_until = ?, claimed_by = 'worker-1',
				last_error_code = 'PROVIDER_TIMEOUT', reconciliation_required = true WHERE id = ?
				""", activationStatus.name(), OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC),
				OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC),
				OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC), id);
		var storedBefore = jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
		var expected = (ObjectNode) objectMapper.readTree(created);
		expected.put("status", activationStatus.name());
		expected.put("updatedAt", PROCESSED_AT.toString());

		for (int repetition = 0; repetition < 2; repetition++) {
			mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
					.andExpect(content().json(objectMapper.writeValueAsString(expected), JsonCompareMode.STRICT));
		}

		assertThat(storedRequestCount()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id))
				.isEqualTo(storedBefore);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void returnsCreationSnapshotWhenProcessingFinishesBeforeSerialization() throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.put("externalId", SNAPSHOT_EXTERNAL_ID);

		var result = mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.externalId").value(SNAPSHOT_EXTERNAL_ID))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(CREATED_AT.toString()))
				.andReturn();

		var body = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID id = UUID.fromString(body.get("id").asText());
		assertThat(body.size()).isEqualTo(7);
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(PATH + "/" + id);
		jdbcTemplate.queryForObject("SELECT status, created_at, updated_at FROM activation_requests WHERE id = ?",
				(rs, rowNum) -> {
					assertThat(rs.getString("status")).isEqualTo("COMPLETED");
					assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
					assertThat(rs.getObject("updated_at", OffsetDateTime.class).toInstant()).isEqualTo(PROCESSED_AT);
					return id;
				}, id);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	@ParameterizedTest
	@MethodSource("conflictingRequests")
	void rejectsRepetitionWithDifferentContentOrConsumerWithoutExposingOrChangingRecord(
			ActivationStatus activationStatus, String consumer, String customerId, String productCode)
			throws Exception {
		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isCreated());
		jdbcTemplate.update("UPDATE activation_requests SET status = ? WHERE consumer_id = ?",
				activationStatus.name(), CONSUMER);
		var storedBefore = jdbcTemplate.queryForMap(
				"SELECT * FROM activation_requests WHERE consumer_id = ?", CONSUMER);
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.put("customerId", customerId);
		payload.put("productCode", productCode);

		var result = mockMvc.perform(post(PATH).with(user(consumer)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andReturn();
		assertConflictProblem(result);

		assertThat(jdbcTemplate.queryForMap(
				"SELECT * FROM activation_requests WHERE consumer_id = ?", CONSUMER)).isEqualTo(storedBefore);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM activation_requests WHERE external_id = ?", Long.class, " EXT-12345 "))
				.isEqualTo(1);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	static Stream<Arguments> conflictingRequests() {
		return Stream.of(ActivationStatus.values()).flatMap(status -> Stream.of(
				Arguments.of(status, CONSUMER, "CUST-OTHER", "INTERNET-500"),
				Arguments.of(status, CONSUMER, "CUST-10001 ", "INTERNET-500"),
				Arguments.of(status, CONSUMER, "cust-10001", "INTERNET-500"),
				Arguments.of(status, CONSUMER, "CUST-10001", "INTERNET-OTHER"),
				Arguments.of(status, CONSUMER, "CUST-10001", "INTERNET-500 "),
				Arguments.of(status, CONSUMER, "CUST-10001", "internet-500"),
				Arguments.of(status, CONSUMER, "CUST-OTHER", "INTERNET-OTHER"),
				Arguments.of(status, "other-consumer", "CUST-10001", "INTERNET-500"),
				Arguments.of(status, "other-consumer", "CUST-OTHER", "INTERNET-500"),
				Arguments.of(status, "other-consumer", "CUST-10001", "INTERNET-OTHER"),
				Arguments.of(status, "other-consumer", "CUST-OTHER", "INTERNET-OTHER")));
	}

	@Test
	void concurrentIdenticalRequestsCreateOneRecordAndReturnItToEveryCaller() throws Exception {
		int callers = 4;
		var start = new CyclicBarrier(callers);
		try (var executor = Executors.newFixedThreadPool(callers)) {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int caller = 0; caller < callers; caller++) {
				futures.add(executor.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
							.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD)).andReturn();
				}));
			}
			List<MvcResult> results = new ArrayList<>();
			for (var future : futures) {
				results.add(future.get(20, TimeUnit.SECONDS));
			}
			assertThat(results).extracting(result -> result.getResponse().getStatus())
					.containsExactlyInAnyOrder(201, 200, 200, 200);
			var expected = objectMapper.readTree(results.getFirst().getResponse().getContentAsString());
			for (var result : results) {
				assertThat(objectMapper.readTree(result.getResponse().getContentAsString())).isEqualTo(expected);
			}
		}
		assertThat(storedRequestCount()).isEqualTo(1);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"identical", "customerId", "productCode", "consumerId", "consumerIdAndContent"})
	void requestsLosingUniqueConstraintRaceApplyRepetitionRulesAfterRollback(String changedField) throws Exception {
		UUID id = UUID.randomUUID();
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		if (changedField.equals("customerId") || changedField.equals("productCode")) {
			payload.put(changedField, "DIFFERENT");
		}
		if (changedField.equals("consumerIdAndContent")) {
			payload.put("customerId", "CUST-OTHER");
			payload.put("productCode", "INTERNET-OTHER");
		}
		String consumer = changedField.startsWith("consumerId") ? "other-consumer" : CONSUMER;
		String requestBody = objectMapper.writeValueAsString(payload);
		try (var executor = Executors.newFixedThreadPool(3)) {
			List<Future<MvcResult>> futures = new ArrayList<>();
			var storedBefore = new TransactionTemplate(transactionManager).execute(transaction -> {
				jdbcTemplate.update("""
						INSERT INTO activation_requests
						(id, external_id, customer_id, product_code, consumer_id, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?)
						""", id, " EXT-12345 ", "CUST-10001", "INTERNET-500", CONSUMER,
						OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC),
						OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
				for (int caller = 0; caller < 3; caller++) {
					futures.add(executor.submit(() -> mockMvc.perform(post(PATH).with(user(consumer)).with(csrf())
							.contentType(MediaType.APPLICATION_JSON).content(requestBody)).andReturn()));
				}
				// A linha ainda não é visível às consultas. As três inserções devem disputar a constraint real.
				await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject("""
						SELECT count(*) FROM pg_stat_activity
						WHERE datname = current_database() AND wait_event = 'transactionid'
						AND query LIKE 'insert into activation_requests%'
						""", Long.class)).isEqualTo(3));
				return jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id);
			});
			for (var future : futures) {
				var result = future.get(20, TimeUnit.SECONDS);
				if (!changedField.equals("identical")) {
					assertConflictProblem(result);
					continue;
				}
				var response = result.getResponse();
				assertThat(response.getStatus()).isEqualTo(200);
				var body = objectMapper.readTree(response.getContentAsString());
				assertThat(body.get("id").asText()).isEqualTo(id.toString());
				assertThat(body.get("status").asText()).isEqualTo("PENDING");
				assertThat(body.get("createdAt").asText()).isEqualTo(CREATED_AT.toString());
				assertThat(body.size()).isEqualTo(7);
			}
			assertThat(jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", id))
					.isEqualTo(storedBefore);
		}
		assertThat(storedRequestCount()).isEqualTo(1);
		provider.verify(0, anyRequestedFor(anyUrl()));
	}

	private void assertConflictProblem(MvcResult result) throws Exception {
		status().isConflict().match(result);
		content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
		header().doesNotExist("Location").match(result);
		content().json("""
				{
				  "type": "about:blank",
				  "title": "Conflict",
				  "status": 409,
				  "detail": "Activation request conflict",
				  "instance": "/api/v1/requests"
				}
				""", JsonCompareMode.STRICT).match(result);
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void rejectsInvalidFieldsWithoutPersisting(String field, String value) throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.put(field, value);
		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isBadRequest());
		assertThat(storedRequestCount()).isZero();
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of("externalId", "customerId", "productCode")
				.flatMap(field -> Stream.of(null, "", " \t\n", "\u2003", "a".repeat(101), "😀".repeat(51))
						.map(value -> Arguments.of(field, value)));
	}

	@ParameterizedTest
	@MethodSource("validBoundaryFields")
	void acceptsAndPreservesFieldsAtStorageLimit(String field, String value) throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.put(field, value);

		var response = mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$." + field).value(value))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andReturn().getResponse().getContentAsString();

		UUID id = UUID.fromString(objectMapper.readTree(response).get("id").asText());
		assertThat(storedRequestCount()).isEqualTo(1);
		// A releitura também protege contra normalização ou truncamento na persistência.
		mockMvc.perform(get(PATH + "/" + id).with(user(CONSUMER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$." + field).value(value));
	}

	static Stream<Arguments> validBoundaryFields() {
		return Stream.of("externalId", "customerId", "productCode")
				.flatMap(field -> Stream.of("a".repeat(100), " " + "a".repeat(98) + " ", "😀".repeat(50))
						.map(value -> Arguments.of(field, value)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"externalId", "customerId", "productCode"})
	void rejectsMissingRequiredFieldWithoutPersisting(String field) throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.remove(field);

		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isBadRequest());
		assertThat(storedRequestCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "{", "null", "[]", "{}", "{\"externalId\":}"})
	void rejectsMissingOrInvalidJsonBodyWithoutPersisting(String payload) throws Exception {
		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isBadRequest());
		assertThat(storedRequestCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "status", "consumerId", "owner", "createdAt", "updatedAt", "version",
			"attemptCount", "nextAttemptAt", "leaseUntil", "claimedBy", "lastErrorCode",
			"reconciliationRequired", "unexpectedField"})
	void rejectsUnknownAndServerControlledFieldsWithoutPersisting(String field) throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		switch (field) {
			case "id" -> payload.put(field, UUID.randomUUID().toString());
			case "status" -> payload.put(field, "COMPLETED");
			case "version", "attemptCount" -> payload.put(field, 5);
			case "reconciliationRequired" -> payload.put(field, true);
			case "createdAt", "updatedAt", "nextAttemptAt", "leaseUntil" ->
					payload.put(field, "2026-10-27T15:00:00Z");
			default -> payload.put(field, "forged");
		}
		mockMvc.perform(post(PATH).with(user(CONSUMER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isBadRequest());
		assertThat(storedRequestCount()).isZero();
	}

	@Test
	void rejectsAnonymousCreationWithoutPersisting() throws Exception {
		mockMvc.perform(post(PATH).with(csrf()).accept(MediaType.APPLICATION_JSON)
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isUnauthorized());
		assertThat(storedRequestCount()).isZero();
	}

	@Test
	void preservesCsrfProtectionWithoutPersisting() throws Exception {
		mockMvc.perform(post(PATH).with(user(CONSUMER))
				.contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
				.andExpect(status().isForbidden());
		assertThat(storedRequestCount()).isZero();
	}

	@Nullable
	private Long storedRequestCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM activation_requests WHERE consumer_id = ?", Long.class, CONSUMER);
	}

	/** Simula o avanço do worker após o commit e antes de o Jackson escrever o corpo HTTP. */
	@TestComponent
	@ControllerAdvice(assignableTypes = ActivationRequestController.class)
	static class ProcessingBeforeSerialization implements ResponseBodyAdvice<ActivationRequestResponse> {

		private final JdbcTemplate jdbcTemplate;

		ProcessingBeforeSerialization(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Override
		public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
			return returnType.hasMethodAnnotation(PostMapping.class);
		}

		@Override
		@Nullable
		public ActivationRequestResponse beforeBodyWrite(@Nullable ActivationRequestResponse body,
				MethodParameter returnType, MediaType contentType,
				Class<? extends HttpMessageConverter<?>> converterType,
				ServerHttpRequest request, ServerHttpResponse response) {
			if (body != null && SNAPSHOT_EXTERNAL_ID.equals(body.externalId())) {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
				// SQL em autocommit: só encontra a linha se a criação já tiver sido confirmada.
				assertThat(jdbcTemplate.update("""
						UPDATE activation_requests SET status = 'PROCESSING', version = version + 1,
						updated_at = ? WHERE id = ? AND status = 'PENDING'
						""", OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC), body.id())).isEqualTo(1);
				assertThat(jdbcTemplate.update("""
						UPDATE activation_requests SET status = 'COMPLETED', version = version + 1,
						updated_at = ? WHERE id = ? AND status = 'PROCESSING'
						""", OffsetDateTime.ofInstant(PROCESSED_AT, ZoneOffset.UTC), body.id())).isEqualTo(1);
			}
			return body;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeConfiguration {

		@Bean
		@Primary
		Clock creationClock() {
			return Clock.fixed(CREATED_AT, ZoneOffset.UTC);
		}
	}
}
