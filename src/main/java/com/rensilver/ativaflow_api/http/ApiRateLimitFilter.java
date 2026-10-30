package com.rensilver.ativaflow_api.http;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Executa depois da autenticação Bearer e antes da autorização. */
public final class ApiRateLimitFilter extends OncePerRequestFilter {

	private final boolean enabled;
	private final InMemoryRateLimiter limiter;
	private final ApiLimitProblemWriter problems;

	public ApiRateLimitFilter(HttpLimitProperties properties, ObjectMapper mapper) {
		this.enabled = properties.rateLimit().enabled();
		this.limiter = new InMemoryRateLimiter(properties.rateLimit(), System::nanoTime);
		this.problems = new ApiLimitProblemWriter(mapper);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !enabled || !ApiBodyLimitFilter.isApiRequest(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		String key = authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)
				? "consumer:" + authentication.getName() : "ip:" + request.getRemoteAddr();
		long retryAfter = limiter.retryAfterSeconds(key);
		if (retryAfter > 0) {
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
			problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Request rate limit exceeded");
			return;
		}
		chain.doFilter(request, response);
	}
}
