package com.jobs.newjobs.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.scheduling.SchedulerProperties;

class ExternalFeatureConfigurationValidatorTests {
	private static final SchedulerProperties DISABLED_SCHEDULER = new SchedulerProperties(
		false, false, Duration.ofHours(6), ZoneId.of("UTC"), Duration.ofSeconds(30)
	);

	@Test
	void allowsMissingNotionSecretsWhileExternalCapabilitiesAreDisabled() {
		ExternalFeatureConfigurationValidator validator = validator(
			new FeatureProperties(false, false), notion("", "", ""), DISABLED_SCHEDULER
		);

		assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
	}

	@Test
	void failsFastWhenNotionWritesAreEnabledWithoutCompleteSecrets() {
		ExternalFeatureConfigurationValidator validator = validator(
			new FeatureProperties(true, false), notion("", "database", "data-source"), DISABLED_SCHEDULER
		);

		assertThatThrownBy(validator::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("NOTION_TOKEN");
	}

	@Test
	void rejectsImportOrSchedulingWithoutTheNotionWriteBoundary() {
		assertThatThrownBy(() -> validator(
			new FeatureProperties(false, true), notion("", "", ""), DISABLED_SCHEDULER
		).afterPropertiesSet()).hasMessageContaining("imports cannot be enabled");

		SchedulerProperties enabled = new SchedulerProperties(
			true, false, Duration.ofHours(1), ZoneId.of("UTC"), Duration.ofSeconds(30));
		assertThatThrownBy(() -> validator(
			new FeatureProperties(false, false), notion("", "", ""), enabled
		).afterPropertiesSet()).hasMessageContaining("scheduler cannot be enabled");
	}

	private static ExternalFeatureConfigurationValidator validator(
		FeatureProperties features,
		NotionProperties notion,
		SchedulerProperties scheduler
	) {
		return new ExternalFeatureConfigurationValidator(features, notion, scheduler);
	}

	private static NotionProperties notion(String token, String databaseId, String dataSourceId) {
		return new NotionProperties(
			URI.create("https://api.notion.com"), "2026-03-11", token, databaseId, dataSourceId,
			Duration.ofSeconds(3), Duration.ofSeconds(10)
		);
	}
}
