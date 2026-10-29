package com.rensilver.ativaflow_api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GetActivationRequestEndpointTests {

	private static final String CONSUMER = "consumer-get-tests";
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void returnsCurrentPublicRepresentationForOwner(ActivationStatus currentStatus) throws Exception {
		UUID id = insertRequest(CONSUMER);
		jdbcTemplate.update("""
				UPDATE activation_requests SET status = ?, updated_at = ?, version = version + 1
				WHERE id = ?
				""", currentStatus.name(), OffsetDateTime.parse("2026-10-28T12:01:00.654321-03:00"), id);

		mockMvc.perform(get("/api/v1/requests/{id}", id).with(user(CONSUMER)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.externalId").value("EXT-" + id))
				.andExpect(jsonPath("$.customerId").value("CUSTOMER-1"))
				.andExpect(jsonPath("$.productCode").value("PRODUCT-1"))
				.andExpect(jsonPath("$.status").value(currentStatus.name()))
				.andExpect(jsonPath("$.createdAt").value("2026-10-28T15:00:00.123456Z"))
				.andExpect(jsonPath("$.updatedAt").value("2026-10-28T15:01:00.654321Z"));
	}

	@Test
	void returnsSameNotFoundProblemForMissingAndInaccessibleRequests() throws Exception {
		UUID id = insertRequest(CONSUMER);
		String path = "/api/v1/requests/" + id;

		var inaccessible = mockMvc.perform(get(path).with(user("another-consumer")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Not Found"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").isString())
				.andExpect(jsonPath("$.instance").value(path))
				.andExpect(jsonPath("$.length()").value(5))
				.andReturn().getResponse().getContentAsString();

		jdbcTemplate.update("DELETE FROM activation_requests WHERE id = ?", id);

		mockMvc.perform(get(path).with(user("another-consumer")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(content().json(inaccessible));
	}

	@Test
	void rejectsAnonymousLookup() throws Exception {
		UUID id = insertRequest(CONSUMER);

		mockMvc.perform(get("/api/v1/requests/{id}", id).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsMalformedUuid() throws Exception {
		mockMvc.perform(get("/api/v1/requests/not-a-uuid").with(user(CONSUMER)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").isString())
				.andExpect(jsonPath("$.instance").value("/api/v1/requests/not-a-uuid"));
	}

	private UUID insertRequest(String consumerId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, version,
				     attempt_count, claimed_by, last_error_code, reconciliation_required,
				     created_at, updated_at)
				VALUES (?, ?, 'CUSTOMER-1', 'PRODUCT-1', ?, 'PENDING', 0,
				        2, 'worker-1', 'PROVIDER_TIMEOUT', true, ?, ?)
				""", id, "EXT-" + id, consumerId,
				OffsetDateTime.parse("2026-10-28T12:00:00.123456-03:00"),
				OffsetDateTime.parse("2026-10-28T12:00:00.123456-03:00"));
		return id;
	}
}
