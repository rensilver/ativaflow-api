package com.rensilver.ativaflow_api.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Destino externo; credenciais, query e fragmento não fazem parte da URL base. */
@ConfigurationProperties("app.activation-provider")
public record ActivationProviderProperties(URI baseUrl) {

	public ActivationProviderProperties {
		if (baseUrl == null || !("http".equalsIgnoreCase(baseUrl.getScheme())
				|| "https".equalsIgnoreCase(baseUrl.getScheme())) || baseUrl.getHost() == null
				|| baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
			throw new IllegalArgumentException("Provider base URL must be an absolute HTTP(S) URL without credentials, query or fragment");
		}
		baseUrl = URI.create(baseUrl.toString().replaceFirst("/+$", ""));
	}
}
