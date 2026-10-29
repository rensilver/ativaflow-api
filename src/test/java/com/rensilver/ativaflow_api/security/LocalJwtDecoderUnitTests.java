package com.rensilver.ativaflow_api.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalJwtDecoderUnitTests {

	private static final String ISSUER = "https://ativaflow.local";
	private static final KeyPair KEYS = keys();
	@TempDir
	Path directory;
	private OAuth2ResourceServerProperties properties;

	@BeforeEach
	void setUp() throws Exception {
		Path publicKey = directory.resolve("public.pem");
		Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(KEYS.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n");
		properties = new OAuth2ResourceServerProperties();
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(publicKey));
		properties.getJwt().setIssuerUri(ISSUER);
		properties.getJwt().setAudiences(List.of("ativaflow-api"));
	}

	@Test
	void acceptsSignedTokenUsingConfiguredPublicKeyIssuerAndAudience() throws Exception {
		var decoder = new LocalJwtDecoderConfiguration().localJwtDecoder(properties);
		assertThat(decoder.decode(token("valid")).getSubject()).isEqualTo("client-one");
	}

	@Test
	void localProfileBindsEnvironmentAndCreatesDecoderWithoutIssuerDiscovery() throws Exception {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("local");
			context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("jwt-test-systemEnvironment",
					Map.of("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION",
							properties.getJwt().getPublicKeyLocation().getURI().toString(),
							"SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI", ISSUER,
							"SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES", "ativaflow-api")));
			ConfigurationPropertySources.attach(context.getEnvironment());
			context.register(LocalJwtDecoderConfiguration.class);
			context.refresh();

			assertThat(context.getBean(JwtDecoder.class).decode(token("valid")).getSubject()).isEqualTo("client-one");
			String wrongAudienceToken = token("wrong-audience");
			assertThatThrownBy(() -> context.getBean(JwtDecoder.class).decode(wrongAudienceToken))
					.isInstanceOf(JwtException.class);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"wrong-issuer", "wrong-audience", "missing-audience", "expired", "future", "wrong-signature"})
	void rejectsTokensOutsideConfiguredTrust(String variant) throws Exception {
		var decoder = new LocalJwtDecoderConfiguration().localJwtDecoder(properties);
		String token = token(variant);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void audienceValidationIsOptionalWhenNotConfigured() throws Exception {
		properties.getJwt().setAudiences(List.of());
		var decoder = new LocalJwtDecoderConfiguration().localJwtDecoder(properties);
		assertThat(decoder.decode(token("missing-audience")).getSubject()).isEqualTo("client-one");
	}

	@Test
	void refusesLocalDecoderWithoutTrustedIssuer() {
		properties.getJwt().setIssuerUri(null);
		assertThatThrownBy(() -> new LocalJwtDecoderConfiguration().localJwtDecoder(properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("issuer-uri");
	}

	@Test
	void refusesMissingPublicKeyRatherThanGeneratingOne() {
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(directory.resolve("missing.pem")));
		assertThatThrownBy(() -> new LocalJwtDecoderConfiguration().localJwtDecoder(properties))
				.isInstanceOf(java.io.IOException.class);
	}

	private static String token(String variant) throws Exception {
		var now = Instant.now();
		var claims = new JWTClaimsSet.Builder().subject("client-one")
				.issuer(variant.equals("wrong-issuer") ? "https://untrusted.example.test" : ISSUER)
				.expirationTime(Date.from(now.plusSeconds(variant.equals("expired") ? -120 : 600)))
				.notBeforeTime(Date.from(now.plusSeconds(variant.equals("future") ? 120 : -120)));
		if (!variant.equals("missing-audience")) {
			claims.audience(variant.equals("wrong-audience") ? "other-api" : "ativaflow-api");
		}
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) (variant.equals("wrong-signature") ? keys() : KEYS).getPrivate()));
		return jwt.serialize();
	}

	private static KeyPair keys() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
