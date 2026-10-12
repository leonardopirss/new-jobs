package com.jobs.newjobs.notion.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("app.notion")
public record NotionProperties(
	@NotNull URI baseUrl,
	@NotBlank String apiVersion,
	String token,
	String databaseId,
	String dataSourceId,
	@NotNull Duration connectTimeout,
	@NotNull Duration requestTimeout
) {
	public NotionProperties {
		if (baseUrl != null && !usesSafeTransport(baseUrl)) {
			throw new IllegalArgumentException("Notion base URL must use HTTPS except on localhost");
		}
		if (apiVersion != null && !apiVersion.equals("2026-03-11")) {
			throw new IllegalArgumentException("Notion API version must be 2026-03-11");
		}
		validateTimeout("connect timeout", connectTimeout);
		validateTimeout("request timeout", requestTimeout);
	}

	public void validateForWrites() {
		List<String> missing = new ArrayList<>();
		addIfBlank(missing, "NOTION_TOKEN", token);
		addIfBlank(missing, "NOTION_DATABASE_ID", databaseId);
		addIfBlank(missing, "NOTION_DATA_SOURCE_ID", dataSourceId);
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
				"Notion writes are enabled but required configuration is missing: " + String.join(", ", missing)
			);
		}
	}

	private static boolean usesSafeTransport(URI baseUrl) {
		if ("https".equalsIgnoreCase(baseUrl.getScheme())) {
			return true;
		}
		String host = baseUrl.getHost();
		return "http".equalsIgnoreCase(baseUrl.getScheme())
			&& ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
	}

	private static void addIfBlank(List<String> missing, String name, String value) {
		if (value == null || value.isBlank()) {
			missing.add(name);
		}
	}

	private static void validateTimeout(String name, Duration timeout) {
		if (timeout != null && timeout.compareTo(Duration.ofMillis(100)) < 0) {
			throw new IllegalArgumentException(name + " must be at least 100ms");
		}
	}
}
