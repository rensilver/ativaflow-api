package com.rensilver.ativaflow_api.exception;

import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.security.SecurityProblemHandler;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Proteção contra divulgação com MVC real em memória, sem Boot, banco ou rede. */
class ErrorDisclosureProtectionUnitTests {

	private static final String PRIVATE_DETAIL = "SELECT api_key FROM internal_table; token=private-token; "
			+ "com.internal.ProviderClient https://provider.internal/private";
	private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator.afterPropertiesSet();
		mvc = standaloneSetup(new DisclosureController()).setControllerAdvice(new ApiExceptionHandler())
				.setValidator(validator).addFilters(new CorrelationIdFilter()).build();
	}

	@AfterEach
	void closeValidator() {
		validator.close();
	}

	@Test
	void bodyConstraintMessagesNeverExposeInterpolatedValuesOrInternalDetails() throws Exception {
		var response = mvc.perform(post("/disclosure").contentType(MediaType.APPLICATION_JSON)
				.content("{\"customerId\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("customerId", "customerId")))
				.andExpect(jsonPath("$.errors[*].message", containsInAnyOrder("Must not be blank", "Invalid length")))
				.andReturn().getResponse();
		assertNoPrivateDetails(response.getContentAsString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/disclosure?limit=0", "/disclosure/0"})
	void queryAndPathConstraintsNeverCopyTheirOriginalMessage(String path) throws Exception {
		var response = mvc.perform(get(path))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("limit"))
				.andExpect(jsonPath("$.errors[0].message").value("Value out of range"))
				.andReturn().getResponse();
		assertNoPrivateDetails(response.getContentAsString());
	}

	@Test
	void unknownFieldAndGlobalValidationMessagesUseGenericFallback() throws Exception {
		var binding = new BeanPropertyBindingResult(new Object(), "com.internal.PrivateCommand");
		binding.addError(new FieldError(binding.getObjectName(), "customerId", "private-token", false,
				new String[] {"PrivateProviderFailure"}, new Object[] {PRIVATE_DETAIL}, PRIVATE_DETAIL));
		binding.addError(new ObjectError(binding.getObjectName(), null, null, PRIVATE_DETAIL));
		var method = DisclosureController.class.getMethod("body", DisclosureRequest.class);
		var exception = new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);
		var response = new ApiExceptionHandler().handleException(exception,
				new ServletWebRequest(new MockHttpServletRequest("POST", "/disclosure")));
		var json = new ObjectMapper().valueToTree(response.getBody());
		assertThat(json.path("properties").path("errors").findValuesAsText("field"))
				.containsExactly("customerId", "request");
		assertThat(json.path("properties").path("errors").findValuesAsText("message"))
				.containsExactly("Invalid value", "Invalid value");
		assertNoPrivateDetails(json.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"30", "Wed, 21 Oct 2015 07:28:00 GMT"})
	void frameworkErrorsDiscardPrivateBodyAndHeadersWhileKeepingValidRetryAfter(String retryAfter) throws Exception {
		var response = mvc.perform(get("/disclosure/framework").param("retryAfter", retryAfter))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.detail").value("Too Many Requests"))
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, retryAfter))
				.andExpect(header().doesNotExist("X-Api-Key"))
				.andExpect(header().doesNotExist("X-Provider-Error"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andReturn().getResponse();
		assertNoPrivateDetails(response.getContentAsString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"token=private-token", "-1", "30; token=private-token"})
	void invalidRetryAfterCannotBeUsedToDiscloseSensitiveDetails(String retryAfter) throws Exception {
		mvc.perform(get("/disclosure/framework").param("retryAfter", retryAfter))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
	}

	@Test
	void methodNotAllowedKeepsItsProtocolHeader() throws Exception {
		mvc.perform(put("/disclosure"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PATCH"})
	void unsupportedMediaTypeKeepsItsNegotiationHeaders(String method) throws Exception {
		var result = mvc.perform(request(HttpMethod.valueOf(method), "/disclosure")
				.contentType(MediaType.TEXT_PLAIN).content("private-token"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.APPLICATION_JSON_VALUE)));
		if (method.equals("PATCH")) {
			result.andExpect(header().string(HttpHeaders.ACCEPT_PATCH, containsString(MediaType.APPLICATION_JSON_VALUE)));
		}
		assertNoPrivateDetails(result.andReturn().getResponse().getContentAsString());
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	void securityResponsesHideAuthenticationAndProviderDiagnostics(int status) throws Exception {
		var mapper = new MappingJackson2HttpMessageConverter().getObjectMapper();
		var handler = new SecurityProblemHandler(mapper);
		var request = new MockHttpServletRequest("GET", "/disclosure");
		request.setQueryString("token=private-token");
		var response = new MockHttpServletResponse();
		if (status == 401) {
			handler.commence(request, response, new BadCredentialsException(PRIVATE_DETAIL));
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"Realm\"");
		}
		else {
			handler.handle(request, response, new AccessDeniedException(PRIVATE_DETAIL));
		}
		assertThat(response.getStatus()).isEqualTo(status);
		var body = mapper.readTree(response.getContentAsByteArray());
		assertThat(body.properties()).extracting(entry -> entry.getKey())
				.containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "correlationId");
		assertNoPrivateDetails(response.getContentAsString());
	}

	@ParameterizedTest
	@MethodSource("upstreamFailures")
	void unexpectedDatabaseAndProviderFailuresRemainGeneric(RuntimeException failure) throws Exception {
		var failingMvc = standaloneSetup(new FailureController(failure))
				.setControllerAdvice(new ApiExceptionHandler()).build();
		var response = failingMvc.perform(get("/failure"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andReturn().getResponse();
		assertNoPrivateDetails(response.getContentAsString());
	}

	static Stream<RuntimeException> upstreamFailures() {
		return Stream.of(new org.springframework.dao.DataAccessResourceFailureException(PRIVATE_DETAIL),
				new org.springframework.web.client.ResourceAccessException(PRIVATE_DETAIL));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "text/html"})
	void fallbackErrorEndpointHidesDetailsEvenWithDevelopmentDefaultsAndDebugQueryParameters(String accept) throws Exception {
		var environment = new StandardEnvironment();
		environment.getPropertySources().addLast(new MapPropertySource("developmentDefaults", Map.of(
				"server.error.include-stacktrace", "always", "server.error.include-message", "always",
				"server.error.include-binding-errors", "always", "server.error.include-exception", "true")));
		for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
			environment.getPropertySources().addFirst(source);
		}
		ErrorProperties properties = Binder.get(environment).bind("server.error", ErrorProperties.class).get();
		var request = new MockHttpServletRequest("GET", "/error");
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(PRIVATE_DETAIL));
		request.setAttribute(RequestDispatcher.ERROR_MESSAGE, PRIVATE_DETAIL);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/internal/private-token");
		for (String parameter : new String[] {"trace", "message", "errors", "path"}) {
			request.setParameter(parameter, "true");
		}
		var controller = new BasicErrorController(new DefaultErrorAttributes(), properties);
		Map<String, Object> body;
		if (accept.equals("text/html")) {
			var response = new MockHttpServletResponse();
			body = controller.errorHtml(request, response).getModel();
			assertThat(response.getStatus()).isEqualTo(500);
		}
		else {
			var response = controller.error(request);
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			body = response.getBody();
		}
		assertThat(body).doesNotContainKeys("exception", "trace", "message", "errors", "path");
		assertNoPrivateDetails(new ObjectMapper().writeValueAsString(body));
	}

	private void assertNoPrivateDetails(String response) {
		assertThat(response).doesNotContain("SELECT", "api_key", "internal_table", "private-token",
				"com.internal", "provider.internal", "PrivateCommand", "PrivateProviderFailure", "java.lang",
				"org.springframework", "stackTrace");
	}

	@RestController
	static class FailureController {
		private final RuntimeException failure;

		FailureController(RuntimeException failure) {
			this.failure = failure;
		}

		@GetMapping("/failure")
		public void fail() {
			throw failure;
		}
	}

	@RestController
	static class DisclosureController {
		@PostMapping("/disclosure")
		public void body(@Valid @RequestBody DisclosureRequest request) {
		}

		@PatchMapping("/disclosure")
		public void patch(@RequestBody DisclosureRequest request) {
		}

		@GetMapping("/disclosure")
		public int query(@RequestParam("limit") @Min(value = 1, message = PRIVATE_DETAIL) int internalLimit) {
			return internalLimit;
		}

		@GetMapping("/disclosure/{limit}")
		public int path(@PathVariable("limit") @Min(value = 1, message = PRIVATE_DETAIL) int internalLimit) {
			return internalLimit;
		}

		@GetMapping("/disclosure/framework")
		public void framework(@RequestParam String retryAfter) {
			ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, PRIVATE_DETAIL);
			body.setTitle(PRIVATE_DETAIL);
			body.setProperty("trace", PRIVATE_DETAIL);
			var exception = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, body,
					new IllegalStateException(PRIVATE_DETAIL));
			exception.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
			for (String name : new String[] {"X-Api-Key", "X-Provider-Error", HttpHeaders.AUTHORIZATION,
					HttpHeaders.LOCATION}) {
				exception.getHeaders().set(name, PRIVATE_DETAIL);
			}
			exception.getHeaders().set(HttpHeaders.SET_COOKIE, "session=private-token; Path=/");
			throw exception;
		}
	}

	record DisclosureRequest(
			@NotBlank(message = PRIVATE_DETAIL)
			@Size(min = 3, message = "Rejected ${validatedValue}: " + PRIVATE_DETAIL) String customerId) {
	}
}
