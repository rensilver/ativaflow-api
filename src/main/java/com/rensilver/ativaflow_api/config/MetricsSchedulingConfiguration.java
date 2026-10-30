package com.rensilver.ativaflow_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A slow metrics aggregate must not occupy the worker's polling thread. */
@Configuration(proxyBeanMethods = false)
public class MetricsSchedulingConfiguration {

	@Bean
	ThreadPoolTaskScheduler taskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("scheduling-");
		return scheduler;
	}

	@Bean
	ThreadPoolTaskScheduler activationMetricsScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("activation-metrics-");
		return scheduler;
	}
}
