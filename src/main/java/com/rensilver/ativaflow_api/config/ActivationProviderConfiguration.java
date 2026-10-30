package com.rensilver.ativaflow_api.config;

import java.net.http.HttpClient;

import com.rensilver.ativaflow_api.client.provider.HttpActivationProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActivationProviderProperties.class)
public class ActivationProviderConfiguration {

	@Bean
	HttpActivationProviderClient activationProviderClient(RestClient.Builder builder,
			ActivationProviderProperties properties, ClientHttpRequestFactorySettings settings,
			ObjectProvider<MeterRegistry> registries) {
		var providerSettings = settings
				.withConnectTimeout(properties.connectTimeout())
				.withReadTimeout(properties.readTimeout())
				.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);
		// Transporte explícito: não muda se bibliotecas HTTP forem adicionadas ao classpath.
		var requestFactory = ClientHttpRequestFactoryBuilder.jdk()
				.withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1))
				.build(providerSettings);
		RestClient restClient = builder.clone()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory)
				.build();
		return new HttpActivationProviderClient(restClient, properties.retryContract(),
				registries.getIfAvailable(CompositeMeterRegistry::new));
	}

}
