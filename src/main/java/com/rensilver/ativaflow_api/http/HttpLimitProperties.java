package com.rensilver.ativaflow_api.http;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.http")
@Validated
public record HttpLimitProperties(
		@DefaultValue("16384") @Min(1) @Max(1048576) int maxBodyBytes,
		@DefaultValue @Valid @NotNull RateLimit rateLimit) {

	public record RateLimit(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("120") @Min(1) @Max(1000000) int requests,
			@DefaultValue("1m") @NotNull @DurationMin(millis = 1) @DurationMax(days = 1) Duration window,
			@DefaultValue("10000") @Min(1) @Max(100000) int maxKeys) {
	}
}
