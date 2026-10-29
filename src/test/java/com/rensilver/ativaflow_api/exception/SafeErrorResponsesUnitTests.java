package com.rensilver.ativaflow_api.exception;

import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Regressões de segurança do contrato público 400/404/409/500, sem contexto Spring Boot. */
class SafeErrorResponsesUnitTests {

	private static final String PATH = "/api/v1/requests";
	private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String CONSUMER = "private-consumer";
	private static final String CORRELATION = "safe-error-test";
	private static final String SECRET = "private-token";
	private static final String INTERNAL_MESSAGE =
			"SELECT password FROM activation_requests; token=private-token; owner=private-consumer";
	private static final String VALID_BODY = """
			{"externalId":"private-external","customerId":"private-customer","productCode":"private-product"}
			""";
	private static final CreateActivationRequest VALID_REQUEST =
			new CreateActivationRequest("private-external", "private-customer", "private-product");

	private final ObjectMapper mapper = new ObjectMapper();
	private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
	private ActivationRequestService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(ActivationRequestService.class);
		validator.afterPropertiesSet();
		var converter = new MappingJackson2HttpMessageConverter();
		converter.getObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		mvc = standaloneSetup(new ActivationRequestController(service))
				.setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
				.setMessageConverters(converter).addFilters(new CorrelationIdFilter()).build();
	}

	@AfterEach
	void closeValidator() {
		validator.close();
	}

	@ParameterizedTest
	@MethodSource("invalidPayloads")
	void invalidPayloadReturns400WithoutEchoingInputOrParserDetails(String body) throws Exception {
		assertSafeProblem(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
				400, "Bad Request", "Invalid request body", PATH, false);
	}

	static Stream<String> invalidPayloads() {
		return Stream.of("", "null", "{\"private-token\":", "{\"externalId\":{\"token\":\"private-token\"}}",
				"""
				{"externalId":"private-external","customerId":"private-customer",
				 "productCode":"private-product","token":"private-token"}
				""");
	}

	@Test
	void invalidBodyFieldsReturn400WithoutRejectedValues() throws Exception {
		JsonNode problem = assertSafeProblem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
				{"externalId":"%s","customerId":" ","productCode":null}
				""".formatted(SECRET.repeat(10))), 400, "Bad Request", "Request validation failed", PATH, true);
		assertThat(problem.path("errors")).hasSize(3);
		assertThat(problem.path("errors").findValuesAsText("field"))
				.containsExactlyInAnyOrder("externalId", "customerId", "productCode");
		for (JsonNode error : problem.path("errors")) {
			assertThat(error.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder("field", "message");
			assertThat(error.path("message").asText()).isNotBlank();
		}
	}

	@Test
	void notFoundReturns404WithoutExceptionMessageOrOwnerDetails() throws Exception {
		var failure = new ActivationRequestNotFoundException() {
			@Override
			public String getMessage() {
				return INTERNAL_MESSAGE;
			}
		};
		failure.initCause(new IllegalStateException("Provider response: " + SECRET));
		when(service.getActivationRequestById(ID, CONSUMER)).thenThrow(failure);

		assertSafeProblem(get(PATH + "/" + ID), 404, "Not Found", "Activation request not found", PATH + "/" + ID, false);
	}

	@Test
	void conflictReturns409WithoutExceptionMessageOrConflictingPayload() throws Exception {
		var failure = new ActivationRequestConflictException() {
			@Override
			public String getMessage() {
				return INTERNAL_MESSAGE;
			}
		};
		failure.initCause(new IllegalStateException("Conflicting customer: private-customer"));
		when(service.createActivationRequest(VALID_REQUEST, CONSUMER)).thenThrow(failure);

		assertSafeProblem(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
				409, "Conflict", "Activation request conflict", PATH, false);
	}

	@ParameterizedTest
	@MethodSource("unexpectedFailures")
	void unexpectedFailureReturns500WithoutInternalDetailsEvenWhenMessageIsAbsent(RuntimeException failure) throws Exception {
		when(service.createActivationRequest(VALID_REQUEST, CONSUMER)).thenThrow(failure);

		assertSafeProblem(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
				500, "Internal Server Error", "An unexpected error occurred", PATH, false);
	}

	static Stream<Arguments> unexpectedFailures() {
		return Stream.of(
				Arguments.of(new DataIntegrityViolationException(INTERNAL_MESSAGE,
						new IllegalStateException("Provider response: " + SECRET))),
				Arguments.of(new NullPointerException()));
	}

	private JsonNode assertSafeProblem(MockHttpServletRequestBuilder request, int expectedStatus, String title,
			String detail, String path, boolean validation) throws Exception {
		var response = mvc.perform(request.principal(() -> CONSUMER).header("X-Correlation-ID", CORRELATION)
				.queryParam("token", SECRET))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(header().string("X-Correlation-ID", CORRELATION))
				.andExpect(jsonPath("$.type").value(validation ? "urn:ativaflow:problem:validation-error" : "about:blank"))
				.andExpect(jsonPath("$.title").value(title))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(jsonPath("$.instance").value(path))
				.andExpect(jsonPath("$.correlationId").value(CORRELATION))
				.andReturn().getResponse();
		String body = response.getContentAsString();
		assertThat(body).doesNotContain(SECRET, CONSUMER, "private-external", "private-customer", "private-product",
				"SELECT", "password", "Provider response", "java.lang", "com.rensilver", "org.springframework");
		JsonNode problem = mapper.readTree(body);
		// Uma lista fechada também impede novos campos com causas, stack traces ou mensagens internas.
		if (validation) {
			assertThat(problem.properties()).extracting(entry -> entry.getKey())
					.containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "correlationId", "errors");
		}
		else {
			assertThat(problem.properties()).extracting(entry -> entry.getKey())
					.containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "correlationId");
		}
		return problem;
	}
}
