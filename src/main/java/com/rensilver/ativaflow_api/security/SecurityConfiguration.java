package com.rensilver.ativaflow_api.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
								"/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
						.requestMatchers(EndpointRequest.to(HealthEndpoint.class),
								EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/requests")
						.hasAuthority("SCOPE_requests/write")
						.requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**")
						.hasAuthority("SCOPE_requests/read")
						.anyRequest().authenticated())
				.cors(withDefaults())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(problemHandler)
						.accessDeniedHandler(problemHandler))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(withDefaults())
						.authenticationEntryPoint(problemHandler)
						.accessDeniedHandler(problemHandler))
				.build();
	}
}
