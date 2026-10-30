package com.rensilver.ativaflow_api.support;

import java.time.Duration;

import com.rensilver.ativaflow_api.http.ApiRateLimitFilter;
import com.rensilver.ativaflow_api.http.HttpLimitProperties;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

public final class RequestLimitTestFilters {
	private RequestLimitTestFilters() { }

	public static ApiRateLimitFilter disabledRateLimitFilter() {
		return new ApiRateLimitFilter(new HttpLimitProperties(16384,
				new HttpLimitProperties.RateLimit(false, 120, Duration.ofMinutes(1), 10000)),
				new MappingJackson2HttpMessageConverter().getObjectMapper());
	}
}
