package com.rensilver.ativaflow_api;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
	void appliesInitialMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(migration -> assertThat(migration.getVersion().getVersion()).isEqualTo("1"));
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
	}

	@Test
	void storesRequestWithUuidAndDefaultsForStatusAndTimestamps() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code)
				VALUES (?, ?, ?, ?)
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
	void rejectsDuplicateExternalIdAcrossCustomersAndProducts() {
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code)
				VALUES (?, 'EXT-DUPLICATE', 'CUST-1', 'PRODUCT-1')
				""", UUID.randomUUID());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code)
				VALUES (?, 'EXT-DUPLICATE', 'CUST-2', 'PRODUCT-2')
				""", UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_activation_requests_external_id");
	}

	@Test
	void rejectsUnknownStatus() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, status)
				VALUES (?, 'EXT-INVALID', 'CUST-1', 'PRODUCT-1', 'UNKNOWN')
				""", UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_activation_requests_status");
	}

}
