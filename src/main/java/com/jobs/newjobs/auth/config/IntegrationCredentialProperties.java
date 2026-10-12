package com.jobs.newjobs.auth.config;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import com.jobs.newjobs.jobs.domain.JobSource;

import tools.jackson.databind.json.JsonMapper;

@Validated
@ConfigurationProperties("app.security.integrations")
public record IntegrationCredentialProperties(
	List<IntegrationCredential> credentials,
	String credentialsJson
) {
	private static final int MINIMUM_SECRET_LENGTH = 32;

	@ConstructorBinding
	public IntegrationCredentialProperties(List<IntegrationCredential> credentials, String credentialsJson) {
		credentials = credentials == null ? List.of() : List.copyOf(credentials);
		Set<String> integrationIds = new HashSet<>();
		Set<String> secrets = new HashSet<>();
		for (IntegrationCredential credential : credentials) {
			if (!integrationIds.add(credential.integrationId())) {
				throw new IllegalArgumentException("integration IDs must be unique");
			}
			if (!secrets.add(credential.currentKey())) {
				throw new IllegalArgumentException("integration credentials must be unique");
			}
			if (credential.previousKey() != null && !secrets.add(credential.previousKey())) {
				throw new IllegalArgumentException("integration credentials must be unique");
			}
		}
		this.credentials = credentials;
		this.credentialsJson = credentialsJson;
	}

	public IntegrationCredentialProperties(List<IntegrationCredential> credentials) {
		this(credentials, null);
	}

	public List<IntegrationCredential> resolvedCredentials() {
		if (credentialsJson == null || credentialsJson.isBlank() || "[]".equals(credentialsJson.strip())) {
			return credentials;
		}
		if (!credentials.isEmpty()) {
			throw new IllegalArgumentException(
				"configure integration credentials either as structured properties or JSON, not both");
		}
		try {
			IntegrationCredential[] parsed = JsonMapper.builder().findAndAddModules().build().readValue(
				credentialsJson, IntegrationCredential[].class);
			return new IntegrationCredentialProperties(Arrays.asList(parsed)).credentials();
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("integration credential JSON is invalid");
		}
	}

	@Override
	public String toString() {
		return "IntegrationCredentialProperties[credentials=" + credentials.size() + ", credentialsJson=<redacted>]";
	}

	public record IntegrationCredential(
		String integrationId,
		JobSource source,
		Set<String> scopes,
		String currentKey,
		String previousKey,
		Instant previousValidUntil,
		boolean enabled
	) {
		public IntegrationCredential {
			if (integrationId == null || integrationId.isBlank() || integrationId.length() > 128) {
				throw new IllegalArgumentException("integration ID must contain 1 to 128 characters");
			}
			if (source == null) throw new IllegalArgumentException("integration source is required");
			scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
			if (scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
				throw new IllegalArgumentException("integration scopes must not be blank");
			}
			validateSecret("current integration key", currentKey);
			boolean hasPrevious = previousKey != null && !previousKey.isBlank();
			if (hasPrevious) validateSecret("previous integration key", previousKey);
			if (hasPrevious != (previousValidUntil != null)) {
				throw new IllegalArgumentException(
					"previous integration key and its expiration must be configured together");
			}
			if (hasPrevious && previousKey.equals(currentKey)) {
				throw new IllegalArgumentException("current and previous integration keys must differ");
			}
			if (!hasPrevious) previousKey = null;
		}

		private static void validateSecret(String label, String secret) {
			if (secret == null || secret.length() < MINIMUM_SECRET_LENGTH) {
				throw new IllegalArgumentException(label + " must contain at least 32 characters");
			}
		}

		@Override
		public String toString() {
			return "IntegrationCredential[integrationId=" + integrationId + ", source=" + source
				+ ", scopes=" + scopes + ", currentKey=<redacted>, previousKey=<redacted>, enabled="
				+ enabled + "]";
		}
	}
}
