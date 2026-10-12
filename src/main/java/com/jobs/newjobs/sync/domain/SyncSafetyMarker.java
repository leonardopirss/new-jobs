package com.jobs.newjobs.sync.domain;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.jobs.newjobs.jobs.domain.JobSource;

public record SyncSafetyMarker(
	UUID operationId,
	String destinationId,
	JobSource source,
	String externalJobId,
	URI canonicalUrl,
	String externalIdHash,
	String canonicalUrlHash,
	SyncMarkerState state,
	String notionPageId,
	String failureCode,
	Instant createdAt,
	Instant updatedAt,
	Instant resolvedAt
) {
	public SyncSafetyMarker {
		if (operationId == null || destinationId == null || destinationId.isBlank() || source == null
			|| ((externalJobId == null || externalJobId.isBlank()) && canonicalUrl == null)
			|| state == null || createdAt == null || updatedAt == null) {
			throw new IllegalArgumentException("complete synchronization marker identity and state are required");
		}
	}
}
