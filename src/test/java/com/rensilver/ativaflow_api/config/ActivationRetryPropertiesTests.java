package com.rensilver.ativaflow_api.config;

import com.rensilver.ativaflow_api.service.policy.ActivationRetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationRetryPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(context -> context.getBeanFactory()
					.setConversionService(ApplicationConversionService.getSharedInstance()))
			.withUserConfiguration(ActivationRetryPolicy.class);

	@Test
	void defaultsAllowPositiveBackoff() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ActivationRetryPolicy.class).delayMillis(1)).isBetween(1_000L, 1_250L);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3})
	void acceptsConfiguredLimitsAndUsesConfiguredBackoff(int maxAttempts) {
		runner.withPropertyValues("app.worker.retry.max-attempts=" + maxAttempts,
				"app.worker.retry.initial-delay=250ms", "app.worker.retry.max-delay=400ms",
				"app.worker.retry.jitter-ratio=0")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(ActivationRetryPolicy.class).maxAttempts()).isEqualTo(maxAttempts);
					assertThat(context.getBean(ActivationRetryPolicy.class).delayMillis(1)).isEqualTo(250);
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"max-attempts=0", "max-attempts=-1", "max-attempts=4", "max-attempts=invalid",
			"initial-delay=0ms", "initial-delay=-1ms", "initial-delay=999999ns", "initial-delay=2m",
			"max-delay=0ms", "max-delay=-1ms", "max-delay=999999ns", "initial-delay=invalid",
			"max-delay=invalid", "jitter-ratio=invalid",
			"max-delay=9223372036854775807ms", "max-delay=9223372036854775807s",
			"jitter-ratio=-0.1", "jitter-ratio=1.1", "jitter-ratio=NaN", "jitter-ratio=Infinity"})
	void invalidConfigurationPreventsStartup(String property) {
		runner.withPropertyValues("app.worker.retry." + property)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("Could not bind properties to 'ActivationRetryProperties'");
				});
	}
}
