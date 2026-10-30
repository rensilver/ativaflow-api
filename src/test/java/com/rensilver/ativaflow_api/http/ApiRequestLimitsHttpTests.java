package com.rensilver.ativaflow_api.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rensilver.ativaflow_api.exception.ApiExceptionHandler;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.security.SecurityConfiguration;
import com.rensilver.ativaflow_api.security.SecurityProblemHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP real no Tomcat: inclui framing chunked e a cadeia de segurança da aplicação. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = ApiRequestLimitsHttpTests.TestApplication.class,
		properties = {"app.http.max-body-bytes=256", "app.http.rate-limit.requests=2",
				"app.http.rate-limit.window=1m", "app.http.rate-limit.enabled=true"})
class ApiRequestLimitsHttpTests {

	private static final String PATH = "/api/v1/requests";
	private static final KeyPair KEYS = keys();
	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	@Autowired ObjectMapper mapper;
	@Autowired ProbeController controller;
	@LocalServerPort int port;

	@AfterEach
	void closeClient() { client.close(); }

	@ParameterizedTest
	@ValueSource(ints = {255, 256})
	void acceptsBodyAtOrBelowByteLimit(int size) throws Exception {
		assertThat(post(body(size), false, token()).statusCode()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rejectsOversizedBodyWithFixedLengthOrChunkedFraming(boolean chunked) throws Exception {
		int before = controller.calls.get();
		var response = post(body(257), chunked, token());
		assertProblem(response, 413, "Request body is too large");
		assertThat(controller.calls.get()).isEqualTo(before);
	}

	@Test
	void countsUtf8BytesAndChecksTrailingBytesBeforeJsonParsing() throws Exception {
		assertProblem(post("{\"text\":\"" + "á".repeat(130) + "\"}", true, token()),
				413, "Request body is too large");
		assertProblem(post("{}" + " ".repeat(255), true, token()), 413, "Request body is too large");
	}

	@Test
	void rateLimitUsesVerifiedConsumerAcrossIpsAndIgnoresIdentityHeaders() throws Exception {
		String token = token();
		assertThat(get(token, "198.51.100.1").statusCode()).isEqualTo(200);
		assertThat(get(token, "198.51.100.2").statusCode()).isEqualTo(200);
		var limited = get(token, "198.51.100.3");
		assertProblem(limited, 429, "Request rate limit exceeded");
		assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
		assertThat(get(token(), "198.51.100.3").statusCode()).isEqualTo(200);
	}

	@Test
	void anonymousRequestsShareConnectionIpAndHealthRemainsAvailable() throws Exception {
		assertThat(get(null, "198.51.100.10").statusCode()).isEqualTo(401);
		assertThat(get(null, "198.51.100.11").statusCode()).isEqualTo(401);
		assertProblem(get(null, "198.51.100.12"), 429, "Request rate limit exceeded");
		for (int i = 0; i < 3; i++) {
			var response = client.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/a%70i/v1/requests", "/%61pi/v1/requests"})
	void encodedApiPathStillEnforcesBodyLimit(String path) throws Exception {
		var response = client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token())
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body(257))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(413);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/a%70i/v1/requests", "/%61pi/v1/requests"})
	void encodedApiPathStillEnforcesConsumerQuota(String path) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token()).GET().build();
		assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
		assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
		assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
	}

	private HttpResponse<String> post(String body, boolean chunked, String token) throws Exception {
		byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))
				: HttpRequest.BodyPublishers.ofByteArray(bytes);
		return client.send(HttpRequest.newBuilder(uri(PATH)).header("Authorization", "Bearer " + token)
				.header("X-Correlation-ID", "request-limits-test").header("Content-Type", "application/json")
				.POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String token, String forwardedIp) throws Exception {
		var builder = HttpRequest.newBuilder(uri(PATH)).header("X-Correlation-ID", "request-limits-test")
				.header("X-Forwarded-For", forwardedIp).header("X-Consumer-ID", UUID.randomUUID().toString());
		if (token != null) builder.header("Authorization", "Bearer " + token);
		return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private void assertProblem(HttpResponse<String> response, int status, String detail) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		var problem = mapper.readTree(response.body());
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		assertThat(problem.path("type").asText()).isEqualTo("about:blank");
		assertThat(problem.path("title").asText()).isNotBlank();
		assertThat(problem.path("detail").asText()).isEqualTo(detail);
		assertThat(problem.path("instance").asText()).isEqualTo(PATH);
		assertThat(problem.path("correlationId").asText()).isEqualTo("request-limits-test");
		assertThat(response.headers().firstValue("X-Correlation-ID")).contains("request-limits-test");
		assertThat(response.body()).doesNotContain("Exception", "stackTrace", "Bearer");
	}

	private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
	private String body(int size) { return "{\"text\":\"" + "x".repeat(size - 11) + "\"}"; }

	private String token() throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer("https://limits.example.test").subject(UUID.randomUUID().toString())
				.claim("scope", "requests/read requests/write").expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
		return jwt.serialize();
	}

	private static KeyPair keys() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (Exception e) { throw new IllegalStateException(e); }
	}

	@TestComponent
	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({SecurityConfiguration.class, SecurityProblemHandler.class, CorrelationIdFilter.class,
			ApiExceptionHandler.class, ProbeController.class, HttpLimitConfiguration.class})
	static class TestApplication {
		@Bean JwtDecoder jwtDecoder() {
			var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://limits.example.test"));
			return decoder;
		}
	}

	@TestComponent
	@RestController
	static class ProbeController {
		final AtomicInteger calls = new AtomicInteger();
		@PostMapping(PATH) Map<String, String> post(@RequestBody Map<String, String> body) {
			calls.incrementAndGet();
			return body;
		}
		@GetMapping(PATH) Map<String, String> get() { return Map.of("status", "ok"); }
	}
}
