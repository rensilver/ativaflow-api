package com.rensilver.ativaflow_api.repository;

import java.util.List;
import java.util.stream.Stream;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Verifica a composição da Specification real na fronteira Criteria, sem banco ou contexto Spring. */
class ActivationRequestConsumerQueryUnitTests {

	@ParameterizedTest
	@MethodSource("filters")
	@SuppressWarnings("unchecked")
	void consumerPredicateIsMandatoryAndCombinedWithEveryPresentFilterUsingAnd(ActivationStatus status,
			String customerId, List<String> expectedAttributes) {
		var repository = mock(ActivationRequestRepository.class, CALLS_REAL_METHODS);
		var pageable = PageRequest.of(1, 1);
		when(repository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty(pageable));

		repository.findAllByFilters("consumer-a", status, customerId, pageable);

		ArgumentCaptor<Specification<ActivationRequest>> specification = ArgumentCaptor.forClass(Specification.class);
		verify(repository).findAll(specification.capture(), eq(pageable));
		Root<ActivationRequest> root = mock(Root.class);
		CriteriaQuery<ActivationRequest> query = mock(CriteriaQuery.class);
		var builder = mock(CriteriaBuilder.class);
		var predicates = new Predicate[expectedAttributes.size()];
		for (int i = 0; i < expectedAttributes.size(); i++) {
			String attribute = expectedAttributes.get(i);
			Path<Object> path = mock(Path.class);
			predicates[i] = mock(Predicate.class);
			Object value = switch (attribute) {
				case "consumerId" -> "consumer-a";
				case "status" -> ActivationStatus.PENDING;
				case "customerId" -> "SHARED-CUSTOMER";
				default -> throw new IllegalArgumentException(attribute);
			};
			when(root.get(attribute)).thenReturn(path);
			when(builder.equal(path, value)).thenReturn(predicates[i]);
		}
		var conjunction = mock(Predicate.class);
		when(builder.and(predicates)).thenReturn(conjunction);

		Predicate result = specification.getValue().toPredicate(root, query, builder);

		assertThat(result).isSameAs(conjunction);
		verify(builder).and(predicates);
		// Não permite trocar AND por OR nem omitir/adicionar filtros silenciosamente.
		for (String attribute : expectedAttributes) {
			verify(root).get(attribute);
		}
		verifyNoMoreInteractions(root);
	}

	static Stream<Arguments> filters() {
		return Stream.of(
				Arguments.of(null, null, List.of("consumerId")),
				Arguments.of(ActivationStatus.PENDING, null, List.of("consumerId", "status")),
				Arguments.of(null, "SHARED-CUSTOMER", List.of("consumerId", "customerId")),
				Arguments.of(ActivationStatus.PENDING, "SHARED-CUSTOMER",
						List.of("consumerId", "status", "customerId")));
	}
}
