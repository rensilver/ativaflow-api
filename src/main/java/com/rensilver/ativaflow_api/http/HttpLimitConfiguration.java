package com.rensilver.ativaflow_api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HttpLimitProperties.class)
public class HttpLimitConfiguration {

	@Bean
	FilterRegistrationBean<ApiBodyLimitFilter> bodyLimitFilter(HttpLimitProperties properties, ObjectMapper mapper) {
		var registration = new FilterRegistrationBean<>(new ApiBodyLimitFilter(properties, new ApiLimitProblemWriter(mapper)));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	@Bean
	ApiRateLimitFilter apiRateLimitFilter(HttpLimitProperties properties, ObjectMapper mapper) {
		return new ApiRateLimitFilter(properties, mapper);
	}

	@Bean
	FilterRegistrationBean<ApiRateLimitFilter> rateLimitRegistration(ApiRateLimitFilter filter) {
		var registration = new FilterRegistrationBean<>(filter);
		// Somente a cadeia Spring Security executa este filtro, com a identidade já validada.
		registration.setEnabled(false);
		return registration;
	}
}
