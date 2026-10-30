package com.rensilver.ativaflow_api.service;

import java.time.Duration;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestLeaseRecoveryUnitTests {

	@Mock
	private ActivationRequestRepository repository;

	@ParameterizedTest
	@CsvSource({"1, 1, 0", "2, 10, 4", "3, 20, 20"})
	void recoveryPreservesTheConfiguredTotalAttemptLimitAndBatchSize(int maxAttempts, int batchSize, int released) {
		when(repository.releaseExpiredLeases(batchSize, maxAttempts)).thenReturn(released);

		assertThat(service(maxAttempts).recoverExpiredLeases(batchSize)).isEqualTo(released);
		// A recuperação não prepara HTTP nem grava um resultado que removeria a reconciliação.
		verifyNoMoreInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, Integer.MIN_VALUE})
	void rejectsNonPositiveRecoveryBatchesBeforeAccessingPersistence(int batchSize) {
		assertThatThrownBy(() -> service(3).recoverExpiredLeases(batchSize)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	private ActivationRequestLeaseRecoveryService service(int maxAttempts) {
		return new ActivationRequestLeaseRecoveryService(repository,
				new ActivationRetryProperties(maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(1), 0));
	}
}
