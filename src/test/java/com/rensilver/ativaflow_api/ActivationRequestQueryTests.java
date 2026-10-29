package com.rensilver.ativaflow_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
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
