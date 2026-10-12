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
	boolean providerAuthorized,
	@NotNull Duration interval,
	@NotNull ZoneId timezone,
	@NotNull Duration shutdownGrace
) {
	public SchedulerProperties {
		if (interval == null || interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException("scheduler interval must be positive");
		}
		if (timezone == null) throw new IllegalArgumentException("scheduler timezone is required");
		if (shutdownGrace == null || shutdownGrace.isZero() || shutdownGrace.isNegative()
			|| shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0) {
			throw new IllegalArgumentException("shutdown grace must be between zero and five minutes");
		}
	}
}
