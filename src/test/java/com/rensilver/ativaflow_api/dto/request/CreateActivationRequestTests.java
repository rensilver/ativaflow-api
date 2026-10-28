package com.rensilver.ativaflow_api.dto.request;

import java.util.stream.Stream;

import com.rensilver.ativaflow_api.model.ActivationRequest;
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
	void acceptsNonBlankIdentifiersUpToLimitWithoutNormalizingThem(String value) {
		var request = new CreateActivationRequest(value, value, value);

		assertThat(validator.validate(request)).isEmpty();
		var entity = new ActivationRequest(request.externalId(), request.customerId(), request.productCode(), "consumer-a");
		assertThat(entity.getExternalId()).isEqualTo(value);
		assertThat(entity.getCustomerId()).isEqualTo(value);
		assertThat(entity.getProductCode()).isEqualTo(value);
	}

	private static Stream<String> invalidText() {
		return Stream.of(null, "", "   ", "\t\n", "\u2003", "\u2003\t\n", "\u3000",
				"a".repeat(101), "a".repeat(99) + "🚀");
	}

	private static Stream<String> validText() {
		return Stream.of("a", "EXT-12345", "a".repeat(99), "a".repeat(100),
				" " + "a".repeat(98) + " ", "cliente / ação:AbC-42", "a".repeat(98) + "🚀",
				"\u2003id\u3000");
	}
}
