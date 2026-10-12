package com.jobs.newjobs.scheduling;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("app.scheduler")
public record SchedulerProperties(
	boolean enabled,
	@NotNull Duration interval,
	@NotNull ZoneId timezone
) {
	public SchedulerProperties {
		if (interval != null && (interval.isZero() || interval.isNegative())) {
			throw new IllegalArgumentException("scheduler interval must be positive");
		}
	}
}
