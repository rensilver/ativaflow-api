package com.rensilver.ativaflow_api.service.policy;

import java.util.concurrent.ThreadLocalRandom;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ActivationRetryProperties.class)
public class ActivationRetryPolicy {

	private final long initialDelayMillis;
	private final long maxDelayMillis;
	private final double jitterRatio;
	private final int maxAttempts;

	public ActivationRetryPolicy(ActivationRetryProperties properties) {
		this.initialDelayMillis = properties.initialDelay().toMillis();
		this.maxDelayMillis = properties.maxDelay().toMillis();
		this.jitterRatio = properties.jitterRatio();
		this.maxAttempts = properties.maxAttempts();
	}

	public int maxAttempts() {
		return maxAttempts;
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
