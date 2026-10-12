package com.jobs.newjobs.sync.domain;

public record SyncItemOutcome(
	int index,
	SyncItemStatus status,
	String externalJobId,
	String notionPageId,
	String reasonCode
) {
	public SyncItemOutcome {
		if (index < 0 || status == null) {
			throw new IllegalArgumentException("item index and status are required");
		}
		if (reasonCode != null && reasonCode.length() > 64) {
			throw new IllegalArgumentException("reason code must not exceed 64 characters");
		}
	}
}
