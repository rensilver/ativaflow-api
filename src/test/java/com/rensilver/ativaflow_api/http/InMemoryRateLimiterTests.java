package com.rensilver.ativaflow_api.http;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTests {

	private final AtomicLong nanos = new AtomicLong();

	@Test
	void renewsQuotaAtExactWindowBoundaryAndRoundsRetryAfterUp() {
		var limiter = limiter(2, Duration.ofMillis(1500), 10);
		assertThat(limiter.retryAfterSeconds("consumer:a")).isZero();
		assertThat(limiter.retryAfterSeconds("consumer:a")).isZero();
		assertThat(limiter.retryAfterSeconds("consumer:a")).isEqualTo(2);
		nanos.set(1_499_999_999L);
		assertThat(limiter.retryAfterSeconds("consumer:a")).isEqualTo(1);
		nanos.set(1_500_000_000L);
		assertThat(limiter.retryAfterSeconds("consumer:a")).isZero();
		assertThat(limiter.retryAfterSeconds("consumer:a")).isZero();
		assertThat(limiter.retryAfterSeconds("consumer:a")).isEqualTo(2);
	}

	@Test
	void separateKeysAndInstancesHaveIndependentQuotas() {
		var first = limiter(1, Duration.ofSeconds(1), 10);
		assertThat(first.retryAfterSeconds("consumer:a")).isZero();
		assertThat(first.retryAfterSeconds("consumer:a")).isEqualTo(1);
		assertThat(first.retryAfterSeconds("consumer:b")).isZero();
		assertThat(first.retryAfterSeconds("ip:a")).isZero();
		assertThat(limiter(1, Duration.ofSeconds(1), 10).retryAfterSeconds("consumer:a")).isZero();
	}

	@Test
	void fullRegistryRejectsNewKeysWithoutEvictingActiveQuotaThenReclaimsExpiredKeys() {
		var limiter = limiter(1, Duration.ofSeconds(2), 2);
		assertThat(limiter.retryAfterSeconds("a")).isZero();
		nanos.set(1_000_000_000L);
		assertThat(limiter.retryAfterSeconds("b")).isZero();
		assertThat(limiter.retryAfterSeconds("c")).isEqualTo(1);
		assertThat(limiter.retryAfterSeconds("a")).isEqualTo(1);
		nanos.set(2_000_000_000L);
		assertThat(limiter.retryAfterSeconds("c")).isZero();
		assertThat(limiter.retryAfterSeconds("b")).isEqualTo(1);
		assertThat(limiter.retryAfterSeconds("d")).isEqualTo(1);
	}

	@Test
	void concurrentRequestsCannotOvershootQuota() throws Exception {
		var limiter = limiter(7, Duration.ofMinutes(1), 10);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var results = IntStream.range(0, 100).mapToObj(i -> executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return limiter.retryAfterSeconds("same-consumer");
			})).toList();
			start.countDown();
			int accepted = 0;
			for (var result : results) {
				long wait = result.get(5, TimeUnit.SECONDS);
				assertThat(wait).isIn(0L, 60L);
				if (wait == 0) accepted++;
			}
			assertThat(accepted).isEqualTo(7);
		}
	}

	private InMemoryRateLimiter limiter(int requests, Duration window, int maxKeys) {
		return new InMemoryRateLimiter(new HttpLimitProperties.RateLimit(true, requests, window, maxKeys), nanos::get);
	}
}
