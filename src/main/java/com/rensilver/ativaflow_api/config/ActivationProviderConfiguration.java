package com.rensilver.ativaflow_api.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.rensilver.ativaflow_api.client.provider.HttpActivationProviderClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActivationProviderProperties.class)
public class ActivationProviderConfiguration {

	@Bean
	HttpActivationProviderClient activationProviderClient(RestClient.Builder builder,
			ActivationProviderProperties properties, ClientHttpRequestFactorySettings settings, Clock clock) {
		var providerSettings = settings
				.withConnectTimeout(requireBoundedTimeout(settings.connectTimeout(), Duration.ofSeconds(2)))
				.withReadTimeout(requireBoundedTimeout(settings.readTimeout(), Duration.ofSeconds(5)))
				.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);
		// Transporte explícito: não muda se bibliotecas HTTP forem adicionadas ao classpath.
		var requestFactory = ClientHttpRequestFactoryBuilder.jdk()
				.withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1))
				.build(providerSettings);
		RestClient restClient = builder.clone()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory)
				.build();
		return new HttpActivationProviderClient(restClient, clock);
	}

	private static Duration requireBoundedTimeout(@Nullable Duration configured, Duration defaultValue) {
		Duration timeout = configured == null ? defaultValue : configured;
		if (timeout.compareTo(Duration.ofMillis(1)) < 0
				|| timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
			throw new IllegalArgumentException("Provider HTTP timeouts must be between 1ms and 2147483647ms");
		}
		return timeout;
	}
}
