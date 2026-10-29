package com.rensilver.ativaflow_api.security;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

import static org.assertj.core.api.Assertions.assertThat;

/** Testa os filtros de segurança em memória, sem contexto Spring ou servidor. */
class SecurityProblemHandlerUnitTests {

	private static final String PATH = "/api/v1/requests";
	private final ObjectMapper mapper = new MappingJackson2HttpMessageConverter().getObjectMapper();
	private final SecurityProblemHandler handler = new SecurityProblemHandler(mapper);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "text/html"})
	void missingAuthenticationReturnsProblemInsteadOfRedirect(String accept) throws Exception {
		var request = request("GET");
		request.addHeader(HttpHeaders.ACCEPT, accept);
		var response = new MockHttpServletResponse();
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
		var filter = translationFilter();

		new CorrelationIdFilter().doFilter(request, response, (req, res) ->
				filter.doFilter(req, res, (ignoredRequest, ignoredResponse) -> {
					throw new AccessDeniedException("internal token=secret");
				}));

		assertProblem(response, 401, "Unauthorized", "Authentication is required");
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	void invalidBearerTokenReturnsSafeProblemWithChallenge() throws Exception {
		var request = request("GET");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret");
		var response = new MockHttpServletResponse();
		var filter = new BearerTokenAuthenticationFilter(authentication -> {
			throw new InvalidBearerTokenException("token=secret");
		});
		filter.setAuthenticationEntryPoint(handler);

		new CorrelationIdFilter().doFilter(request, response, (req, res) ->
				filter.doFilter(req, res, (ignoredRequest, ignoredResponse) -> {
					throw new AssertionError("Invalid credentials must stop the filter chain");
				}));

		assertProblem(response, 401, "Unauthorized", "Authentication is required");
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer", "invalid_token")
				.doesNotContain("secret");
	}

	@Test
	void authenticatedAccessDeniedReturnsForbiddenProblemAndCorrelatedLog() throws Exception {
		var request = request("GET");
		var response = new MockHttpServletResponse();
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"consumer", "secret", AuthorityUtils.createAuthorityList("ROLE_USER")));
		var filter = translationFilter();

		try (var logs = new LogCapture(SecurityProblemHandler.class)) {
			new CorrelationIdFilter().doFilter(request, response, (req, res) ->
					filter.doFilter(req, res, (ignoredRequest, ignoredResponse) -> {
						throw new AccessDeniedException("internal token=secret");
					}));

			assertProblem(response, 403, "Forbidden", "Access is denied");
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			assertThat(logs.events()).singleElement().satisfies(event -> {
				assertThat(LogCapture.fields(event)).containsEntry("status", 403).containsEntry("correlationId", "security-problem-test");
				assertThat(event.getFormattedMessage()).doesNotContain("secret");
				assertThat(event.getThrowableProxy()).isNull();
			});
		}
	}

	@Test
	void csrfRejectionReturnsProblemAndGeneratedCorrelation() throws Exception {
		var request = new MockHttpServletRequest("POST", PATH);
		var response = new MockHttpServletResponse();
		var filter = new CsrfFilter(new HttpSessionCsrfTokenRepository());
		filter.setAccessDeniedHandler(handler);

		new CorrelationIdFilter().doFilter(request, response, (req, res) ->
				filter.doFilter(req, res, (ignoredRequest, ignoredResponse) -> {
					throw new AssertionError("Missing CSRF token must stop the filter chain");
				}));

		assertProblem(response, 403, "Forbidden", "Access is denied");
		assertThat(UUID.fromString(response.getHeader("X-Correlation-ID")).toString())
				.isEqualTo(response.getHeader("X-Correlation-ID"));
	}

	private ExceptionTranslationFilter translationFilter() {
		var filter = new ExceptionTranslationFilter(handler, new NullRequestCache());
		filter.setAccessDeniedHandler(handler);
		return filter;
	}

	private MockHttpServletRequest request(String method) {
		var request = new MockHttpServletRequest(method, PATH);
		request.setQueryString("token=secret");
		request.addHeader("X-Correlation-ID", "security-problem-test");
		return request;
	}

	private void assertProblem(MockHttpServletResponse response, int status, String title, String detail) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		var problem = mapper.readTree(response.getContentAsByteArray());
		assertThat(problem.path("type").asText()).isEqualTo("about:blank");
		assertThat(problem.path("title").asText()).isEqualTo(title);
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		assertThat(problem.path("detail").asText()).isEqualTo(detail);
		assertThat(problem.path("instance").asText()).isEqualTo(PATH);
		assertThat(problem.path("correlationId").asText()).isNotBlank().isEqualTo(response.getHeader("X-Correlation-ID"));
		assertThat(response.getContentAsString()).doesNotContain("secret", "Exception", "trace", "stackTrace");
		assertThat(problem.has("properties")).isFalse();
	}
}
