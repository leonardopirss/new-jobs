package com.jobs.newjobs.sync.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.sync.domain.SyncSafetyMarker;

public interface SyncSafetyMarkerRepository {
	PrepareResult prepare(CreateIntent intent);

	SyncSafetyMarker markInFlight(UUID operationId, Instant at);

	SyncSafetyMarker confirm(UUID operationId, String notionPageId, Instant at);

	SyncSafetyMarker markNotSent(UUID operationId, String failureCode, Instant at);

	SyncSafetyMarker markUncertain(UUID operationId, String failureCode, Instant at);

	SyncSafetyMarker markConflict(UUID operationId, String failureCode, Instant at);

	Optional<SyncSafetyMarker> find(UUID operationId);

	List<SyncSafetyMarker> findUnresolved(String destinationId);

	record CreateIntent(UUID operationId, String destinationId, JobSource source, String externalJobId,
		URI canonicalUrl, Instant createdAt) {
		public CreateIntent {
			if (operationId == null || destinationId == null || destinationId.isBlank() || source == null
				|| ((externalJobId == null || externalJobId.isBlank()) && canonicalUrl == null) || createdAt == null) {
				throw new IllegalArgumentException("complete create intent identity is required");
			}
			externalJobId = externalJobId == null || externalJobId.isBlank() ? null : externalJobId.trim();
		}
	}

	sealed interface PrepareResult permits PrepareResult.Prepared, PrepareResult.Existing, PrepareResult.Conflict {
		record Prepared(SyncSafetyMarker marker) implements PrepareResult { }
		record Existing(SyncSafetyMarker marker) implements PrepareResult { }
		record Conflict(List<SyncSafetyMarker> markers) implements PrepareResult {
			public Conflict { markers = List.copyOf(markers); }
		}
	}
}
