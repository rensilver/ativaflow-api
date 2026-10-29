package com.rensilver.ativaflow_api.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalJwtKeySourceUnitTests {

	@Test
	void rejectsAmbiguousPublicKeyAndJwksBeforeLoadingEitherSource() {
		var properties = new OAuth2ResourceServerProperties();
		properties.getJwt().setIssuerUri("https://ativaflow.local");
		properties.getJwt().setPublicKeyLocation(new FileSystemResource("/missing/public.pem"));
		properties.getJwt().setJwkSetUri("http://127.0.0.1:8082/jwks.json");

		assertThatThrownBy(() -> new LocalJwtDecoderConfiguration().localJwtDecoder(properties))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("public-key-location", "jwk-set-uri");
	}
}
