package com.rensilver.ativaflow_api.config;

import java.time.Duration;
import java.util.Map;

import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationConfigurationEnvironmentTests {

	@Test
	void environmentVariablesConfigureProviderTimeoutsAndRetryThroughApplicationYaml() {
		new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
						new SystemEnvironmentPropertySource("activation-test-env", Map.of(
								"ACTIVATION_PROVIDER_CONNECT_TIMEOUT", "350ms",
								"ACTIVATION_PROVIDER_READ_TIMEOUT", "750ms",
								"ACTIVATION_PROVIDER_RETRY_CONTRACT", "rejected-before-activation",
								"WORKER_RETRY_MAX_ATTEMPTS", "1",
								"WORKER_RETRY_INITIAL_DELAY", "250ms",
								"WORKER_RETRY_MAX_DELAY", "400ms",
								"WORKER_RETRY_JITTER_RATIO", "0"))))
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(PropertiesConfiguration.class, ActivationRetryPolicy.class)
				.withPropertyValues("app.activation-provider.base-url=http://localhost:8081")
				.run(context -> {
					assertThat(context).hasNotFailed();
					var provider = context.getBean(ActivationProviderProperties.class);
					assertThat(provider.connectTimeout()).isEqualTo(Duration.ofMillis(350));
					assertThat(provider.readTimeout()).isEqualTo(Duration.ofMillis(750));
					assertThat(provider.retryContract()).isEqualTo(ActivationProviderProperties.RetryContract.REJECTED_BEFORE_ACTIVATION);
					var retry = context.getBean(ActivationRetryProperties.class);
					assertThat(retry.maxAttempts()).isEqualTo(1);
					assertThat(retry.maxDelay()).isEqualTo(Duration.ofMillis(400));
					assertThat(context.getBean(ActivationRetryPolicy.class).delayMillis(1)).isEqualTo(250);
				});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ActivationProviderProperties.class)
	static class PropertiesConfiguration {
	}
}
