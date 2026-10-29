package com.rensilver.ativaflow_api.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
								"/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
						.requestMatchers(EndpointRequest.to(HealthEndpoint.class),
								EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
						.permitAll()
						.anyRequest().authenticated())
				.cors(withDefaults())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(problemHandler)
						.accessDeniedHandler(problemHandler))
				.formLogin(withDefaults())
				.httpBasic(basic -> basic.authenticationEntryPoint(problemHandler))
				.build();
	}
}
