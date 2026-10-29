package com.rensilver.ativaflow_api.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.exception.ApiExceptionHandler;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestCreationTransaction;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller, serviço e mapper reais; principal JWT já autenticado e banco substituído. */
@ExtendWith(MockitoExtension.class)
class ActivationRequestBolaUnitTests {

	private static final String PATH = "/api/v1/requests";
	@Mock
	private ActivationRequestRepository repository;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		var mapper = Mappers.getMapper(ActivationRequestMapper.class);
		var service = new ActivationRequestService(repository, mapper,
				new ActivationRequestCreationTransaction(repository, mapper));
		mvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service))
				.setControllerAdvice(new ApiExceptionHandler())
				.addFilters(new CorrelationIdFilter()).build();
	}

	@ParameterizedTest
	@CsvSource({"consumer-a, consumer-b", "consumer-b, consumer-a"})
	void foreignAndMissingResourcesReturnTheSame404WithoutUnscopedLookup(String owner, String other) throws Exception {
		var request = ActivationRequest.create("EXT-PRIVATE", "CUSTOMER-PRIVATE", "PRODUCT-PRIVATE", owner);
		UUID id = request.getId();
		when(repository.findByIdAndConsumerId(id, owner))
				.thenReturn(Optional.of(request)).thenReturn(Optional.empty());

		mvc.perform(authenticated(get(PATH + "/" + id), owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));

		var foreign = mvc.perform(authenticated(get(PATH + "/" + id), other)
				.param("consumerId", owner).header("X-Consumer-ID", owner))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Activation request not found"))
				.andExpect(jsonPath("$.correlationId").value("bola-test"))
				.andExpect(jsonPath("$.externalId").doesNotExist())
				.andExpect(jsonPath("$.customerId").doesNotExist())
				.andExpect(jsonPath("$.consumerId").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		// Mesmo caminho/correlação: a resposta não distingue recurso alheio de inexistente.
		mvc.perform(authenticated(get(PATH + "/" + id), owner))
				.andExpect(status().isNotFound()).andExpect(content().json(foreign));
		verify(repository, times(2)).findByIdAndConsumerId(id, owner);
		verify(repository).findByIdAndConsumerId(id, other);
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@MethodSource("consumerFilters")
	void listsTheAuthenticatedConsumerPageWithFiltersAndScopedTotals(String consumer, ActivationStatus filterStatus,
			String customerId) throws Exception {
		var owned = ActivationRequest.create("EXT-OWNED", "SHARED-CUSTOMER", "PRODUCT-1", consumer);
		var pageable = PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
		when(repository.findAllByFilters(consumer, filterStatus, customerId, pageable))
				.thenReturn(new PageImpl<>(List.of(owned), pageable, 3));
		var request = authenticated(get(PATH), consumer).param("page", "1").param("size", "1")
				.param("consumerId", "forged-owner").header("X-Consumer-ID", "forged-owner");
		if (filterStatus != null) {
			request.param("status", filterStatus.name());
		}
		if (customerId != null) {
			request.param("customerId", customerId);
		}

		mvc.perform(request).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(owned.getId().toString()))
				.andExpect(jsonPath("$.content[0].consumerId").doesNotExist())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(3));
		verify(repository).findAllByFilters(consumer, filterStatus, customerId, pageable);
		verifyNoMoreInteractions(repository);
	}

	static Stream<Arguments> consumerFilters() {
		return Stream.of("consumer-a", "consumer-b").flatMap(consumer -> Stream.of(
				Arguments.of(consumer, null, null),
				Arguments.of(consumer, ActivationStatus.PENDING, null),
				Arguments.of(consumer, null, "SHARED-CUSTOMER"),
				Arguments.of(consumer, ActivationStatus.PENDING, "SHARED-CUSTOMER")));
	}

	@Test
	void consumerWithoutRequestsReceivesEmptyContentAndZeroTotals() throws Exception {
		var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
		when(repository.findAllByFilters("consumer-empty", null, null, pageable)).thenReturn(Page.empty(pageable));

		mvc.perform(authenticated(get(PATH), "consumer-empty").param("consumerId", "consumer-with-data"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.totalPages").value(0));
		verify(repository).findAllByFilters("consumer-empty", null, null, pageable);
		verifyNoMoreInteractions(repository);
	}

	private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, String consumer) {
		var jwt = Jwt.withTokenValue("already-validated-jwt").header("alg", "RS256")
				.subject(consumer).claim("scope", "requests/read")
				.claim("client_id", "forged-owner").claim("consumerId", "forged-owner").build();
		return request.principal(new JwtAuthenticationToken(jwt,
				List.of(new SimpleGrantedAuthority("SCOPE_requests/read")), consumer))
				.header("X-Correlation-ID", "bola-test");
	}
}
