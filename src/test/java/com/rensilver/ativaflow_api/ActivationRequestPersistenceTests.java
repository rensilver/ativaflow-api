package com.rensilver.ativaflow_api;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, ActivationRequestPersistenceTests.TimeConfiguration.class})
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActivationRequestPersistenceTests {

	private static final Instant CREATED_AT = Instant.parse("2026-10-28T15:00:00Z");

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestClock clock;

	@BeforeEach
	void resetClock() {
		clock.now = CREATED_AT;
	}

	@Test
	void persistsAndReloadsPendingRequestWithUtcAuditAndDefaults() {
		jdbcTemplate.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
		ActivationRequest request = newRequest();
		entityManager.persist(request);
		entityManager.flush();
		entityManager.clear();

		ActivationRequest stored = entityManager.find(ActivationRequest.class, request.getId());
		assertThat(stored.getExternalId()).isEqualTo(request.getExternalId());
		assertThat(stored.getCustomerId()).isEqualTo("CUSTOMER-1");
		assertThat(stored.getProductCode()).isEqualTo("PRODUCT-1");
		assertThat(stored.getConsumerId()).isEqualTo("consumer-a");
		assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(stored.getVersion()).isZero();
		assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(stored.getUpdatedAt()).isEqualTo(CREATED_AT);
		assertThat(stored.getAttemptCount()).isZero();
		assertThat(stored.getNextAttemptAt()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
		assertThat(stored.getClaimedBy()).isNull();
		assertThat(stored.getLastErrorCode()).isNull();
		assertThat(stored.isReconciliationRequired()).isFalse();

		jdbcTemplate.queryForObject("SELECT * FROM activation_requests WHERE id = ?", (rs, rowNum) -> {
			assertThat(rs.getString("status")).isEqualTo("PENDING");
			assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
			assertThat(rs.getObject("updated_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
			return stored.getId();
		}, stored.getId());
	}

	@Test
	void updatesAuditAndVersionWhilePreservingCreationTime() {
		ActivationRequest request = newRequest();
		entityManager.persist(request);
		entityManager.flush();

		Instant processingAt = Instant.parse("2026-10-28T15:01:00Z");
		clock.now = processingAt;
		request.startProcessing();
		entityManager.flush();
		entityManager.clear();

		ActivationRequest processing = entityManager.find(ActivationRequest.class, request.getId());
		assertThat(processing.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(processing.getVersion()).isEqualTo(1L);
		assertThat(processing.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(processing.getUpdatedAt()).isEqualTo(processingAt);

		Instant failedAt = Instant.parse("2026-10-28T15:02:00Z");
		clock.now = failedAt;
		processing.fail("PROVIDER_REJECTED");
		entityManager.flush();
		entityManager.clear();

		ActivationRequest failed = entityManager.find(ActivationRequest.class, request.getId());
		assertThat(failed.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(failed.getLastErrorCode()).isEqualTo("PROVIDER_REJECTED");
		assertThat(failed.getVersion()).isEqualTo(2L);
		assertThat(failed.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(failed.getUpdatedAt()).isEqualTo(failedAt);
	}

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void loadsExistingRowsWithStringStatusAndAllProcessingMetadata(ActivationStatus status) {
		UUID id = UUID.randomUUID();
		OffsetDateTime nextAttemptAt = OffsetDateTime.parse("2026-10-28T12:05:00-03:00");
		OffsetDateTime leaseUntil = OffsetDateTime.parse("2026-10-28T12:06:00-03:00");
		jdbcTemplate.update("""
				INSERT INTO activation_requests
				    (id, external_id, customer_id, product_code, consumer_id, status, version,
				     attempt_count, next_attempt_at, lease_until, claimed_by, last_error_code,
				     reconciliation_required, created_at, updated_at)
				VALUES (?, ?, 'CUSTOMER-1', 'PRODUCT-1', 'consumer-a', ?, 4, 2,
				        ?, ?, 'worker-1', 'PROVIDER_TIMEOUT', true, ?, ?)
				""", id, "EXT-" + id, status.name(), nextAttemptAt, leaseUntil,
				OffsetDateTime.parse("2026-10-28T12:00:00-03:00"),
				OffsetDateTime.parse("2026-10-28T12:01:00-03:00"));

		ActivationRequest stored = entityManager.find(ActivationRequest.class, id);
		assertThat(stored.getStatus()).isEqualTo(status);
		assertThat(stored.getVersion()).isEqualTo(4L);
		assertThat(stored.getAttemptCount()).isEqualTo(2);
		assertThat(stored.getNextAttemptAt()).isEqualTo(Instant.parse("2026-10-28T15:05:00Z"));
		assertThat(stored.getLeaseUntil()).isEqualTo(Instant.parse("2026-10-28T15:06:00Z"));
		assertThat(stored.getClaimedBy()).isEqualTo("worker-1");
		assertThat(stored.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(stored.isReconciliationRequired()).isTrue();
		assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(stored.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-28T15:01:00Z"));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void rejectsStaleUpdateFromAnotherPersistenceContext() {
		ActivationRequest request = newRequest();
		try (EntityManager first = entityManagerFactory.createEntityManager();
			 EntityManager second = entityManagerFactory.createEntityManager()) {
			try {
				first.getTransaction().begin();
				first.persist(request);
				first.getTransaction().commit();
				first.clear();

				first.getTransaction().begin();
				second.getTransaction().begin();
				ActivationRequest current = first.find(ActivationRequest.class, request.getId());
				ActivationRequest stale = second.find(ActivationRequest.class, request.getId());

				current.startProcessing();
				first.getTransaction().commit();

				stale.startProcessing();
				assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
			} finally {
				if (first.getTransaction().isActive()) {
					first.getTransaction().rollback();
				}
				if (second.getTransaction().isActive()) {
					second.getTransaction().rollback();
				}
			}

			assertThat(jdbcTemplate.queryForObject("SELECT version FROM activation_requests WHERE id = ?",
					Long.class, request.getId())).isEqualTo(1L);
			assertThat(jdbcTemplate.queryForObject("SELECT status FROM activation_requests WHERE id = ?",
					String.class, request.getId())).isEqualTo("PROCESSING");
		} finally {
			jdbcTemplate.update("DELETE FROM activation_requests WHERE id = ?", request.getId());
		}
	}

	@ParameterizedTest
	@EnumSource(value = ActivationStatus.class, names = {"COMPLETED", "FAILED"})
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void preservesTerminalResultWhenConcurrentWriterHasStaleProcessingState(ActivationStatus winningStatus) {
		ActivationRequest request = newRequest();
		request.startProcessing();
		try (EntityManager winner = entityManagerFactory.createEntityManager();
			 EntityManager loser = entityManagerFactory.createEntityManager()) {
			try {
				winner.getTransaction().begin();
				winner.persist(request);
				winner.getTransaction().commit();
				winner.clear();

				winner.getTransaction().begin();
				loser.getTransaction().begin();
				var current = winner.find(ActivationRequest.class, request.getId());
				var stale = loser.find(ActivationRequest.class, request.getId());
				if (winningStatus == ActivationStatus.COMPLETED) {
					current.complete();
					stale.fail("PROVIDER_REJECTED");
				} else {
					current.fail("PROVIDER_REJECTED");
					stale.complete();
				}
				winner.getTransaction().commit();
				var terminalRow = jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", request.getId());
				assertThat(terminalRow.get("status")).isEqualTo(winningStatus.name());
				assertThat(terminalRow.get("version")).isEqualTo(1L);
				assertThat(terminalRow.get("last_error_code"))
						.isEqualTo(winningStatus == ActivationStatus.FAILED ? "PROVIDER_REJECTED" : null);

				assertThatThrownBy(loser::flush).isInstanceOf(OptimisticLockException.class);
				loser.getTransaction().rollback();
				assertThat(jdbcTemplate.queryForMap("SELECT * FROM activation_requests WHERE id = ?", request.getId()))
						.isEqualTo(terminalRow);
			} finally {
				if (winner.getTransaction().isActive()) {
					winner.getTransaction().rollback();
				}
				if (loser.getTransaction().isActive()) {
					loser.getTransaction().rollback();
				}
			}
		} finally {
			jdbcTemplate.update("DELETE FROM activation_requests WHERE id = ?", request.getId());
		}
	}

	private ActivationRequest newRequest() {
		return ActivationRequest.create("EXT-" + UUID.randomUUID(), "CUSTOMER-1", "PRODUCT-1", "consumer-a");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeConfiguration {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}

	static class TestClock extends Clock {

		private Instant now = CREATED_AT;

		@Override
		public ZoneId getZone() {
			return ZoneId.of("America/Recife");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
