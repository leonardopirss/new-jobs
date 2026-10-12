package com.jobs.newjobs.shared.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.scheduling.SchedulerProperties;

@Component
public final class ExternalFeatureConfigurationValidator implements InitializingBean {
	private final FeatureProperties features;
	private final NotionProperties notion;
	private final SchedulerProperties scheduler;

	public ExternalFeatureConfigurationValidator(
		FeatureProperties features,
		NotionProperties notion,
		SchedulerProperties scheduler
	) {
		this.features = features;
		this.notion = notion;
		this.scheduler = scheduler;
	}

	@Override
	public void afterPropertiesSet() {
		if (features.notionWritesEnabled()) {
			notion.validateForWrites();
		}
		if (features.importsEnabled() && !features.notionWritesEnabled()) {
			throw new IllegalStateException("imports cannot be enabled while Notion writes are disabled");
		}
		if (scheduler.enabled() && !features.notionWritesEnabled()) {
			throw new IllegalStateException("scheduler cannot be enabled while Notion writes are disabled");
		}
	}
}
