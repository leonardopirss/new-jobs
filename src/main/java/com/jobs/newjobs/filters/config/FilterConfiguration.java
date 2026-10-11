package com.jobs.newjobs.filters.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FilterConfiguration {
	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}
}
