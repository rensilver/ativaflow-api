package com.rensilver.ativaflow_api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.exception.InvalidPaginationException;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class ListActivationRequestsEndpointTests {

	private static final String CONSUMER = "consumer-list-tests";
	private static final String LOW_ID = "00000000-0000-0000-0000-000000000001";
	private static final String HIGH_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";
	private static final String OLD_ID = "00000000-0000-0000-0000-000000000002";
	private static final String CREATED_AT = "2026-10-28T15:00:00Z";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ActivationRequestService service;

	@ParameterizedTest
	@CsvSource({"0, 101", "0, 2147483647", "0, 0", "0, -1", "-1, 20", "2147483647, 20"})
	void rejectsInvalidPaginationEvenWhenCalledOutsideController(int page, int size) {
		assertThatThrownBy(() -> service.listActivationRequests(null, null, page, size, CONSUMER))
				.isInstanceOf(InvalidPaginationException.class);
	}

	@Test
	void listsOnlyOwnedPublicRepresentationsWithDefaultPaginationAndStableOrder() throws Exception {
		insertRequest(OLD_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PENDING, "2026-10-27T15:00:00Z");
		insertRequest(LOW_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PROCESSING, CREATED_AT);
		insertRequest(HIGH_ID, CONSUMER, "CUSTOMER-2", ActivationStatus.COMPLETED, CREATED_AT);
		insertRequest(UUID.randomUUID().toString(), "other-consumer", "CUSTOMER-1",
				ActivationStatus.PENDING, "2026-10-29T15:00:00Z");

		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.content.length()").value(3))
				.andExpect(jsonPath("$.content[0].length()").value(7))
				.andExpect(jsonPath("$.content[0].id").value(HIGH_ID))
				.andExpect(jsonPath("$.content[0].externalId").value("EXT-" + HIGH_ID))
				.andExpect(jsonPath("$.content[0].customerId").value("CUSTOMER-2"))
				.andExpect(jsonPath("$.content[0].productCode").value("PRODUCT-1"))
				.andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.content[0].createdAt").value(CREATED_AT))
				.andExpect(jsonPath("$.content[0].updatedAt").value(CREATED_AT))
				.andExpect(jsonPath("$.content[1].id").value(LOW_ID))
				.andExpect(jsonPath("$.content[2].id").value(OLD_ID));
	}

	@Test
	void paginatesFromZeroAndCountsOnlyMatchingOwnedRequests() throws Exception {
		insertRequest(LOW_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(HIGH_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(OLD_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PENDING, "2026-10-27T15:00:00Z");
		insertRequest(UUID.randomUUID().toString(), "other-consumer", "CUSTOMER-1", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(UUID.randomUUID().toString(), CONSUMER, "CUSTOMER-2", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(UUID.randomUUID().toString(), CONSUMER, "CUSTOMER-1", ActivationStatus.FAILED, CREATED_AT);

		String[] expectedIds = { HIGH_ID, LOW_ID, OLD_ID };
		for (int page = 0; page < expectedIds.length; page++) {
			mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER))
					.param("status", "PENDING").param("customerId", "CUSTOMER-1")
					.param("page", Integer.toString(page)).param("size", "1"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.page").value(page))
					.andExpect(jsonPath("$.size").value(1))
					.andExpect(jsonPath("$.totalElements").value(3))
					.andExpect(jsonPath("$.totalPages").value(3))
					.andExpect(jsonPath("$.content.length()").value(1))
					.andExpect(jsonPath("$.content[0].id").value(expectedIds[page]));
		}
	}

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void filtersByEachStatus(ActivationStatus filteredStatus) throws Exception {
		for (ActivationStatus currentStatus : ActivationStatus.values()) {
			insertRequest(UUID.randomUUID().toString(), CONSUMER, "CUSTOMER-1", currentStatus, CREATED_AT);
		}
		insertRequest(UUID.randomUUID().toString(), "other-consumer", "CUSTOMER-1", filteredStatus, CREATED_AT);

		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param("status", filteredStatus.name()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].status").value(filteredStatus.name()))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void filtersCustomerIdByExactValueWithoutTrimmingOrWildcardMatching() throws Exception {
		insertRequest(LOW_ID, CONSUMER, " CUSTOMER_% ", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(HIGH_ID, CONSUMER, "CUSTOMER_123", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(OLD_ID, CONSUMER, " customer_% ", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(UUID.randomUUID().toString(), "other-consumer", " CUSTOMER_% ", ActivationStatus.PENDING, CREATED_AT);

		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param("customerId", " CUSTOMER_% "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(LOW_ID))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@ParameterizedTest
	@CsvSource({"0, 0, 0", "2, 1, 1"})
	void returnsEmptyPageForNoMatchesOrPageBeyondLast(int page, int totalElements, int totalPages) throws Exception {
		insertRequest(LOW_ID, CONSUMER, "CUSTOMER-1", ActivationStatus.PENDING, CREATED_AT);
		insertRequest(HIGH_ID, "other-consumer", "CUSTOMER-2", ActivationStatus.PENDING, CREATED_AT);

		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER))
				.param("customerId", page == 0 ? "CUSTOMER-2" : "CUSTOMER-1")
				.param("page", Integer.toString(page)).param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.page").value(page))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalElements").value(totalElements))
				.andExpect(jsonPath("$.totalPages").value(totalPages));
	}

	@Test
	void acceptsMaximumPageSize() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(100));
	}

	@ParameterizedTest
	@CsvSource({"page, -1", "page, abc", "page, 2147483648", "size, 0", "size, -1", "size, 101",
			"size, abc", "status, UNKNOWN", "status, pending", "customerId, ' '", "customerId, ''"})
	void rejectsInvalidParameters(String parameter, String value) throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param(parameter, value))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void rejectsCustomerIdLongerThanStorageLimit() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param("customerId", "x".repeat(101)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsPaginationOffsetBeyondJpaRange() throws Exception {
		mockMvc.perform(get("/api/v1/requests").with(user(CONSUMER)).param("page", "2147483647"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void rejectsAnonymousListing() throws Exception {
		mockMvc.perform(get("/api/v1/requests").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized());
	}

	private void insertRequest(String id, String consumerId, String customerId, ActivationStatus requestStatus,
			String createdAt) {
		jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, version,
				     attempt_count, claimed_by, last_error_code, reconciliation_required, created_at, updated_at)
				VALUES (?, ?, ?, 'PRODUCT-1', ?, ?, 0, 2, 'worker-1', 'PROVIDER_TIMEOUT', true, ?, ?)
				""", UUID.fromString(id), "EXT-" + id, customerId, consumerId, requestStatus.name(),
				OffsetDateTime.parse(createdAt), OffsetDateTime.parse(createdAt));
	}
}
