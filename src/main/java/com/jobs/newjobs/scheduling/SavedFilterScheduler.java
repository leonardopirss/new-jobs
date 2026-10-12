package com.jobs.newjobs.scheduling;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.filters.application.PreviewService;
import com.jobs.newjobs.filters.application.RankedPreviewResult;
import com.jobs.newjobs.filters.application.SavedFilterService;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.observability.OperationalTelemetry;
import com.jobs.newjobs.observability.OperationalTelemetry.Boundary;
import com.jobs.newjobs.observability.OperationalTelemetry.BoundaryStatus;
import com.jobs.newjobs.observability.OperationalTelemetry.SchedulerOutcome;
import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown.WorkKind;
import com.jobs.newjobs.sync.application.SyncCoordinator;
import com.jobs.newjobs.sync.domain.SyncRunResult;

@Component
public final class SavedFilterScheduler {
	private final SchedulerProperties properties;
	private final SavedFilterService filters;
	private final PreviewService preview;
	private final SyncCoordinator coordinator;
	private final NotionProperties notion;
	private final PostgresFilterRunLock runLock;
	private final OperationalTelemetry telemetry;
	private final GracefulWorkShutdown lifecycle;
	private final AtomicBoolean cycleRunning = new AtomicBoolean();

	public SavedFilterScheduler(SchedulerProperties properties, SavedFilterService filters, PreviewService preview,
		SyncCoordinator coordinator, NotionProperties notion, PostgresFilterRunLock runLock,
		OperationalTelemetry telemetry, GracefulWorkShutdown lifecycle) {
		this.properties = properties; this.filters = filters; this.preview = preview; this.coordinator = coordinator;
		this.notion = notion; this.runLock = runLock; this.telemetry = telemetry; this.lifecycle = lifecycle;
	}

	@Scheduled(fixedDelayString = "${app.scheduler.interval:PT6H}",
		initialDelayString = "${app.scheduler.interval:PT6H}")
	public void scheduledTick() { runCycle(); }

	public void runCycle() {
		if (!properties.enabled()) { telemetry.schedulerCycle(SchedulerOutcome.DISABLED, properties.timezone().getId()); return; }
		if (!properties.providerAuthorized()) {
			telemetry.boundary(Boundary.HIRING_CAFE, BoundaryStatus.DEGRADED,
				"PROVIDER_AUTHORIZATION_REQUIRED");
			telemetry.schedulerCycle(SchedulerOutcome.PROVIDER_DISABLED, properties.timezone().getId()); return;
		}
		telemetry.boundary(Boundary.HIRING_CAFE, BoundaryStatus.AVAILABLE, "AUTHORIZED");
		if (!lifecycle.accepting()) {
			telemetry.schedulerCycle(SchedulerOutcome.SHUTTING_DOWN, properties.timezone().getId()); return;
		}
		if (!cycleRunning.compareAndSet(false, true)) {
			telemetry.schedulerCycle(SchedulerOutcome.MISFIRE, properties.timezone().getId()); return;
		}
		try {
			List<SavedFilter> snapshot = List.copyOf(filters.list());
			for (SavedFilter filter : snapshot) runFilter(filter);
			telemetry.schedulerCycle(SchedulerOutcome.COMPLETED, properties.timezone().getId());
		}
		finally { cycleRunning.set(false); }
	}

	private void runFilter(SavedFilter filter) {
		UUID runId = UUID.randomUUID();
		long started = System.nanoTime();
		var lease = runLock.tryAcquire(filter.id());
		Duration lockWait = Duration.ofNanos(System.nanoTime() - started);
		if (lease.isEmpty()) {
			telemetry.schedulerFilter(runId, filter.source(), filter.id(), SchedulerOutcome.LOCKED,
				lockWait, null, 0, 0);
			return;
		}
		try (PostgresFilterRunLock.Lease ignored = lease.get()) {
			var permit = lifecycle.begin(WorkKind.SCHEDULED_SYNC);
			if (permit.isEmpty()) {
				telemetry.schedulerFilter(runId, filter.source(), filter.id(), SchedulerOutcome.SHUTTING_DOWN,
					lockWait, null, 0, 0);
				return;
			}
			try (var ignoredPermit = permit.get()) {
				RankedPreviewResult ranked = preview.preview(
					filter.source(), filter.specification(), filter.rankingPreferences());
				int eligible = (int) ranked.jobs().stream()
					.filter(job -> job.eligibility().status() == EligibilityStatus.ELIGIBLE).count();
				int unknown = (int) ranked.jobs().stream()
					.filter(job -> job.eligibility().status() == EligibilityStatus.UNKNOWN).count();
				SyncRunResult result = coordinator.synchronize(notion.dataSourceId(), ranked.jobs());
				SchedulerOutcome outcome = result.status() == SyncRunResult.Status.COMPLETED
					? SchedulerOutcome.COMPLETED : SchedulerOutcome.PARTIAL;
				telemetry.schedulerFilter(runId, filter.source(), filter.id(), outcome,
					lockWait, result, eligible, unknown);
			}
			catch (RuntimeException exception) {
				telemetry.schedulerFilter(runId, filter.source(), filter.id(), SchedulerOutcome.FAILED,
					lockWait, null, 0, 0);
			}
		}
	}
}
