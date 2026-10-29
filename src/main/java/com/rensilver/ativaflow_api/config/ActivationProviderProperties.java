package com.rensilver.ativaflow_api.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Destino externo; credenciais, query e fragmento não fazem parte da URL base. */
@ConfigurationProperties("app.activation-provider")
@Validated
public record ActivationProviderProperties(URI baseUrl,
		@DefaultValue("2s") @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE)
		Duration connectTimeout,
		@DefaultValue("5s") @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE)
		Duration readTimeout) {

	public ActivationProviderProperties {
		if (baseUrl == null || !("http".equalsIgnoreCase(baseUrl.getScheme())
				|| "https".equalsIgnoreCase(baseUrl.getScheme())) || baseUrl.getHost() == null
				|| baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
			throw new IllegalArgumentException("Provider base URL must be an absolute HTTP(S) URL without credentials, query or fragment");
		}
		baseUrl = URI.create(baseUrl.toString().replaceFirst("/+$", ""));
	}
}
