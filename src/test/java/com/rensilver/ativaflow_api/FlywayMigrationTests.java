package com.rensilver.ativaflow_api;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FlywayMigrationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void storesConsumerAndDefaultsForInternalProcessingFields() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id)
				VALUES (?, 'EXT-INTERNAL', 'CUST-1', 'PRODUCT-1', 'consumer-a')
				""", id);

		jdbcTemplate.queryForObject("SELECT * FROM activation_requests WHERE id = ?", (rs, rowNum) -> {
			assertThat(rs.getString("consumer_id")).isEqualTo("consumer-a");
			assertThat(rs.getObject("version", Long.class)).isZero();
			assertThat(rs.getObject("attempt_count", Integer.class)).isZero();
			assertThat(rs.getObject("next_attempt_at")).isNull();
			assertThat(rs.getObject("lease_until")).isNull();
			assertThat(rs.getString("claimed_by")).isNull();
			assertThat(rs.getString("last_error_code")).isNull();
			assertThat(rs.getObject("reconciliation_required", Boolean.class)).isFalse();
			return id;
		}, id);
	}

	@Test
	void storesProcessingMetadataWithoutChangingPublicStates() {
		UUID id = UUID.randomUUID();
		OffsetDateTime nextAttemptAt = OffsetDateTime.parse("2026-10-28T18:00:00-03:00");
		OffsetDateTime leaseUntil = nextAttemptAt.plusMinutes(1);
		jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, version,
				     attempt_count, next_attempt_at, lease_until, claimed_by, last_error_code,
				     reconciliation_required)
				VALUES (?, 'EXT-PROCESSING', 'CUST-1', 'PRODUCT-1', 'consumer-a', 'PROCESSING',
				        2, 1, ?, ?, 'worker-1', 'PROVIDER_TIMEOUT', true)
				""", id, nextAttemptAt, leaseUntil);

		jdbcTemplate.queryForObject("SELECT * FROM activation_requests WHERE id = ?", (rs, rowNum) -> {
			assertThat(rs.getString("status")).isEqualTo("PROCESSING");
			assertThat(rs.getLong("version")).isEqualTo(2);
			assertThat(rs.getInt("attempt_count")).isEqualTo(1);
			assertThat(rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant())
					.isEqualTo(nextAttemptAt.toInstant());
			assertThat(rs.getObject("lease_until", OffsetDateTime.class).toInstant())
					.isEqualTo(leaseUntil.toInstant());
			assertThat(rs.getString("claimed_by")).isEqualTo("worker-1");
			assertThat(rs.getString("last_error_code")).isEqualTo("PROVIDER_TIMEOUT");
			assertThat(rs.getBoolean("reconciliation_required")).isTrue();
			return id;
		}, id);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void rejectsMissingOrBlankConsumer(String consumerId) {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id)
				VALUES (?, 'EXT-NO-OWNER', 'CUST-1', 'PRODUCT-1', ?)
				""", UUID.randomUUID(), consumerId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("consumer_id");
	}

	@ParameterizedTest
	@ValueSource(strings = {"version", "attempt_count"})
	void rejectsNegativeProcessingCounters(String column) {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id, %s)
				VALUES (?, 'EXT-NEGATIVE', 'CUST-1', 'PRODUCT-1', 'consumer-a', -1)
				""".formatted(column), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_activation_requests_" + column);
	}

	@Test
	void appliesAndValidatesAllMigrations() {
		assertThat(flyway.info().applied())
				.extracting(migration -> migration.getVersion().getVersion())
				.containsExactly("1", "2", "3", "4");
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
	}

	@Test
	void servesConsumerListingsInIndexOrderWithAndWithoutFilters() {
		jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, created_at)
				SELECT md5('index-plan-' || n)::uuid, 'INDEX-PLAN-' || n,
				       'customer-' || ((n / 80) % 20), 'PRODUCT-1', 'consumer-' || (n % 20),
				       (ARRAY['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'])[1 + ((n / 20) % 4)],
				       TIMESTAMPTZ '2026-10-28 15:00:00+00' + n * INTERVAL '1 second'
				FROM generate_series(1, 16000) AS n
				""");
		jdbcTemplate.execute("ANALYZE activation_requests");

		assertListingPlan("", "idx_activation_requests_consumer_created_id");
		assertListingPlan("AND status = 'PENDING'", "idx_activation_requests_consumer_status_created_id");
		assertListingPlan("AND customer_id = 'customer-1'", "idx_activation_requests_consumer_customer_created_id");
	}

	private void assertListingPlan(String filter, String expectedIndex) {
		String plan = String.join("\n", jdbcTemplate.queryForList("""
				EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
				SELECT * FROM activation_requests
				WHERE consumer_id = 'consumer-1' %s
				ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0
				""".formatted(filter), String.class));
		assertThat(plan).contains(expectedIndex).doesNotContain("Sort");
	}

	@Test
	void storesRequestWithUuidAndDefaultsForStatusAndTimestamps() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id)
				VALUES (?, ?, ?, ?, 'consumer-a')
				""", id, "EXT-12345", "CUST-10001", "INTERNET-500");

		jdbcTemplate.queryForObject("SELECT * FROM activation_requests WHERE id = ?", (rs, rowNum) -> {
			assertThat(rs.getObject("id", UUID.class)).isEqualTo(id);
			assertThat(rs.getString("external_id")).isEqualTo("EXT-12345");
			assertThat(rs.getString("customer_id")).isEqualTo("CUST-10001");
			assertThat(rs.getString("product_code")).isEqualTo("INTERNET-500");
			assertThat(rs.getString("status")).isEqualTo("PENDING");
			OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
			OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
			assertThat(createdAt).isNotNull();
			assertThat(updatedAt.toInstant()).isEqualTo(createdAt.toInstant());
			return id;
		}, id);
	}

	@Test
	void definesValidatedGlobalUniqueConstraintOnExternalId() {
		String definition = jdbcTemplate.queryForObject("""
				SELECT pg_get_constraintdef(oid)
				FROM pg_constraint
				WHERE conrelid = 'activation_requests'::regclass
				  AND conname = 'uk_activation_requests_external_id'
				  AND contype = 'u'
				  AND convalidated
				""", String.class);

		assertThat(definition).isEqualTo("UNIQUE (external_id)");
	}

	@ParameterizedTest
	@CsvSource({
			"consumer-a, CUST-1, PRODUCT-1, PENDING",
			"consumer-b, CUST-1, PRODUCT-1, PENDING",
			"consumer-a, CUST-2, PRODUCT-1, PENDING",
			"consumer-a, CUST-1, PRODUCT-2, PENDING",
			"consumer-b, CUST-2, PRODUCT-2, COMPLETED"
	})
	void rejectsDuplicateExternalIdRegardlessOfConsumerCustomerProductOrStatus(
			String consumerId, String customerId, String productCode, String status) {
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id)
				VALUES (?, 'EXT-DUPLICATE', 'CUST-1', 'PRODUCT-1', 'consumer-a')
				""", UUID.randomUUID());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status)
				VALUES (?, 'EXT-DUPLICATE', ?, ?, ?, ?)
				""", UUID.randomUUID(), customerId, productCode, consumerId, status))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_activation_requests_external_id")
				.rootCause()
				.isInstanceOfSatisfying(SQLException.class,
						cause -> assertThat(cause.getSQLState()).isEqualTo("23505"));
	}

	@Test
	void rejectsUnknownStatus() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id, status)
				VALUES (?, 'EXT-INVALID', 'CUST-1', 'PRODUCT-1', 'consumer-a', 'UNKNOWN')
				""", UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_activation_requests_status");
	}

}
