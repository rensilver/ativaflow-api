package com.rensilver.ativaflow_api.observability;

import java.util.List;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActivationRequestStateMetricsTests {

	@Test
	void readsCachedSnapshotWithoutDatabaseWorkAndResetsStatesAbsentFromTheNextRefresh() {
		var repository = mock(ActivationRequestRepository.class);
		var registry = new SimpleMeterRegistry();
		try {
			var metrics = new ActivationRequestStateMetrics(repository, registry);
			assertThat(count(registry, "PENDING")).isNaN();
			when(repository.countByState()).thenReturn(List.of(new Count(ActivationStatus.PENDING, 7))).thenReturn(List.of());
			metrics.refresh();
			clearInvocations(repository);
			for (int scrape = 0; scrape < 10; scrape++) {
				assertThat(count(registry, "PENDING")).isEqualTo(7);
				assertThat(count(registry, "FAILED")).isZero();
			}
			verifyNoInteractions(repository);
			metrics.refresh();
			assertThat(count(registry, "PENDING")).isZero();
		}
		finally {
			registry.close();
		}
	}

	@Test
	void failedRefreshRetainsTheLastSnapshotAndTimestampAndCanRecover() {
		var repository = mock(ActivationRequestRepository.class);
		var registry = new SimpleMeterRegistry();
		try {
			var metrics = new ActivationRequestStateMetrics(repository, registry);
			when(repository.countByState()).thenThrow(new DataAccessResourceFailureException("private details"))
					.thenReturn(List.of(new Count(ActivationStatus.COMPLETED, 3)))
					.thenThrow(new DataAccessResourceFailureException("private details"));
			metrics.refresh();
			assertThat(count(registry, "COMPLETED")).isNaN();
			assertThat(registry.get("activation.requests.snapshot.timestamp").gauge().value()).isZero();
			metrics.refresh();
			double timestamp = registry.get("activation.requests.snapshot.timestamp").gauge().value();
			assertThat(timestamp).isPositive();
			metrics.refresh();
			assertThat(count(registry, "COMPLETED")).isEqualTo(3);
			assertThat(registry.get("activation.requests.snapshot.timestamp").gauge().value()).isEqualTo(timestamp);
			assertThat(registry.get("activation.requests.snapshot.failures").counter().count()).isEqualTo(2);
		}
		finally {
			registry.close();
		}
	}

	@Test
	void failedTransactionIsCountedWhileKeepingTheLastSuccessfulSnapshot() {
		var repository = mock(ActivationRequestRepository.class);
		var registry = new SimpleMeterRegistry();
		try {
			var metrics = new ActivationRequestStateMetrics(repository, registry);
			when(repository.countByState()).thenReturn(List.of(new Count(ActivationStatus.PENDING, 4)))
					.thenThrow(new CannotCreateTransactionException("connection unavailable"));
			metrics.refresh();
			double timestamp = registry.get("activation.requests.snapshot.timestamp").gauge().value();
			assertThatCode(metrics::refresh).doesNotThrowAnyException();
			assertThat(count(registry, "PENDING")).isEqualTo(4);
			assertThat(registry.get("activation.requests.snapshot.timestamp").gauge().value()).isEqualTo(timestamp);
			assertThat(registry.get("activation.requests.snapshot.failures").counter().count()).isEqualTo(1);
		}
		finally {
			registry.close();
		}
	}

	private double count(SimpleMeterRegistry registry, String state) {
		return registry.get("activation.requests").tag("state", state).gauge().value();
	}

	private record Count(ActivationStatus state, long total) implements ActivationRequestRepository.StateCount {
		@Override public ActivationStatus getState() { return state; }
		@Override public long getTotal() { return total; }
	}
}
