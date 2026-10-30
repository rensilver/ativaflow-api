package com.rensilver.ativaflow_api.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class ProviderOutcomeTests {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n", "\u2003"})
	void rejectsMissingErrorCodesInEveryFailureVariant(String errorCode) {
		assertAll(
				() -> assertThatThrownBy(() -> new ProviderOutcome.PermanentFailure(errorCode))
						.isInstanceOf(IllegalArgumentException.class),
				() -> assertThatThrownBy(() -> new ProviderOutcome.TransientFailure(errorCode, Optional.empty()))
						.isInstanceOf(IllegalArgumentException.class),
				() -> assertThatThrownBy(() -> new ProviderOutcome.Indeterminate(errorCode))
						.isInstanceOf(IllegalArgumentException.class));
	}

	@Test
	void rejectsErrorCodesExceedingThePersistedLimitInEveryFailureVariant() {
		String errorCode = "E".repeat(101);
		assertAll(
				() -> assertThatThrownBy(() -> new ProviderOutcome.PermanentFailure(errorCode))
						.isInstanceOf(IllegalArgumentException.class),
				() -> assertThatThrownBy(() -> new ProviderOutcome.TransientFailure(errorCode, Optional.empty()))
						.isInstanceOf(IllegalArgumentException.class),
				() -> assertThatThrownBy(() -> new ProviderOutcome.Indeterminate(errorCode))
						.isInstanceOf(IllegalArgumentException.class));
	}

	@Test
	void rejectsNullRetryAfterInsteadOfTreatingItAsAbsent() {
		assertThatThrownBy(() -> new ProviderOutcome.TransientFailure("PROVIDER_BUSY", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@MethodSource("validErrorCodes")
	void preservesValidErrorCodesIncludingWhitespaceAndTheLengthLimit(String errorCode) {
		assertThat(new ProviderOutcome.PermanentFailure(errorCode).errorCode()).isEqualTo(errorCode);
		assertThat(new ProviderOutcome.TransientFailure(errorCode, Optional.empty()).errorCode()).isEqualTo(errorCode);
		assertThat(new ProviderOutcome.Indeterminate(errorCode).errorCode()).isEqualTo(errorCode);
	}

	@ParameterizedTest
	@MethodSource("validRetryAfterValues")
	void acceptsExplicitAbsenceAndPreservesRetryAfterPrecision(Optional<Instant> retryAfter) {
		assertThat(new ProviderOutcome.TransientFailure("PROVIDER_BUSY", retryAfter).retryAfter())
				.isEqualTo(retryAfter);
	}

	private static Stream<String> validErrorCodes() {
		return Stream.of("E", " PROVIDER_BUSY ", "E".repeat(100));
	}

	private static Stream<Optional<Instant>> validRetryAfterValues() {
		return Stream.of(Optional.empty(), Optional.of(Instant.parse("2026-10-29T12:02:00.123456789Z")));
	}
}
