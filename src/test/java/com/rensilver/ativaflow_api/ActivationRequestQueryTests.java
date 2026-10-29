package com.rensilver.ativaflow_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rensilver.ativaflow_api.config.JpaAuditingConfiguration;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, JpaAuditingConfiguration.class})
@DataJpaTest(properties = {
		"spring.jpa.properties.hibernate.session_factory.statement_inspector="
				+ "com.rensilver.ativaflow_api.ActivationRequestQueryTests$SqlCapture"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ActivationRequestQueryTests {

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void winnerLookupUsesNewTransactionWhenCallerWasInvalidatedByUniqueConstraint() {
		var winner = repository.saveAndFlush(
				ActivationRequest.create("EXT-RECOVERY", "CUSTOMER-1", "PRODUCT-1", "consumer-a"));
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
				assertThatThrownBy(() -> repository.saveAndFlush(
						ActivationRequest.create("EXT-RECOVERY", "CUSTOMER-1", "PRODUCT-1", "consumer-a")))
						.isInstanceOf(DataIntegrityViolationException.class);
				assertThat(transaction.isRollbackOnly()).isTrue();
				transaction.setRollbackOnly();

				var recovered = repository.findWinnerByExternalIdAndConsumerId("EXT-RECOVERY", "consumer-a")
						.orElseThrow();
				assertThat(recovered.getId()).isEqualTo(winner.getId());
				assertThat(recovered.getCustomerId()).isEqualTo("CUSTOMER-1");
				assertThat(recovered.getProductCode()).isEqualTo("PRODUCT-1");
				assertThat(repository.findWinnerByExternalIdAndConsumerId("EXT-RECOVERY", "consumer-b")).isEmpty();
				assertThat(repository.findWinnerByExternalIdAndConsumerId("EXT-MISSING", "consumer-a")).isEmpty();

				// A releitura suspende a transação inválida, sem recuperá-la nem confirmá-la.
				assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class))
						.hasRootCauseInstanceOf(java.sql.SQLException.class)
						.satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState())
								.isEqualTo("25P02"));
			});
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM activation_requests WHERE external_id = 'EXT-RECOVERY'", Long.class))
					.isEqualTo(1);
		} finally {
			repository.deleteById(winner.getId());
		}
	}

	@Test
	void externalIdLookupOnlyReturnsRecordsOwnedByTheAuthenticatedConsumer() {
		var owned = repository.saveAndFlush(
				ActivationRequest.create("EXT-OWNED", "CUSTOMER-1", "PRODUCT-1", "consumer-a"));
		repository.saveAndFlush(
				ActivationRequest.create("EXT-FOREIGN", "CUSTOMER-1", "PRODUCT-1", "consumer-b"));

		assertThat(repository.findByExternalIdAndConsumerId("EXT-OWNED", "consumer-a"))
				.contains(owned);
		assertThat(repository.findByExternalIdAndConsumerId("EXT-FOREIGN", "consumer-a")).isEmpty();
		assertThat(repository.findByExternalIdAndConsumerId("EXT-OWNED", "consumer-b")).isEmpty();
		assertThat(repository.findByExternalIdAndConsumerId("EXT-MISSING", "consumer-a")).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({", , 9", "PENDING, , 6", ", CUSTOMER-1, 6", "PENDING, CUSTOMER-1, 3"})
	void pushesConsumerFiltersPaginationAndCountIntoSql(ActivationStatus status, String customerId, long total) {
		for (int i = 0; i < 3; i++) {
			insertRequest("consumer-a", "CUSTOMER-1", ActivationStatus.PENDING);
			insertRequest("consumer-a", "CUSTOMER-1", ActivationStatus.FAILED);
			insertRequest("consumer-a", "CUSTOMER-2", ActivationStatus.PENDING);
			insertRequest("consumer-b", "CUSTOMER-1", ActivationStatus.PENDING);
		}

		List<String> statements = new ArrayList<>();
		SqlCapture.STATEMENTS.set(statements);
		try {
			var result = repository.findAllByFilters("consumer-a", status, customerId,
					PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
			assertThat(result.getTotalElements()).isEqualTo(total);
			assertThat(result.getContent()).hasSize(1).allSatisfy(request -> {
				assertThat(request.getConsumerId()).isEqualTo("consumer-a");
				if (status != null) {
					assertThat(request.getStatus()).isEqualTo(status);
				}
				if (customerId != null) {
					assertThat(request.getCustomerId()).isEqualTo(customerId);
				}
			});
		} finally {
			SqlCapture.STATEMENTS.remove();
		}

		assertThat(statements).hasSize(2).allSatisfy(sql -> {
			String where = sql.substring(sql.indexOf(" where "));
			assertThat(where).contains(".consumer_id=?").doesNotContain(" is null", " or ");
			assertThat(where.contains(".status=?")).isEqualTo(status != null);
			assertThat(where.contains(".customer_id=?")).isEqualTo(customerId != null);
		});
		assertThat(statements.getFirst()).contains(" order by ", ".created_at desc", ".id desc",
				" offset ? rows fetch first ? rows only");
		assertThat(statements.getLast()).contains("count(").doesNotContain(" order by ");
	}

	private void insertRequest(String consumerId, String customerId, ActivationStatus status) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO activation_requests (id, external_id, customer_id, product_code, consumer_id, status)
				VALUES (?, ?, ?, 'PRODUCT-1', ?, ?)
				""", id, "EXT-" + id, customerId, consumerId, status.name());
	}

	public static class SqlCapture implements StatementInspector {

		private static final ThreadLocal<List<String>> STATEMENTS = new ThreadLocal<>();

		@Override
		public String inspect(String sql) {
			List<String> statements = STATEMENTS.get();
			if (statements != null) {
				statements.add(sql);
			}
			return sql;
		}
	}
}
