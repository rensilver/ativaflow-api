package com.rensilver.ativaflow_api.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.Assert;

/** Chave pública RSA local, sem emissão de tokens ou dependência de um IdP em desenvolvimento. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class LocalJwtDecoderConfiguration {

	@Bean
	@ConditionalOnMissingBean(JwtDecoder.class)
	@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "public-key-location")
	JwtDecoder localJwtDecoder(OAuth2ResourceServerProperties properties) throws IOException {
		var jwt = properties.getJwt();
		Assert.hasText(jwt.getIssuerUri(), "spring.security.oauth2.resourceserver.jwt.issuer-uri must be configured");
		try (var input = jwt.getPublicKeyLocation().getInputStream()) {
			var decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input)).build();
			var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
			validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
			var audiences = jwt.getAudiences();
			if (audiences != null && !audiences.isEmpty()) {
				validators.add(new JwtClaimValidator<List<String>>("aud",
						actual -> actual != null && actual.stream().anyMatch(audiences::contains)));
			}
			decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
			return decoder;
		}
	}
}
