package com.rensilver.ativaflow_api.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Tokens assinados de verdade; o transporte JWKS é substituído em memória. */
class LocalAccessTokenValidationUnitTests {

	private static final String ISSUER = "https://ativaflow.local";
	private static final String JWKS = "https://keys.example.test/jwks.json";
	private static final KeyPair KEYS = keys();
	private static final KeyPair UNTRUSTED_KEYS = keys();
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
		properties.getJwt().setIssuerUri(ISSUER);
		properties.getJwt().setAudiences(List.of("ativaflow-api"));
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(publicKey));
	}

	@ParameterizedTest
	@ValueSource(strings = {"PEM", "JWKS"})
	void acceptsAccessTokenWithoutOptionalNotBefore(String source) throws Exception {
		var jwt = decoder(source).decode(token("valid"));
		assertThat(jwt.getSubject()).isEqualTo("demo-consumer");
		assertThat(jwt.getClaimAsString("token_use")).isEqualTo("access");
		assertThat(jwt.getNotBefore()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"PEM", "JWKS"})
	void acceptsOneMatchingAudienceAmongSeveral(String source) throws Exception {
		assertThat(decoder(source).decode(token("multiple-audiences")).getAudience())
				.containsExactly("another-api", "ativaflow-api");
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "refresh", "missing-purpose", "invalid-purpose-type", "missing-exp",
			"expired", "future-nbf", "wrong-issuer", "missing-issuer", "wrong-audience", "missing-audience",
			"wrong-signature", "wrong-algorithm", "malformed", "unsigned"})
	void rejectsInvalidTokenWithPublicKey(String variant) throws Exception {
		assertRejected("PEM", variant);
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "refresh", "missing-purpose", "invalid-purpose-type", "missing-exp",
			"expired", "future-nbf", "wrong-issuer", "missing-issuer", "wrong-audience", "missing-audience",
			"wrong-signature", "wrong-algorithm", "malformed", "unsigned"})
	void rejectsInvalidTokenWithJwks(String variant) throws Exception {
		assertRejected("JWKS", variant);
	}

	@ParameterizedTest
	@ValueSource(strings = {"PEM", "JWKS"})
	void acceptsNotBeforeInThePast(String source) throws Exception {
		assertThat(decoder(source).decode(token("past-nbf")).getSubject()).isEqualTo("demo-consumer");
	}

	@ParameterizedTest
	@ValueSource(strings = {"empty", "blank", "null"})
	void refusesConfigurationWithoutExpectedAudience(String variant) {
		properties.getJwt().setAudiences(switch (variant) {
			case "empty" -> List.of();
			case "blank" -> List.of(" ");
			default -> null;
		});
		assertThatThrownBy(() -> new LocalJwtDecoderConfiguration().localJwtDecoder(properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audiences");
	}

	@Test
	void refusesConfigurationWithOnlyIssuerAndNoKeySource() {
		properties.getJwt().setPublicKeyLocation(null);
		assertThatThrownBy(() -> new LocalJwtDecoderConfiguration().localJwtDecoder(properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("public-key-location", "jwk-set-uri");
	}

	private void assertRejected(String source, String variant) throws Exception {
		var decoder = decoder(source);
		String serialized = token(variant);
		assertThatThrownBy(() -> decoder.decode(serialized)).isInstanceOf(JwtException.class);
	}

	private JwtDecoder decoder(String source) throws Exception {
		if (source.equals("PEM")) {
			return new LocalJwtDecoderConfiguration().localJwtDecoder(properties);
		}
		properties.getJwt().setPublicKeyLocation(null);
		properties.getJwt().setJwkSetUri(JWKS);
		var rest = new RestTemplate();
		var server = MockRestServiceServer.bindTo(rest).build();
		String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("local").build())
				.toString();
		server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks, MediaType.APPLICATION_JSON));
		// Mantém o builder e o decoder reais; troca apenas o transporte HTTP.
		var builder = NimbusJwtDecoder.withJwkSetUri(JWKS).restOperations(rest);
		try (var factory = mockStatic(NimbusJwtDecoder.class);
				var context = new AnnotationConfigApplicationContext()) {
			factory.when(() -> NimbusJwtDecoder.withJwkSetUri(JWKS)).thenReturn(builder);
			context.getEnvironment().setActiveProfiles("local");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-jwks", Map.of(
					"spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER,
					"spring.security.oauth2.resourceserver.jwt.audiences", "ativaflow-api",
					"spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS)));
			context.register(LocalJwtDecoderConfiguration.class);
			context.refresh();
			return context.getBean(JwtDecoder.class);
		}
	}

	private String token(String variant) throws Exception {
		if (variant.equals("malformed")) {
			return "invalid-token";
		}
		var now = Instant.now();
		var claims = new JWTClaimsSet.Builder().subject("demo-consumer")
				.issuer(variant.equals("wrong-issuer") ? "https://untrusted.example.test" : ISSUER);
		if (variant.equals("missing-issuer")) {
			claims.issuer(null);
		}
		if (!variant.equals("missing-exp")) {
			claims.expirationTime(Date.from(now.plusSeconds(variant.equals("expired") ? -120 : 600)));
		}
		if (variant.equals("future-nbf") || variant.equals("past-nbf")) {
			claims.notBeforeTime(Date.from(now.plusSeconds(variant.equals("future-nbf") ? 120 : -120)));
		}
		if (!variant.equals("missing-audience")) {
			claims.audience(switch (variant) {
				case "wrong-audience" -> List.of("another-api");
				case "multiple-audiences" -> List.of("another-api", "ativaflow-api");
				default -> List.of("ativaflow-api");
			});
		}
		if (!variant.equals("missing-purpose")) {
			claims.claim("token_use", switch (variant) {
				case "id", "refresh" -> variant;
				case "invalid-purpose-type" -> List.of("access");
				default -> "access";
			});
		}
		if (variant.equals("unsigned")) {
			return new com.nimbusds.jwt.PlainJWT(claims.build()).serialize();
		}
		var algorithm = variant.equals("wrong-algorithm") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256;
		var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID("local").build(),
				claims.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) (variant.equals("wrong-signature") ? UNTRUSTED_KEYS : KEYS).getPrivate()));
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
