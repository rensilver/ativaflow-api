package com.rensilver.ativaflow_api.http;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Janela fixa por identidade; relógio monotônico, admissão atômica e memória limitada. */
final class InMemoryRateLimiter {

	private final Map<String, Window> windows = new HashMap<>();
	private final HttpLimitProperties.RateLimit properties;
	private final LongSupplier ticker;
	private final long windowNanos;

	InMemoryRateLimiter(HttpLimitProperties.RateLimit properties, LongSupplier ticker) {
		this.properties = properties;
		this.ticker = ticker;
		this.windowNanos = properties.window().toNanos();
	}

	/** Retorna zero na admissão, ou segundos positivos até a próxima oportunidade. */
	synchronized long retryAfterSeconds(String key) {
		long now = ticker.getAsLong();
		Window window = windows.get(key);
		if (window != null && now - window.startedAt() >= windowNanos) {
			windows.remove(key);
			window = null;
		}
		if (window == null) {
			if (windows.size() >= properties.maxKeys()) {
				windows.values().removeIf(value -> now - value.startedAt() >= windowNanos);
				if (windows.size() >= properties.maxKeys()) {
					long minimumWait = windows.values().stream()
							.mapToLong(value -> windowNanos - (now - value.startedAt())).min().orElse(windowNanos);
					return secondsCeiling(minimumWait);
				}
			}
			windows.put(key, new Window(now, 1));
			return 0;
		}
		if (window.count() >= properties.requests()) {
			return secondsCeiling(windowNanos - (now - window.startedAt()));
		}
		windows.put(key, new Window(window.startedAt(), window.count() + 1));
		return 0;
	}

	private long secondsCeiling(long nanos) {
		return 1 + (nanos - 1) / 1_000_000_000L;
	}

	private record Window(long startedAt, int count) { }
}
