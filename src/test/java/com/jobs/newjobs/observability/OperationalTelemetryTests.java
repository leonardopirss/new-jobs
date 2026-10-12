package com.jobs.newjobs.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.observability.OperationalTelemetry.Boundary;
import com.jobs.newjobs.observability.OperationalTelemetry.BoundaryStatus;
import com.jobs.newjobs.observability.OperationalTelemetry.NotionOperation;
import com.jobs.newjobs.observability.OperationalTelemetry.SchedulerOutcome;
import com.jobs.newjobs.scheduling.SchedulerProperties;
import com.jobs.newjobs.shared.config.FeatureProperties;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OperationalTelemetryTests {
	@Test
	void keepsMetricLabelsBoundedWhileStructuredEventsCarrySafeCorrelationIdsAndCounts() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		List<Event> events = new ArrayList<>();
		OperationalTelemetry telemetry = new OperationalTelemetry(meters,
			(name, fields) -> events.add(new Event(name, fields)));
		UUID runId = UUID.randomUUID(); UUID filterId = UUID.randomUUID();
		SyncRunResult result = SyncRunResult.from(UUID.randomUUID(), List.of(
			new SyncItemOutcome(0, SyncItemStatus.CREATED, "external-1", "page-1", null),
			new SyncItemOutcome(1, SyncItemStatus.NOT_PROCESSED, "external-2", null, "QUOTA_EXHAUSTED")));

		telemetry.schedulerFilter(runId, JobSource.HIRING_CAFE, filterId, SchedulerOutcome.PARTIAL,
			Duration.ofMillis(17), result, 4, 2);

		var counter = meters.get("jobs.scheduler.filters").counter();
		assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
			.containsExactlyInAnyOrder("source", "outcome");
		assertThat(counter.getId().getTags()).extracting(tag -> tag.getValue())
			.doesNotContain(runId.toString(), filterId.toString());
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.fields().get("runId")).isEqualTo(runId);
			assertThat(event.fields().get("filterId")).isEqualTo(filterId);
			assertThat(event.fields().get("created")).isEqualTo(1);
			assertThat(event.fields().get("notProcessed")).isEqualTo(1);
		});
	}

	@Test
	void emitsQuotaNotionRetryMarkerRankingAndImportMetricsWithoutSecretOrJobLabels() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		OperationalTelemetry telemetry = new OperationalTelemetry(meters, (name, fields) -> { });
		telemetry.quotaRemaining(27); telemetry.unresolvedMarkers(2);
		telemetry.notionRequest(NotionOperation.READ, 429, Duration.ofMillis(80), 2);
		telemetry.importAdmission(true); telemetry.importAdmission(false);
		telemetry.schedulerFilter(UUID.randomUUID(), JobSource.HIRING_CAFE, UUID.randomUUID(),
			SchedulerOutcome.COMPLETED, Duration.ZERO, null, 8, 3);

		assertThat(meters.find("jobs.notion.quota.remaining").summary()).isNotNull();
		assertThat(meters.find("jobs.sync.markers.unresolved").summary()).isNotNull();
		assertThat(meters.get("jobs.notion.requests").tags("operation", "READ", "status", "4xx")
			.timer().count()).isOne();
		assertThat(meters.get("jobs.import.admissions").tag("decision", "ADMITTED").counter().count()).isOne();
		assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
			.allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("secret", "Backend Engineer", "notes")));
	}

	@Test
	void redactsUnboundedBoundaryReasonsAndHealthExposesOnlySanitizedState() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry(); List<Event> events = new ArrayList<>();
		OperationalTelemetry telemetry = new OperationalTelemetry(meters,
			(name, fields) -> events.add(new Event(name, fields)));
		telemetry.boundary(Boundary.NOTION, BoundaryStatus.DEGRADED,
			"token=super-secret full job title Backend Engineer");
		ExternalBoundaryHealthIndicator health = new ExternalBoundaryHealthIndicator(() -> true,
			new FeatureProperties(false, false), notion(), scheduler(), telemetry);

		assertThat(events.getFirst().fields().get("reasonCode")).isEqualTo("UNCLASSIFIED");
		assertThat(events.toString()).doesNotContain("super-secret", "Backend Engineer");
		assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");
		assertThat(health.health().getDetails()).containsOnlyKeys("externalBoundaries");
	}

	@Test
	void readinessFailsClosedForDatabaseOrConfigurationWithoutLeakingSecrets() {
		OperationalTelemetry telemetry = new OperationalTelemetry(new SimpleMeterRegistry(), (name, fields) -> { });
		ExternalBoundaryHealthIndicator databaseDown = new ExternalBoundaryHealthIndicator(() -> false,
			new FeatureProperties(false, false), notion(), scheduler(), telemetry);
		ExternalBoundaryHealthIndicator invalidConfig = new ExternalBoundaryHealthIndicator(() -> true,
			new FeatureProperties(true, false), new NotionProperties(URI.create("https://api.notion.com"),
				"2026-03-11", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(3)), scheduler(), telemetry);

		assertThat(databaseDown.health().getStatus().getCode()).isEqualTo("DOWN");
		assertThat(invalidConfig.health().getStatus().getCode()).isEqualTo("DOWN");
		assertThat(invalidConfig.health().getDetails().toString()).doesNotContain("token", "api.notion.com");
	}

	private static NotionProperties notion() {
		return new NotionProperties(URI.create("https://api.notion.com"), "2026-03-11", "token", "database",
			"data-source", Duration.ofSeconds(1), Duration.ofSeconds(3));
	}

	private static SchedulerProperties scheduler() {
		return new SchedulerProperties(false, false, Duration.ofHours(6), ZoneId.of("UTC"), Duration.ofSeconds(30));
	}

	private record Event(String name, Map<String, ?> fields) { }
}
