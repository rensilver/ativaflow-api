package com.rensilver.ativaflow_api.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Contrato do emissor local de demonstração, com chave pública PEM ou JWKS. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class LocalJwtDecoderConfiguration {

	@Bean
	@ConditionalOnMissingBean(JwtDecoder.class)
	JwtDecoder localJwtDecoder(OAuth2ResourceServerProperties properties) throws IOException {
		var jwt = properties.getJwt();
		Assert.hasText(jwt.getIssuerUri(), "spring.security.oauth2.resourceserver.jwt.issuer-uri must be configured");
		boolean publicKeyConfigured = jwt.getPublicKeyLocation() != null;
		boolean jwksConfigured = StringUtils.hasText(jwt.getJwkSetUri());
		Assert.isTrue(publicKeyConfigured != jwksConfigured,
				"Configure exactly one of public-key-location or jwk-set-uri for local JWT validation");
		var audiences = jwt.getAudiences();
		Assert.isTrue(audiences != null && !audiences.isEmpty() && audiences.stream().allMatch(StringUtils::hasText),
				"spring.security.oauth2.resourceserver.jwt.audiences must contain a non-blank audience");

		NimbusJwtDecoder decoder;
		if (publicKeyConfigured) {
			try (var input = jwt.getPublicKeyLocation().getInputStream()) {
				decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input)).build();
			}
		}
		else {
			decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
		}
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
				new JwtIssuerValidator(jwt.getIssuerUri()),
				new JwtClaimValidator<Instant>("exp", Objects::nonNull),
				new JwtClaimValidator<Object>("token_use", "access"::equals),
				new JwtClaimValidator<List<String>>("aud",
						actual -> actual != null && actual.stream().anyMatch(audiences::contains))));
		return decoder;
	}
}
