package com.jobs.newjobs.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.auth.config.ManualCredentialProperties;

@Component
public final class ManualCredentialVerifier {
	private static final byte[] NO_PREVIOUS_CREDENTIAL = new byte[32];

	private final ManualCredentialProperties properties;
	private final Clock clock;
	private final byte[] currentDigest;
	private final byte[] previousDigest;

	public ManualCredentialVerifier(ManualCredentialProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.currentDigest = digest(properties.currentKey());
		this.previousDigest = properties.previousKey() == null
			? NO_PREVIOUS_CREDENTIAL
			: digest(properties.previousKey());
	}

	public boolean isValid(String presentedCredential) {
		byte[] presentedDigest = digest(presentedCredential == null ? "" : presentedCredential);
		boolean currentMatches = MessageDigest.isEqual(currentDigest, presentedDigest);
		boolean previousMatches = MessageDigest.isEqual(previousDigest, presentedDigest);
		boolean previousActive = properties.previousValidUntil() != null
			&& clock.instant().isBefore(properties.previousValidUntil());
		return currentMatches || (previousActive && previousMatches);
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
		}
	}
}
