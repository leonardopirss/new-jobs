package com.jobs.newjobs.sync.application;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.domain.NotionQuotaEvidence;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Service
public final class NotionCreationQuotaService {
	public static final int MAX_CREATIONS = 300;
	private static final Duration WINDOW = Duration.ofHours(24);

	private final NotionGateway notion;
	private final SyncSafetyMarkerRepository markers;
	private final Clock clock;

	@Autowired
	public NotionCreationQuotaService(NotionGateway notion, SyncSafetyMarkerRepository markers, Clock clock) {
		this.notion = notion;
		this.markers = markers;
		this.clock = clock;
	}

	public QuotaAvailability inspect(Lease lease) {
		lease.assertHeld();
		if (!markers.findUnresolved(lease.destinationId()).isEmpty()) {
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
		return new QuotaAvailability.Available(used, Math.max(0, MAX_CREATIONS - used));
	}

	public sealed interface QuotaAvailability permits QuotaAvailability.Available, QuotaAvailability.Incomplete {
		record Available(int used, int remaining) implements QuotaAvailability { }
		record Incomplete(String reasonCode) implements QuotaAvailability { }
	}
}
