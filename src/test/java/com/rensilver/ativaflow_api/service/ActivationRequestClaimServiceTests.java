package com.rensilver.ativaflow_api.service;

import java.time.Duration;
import java.util.Optional;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestClaimServiceTests {

	@Mock
	private ActivationRequestRepository repository;

	@Test
	void doesNotGrantOwnershipWhenConditionalUpdateAffectsZeroRows() {
		var candidate = candidate();
		when(repository.findNextClaimCandidate(3)).thenReturn(Optional.of(candidate));
		when(repository.tryClaim(candidate.getId(), 7, "worker-a", 30000, 3)).thenReturn(0);
		var service = new ActivationRequestClaimService(repository, Duration.ofSeconds(30), retryProperties());

		assertThat(service.claimNext("worker-a")).isEmpty();
	}

	@Test
	void rejectsUnexpectedAffectedRowCountInsteadOfGrantingOwnership() {
		var candidate = candidate();
		when(repository.findNextClaimCandidate(3)).thenReturn(Optional.of(candidate));
		when(repository.tryClaim(candidate.getId(), 7, "worker-a", 30000, 3)).thenReturn(2);
		var service = new ActivationRequestClaimService(repository, Duration.ofSeconds(30), retryProperties());

		assertThatThrownBy(() -> service.claimNext("worker-a")).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\u2003", "\u3000"})
	void rejectsBlankWorkerIdBeforeAccessingDatabase(String workerId) {
		var service = new ActivationRequestClaimService(repository, Duration.ofSeconds(30), retryProperties());

		assertThatThrownBy(() -> service.claimNext(workerId)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsWorkerIdAboveColumnLimit() {
		var service = new ActivationRequestClaimService(repository, Duration.ofSeconds(30), retryProperties());

		assertThatThrownBy(() -> service.claimNext("w".repeat(101))).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"PT0S", "PT-1S", "PT0.000001S"})
	void rejectsLeaseWithoutAtLeastOnePositiveMillisecond(String duration) {
		assertThatThrownBy(() -> new ActivationRequestClaimService(repository, Duration.parse(duration), retryProperties()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private ActivationRequest candidate() {
		var candidate = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		ReflectionTestUtils.setField(candidate, "version", 7L);
		return candidate;
	}

	private ActivationRetryProperties retryProperties() {
		return new ActivationRetryProperties(3, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.25);
	}
}
