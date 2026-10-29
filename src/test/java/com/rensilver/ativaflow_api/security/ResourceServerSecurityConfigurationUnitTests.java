package com.rensilver.ativaflow_api.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Executa a configuração real e seus filtros em memória, sem Boot, banco ou rede. */
class ResourceServerSecurityConfigurationUnitTests {

	private static final String PATH = "/api/v1/requests";
	private static final String ISSUER = "https://issuer.example.test";
	private static final KeyPair KEYS = generateKeys();
	private final ObjectMapper mapper = new MappingJackson2HttpMessageConverter().getObjectMapper();
	private StaticWebApplicationContext context;
	private FilterChainProxy filters;

	@BeforeEach
	void configureFilters() throws Exception {
		context = new StaticWebApplicationContext();
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		context.getBeanFactory().registerSingleton("jwtDecoder", decoder);
		context.getBeanFactory().registerSingleton("corsConfigurationSource", new UrlBasedCorsConfigurationSource());
		context.getBeanFactory().registerSingleton("pathMappedEndpoints", new PathMappedEndpoints("/actuator", List.of()));
		var processor = new ObjectPostProcessor<Object>() {
			@Override
			@SuppressWarnings("unchecked")
			public <O> O postProcess(O object) {
				// Executa os callbacks de inicialização dos filtros, como no contexto gerenciado pelo Spring.
				return (O) context.getAutowireCapableBeanFactory().initializeBean(object, object.getClass().getName());
			}
		};
		var shared = new HashMap<Class<?>, Object>();
		shared.put(ApplicationContext.class, context);
		var http = new HttpSecurity(processor, new AuthenticationManagerBuilder(processor), shared);
		// Defaults aplicados pelo HttpSecurityConfiguration antes do bean da aplicação.
		http.csrf(Customizer.withDefaults()).exceptionHandling(Customizer.withDefaults())
				.sessionManagement(Customizer.withDefaults()).securityContext(Customizer.withDefaults())
				.requestCache(Customizer.withDefaults()).anonymous(Customizer.withDefaults())
				.servletApi(Customizer.withDefaults()).logout(Customizer.withDefaults());
		filters = new FilterChainProxy(new SecurityConfiguration().securityFilterChain(http, new SecurityProblemHandler(mapper)));
	}

	@AfterEach
	void cleanUp() {
		SecurityContextHolder.clearContext();
		context.close();
	}

	@ParameterizedTest
	@ValueSource(strings = {"GET", "POST"})
	void missingBearerTokenReturnsUnauthorizedWithoutSessionOrRedirect(String method) throws Exception {
		var request = request(method, PATH);
		request.addHeader(HttpHeaders.ACCEPT, "text/html");
		var response = execute(request);

		assertProblem(response, 401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
		assertThat(request.getSession(false)).isNull();
	}

	@Test
	void signedBearerTokenAuthenticatesPostWithoutCsrfOrSession() throws Exception {
		var request = request("POST", PATH);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEYS, ISSUER, Instant.now().plusSeconds(300)));

		var response = execute(request);

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(response.getHeader("Authenticated-Consumer")).isEqualTo("consumer-one");
		assertThat(request.getSession(false)).isNull();
		assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertProblem(execute(request("GET", PATH)), 401);
	}

	@ParameterizedTest
	@ValueSource(strings = {"malformed", "expired", "wrong-signature", "wrong-issuer"})
	void rejectsInvalidBearerTokenWithSafeProblem(String failure) throws Exception {
		String token = switch (failure) {
			case "expired" -> token(KEYS, ISSUER, Instant.now().minusSeconds(120));
			case "wrong-signature" -> token(generateKeys(), ISSUER, Instant.now().plusSeconds(300));
			case "wrong-issuer" -> token(KEYS, "https://untrusted.example.test", Instant.now().plusSeconds(300));
			default -> "private-token";
		};
		var request = request("GET", PATH);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

		var response = execute(request);

		assertProblem(response, 401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer", "invalid_token")
				.doesNotContain(token, "Jwt", "signature", "expired");
		assertThat(request.getSession(false)).isNull();
	}

	@Test
	void ignoresAuthenticationStoredInExistingSession() throws Exception {
		var request = request("GET", PATH);
		var session = new MockHttpSession();
		var securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("session-user", "secret", List.of()));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
		request.setSession(session);

		assertProblem(execute(request), 401);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/login", "/logout", "/oauth2/authorize", "/oauth2/token", "/actuator/env", "/actuator/prometheus"})
	void protectsRoutesWithoutProvidingLoginOrTokenEndpoints(String path) throws Exception {
		var response = execute(request("GET", path));

		assertProblem(response, 401);
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	void basicCredentialsDoNotAuthenticate() throws Exception {
		var request = request("GET", PATH);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==");

		assertProblem(execute(request), 401);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs.yaml"})
	void keepsDocumentationPublic(String path) throws Exception {
		assertThat(execute(request("GET", path)).getStatus()).isEqualTo(204);
	}

	private MockHttpServletRequest request(String method, String path) {
		var request = new MockHttpServletRequest(context.getServletContext(), method, path);
		request.setServletPath(path);
		request.getServletContext().setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
		request.addHeader("X-Correlation-ID", "resource-server-test");
		return request;
	}

	private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
		var response = new MockHttpServletResponse();
		new CorrelationIdFilter().doFilter(request, response, (req, res) -> filters.doFilter(req, res, (r, s) -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			response.setHeader("Authenticated-Consumer", authentication.getName());
			response.setStatus(204);
		}));
		return response;
	}

	private void assertProblem(MockHttpServletResponse response, int status) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		var problem = mapper.readTree(response.getContentAsByteArray());
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		assertThat(problem.path("correlationId").asText()).isEqualTo("resource-server-test");
		assertThat(response.getContentAsString()).doesNotContain("private-token", "Exception", "stackTrace");
	}

	private static String token(KeyPair keys, String issuer, Instant expiresAt) throws JOSEException {
		var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("consumer-one")
				.claim("scope", "requests/read requests/write")
				.expirationTime(java.util.Date.from(expiresAt)).build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
		return jwt.serialize();
	}

	private static KeyPair generateKeys() {
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
