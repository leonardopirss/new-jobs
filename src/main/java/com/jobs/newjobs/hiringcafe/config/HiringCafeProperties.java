package com.jobs.newjobs.hiringcafe.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "hiringcafe")
public record HiringCafeProperties(
	@NotNull URI mcpUrl,
	@NotNull Duration connectTimeout,
	@NotNull Duration requestTimeout,
	@NotNull Duration retryBackoff,
	@NotNull Duration maxRetryDelay,
	@Min(1) @Max(3) int maxAttempts,
	@Min(1) @Max(25) int resultLimit
) {
	public HiringCafeProperties {
		if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
			throw new IllegalArgumentException("hiringcafe.connect-timeout must be positive");
		}
		if (requestTimeout != null && (requestTimeout.isZero() || requestTimeout.isNegative())) {
			throw new IllegalArgumentException("hiringcafe.request-timeout must be positive");
		}
		if (retryBackoff != null && retryBackoff.isNegative()) {
			throw new IllegalArgumentException("hiringcafe.retry-backoff must not be negative");
		}
		if (maxRetryDelay != null && maxRetryDelay.isNegative()) {
			throw new IllegalArgumentException("hiringcafe.max-retry-delay must not be negative");
		}
	}
}
