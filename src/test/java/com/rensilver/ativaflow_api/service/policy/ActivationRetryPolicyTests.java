package com.rensilver.ativaflow_api.service.policy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRetryPolicyTests {

	@Test
	void growsExponentiallyAndCapsTheBaseDelay() {
		var policy = new ActivationRetryPolicy(Duration.ofSeconds(20), Duration.ofSeconds(60), 0);
		assertThat(policy.delayMillis(1)).isEqualTo(20_000);
		assertThat(policy.delayMillis(2)).isEqualTo(40_000);
		assertThat(policy.delayMillis(3)).isEqualTo(60_000);
	}

	@Test
	void jitterNeverShortensThePositiveBackoffOrExceedsTheConfiguredRatio() {
		var policy = new ActivationRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.25);
		for (int sample = 0; sample < 50; sample++) {
			assertThat(policy.delayMillis(1)).isBetween(1_000L, 1_250L);
			assertThat(policy.delayMillis(2)).isBetween(2_000L, 2_500L);
		}
	}

	@ParameterizedTest
	@CsvSource({"0, 1000, 0.25", "-1, 1000, 0.25", "1000, 0, 0.25", "2000, 1000, 0.25",
			"1000, 2000, -0.1", "1000, 2000, 1.1", "1000, 2000, NaN", "1000, 2000, Infinity"})
	void rejectsConfigurationThatCouldProduceImmediateOrInvalidRetries(long initial, long maximum, double jitter) {
		assertThatThrownBy(() -> new ActivationRetryPolicy(Duration.ofMillis(initial), Duration.ofMillis(maximum), jitter))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAttemptNumbersOutsideTheProcessingContract() {
		var policy = new ActivationRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.25);
		assertThatThrownBy(() -> policy.delayMillis(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.delayMillis(4)).isInstanceOf(IllegalArgumentException.class);
	}
}
