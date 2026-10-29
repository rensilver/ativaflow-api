package com.rensilver.ativaflow_api.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercita as regras reais com JWT assinado e filtros em memória, sem Boot, banco ou rede. */
class RequestScopeAuthorizationUnitTests {

	private static final String ISSUER = "https://issuer.example.test";
	private static final KeyPair KEYS = keys();
	private final ObjectMapper mapper = new MappingJackson2HttpMessageConverter().getObjectMapper();
	private StaticWebApplicationContext context;
	private FilterChainProxy filters;

	@BeforeEach
	void setUp() throws Exception {
		context = new StaticWebApplicationContext();
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		context.getBeanFactory().registerSingleton("jwtDecoder", decoder);
		context.getBeanFactory().registerSingleton("corsConfigurationSource", new UrlBasedCorsConfigurationSource());
		context.getBeanFactory().registerSingleton("pathMappedEndpoints", new PathMappedEndpoints("/actuator", List.of()));
		var processor = new ObjectPostProcessor<Object>() {
			@Override
			public <O> O postProcess(O object) {
				context.getAutowireCapableBeanFactory().initializeBean(object, object.getClass().getName());
				return object;
			}
		};
		var shared = new HashMap<Class<?>, Object>();
		shared.put(ApplicationContext.class, context);
		var http = new HttpSecurity(processor, new AuthenticationManagerBuilder(processor), shared);
		http.csrf(Customizer.withDefaults()).sessionManagement(Customizer.withDefaults())
				.securityContext(Customizer.withDefaults()).requestCache(Customizer.withDefaults())
				.anonymous(Customizer.withDefaults()).exceptionHandling(Customizer.withDefaults())
				.logout(Customizer.withDefaults()).servletApi(Customizer.withDefaults());
		filters = new FilterChainProxy(new SecurityConfiguration().securityFilterChain(http, new SecurityProblemHandler(mapper)));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		context.close();
	}

	@ParameterizedTest
	@CsvSource(textBlock = """
			POST, /api/v1/requests, requests/write, 204
			POST, /api/v1/requests, requests/read, 403
			POST, /api/v1/requests, , 403
			POST, /api/v1/requests, '', 403
			POST, /api/v1/requests, requests/write-other, 403
			POST, /api/v1/requests, SCOPE_requests/write, 403
			POST, /api/v1/requests, requests/read requests/write, 204
			GET, /api/v1/requests, requests/read, 204
			GET, /api/v1/requests, requests/write, 403
			GET, /api/v1/requests, , 403
			GET, /api/v1/requests, '', 403
			GET, /api/v1/requests, requests/read-other, 403
			GET, /api/v1/requests, SCOPE_requests/read, 403
			GET, /api/v1/requests, requests/read requests/write, 204
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, requests/read, 204
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, requests/write, 403
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, , 403
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, '', 403
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, requests/read-other, 403
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, SCOPE_requests/read, 403
			GET, /api/v1/requests/00000000-0000-0000-0000-000000000001, requests/read requests/write, 204
			""")
	void enforcesScopeForEachOperation(String method, String path, String scope, int expectedStatus) throws Exception {
		var request = request(method, path);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token(scope));

		var response = execute(request);

		assertThat(response.getStatus()).isEqualTo(expectedStatus);
		if (expectedStatus == 403) {
			assertProblem(response, 403, path);
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer", "insufficient_scope");
			assertThat(response.getHeader("Authenticated-Consumer")).isNull();
		}
		else {
			assertThat(response.getHeader("Authenticated-Consumer")).isEqualTo("consumer-one");
		}
	}

	@ParameterizedTest
	@CsvSource({"POST, /api/v1/requests", "GET, /api/v1/requests",
			"GET, /api/v1/requests/00000000-0000-0000-0000-000000000001"})
	void requiresAuthenticationBeforeCheckingScope(String method, String path) throws Exception {
		var response = execute(request(method, path));

		assertProblem(response, 401, path);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(response.getHeader("Authenticated-Consumer")).isNull();
	}

	private MockHttpServletRequest request(String method, String path) {
		var request = new MockHttpServletRequest(context.getServletContext(), method, path);
		request.setServletPath(path);
		request.getServletContext().setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
		request.addHeader("X-Correlation-ID", "request-scope-test");
		return request;
	}

	private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
		var response = new MockHttpServletResponse();
		new CorrelationIdFilter().doFilter(request, response, (req, res) -> filters.doFilter(req, res, (r, s) -> {
			response.setHeader("Authenticated-Consumer", SecurityContextHolder.getContext().getAuthentication().getName());
			response.setStatus(204);
		}));
		return response;
	}

	private void assertProblem(MockHttpServletResponse response, int status, String path) throws Exception {
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		var problem = mapper.readTree(response.getContentAsByteArray());
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		assertThat(problem.path("instance").asText()).isEqualTo(path);
		assertThat(problem.path("correlationId").asText()).isEqualTo("request-scope-test");
	}

	private static String token(String scope) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("consumer-one")
				.expirationTime(Date.from(Instant.now().plusSeconds(300)));
		if (scope != null) {
			claims.claim("scope", scope);
		}
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
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
