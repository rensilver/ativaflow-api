package com.rensilver.ativaflow_api.security;

import java.util.List;

import com.rensilver.ativaflow_api.exception.ApiExceptionHandler;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Advice e handlers reais na fronteira MVC/filtros, sem Boot, banco ou rede. */
class SecurityMvcProblemContractUnitTests {

	private static final String PATH = "/security-failure";
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		var mapper = new MappingJackson2HttpMessageConverter().getObjectMapper();
		var handler = new SecurityProblemHandler(mapper);
		var translation = new ExceptionTranslationFilter(handler, new NullRequestCache());
		translation.setAccessDeniedHandler(handler);
		// Assim como na cadeia real, expõe a autenticação em request.getUserPrincipal().
		var servletApi = new SecurityContextHolderAwareRequestFilter();
		servletApi.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new SecurityFailureController())
				.setControllerAdvice(new ApiExceptionHandler())
				.addFilters(new CorrelationIdFilter(), servletApi, translation).build();
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "text/html"})
	void mvcAuthenticationFailureUsesEntryPointInsteadOfGenericAdvice(String accept) throws Exception {
		problem("/authentication", accept, 401, "Unauthorized", "Authentication is required")
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "text/html"})
	void mvcInvalidTokenPreservesOAuthChallengeAndHidesInternalDetails(String accept) throws Exception {
		problem("/invalid-token", accept, 401, "Unauthorized", "Authentication is required")
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "text/html"})
	void anonymousMvcAccessDeniedUsesEntryPointAndReturns401(String accept) throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		problem("/authorization", accept, 401, "Unauthorized", "Authentication is required")
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "application/problem+json", "text/html"})
	void authenticatedMvcAccessDeniedUsesDeniedHandlerAndPreservesInsufficientScope(String accept) throws Exception {
		var jwt = Jwt.withTokenValue("validated-token").header("alg", "RS256").subject("consumer").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

		problem("/authorization", accept, 403, "Forbidden", "Access is denied")
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"insufficient_scope\"")));
	}

	private ResultActions problem(String path, String accept, int expectedStatus, String title, String detail)
			throws Exception {
		return mvc.perform(get(PATH + path).accept(accept).queryParam("token", "secret")
				.header("X-Correlation-ID", "mvc-security-contract"))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value(title))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(jsonPath("$.instance").value(PATH + path))
				.andExpect(jsonPath("$.correlationId").value("mvc-security-contract"))
				.andExpect(jsonPath("$.properties").doesNotExist())
				.andExpect(header().string("X-Correlation-ID", "mvc-security-contract"))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, not(containsString("secret"))))
				.andExpect(content().string(not(containsString("secret"))))
				.andExpect(content().string(not(containsString("Exception"))))
				.andExpect(content().string(not(containsString("stackTrace"))));
	}

	@RestController
	static class SecurityFailureController {

		@GetMapping(PATH + "/authentication")
		void authenticationFailure() {
			throw new BadCredentialsException("internal token=secret");
		}

		@GetMapping(PATH + "/invalid-token")
		void invalidToken() {
			throw new InvalidBearerTokenException("decoder token=secret");
		}

		@GetMapping(PATH + "/authorization")
		void accessDenied() {
			throw new AccessDeniedException("private resource token=secret");
		}
	}
}
