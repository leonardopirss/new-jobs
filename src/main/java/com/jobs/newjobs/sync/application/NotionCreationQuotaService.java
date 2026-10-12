package com.jobs.newjobs.sync.application;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.domain.NotionQuotaEvidence;
import com.jobs.newjobs.observability.OperationalTelemetry;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Service
public final class NotionCreationQuotaService {
	public static final int MAX_CREATIONS = 300;
	private static final Duration WINDOW = Duration.ofHours(24);

	private final NotionGateway notion;
	private final SyncSafetyMarkerRepository markers;
	private final Clock clock;
	private final OperationalTelemetry telemetry;

	@Autowired
	public NotionCreationQuotaService(NotionGateway notion, SyncSafetyMarkerRepository markers, Clock clock,
		OperationalTelemetry telemetry) {
		this.notion = notion;
		this.markers = markers;
		this.clock = clock;
		this.telemetry = telemetry;
	}

	public QuotaAvailability inspect(Lease lease) {
		lease.assertHeld();
		int unresolved = markers.findUnresolved(lease.destinationId()).size();
		telemetry.unresolvedMarkers(unresolved);
		if (unresolved > 0) {
			return new QuotaAvailability.Incomplete("UNRESOLVED_CREATE_MARKERS");
		}
		NotionGateway.QuotaResult result = notion.scanAutomationCreations(clock.instant().minus(WINDOW));
		if (result instanceof NotionGateway.QuotaResult.Incomplete) {
			return new QuotaAvailability.Incomplete("QUOTA_EVIDENCE_INCOMPLETE");
		}
		Set<String> distinctPages = new HashSet<>();
		for (NotionQuotaEvidence evidence : ((NotionGateway.QuotaResult.Complete) result).pages()) {
			distinctPages.add(evidence.pageId());
		}
		int used = distinctPages.size();
		int remaining = Math.max(0, MAX_CREATIONS - used);
		telemetry.quotaRemaining(remaining);
		return new QuotaAvailability.Available(used, remaining);
	}

	public sealed interface QuotaAvailability permits QuotaAvailability.Available, QuotaAvailability.Incomplete {
		record Available(int used, int remaining) implements QuotaAvailability { }
		record Incomplete(String reasonCode) implements QuotaAvailability { }
	}
}
