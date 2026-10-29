package com.rensilver.ativaflow_api.exception;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import com.rensilver.ativaflow_api.support.LogCapture;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Contrato de validação com MVC e Bean Validation reais, sem contexto Spring Boot. */
class ValidationProblemContractUnitTests {

	private static final String PATH = "/api/v1/requests";
	private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator.afterPropertiesSet();
		mvc = standaloneSetup(new ActivationRequestController(mock(ActivationRequestService.class)), new ValidationController())
				.setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
				.addFilters(new CorrelationIdFilter()).build();
	}

	@AfterEach
	void closeValidator() {
		validator.close();
	}

	@Test
	void bodyValidationReturnsStableTypeAndEveryFieldWithoutRejectedValues() throws Exception {
		String secret = "sensitive-value-".repeat(10);
		mvc.perform(post(PATH).header("X-Correlation-ID", "validation-body")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"externalId":"%s","customerId":" ","productCode":null}
						""".formatted(secret)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value("Request validation failed"))
				.andExpect(jsonPath("$.instance").value(PATH))
				.andExpect(jsonPath("$.correlationId").value("validation-body"))
				.andExpect(header().string("X-Correlation-ID", "validation-body"))
				.andExpect(jsonPath("$.errors.length()").value(3))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("externalId", "customerId", "productCode")))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty())
				.andExpect(jsonPath("$.errors[0].length()").value(2))
				.andExpect(content().string(not(containsString(secret))));
	}

	@ParameterizedTest
	@CsvSource({"page, -1", "size, 0", "size, 101", "customerId, ' '",
			"page, sensitive", "size, sensitive", "status, sensitive"})
	void queryValidationIdentifiesFieldWithSameTypeForConstraintsAndConversion(String field, String value) throws Exception {
		mvc.perform(get(PATH).param(field, value).header("X-Correlation-ID", "validation-query"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.correlationId").value("validation-query"))
				.andExpect(jsonPath("$.instance").value(PATH))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty())
				.andExpect(jsonPath("$.errors[0].length()").value(2))
				.andExpect(content().string(not(containsString("sensitive"))));
	}

	@Test
	void uuidConversionIdentifiesPathVariableWithoutExposingConversionDetails() throws Exception {
		mvc.perform(get(PATH + "/invalid-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.errors[0].field").value("id"))
				.andExpect(jsonPath("$.errors[0].message").value("Invalid value"))
				.andExpect(jsonPath("$.errors[0].length()").value(2));
	}

	@ParameterizedTest
	@CsvSource({"/validation-probe?limit=0, limit", "/validation-probe/0, requestId"})
	void parameterConstraintUsesPublicAliasInsteadOfJavaParameterName(String path, String field) throws Exception {
		mvc.perform(get(path))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void missingRequiredParameterIdentifiesPublicName() throws Exception {
		mvc.perform(get("/validation-probe"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.errors[0].field").value("limit"))
				.andExpect(jsonPath("$.errors[0].message").value("Required parameter is missing"));
	}

	@Test
	void preservesMultipleConstraintMessagesForSameField() throws Exception {
		mvc.perform(post("/validation-probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "name")))
				.andExpect(jsonPath("$.errors[*].message", containsInAnyOrder("Name is required", "Name is too short")));
	}

	@Test
	void generatedCorrelationMatchesResponseHeaderAndValidationLog() throws Exception {
		try (var logs = new LogCapture(ApiExceptionHandler.class)) {
			var response = mvc.perform(get(PATH).param("page", "invalid"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.type").value("urn:ativaflow:problem:validation-error"))
					.andReturn().getResponse();
			String correlationId = response.getHeader("X-Correlation-ID");
			assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
			assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("correlationId").asText())
					.isEqualTo(correlationId);
			assertThat(logs.events()).singleElement().satisfies(event -> {
				assertThat(LogCapture.fields(event)).containsEntry("correlationId", correlationId).containsEntry("status", 400);
				assertThat(event.getThrowableProxy()).isNull();
			});
		}
	}

	@Test
	void unreadableJsonDoesNotInventAFieldOrUseValidationType() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	void returnValueValidationRemainsGenericServerErrorWithoutFieldDetails() throws Exception {
		mvc.perform(get("/validation-probe/invalid-result"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@RestController
	static class ValidationController {

		@GetMapping("/validation-probe")
		public int query(@RequestParam("limit") @Min(1) int internalLimit) {
			return internalLimit;
		}

		@GetMapping("/validation-probe/{requestId}")
		public int path(@PathVariable("requestId") @Min(1) int internalId) {
			return internalId;
		}

		@PostMapping("/validation-probe")
		public void body(@Valid @RequestBody ProbeRequest request) {
		}

		@GetMapping("/validation-probe/invalid-result")
		@NotNull
		public String invalidResult() {
			return null;
		}
	}

	record ProbeRequest(@NotBlank(message = "Name is required") @Size(min = 3, message = "Name is too short") String name) {
	}
}
