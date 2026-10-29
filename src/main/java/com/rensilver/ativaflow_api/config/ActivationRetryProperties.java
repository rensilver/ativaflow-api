package com.rensilver.ativaflow_api.config;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Tentativas totais, incluindo a inicial; maxDelay limita a base antes do jitter adicional. */
@ConfigurationProperties("app.worker.retry")
@Validated
public record ActivationRetryProperties(
		@DefaultValue("3") @Min(1) @Max(3) int maxAttempts,
		@DefaultValue("1s") @NotNull @DurationMin(millis = 1) Duration initialDelay,
		@DefaultValue("1m") @NotNull @DurationMin(millis = 1) Duration maxDelay,
		@DefaultValue("0.25") @DecimalMin("0") @DecimalMax("1") double jitterRatio) {

	public ActivationRetryProperties {
		if (initialDelay != null && maxDelay != null) {
			if (maxDelay.compareTo(initialDelay) < 0) {
				throw new IllegalArgumentException("Retry maximum delay must be greater than or equal to initial delay");
			}
			// Rejeita overflow tanto na conversão quanto na soma do atraso máximo com jitter.
			long maximumMillis = maxDelay.toMillis();
			Math.addExact(maximumMillis, (long) (maximumMillis * jitterRatio));
		}
	}
}
