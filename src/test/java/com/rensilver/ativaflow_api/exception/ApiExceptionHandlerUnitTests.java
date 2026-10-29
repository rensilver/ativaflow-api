package com.rensilver.ativaflow_api.exception;

import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import com.rensilver.ativaflow_api.support.LogCapture;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP do advice, sem contexto Spring Boot, banco ou servidor externo. */
class ApiExceptionHandlerUnitTests {

	private static final String PATH = "/api/v1/requests";
	private static final String CORRELATION = "api-error-unit-test";
	private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
	private ActivationRequestService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(ActivationRequestService.class);
		validator.afterPropertiesSet();
		var converter = new MappingJackson2HttpMessageConverter();
		converter.getObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		mvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service), new InvalidResultController())
				.setControllerAdvice(new ApiExceptionHandler())
				.setValidator(validator)
				.setMessageConverters(converter)
				.addFilters(new CorrelationIdFilter()).build();
	}

	@AfterEach
	void closeValidator() {
		validator.close();
	}

	@ParameterizedTest
	@MethodSource("businessErrors")
	void translatesApplicationErrorsWithoutExposingInternalMessages(RuntimeException failure, int expectedStatus,
			String title, String detail) throws Exception {
		failListing(failure);
		problem(get(PATH), expectedStatus, title, PATH)
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(header().doesNotExist("Location"));
	}

	static Stream<Arguments> businessErrors() {
		return Stream.of(
				Arguments.of(new ActivationRequestNotFoundException(), 404, "Not Found", "Activation request not found"),
				Arguments.of(new ActivationRequestConflictException(), 409, "Conflict", "Activation request conflict"),
				Arguments.of(new InvalidPaginationException(), 400, "Bad Request", "Invalid request parameters"),
				Arguments.of(new ActivationRequestConflictException() {
					@Override
					public String getMessage() {
						return "SQL token=secret consumer=other";
					}
				}, 409, "Conflict", "Activation request conflict"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "", "{\"externalId\":{\"token\":\"secret\"}}",
			"{\"externalId\":\"EXT\",\"customerId\":\"CUST\",\"productCode\":\"PROD\",\"token\":\"secret\"}"})
	void returnsSafeProblemForUnreadableOrUnknownJson(String body) throws Exception {
		problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body), 400, "Bad Request", PATH)
				.andExpect(jsonPath("$.detail").value("Invalid request body"))
				.andExpect(content().string(not(containsString("secret"))));
	}

	@Test
	void reportsEachInvalidBodyFieldWithoutRejectedValues() throws Exception {
		String secret = "sensitive-value-".repeat(10);
		problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
				{"externalId":"%s","customerId":" ","productCode":null}
				""".formatted(secret)), 400, "Bad Request", PATH)
				.andExpect(jsonPath("$.errors.length()").value(3))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("externalId", "customerId", "productCode")))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty())
				.andExpect(jsonPath("$.errors[0].length()").value(2))
				.andExpect(content().string(not(containsString(secret))));
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=-1", "size=0", "size=101", "customerId= "})
	void reportsInvalidQueryParameterByName(String query) throws Exception {
		String field = query.substring(0, query.indexOf('='));
		problem(get(PATH).queryParam(field, query.substring(query.indexOf('=') + 1)), 400, "Bad Request", PATH)
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"?page=secret", "?status=secret", "/secret"})
	void rejectsTypeMismatchesWithSafeDetails(String suffix) throws Exception {
		String path = suffix.startsWith("?") ? PATH : PATH + suffix;
		problem(get(PATH + suffix), 400, "Bad Request", path)
				.andExpect(jsonPath("$.detail").value("Invalid request parameters"));
	}

	@Test
	void logsUnexpectedFailureWithSameCorrelationAndReturnsGenericProblem() throws Exception {
		var failure = new DataIntegrityViolationException("SQL password=secret", new IllegalStateException("db internal"));
		failListing(failure);
		try (var logs = new LogCapture(ApiExceptionHandler.class)) {
			problem(get(PATH).queryParam("token", "secret"), 500, "Internal Server Error", PATH)
					.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
					.andExpect(content().string(not(containsString("secret"))))
					.andExpect(content().string(not(containsString("DataIntegrityViolationException"))));
			assertThat(logs.events()).singleElement().satisfies(event -> {
				assertThat(event.getLevel().toString()).isEqualTo("ERROR");
				assertThat(LogCapture.fields(event)).containsEntry("correlationId", CORRELATION).containsEntry("status", 500);
				assertThat(event.getThrowableProxy().getClassName()).isEqualTo(DataIntegrityViolationException.class.getName());
			});
		}
	}

	@Test
	void logsExpectedRejectionWithoutSensitivePayloadOrStackTrace() throws Exception {
		try (var logs = new LogCapture(ApiExceptionHandler.class)) {
			problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"secret\":"), 400, "Bad Request", PATH);
			assertThat(logs.events()).singleElement().satisfies(event -> {
				assertThat(LogCapture.fields(event)).containsEntry("correlationId", CORRELATION).containsEntry("status", 400);
				assertThat(event.getFormattedMessage()).doesNotContain("secret");
				assertThat(event.getThrowableProxy()).isNull();
			});
		}
	}

	@Test
	void keepsMethodNotAllowedStatusAndAllowHeader() throws Exception {
		problem(put(PATH), 405, "Method Not Allowed", PATH)
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(header().string("Allow", containsString("POST")));
	}

	@Test
	void keepsUnsupportedMediaTypeStatus() throws Exception {
		problem(post(PATH).contentType(MediaType.TEXT_PLAIN).content("secret"), 415, "Unsupported Media Type", PATH);
	}

	@ParameterizedTest
	@MethodSource("frameworkStatuses")
	void preservesFrameworkStatusAndHeadersWithoutExposingItsReason(int expectedStatus, String title) throws Exception {
		problem(get("/framework-error").param("status", Integer.toString(expectedStatus)), expectedStatus, title, "/framework-error")
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(content().string(not(containsString("secret"))));
	}

	static Stream<Arguments> frameworkStatuses() {
		return Stream.of(Arguments.of(429, "Too Many Requests"), Arguments.of(499, "Request failed"));
	}

	@ParameterizedTest
	@MethodSource("problemStatusContract")
	void returnsCompleteProblemForStandardAndNonStandardStatuses(int expectedStatus, String title, String detail)
			throws Exception {
		problem(get("/framework-error").param("status", Integer.toString(expectedStatus))
				.queryParam("token", "secret"), expectedStatus, title, "/framework-error")
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(content().string(not(containsString("secret"))));
	}

	static Stream<Arguments> problemStatusContract() {
		return Stream.of(
				Arguments.of(429, "Too Many Requests", "Too Many Requests"),
				Arguments.of(499, "Request failed", "Request failed"),
				Arguments.of(503, "Service Unavailable", "An unexpected error occurred"),
				Arguments.of(599, "Request failed", "An unexpected error occurred"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "*/*"})
	void returnsProblemJsonForAcceptedJsonMediaTypes(String accept) throws Exception {
		failListing(new ActivationRequestConflictException());
		problem(get(PATH).accept(accept), 409, "Conflict", PATH)
				.andExpect(jsonPath("$.detail").value("Activation request conflict"));
	}

	@Test
	void treatsReturnValueValidationAsInternalErrorWithoutLeakingValidationDetails() throws Exception {
		problem(get("/invalid-result"), 500, "Internal Server Error", "/invalid-result")
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@ParameterizedTest
	@MethodSource("securityErrors")
	void letsSecurityExceptionsPropagateToTheSecurityFilterChain(RuntimeException failure) {
		failListing(failure);
		assertThatThrownBy(() -> mvc.perform(get(PATH).principal(() -> "consumer")))
				.hasRootCause(failure);
	}

	static Stream<RuntimeException> securityErrors() {
		return Stream.of(new AccessDeniedException("denied"), new BadCredentialsException("invalid"));
	}

	@Test
	void usesGeneratedCorrelationInProblemAndResponseHeader() throws Exception {
		failListing(new ActivationRequestNotFoundException());
		var response = mvc.perform(get(PATH).principal(() -> "consumer"))
				.andExpect(status().isNotFound()).andReturn().getResponse();
		String correlationId = response.getHeader("X-Correlation-ID");
		assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
		assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("correlationId").asText())
				.isEqualTo(correlationId);
	}

	private void failListing(RuntimeException failure) {
		when(service.listActivationRequests(isNull(), isNull(), anyInt(), anyInt(), eq("consumer"))).thenThrow(failure);
	}

	private ResultActions problem(MockHttpServletRequestBuilder request, int expectedStatus, String title, String path)
			throws Exception {
		return mvc.perform(request.principal(() -> "consumer").header("X-Correlation-ID", CORRELATION))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(header().string("X-Correlation-ID", CORRELATION))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value(title))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(jsonPath("$.instance").value(path))
				.andExpect(jsonPath("$.correlationId").value(CORRELATION))
				.andExpect(jsonPath("$.exception").doesNotExist())
				.andExpect(jsonPath("$.trace").doesNotExist());
	}

	@RestController
	static class InvalidResultController {
		@GetMapping("/framework-error")
		public void frameworkError(@RequestParam int status) {
			var failure = new ResponseStatusException(HttpStatusCode.valueOf(status), "SQL token=secret") {
				private final HttpHeaders headers = new HttpHeaders();

				@Override
				public HttpHeaders getHeaders() {
					return headers;
				}
			};
			failure.getHeaders().set("Retry-After", "30");
			throw failure;
		}

		@GetMapping("/invalid-result")
		@NotNull
		public String invalidResult() {
			return null;
		}
	}
}
