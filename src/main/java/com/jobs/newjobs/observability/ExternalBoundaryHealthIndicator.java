package com.jobs.newjobs.observability;

import java.util.function.BooleanSupplier;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.observability.OperationalTelemetry.Boundary;
import com.jobs.newjobs.observability.OperationalTelemetry.BoundaryStatus;
import com.jobs.newjobs.scheduling.SchedulerProperties;
import com.jobs.newjobs.shared.config.FeatureProperties;

@Component
public final class ExternalBoundaryHealthIndicator implements HealthIndicator {
	private final BooleanSupplier databaseProbe;
	private final FeatureProperties features;
	private final NotionProperties notion;
	private final SchedulerProperties scheduler;
	private final OperationalTelemetry telemetry;

	@Autowired
	public ExternalBoundaryHealthIndicator(JdbcClient jdbc, FeatureProperties features, NotionProperties notion,
		SchedulerProperties scheduler, OperationalTelemetry telemetry) {
		this(() -> jdbc.sql("select 1").query(Integer.class).single() == 1,
			features, notion, scheduler, telemetry);
	}

	ExternalBoundaryHealthIndicator(BooleanSupplier databaseProbe, FeatureProperties features, NotionProperties notion,
		SchedulerProperties scheduler, OperationalTelemetry telemetry) {
		this.databaseProbe = databaseProbe; this.features = features; this.notion = notion;
		this.scheduler = scheduler; this.telemetry = telemetry;
	}

	@Override
	public Health health() {
		try {
			if (!databaseProbe.getAsBoolean()) return Health.down().withDetail("database", "unavailable").build();
			if (features.notionWritesEnabled()) notion.validateForWrites();
			if (features.importsEnabled() && !features.notionWritesEnabled()) return invalidConfiguration();
			if (scheduler.enabled() && !features.notionWritesEnabled()) return invalidConfiguration();
		}
		catch (RuntimeException exception) {
			return Health.down().withDetail("configuration", "invalid").build();
		}
		if (telemetry.boundaryStatus(Boundary.NOTION) == BoundaryStatus.DEGRADED
			|| telemetry.boundaryStatus(Boundary.HIRING_CAFE) == BoundaryStatus.DEGRADED) {
			return Health.status("DEGRADED").withDetail("externalBoundaries", "degraded").build();
		}
		return Health.up().build();
	}

	private static Health invalidConfiguration() {
		return Health.down().withDetail("configuration", "invalid").build();
	}
}
