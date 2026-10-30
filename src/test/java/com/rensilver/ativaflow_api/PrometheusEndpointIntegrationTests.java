package com.rensilver.ativaflow_api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, Prometheus registry, PostgreSQL and the application's local JWT decoder. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"DB_PASSWORD=monitoring-test",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://ativaflow.local",
		"spring.security.oauth2.resourceserver.jwt.audiences=ativaflow-api"
})
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "test"})
class PrometheusEndpointIntegrationTests {

	private static final String PATH = "/actuator/prometheus";
	private static final KeyPair KEYS = keys();
	private static final Path PUBLIC_KEY = publicKeyFile();
	private final HttpClient client = HttpClient.newHttpClient();
	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void publicKey(DynamicPropertyRegistry properties) {
		properties.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
				() -> PUBLIC_KEY.toUri().toString());
	}

	@AfterEach
	void closeClient() {
		client.close();
	}

	@AfterAll
	static void removePublicKey() throws Exception {
		Files.deleteIfExists(PUBLIC_KEY);
	}

	@Test
	void monitoringScopeCanScrapePrometheusWithoutCreatingSession() throws Exception {
		var response = get(PATH, token("monitoring/read", KEYS, Instant.now().plusSeconds(300)));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
		assertThat(response.body()).contains("# HELP ", "# TYPE ", "jvm_memory_used_bytes");
		assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
	}

	@Test
	void anonymousScrapeRequiresBearerAuthentication() throws Exception {
		var response = get(PATH, null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("Bearer");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "requests/read", "requests/write", "requests/read requests/write", "monitoring/write"})
	void apiScopesAndOtherScopesCannotScrape(String scope) throws Exception {
		var response = get(PATH, token(scope, KEYS, Instant.now().plusSeconds(300)));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("insufficient_scope");
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
	}

	@Test
	void monitoringScopeDoesNotBypassSignatureOrExpirationValidation() throws Exception {
		var forged = get(PATH, token("monitoring/read", keys(), Instant.now().plusSeconds(300)));
		var expired = get(PATH, token("monitoring/read", KEYS, Instant.now().minusSeconds(300)));

		for (var response : java.util.List.of(forged, expired)) {
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("invalid_token");
		}
	}

	@Test
	void monitoringCredentialCannotReadOrCreateActivationRequests() throws Exception {
		String token = token("monitoring/read", KEYS, Instant.now().plusSeconds(300));
		assertThat(get("/api/v1/requests", token).statusCode()).isEqualTo(403);
		var response = client.send(HttpRequest.newBuilder(uri("/api/v1/requests"))
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(403);
	}

	@ParameterizedTest
	@ValueSource(strings = {"env", "configprops", "heapdump", "metrics", "info"})
	void monitoringCredentialDoesNotExposeOtherActuatorEndpoints(String endpoint) throws Exception {
		assertThat(get("/actuator/" + endpoint,
				token("monitoring/read", KEYS, Instant.now().plusSeconds(300))).statusCode()).isEqualTo(404);
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).header("Accept", "text/plain");
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private String token(String scope, KeyPair keys, Instant expiration) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer("https://ativaflow.local").subject("prometheus")
				.audience("ativaflow-api").claim("token_use", "access").claim("scope", scope)
				.expirationTime(Date.from(expiration)).build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
		return jwt.serialize();
	}

	private static KeyPair keys() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Path publicKeyFile() {
		try {
			Path file = Files.createTempFile("ativaflow-monitoring-public-", ".pem");
			Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n"
					+ Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(KEYS.getPublic().getEncoded())
					+ "\n-----END PUBLIC KEY-----\n");
			return file;
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
