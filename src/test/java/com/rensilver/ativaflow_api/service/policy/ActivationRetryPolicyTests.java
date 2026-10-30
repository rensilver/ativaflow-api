package com.rensilver.ativaflow_api.service.policy;

import java.time.Duration;

import com.rensilver.ativaflow_api.config.ActivationRetryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRetryPolicyTests {

	@Test
	void growsExponentiallyAndCapsTheBaseDelay() {
		var policy = new ActivationRetryPolicy(
				new ActivationRetryProperties(3, Duration.ofSeconds(20), Duration.ofSeconds(60), 0));
		assertThat(policy.delayMillis(1)).isEqualTo(20_000);
		assertThat(policy.delayMillis(2)).isEqualTo(40_000);
		assertThat(policy.delayMillis(3)).isEqualTo(60_000);
	}

	@Test
	void jitterNeverShortensThePositiveBackoffOrExceedsTheConfiguredRatio() {
		var policy = new ActivationRetryPolicy(
				new ActivationRetryProperties(3, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.25));
		for (int sample = 0; sample < 50; sample++) {
			assertThat(policy.delayMillis(1)).isBetween(1_000L, 1_250L);
			assertThat(policy.delayMillis(2)).isBetween(2_000L, 2_500L);
		}
	}

	@Test
	void rejectsAttemptNumbersOutsideTheProcessingContract() {
		var policy = new ActivationRetryPolicy(
				new ActivationRetryProperties(3, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.25));
		assertThatThrownBy(() -> policy.delayMillis(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.delayMillis(4)).isInstanceOf(IllegalArgumentException.class);
	}
}
