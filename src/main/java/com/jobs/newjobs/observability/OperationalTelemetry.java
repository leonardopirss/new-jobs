package com.jobs.newjobs.observability;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.sync.domain.SyncRunResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Component
public final class OperationalTelemetry {
	private static final Logger LOGGER = LoggerFactory.getLogger(OperationalTelemetry.class);
	private final MeterRegistry meters;
	private final EventSink events;
	private final Map<Boundary, BoundaryStatus> boundaries = new ConcurrentHashMap<>();

	@Autowired
	public OperationalTelemetry(MeterRegistry meters) {
		this(meters, (name, fields) -> LOGGER.info("event={} fields={}", name, fields));
	}

	OperationalTelemetry(MeterRegistry meters, EventSink events) {
		this.meters = meters;
		this.events = events;
		for (Boundary boundary : Boundary.values()) boundaries.put(boundary, BoundaryStatus.AVAILABLE);
	}

	public static OperationalTelemetry noop() {
		return new OperationalTelemetry(new SimpleMeterRegistry(), (name, fields) -> { });
	}

	public void schedulerCycle(SchedulerOutcome outcome, String timezone) {
		counter("jobs.scheduler.cycles", "outcome", outcome.name()).increment();
		event("scheduler_cycle", Map.of("outcome", outcome.name(), "timezone", bounded(timezone)));
	}

	public void schedulerFilter(UUID runId, JobSource source, UUID filterId, SchedulerOutcome outcome,
		Duration lockWait, SyncRunResult result, int eligible, int unknown) {
		counter("jobs.scheduler.filters", "source", source.name(), "outcome", outcome.name()).increment();
		Timer.builder("jobs.scheduler.lock.wait").tag("outcome", outcome.name())
			.register(meters).record(lockWait);
		DistributionSummary.builder("jobs.ranking.eligible").tag("source", source.name())
			.register(meters).record(Math.max(0, eligible));
		DistributionSummary.builder("jobs.ranking.unknown").tag("source", source.name())
			.register(meters).record(Math.max(0, unknown));
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("runId", runId); fields.put("source", source.name()); fields.put("filterId", filterId);
		fields.put("outcome", outcome.name()); fields.put("lockWaitMs", Math.max(0, lockWait.toMillis()));
		fields.put("eligible", Math.max(0, eligible)); fields.put("unknown", Math.max(0, unknown));
		if (result != null) {
			fields.put("requestId", result.requestId()); fields.put("status", result.status().name());
			fields.put("received", result.summary().received()); fields.put("created", result.summary().created());
			fields.put("updated", result.summary().updated()); fields.put("unchanged", result.summary().unchanged());
			fields.put("invalid", result.summary().invalid()); fields.put("conflicted", result.summary().conflicted());
			fields.put("uncertain", result.summary().uncertain());
			fields.put("notProcessed", result.summary().notProcessed());
		}
		event("scheduler_filter", fields);
	}

	public void importAdmission(boolean admitted) {
		counter("jobs.import.admissions", "decision", admitted ? "ADMITTED" : "REJECTED").increment();
	}

	public void quotaRemaining(int remaining) {
		DistributionSummary.builder("jobs.notion.quota.remaining").register(meters).record(Math.max(0, remaining));
	}

	public void unresolvedMarkers(int count) {
		DistributionSummary.builder("jobs.sync.markers.unresolved").register(meters).record(Math.max(0, count));
	}

	public void notionRequest(NotionOperation operation, Integer status, Duration latency, int retryCount) {
		String statusClass = status == null ? "transport" : (status / 100) + "xx";
		Timer.builder("jobs.notion.requests").tag("operation", operation.name()).tag("status", statusClass)
			.register(meters).record(latency);
		DistributionSummary.builder("jobs.notion.retries").tag("operation", operation.name())
			.register(meters).record(Math.max(0, retryCount));
	}

	public void boundary(Boundary boundary, BoundaryStatus status, String reasonCode) {
		boundaries.put(boundary, status);
		event("external_boundary", Map.of("boundary", boundary.name(), "status", status.name(),
			"reasonCode", stableReason(reasonCode)));
	}

	public BoundaryStatus boundaryStatus(Boundary boundary) { return boundaries.get(boundary); }

	private Counter counter(String name, String... tags) { return Counter.builder(name).tags(tags).register(meters); }
	private void event(String name, Map<String, ?> fields) { events.write(name, Map.copyOf(fields)); }

	private static String stableReason(String value) {
		return value != null && value.matches("[A-Z][A-Z0-9_.-]{0,63}") ? value : "UNCLASSIFIED";
	}

	private static String bounded(String value) {
		if (value == null) return "UNKNOWN";
		return value.substring(0, Math.min(value.length(), 64));
	}

	interface EventSink { void write(String eventName, Map<String, ?> fields); }
	public enum SchedulerOutcome { COMPLETED, PARTIAL, FAILED, LOCKED, MISFIRE, DISABLED, PROVIDER_DISABLED, SHUTTING_DOWN }
	public enum Boundary { NOTION, HIRING_CAFE }
	public enum BoundaryStatus { AVAILABLE, DEGRADED }
	public enum NotionOperation { READ, CREATE, UPDATE }
}
