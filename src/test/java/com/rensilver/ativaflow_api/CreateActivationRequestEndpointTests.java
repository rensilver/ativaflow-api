package com.rensilver.ativaflow_api;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, CreateActivationRequestEndpointTests.TimeConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreateActivationRequestEndpointTests {

	private static final String CONSUMER = "consumer-post-tests";
	private static final String PATH = "/api/v1/requests";
	private static final Instant CREATED_AT = Instant.parse("2026-10-28T15:00:00.123456Z");
	private static final String PAYLOAD = """
			{"externalId":" EXT-12345 ","customerId":"CUST-10001","productCode":"INTERNET-500"}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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
				.flatMap(field -> Stream.of(null, "", " \t\n", "\u2003", "a".repeat(101))
						.map(value -> Arguments.of(field, value)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "status", "consumerId", "createdAt", "version"})
	void rejectsServerControlledFieldsWithoutPersisting(String field) throws Exception {
		var payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
		payload.put(field, "forged");
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

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeConfiguration {

		@Bean
		@Primary
		Clock creationClock() {
			return Clock.fixed(CREATED_AT, ZoneOffset.UTC);
		}
	}
}
