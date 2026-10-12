package com.jobs.newjobs.auth.config;

import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Validated
@ConfigurationProperties("app.security.manual")
public record ManualCredentialProperties(
	@NotBlank @Size(min = 32) String currentKey,
	String previousKey,
	Instant previousValidUntil
) {
	public ManualCredentialProperties {
		boolean hasPreviousKey = previousKey != null && !previousKey.isBlank();
		if (hasPreviousKey && previousKey.length() < 32) {
			throw new IllegalArgumentException("previous manual API key must contain at least 32 characters");
		}
		if (hasPreviousKey != (previousValidUntil != null)) {
			throw new IllegalArgumentException(
				"previous manual API key and its expiration must be configured together"
			);
		}
		if (hasPreviousKey && previousKey.equals(currentKey)) {
			throw new IllegalArgumentException("current and previous manual API keys must differ");
		}
	}
}
