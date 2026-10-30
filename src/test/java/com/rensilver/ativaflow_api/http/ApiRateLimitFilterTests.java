package com.rensilver.ativaflow_api.http;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTests {

	@AfterEach
	void clearIdentity() { SecurityContextHolder.clearContext(); }

	@Test
	void differentConnectionIpsHaveIndependentAnonymousQuotas() throws Exception {
		var filter = filter(true);
		assertThat(execute(filter, "192.0.2.1")).isEqualTo(204);
		assertThat(execute(filter, "192.0.2.1")).isEqualTo(429);
		assertThat(execute(filter, "192.0.2.2")).isEqualTo(204);
	}

	@Test
	void disabledLimiterAllowsRequestsBeyondConfiguredQuota() throws Exception {
		var filter = filter(false);
		for (int i = 0; i < 3; i++) assertThat(execute(filter, "192.0.2.1")).isEqualTo(204);
	}

	@Test
	void authenticatedConsumerDoesNotConsumeAnonymousIpQuota() throws Exception {
		var filter = filter(true);
		assertThat(execute(filter, "192.0.2.1")).isEqualTo(204);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated("192.0.2.1", null, java.util.List.of()));
		assertThat(execute(filter, "192.0.2.1")).isEqualTo(204);
		assertThat(execute(filter, "192.0.2.2")).isEqualTo(429);
	}

	private int execute(ApiRateLimitFilter filter, String ip) throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/requests");
		request.setRemoteAddr(ip);
		var response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
		return response.getStatus();
	}

	private ApiRateLimitFilter filter(boolean enabled) {
		return new ApiRateLimitFilter(new HttpLimitProperties(16384,
				new HttpLimitProperties.RateLimit(enabled, 1, Duration.ofMinutes(1), 10)),
				new MappingJackson2HttpMessageConverter().getObjectMapper());
	}
}
