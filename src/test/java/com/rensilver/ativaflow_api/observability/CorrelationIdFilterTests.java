package com.rensilver.ativaflow_api.observability;

import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTests {

	@AfterEach
	void clearContext() {
		MDC.clear();
	}

	@Test
	void exposesIncomingCorrelationDuringProcessingAndReturnsItInTheResponse() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader("X-Correlation-ID", "client-123:attempt_1.v2");
		var response = new MockHttpServletResponse();

		filter().doFilter(request, response, (incoming, outgoing) -> {
			assertThat(MDC.get("correlationId")).isEqualTo("client-123:attempt_1.v2");
			assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("client-123:attempt_1.v2");
		});

		assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("client-123:attempt_1.v2");
		assertThat(MDC.get("correlationId")).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "bad\r\nAuthorization: secret", "contains spaces", "ação", "Bearer consumer.jwt.signature"})
	void generatesUuidForMissingOrInvalidCorrelation(String incomingId) throws Exception {
		var request = new MockHttpServletRequest();
		if (incomingId != null) {
			request.addHeader("X-Correlation-ID", incomingId);
		}
		assertGeneratesUuid(request);
	}

	@Test
	void rejectsAmbiguousRepeatedHeaders() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader("X-Correlation-ID", "first");
		request.addHeader("X-Correlation-ID", "second");
		assertGeneratesUuid(request);
	}

	@Test
	void accepts128CharactersAndReplacesLongerIdentifiers() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader("X-Correlation-ID", "a".repeat(128));
		var response = new MockHttpServletResponse();
		filter().doFilter(request, response, (incoming, outgoing) -> {});
		assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("a".repeat(128));

		request = new MockHttpServletRequest();
		request.addHeader("X-Correlation-ID", "a".repeat(129));
		assertGeneratesUuid(request);
	}

	@Test
	void restoresPreviousContextAndKeepsOtherMdcEntriesWhenProcessingFails() {
		MDC.put("correlationId", "outer-context");
		MDC.put("unrelated", "preserved");
		var request = new MockHttpServletRequest();
		request.addHeader("X-Correlation-ID", "current-request");
		var failure = new ServletException("downstream failed");

		assertThatThrownBy(() -> filter().doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertThat(MDC.get("correlationId")).isEqualTo("current-request");
			throw failure;
		})).isSameAs(failure);

		assertThat(MDC.get("correlationId")).isEqualTo("outer-context");
		assertThat(MDC.get("unrelated")).isEqualTo("preserved");
	}

	private void assertGeneratesUuid(MockHttpServletRequest request) throws Exception {
		var response = new MockHttpServletResponse();
		filter().doFilter(request, response, (incoming, outgoing) -> {
			String generated = response.getHeader("X-Correlation-ID");
			assertThat(generated).isNotNull();
			assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
			assertThat(MDC.get("correlationId")).isEqualTo(generated);
		});
		assertThat(MDC.get("correlationId")).isNull();
	}

	private Filter filter() {
		try (var context = new AnnotationConfigApplicationContext(FilterConfiguration.class)) {
			var filters = context.getBeansOfType(Filter.class).values();
			assertThat(filters).as("correlation filter registered in the application").hasSize(1);
			return filters.iterator().next();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ComponentScan(basePackages = "com.rensilver.ativaflow_api.observability",
			excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
					classes = ActivationRequestStateMetrics.class))
	static class FilterConfiguration {
	}
}
