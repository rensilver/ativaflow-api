package com.rensilver.ativaflow_api.observability;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Scrapes read an immutable snapshot; failed refreshes retain its values and timestamp. */
@Component
public class ActivationRequestStateMetrics {

	private final ActivationRequestRepository repository;
	private final Counter failures;
	private volatile Snapshot snapshot = new Snapshot(Map.of(), 0);

	public ActivationRequestStateMetrics(ActivationRequestRepository repository, MeterRegistry registry) {
		this.repository = repository;
		this.failures = registry.counter("activation.requests.snapshot.failures");
		for (var state : ActivationStatus.values()) {
			Gauge.builder("activation.requests", this, metrics -> metrics.snapshot.counts()
					.getOrDefault(state, Double.NaN))
					.tag("state", state.name()).description("Persisted requests by state, cached")
					.register(registry);
		}
		Gauge.builder("activation.requests.snapshot.timestamp", this, metrics -> metrics.snapshot.timestamp())
				.description("Unix timestamp in seconds of the last successful state count refresh")
				.register(registry);
	}

	@Scheduled(fixedDelayString = "${app.metrics.state-refresh-interval:30s}", scheduler = "activationMetricsScheduler")
	public void refresh() {
		try {
			var counts = new EnumMap<ActivationStatus, Double>(ActivationStatus.class);
			for (var state : ActivationStatus.values()) {
				counts.put(state, 0.0);
			}
			for (var count : repository.countByState()) {
				counts.put(count.getState(), (double) count.getTotal());
			}
			snapshot = new Snapshot(Map.copyOf(counts), Instant.now().getEpochSecond());
		}
		catch (DataAccessException | TransactionException exception) {
			failures.increment();
		}
	}

	private record Snapshot(Map<ActivationStatus, Double> counts, long timestamp) {
	}
}
