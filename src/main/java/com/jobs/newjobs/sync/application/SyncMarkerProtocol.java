package com.jobs.newjobs.sync.application;

import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SYNC_OPERATION_ID;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPageSnapshot;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository.CreateIntent;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository.PrepareResult;
import com.jobs.newjobs.sync.domain.SyncSafetyMarker;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.DestinationLockLostException;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Service
public final class SyncMarkerProtocol {
	private final SyncSafetyMarkerRepository markers;
	private final NotionGateway notion;
	private final Clock clock;

	@Autowired
	public SyncMarkerProtocol(SyncSafetyMarkerRepository markers, NotionGateway notion) {
		this(markers, notion, Clock.systemUTC());
	}

	public SyncMarkerProtocol(SyncSafetyMarkerRepository markers, NotionGateway notion, Clock clock) {
		this.markers = markers;
		this.notion = notion;
		this.clock = clock;
	}

	public CreateOutcome create(Lease lease, CreateIntent intent, NotionPageDraft draft) {
		assertDestination(lease, intent.destinationId());
		lease.assertHeld();
		PrepareResult prepared = markers.prepare(intent);
		if (prepared instanceof PrepareResult.Existing existing) {
			return new CreateOutcome.Existing(existing.marker());
		}
		if (prepared instanceof PrepareResult.Conflict conflict) {
			return new CreateOutcome.Conflict(conflict.markers());
		}

		SyncSafetyMarker marker = ((PrepareResult.Prepared) prepared).marker();
		markers.markInFlight(marker.operationId(), now());
		try {
			lease.assertHeld();
		}
		catch (DestinationLockLostException lost) {
			SyncSafetyMarker notSent = markers.markNotSent(marker.operationId(), "LOCK_LOST_BEFORE_SEND", now());
			return new CreateOutcome.NotSent(notSent);
		}

		NotionGateway.CreateResult result = notion.createPage(draft, marker.operationId());
		return switch (result) {
			case NotionGateway.CreateResult.Confirmed confirmed ->
				new CreateOutcome.Confirmed(markers.confirm(marker.operationId(), confirmed.pageId(), now()));
			case NotionGateway.CreateResult.ReliablyNotSent notSent ->
				new CreateOutcome.NotSent(markers.markNotSent(marker.operationId(), notSent.failure().code().name(), now()));
			case NotionGateway.CreateResult.Ambiguous ambiguous ->
				new CreateOutcome.Uncertain(markers.markUncertain(marker.operationId(), ambiguous.failure().code().name(), now()));
		};
	}

	public ReconciliationOutcome reconcile(Lease lease, SyncSafetyMarker marker,
		NotionGateway.IdentityResult identity) {
		assertDestination(lease, marker.destinationId());
		lease.assertHeld();
		return switch (identity) {
			case NotionGateway.IdentityResult.One one -> reconcileOne(marker, one.page());
			case NotionGateway.IdentityResult.Conflict conflict -> new ReconciliationOutcome.Conflict(
				markers.markConflict(marker.operationId(), "IDENTITY_CONFLICT", now()), conflict.pages());
			case NotionGateway.IdentityResult.None ignored -> new ReconciliationOutcome.Unresolved(marker,
				"negative identity lookup is not proof of non-delivery");
			case NotionGateway.IdentityResult.Incomplete incomplete -> new ReconciliationOutcome.Unresolved(marker,
				"identity visibility is incomplete: " + incomplete.reason());
		};
	}

	private ReconciliationOutcome reconcileOne(SyncSafetyMarker marker, NotionPageSnapshot page) {
		String operationId = switch (page.properties().get(SYNC_OPERATION_ID)) {
			case NotionPropertyValue.Text text -> text.value();
			default -> null;
		};
		if (marker.operationId().toString().equals(operationId)) {
			return new ReconciliationOutcome.Confirmed(markers.confirm(marker.operationId(), page.pageId(), now()));
		}
		return new ReconciliationOutcome.Conflict(
			markers.markConflict(marker.operationId(), "OPERATION_ID_MISMATCH", now()), List.of(page));
	}

	private static void assertDestination(Lease lease, String destinationId) {
		if (!lease.destinationId().equals(destinationId)) {
			throw new IllegalArgumentException("destination lock does not protect this marker");
		}
	}

	private Instant now() {
		return clock.instant();
	}

	public sealed interface CreateOutcome permits CreateOutcome.Confirmed, CreateOutcome.NotSent,
		CreateOutcome.Uncertain, CreateOutcome.Existing, CreateOutcome.Conflict {
		record Confirmed(SyncSafetyMarker marker) implements CreateOutcome { }
		record NotSent(SyncSafetyMarker marker) implements CreateOutcome { }
		record Uncertain(SyncSafetyMarker marker) implements CreateOutcome { }
		record Existing(SyncSafetyMarker marker) implements CreateOutcome { }
		record Conflict(List<SyncSafetyMarker> markers) implements CreateOutcome {
			public Conflict { markers = List.copyOf(markers); }
		}
	}

	public sealed interface ReconciliationOutcome permits ReconciliationOutcome.Confirmed,
		ReconciliationOutcome.Conflict, ReconciliationOutcome.Unresolved {
		record Confirmed(SyncSafetyMarker marker) implements ReconciliationOutcome { }
		record Conflict(SyncSafetyMarker marker, List<NotionPageSnapshot> pages) implements ReconciliationOutcome {
			public Conflict { pages = List.copyOf(pages); }
		}
		record Unresolved(SyncSafetyMarker marker, String reason) implements ReconciliationOutcome { }
	}
}
