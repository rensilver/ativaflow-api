package com.rensilver.ativaflow_api.http;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLimitConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@ParameterizedTest
	@ValueSource(strings = {"max-body-bytes=0", "max-body-bytes=1048577", "max-body-bytes=invalid",
			"rate-limit.requests=0", "rate-limit.requests=1000001", "rate-limit.window=0ms",
			"rate-limit.window=999999ns", "rate-limit.window=2d", "rate-limit.window=invalid",
			"rate-limit.max-keys=0", "rate-limit.max-keys=100001", "rate-limit.enabled=invalid"})
	void invalidLimitsPreventStartup(String property) {
		runner.withPropertyValues("app.http." + property).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void environmentVariablesConfigureLimitsThroughApplicationYaml() {
		runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
				new SystemEnvironmentPropertySource("http-limits-test-env", Map.of(
						"API_MAX_BODY_BYTES", "512", "API_RATE_LIMIT_ENABLED", "false",
						"API_RATE_LIMIT_REQUESTS", "5", "API_RATE_LIMIT_WINDOW", "1500ms",
						"API_RATE_LIMIT_MAX_KEYS", "25"))))
				.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
					assertThat(context).hasNotFailed();
					var properties = context.getBean(HttpLimitProperties.class);
					assertThat(properties.maxBodyBytes()).isEqualTo(512);
					assertThat(properties.rateLimit().enabled()).isFalse();
					assertThat(properties.rateLimit().requests()).isEqualTo(5);
					assertThat(properties.rateLimit().window()).isEqualTo(Duration.ofMillis(1500));
					assertThat(properties.rateLimit().maxKeys()).isEqualTo(25);
				});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(HttpLimitProperties.class)
	static class PropertiesConfiguration { }
}
