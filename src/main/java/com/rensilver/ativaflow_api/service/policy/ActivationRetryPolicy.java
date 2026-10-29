package com.rensilver.ativaflow_api.service.policy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ActivationRetryPolicy {

	private final long initialDelayMillis;
	private final long maxDelayMillis;
	private final double jitterRatio;

	public ActivationRetryPolicy(@Value("${app.worker.retry.initial-delay:1s}") Duration initialDelay,
			@Value("${app.worker.retry.max-delay:1m}") Duration maxDelay,
			@Value("${app.worker.retry.jitter-ratio:0.25}") double jitterRatio) {
		this.initialDelayMillis = initialDelay.toMillis();
		this.maxDelayMillis = maxDelay.toMillis();
		if (initialDelayMillis < 1 || maxDelayMillis < initialDelayMillis
				|| !Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
			throw new IllegalArgumentException("Retry delays must be positive, maximum >= initial, and jitter ratio between 0 and 1");
		}
		this.jitterRatio = jitterRatio;
		// Rejeita configuração que não permita representar o atraso total em milissegundos.
		Math.addExact(maxDelayMillis, (long) (maxDelayMillis * jitterRatio));
	}

	/** A tentativa inicial é 1; o limite se aplica à base, antes do jitter adicional. */
	public long delayMillis(int attemptCount) {
		if (attemptCount < 1 || attemptCount > 3) {
			throw new IllegalArgumentException("Attempt count must be between 1 and 3");
		}
		long multiplier = 1L << (attemptCount - 1);
		long base = initialDelayMillis > maxDelayMillis / multiplier
				? maxDelayMillis : initialDelayMillis * multiplier;
		long jitter = (long) (base * jitterRatio * ThreadLocalRandom.current().nextDouble());
		return base + jitter;
	}
}
