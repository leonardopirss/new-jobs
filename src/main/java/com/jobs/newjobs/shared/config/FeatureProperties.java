package com.jobs.newjobs.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.features")
public record FeatureProperties(
	boolean notionWritesEnabled,
	boolean importsEnabled
) {
}
