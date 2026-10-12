package com.jobs.newjobs.notion.domain;

import java.time.Instant;
import java.util.UUID;

public record NotionQuotaEvidence(String pageId, UUID operationId, Instant firstSyncedAt, boolean archived) {
	public NotionQuotaEvidence {
		if (pageId == null || operationId == null || firstSyncedAt == null) {
			throw new IllegalArgumentException("complete quota evidence is required");
		}
	}
}
