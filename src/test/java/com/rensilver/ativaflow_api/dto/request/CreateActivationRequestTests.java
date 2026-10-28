package com.rensilver.ativaflow_api.dto.request;

import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreateActivationRequestTests {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@ParameterizedTest
	@MethodSource("invalidText")
	void rejectsMissingBlankOrOversizedExternalId(String invalid) {
		var request = new CreateActivationRequest(invalid, "CUST-10001", "INTERNET-500");

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("externalId");
	}

	@ParameterizedTest
	@MethodSource("invalidText")
	void rejectsMissingBlankOrOversizedCustomerId(String invalid) {
		var request = new CreateActivationRequest("EXT-12345", invalid, "INTERNET-500");

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("customerId");
	}

	@ParameterizedTest
	@MethodSource("invalidText")
	void rejectsMissingBlankOrOversizedProductCode(String invalid) {
		var request = new CreateActivationRequest("EXT-12345", "CUST-10001", invalid);

		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("productCode");
	}

	@ParameterizedTest
	@MethodSource("validText")
	void acceptsNonBlankIdentifiersUpToColumnLimit(String value) {
		var request = new CreateActivationRequest(value, value, value);

		assertThat(validator.validate(request)).isEmpty();
	}

	private static Stream<String> invalidText() {
		return Stream.of(null, "", "   ", "\t\n", "a".repeat(101));
	}

	private static Stream<String> validText() {
		return Stream.of("a", "EXT-12345", "a".repeat(100), " " + "a".repeat(98) + " ");
	}
}
