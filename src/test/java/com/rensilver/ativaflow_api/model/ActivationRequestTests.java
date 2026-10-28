package com.rensilver.ativaflow_api.model;

import java.lang.reflect.Modifier;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.domain.exception.InvalidActivationStatusTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTests {

	@Test
	void createsPendingRequestWithApplicationIdAndSafeProcessingDefaults() {
		ActivationRequest request = newRequest();

		assertThat(request.getId()).isNotNull();
		assertThat(newRequest().getId()).isNotEqualTo(request.getId());
		assertThat(request.getExternalId()).isEqualTo("EXT-1");
		assertThat(request.getCustomerId()).isEqualTo("CUSTOMER-1");
		assertThat(request.getProductCode()).isEqualTo("PRODUCT-1");
		assertThat(request.getConsumerId()).isEqualTo("consumer-a");
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getVersion()).isNull();
		assertThat(request.getAttemptCount()).isZero();
		assertThat(request.getNextAttemptAt()).isNull();
		assertThat(request.getLeaseUntil()).isNull();
		assertThat(request.getClaimedBy()).isNull();
		assertThat(request.getLastErrorCode()).isNull();
		assertThat(request.isReconciliationRequired()).isFalse();
		assertThat(request.getCreatedAt()).isNull();
		assertThat(request.getUpdatedAt()).isNull();
	}

	@Test
	void providesProtectedNoArgConstructorAndNoPublicSetters() throws Exception {
		assertThat(Modifier.isProtected(ActivationRequest.class.getDeclaredConstructor().getModifiers())).isTrue();
		assertThat(ActivationRequest.class.getMethods())
				.noneMatch(method -> method.getName().startsWith("set"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void rejectsMissingOrBlankCreationFields(String invalid) {
		assertThatThrownBy(() -> new ActivationRequest(invalid, "CUSTOMER-1", "PRODUCT-1", "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", invalid, "PRODUCT-1", "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", "CUSTOMER-1", invalid, "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1", invalid))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsCreationFieldsLongerThanDatabaseColumns() {
		String oversized = "a".repeat(101);
		assertThatThrownBy(() -> new ActivationRequest(oversized, "CUSTOMER-1", "PRODUCT-1", "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", oversized, "PRODUCT-1", "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", "CUSTOMER-1", oversized, "consumer-a"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActivationRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1", oversized))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsColumnLimitWithoutNormalizingIdentifiers() {
		String value = " " + "a".repeat(98) + " ";
		ActivationRequest request = new ActivationRequest(value, value, value, value);

		assertThat(request.getExternalId()).isEqualTo(value);
		assertThat(request.getCustomerId()).isEqualTo(value);
		assertThat(request.getProductCode()).isEqualTo(value);
		assertThat(request.getConsumerId()).isEqualTo(value);
	}

	@Test
	void completesOnlyAfterStartingProcessing() {
		ActivationRequest request = newRequest();
		assertThatThrownBy(request::complete).isInstanceOf(InvalidActivationStatusTransitionException.class);
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);

		request.startProcessing();
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidActivationStatusTransitionException.class);

		request.complete();
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
	}

	@Test
	void recordsCategorizedFailureOnlyAfterStartingProcessing() {
		ActivationRequest request = newRequest();
		assertThatThrownBy(() -> request.fail("PROVIDER_REJECTED"))
				.isInstanceOf(InvalidActivationStatusTransitionException.class);
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getLastErrorCode()).isNull();

		request.startProcessing();
		request.fail("PROVIDER_REJECTED");

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(request.getLastErrorCode()).isEqualTo("PROVIDER_REJECTED");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void rejectsMissingFailureCodeWithoutChangingState(String invalid) {
		ActivationRequest request = newRequest();
		request.startProcessing();

		assertThatThrownBy(() -> request.fail(invalid)).isInstanceOf(IllegalArgumentException.class);
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(request.getLastErrorCode()).isNull();
	}

	@Test
	void rejectsFailureCodeLongerThanDatabaseColumn() {
		ActivationRequest request = newRequest();
		request.startProcessing();

		assertThatThrownBy(() -> request.fail("a".repeat(101))).isInstanceOf(IllegalArgumentException.class);
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(request.getLastErrorCode()).isNull();
	}

	@ParameterizedTest
	@EnumSource(value = ActivationStatus.class, names = {"COMPLETED", "FAILED"})
	void terminalStatesRejectAllFurtherTransitions(ActivationStatus terminalStatus) {
		ActivationRequest request = newRequest();
		request.startProcessing();
		if (terminalStatus == ActivationStatus.COMPLETED) {
			request.complete();
		} else {
			request.fail("PROVIDER_REJECTED");
		}
		String previousErrorCode = request.getLastErrorCode();

		assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidActivationStatusTransitionException.class);
		assertThatThrownBy(request::complete).isInstanceOf(InvalidActivationStatusTransitionException.class);
		assertThatThrownBy(() -> request.fail("OTHER_ERROR"))
				.isInstanceOf(InvalidActivationStatusTransitionException.class);
		assertThat(request.getStatus()).isEqualTo(terminalStatus);
		assertThat(request.getLastErrorCode()).isEqualTo(previousErrorCode);
	}

	private ActivationRequest newRequest() {
		return new ActivationRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
	}
}
