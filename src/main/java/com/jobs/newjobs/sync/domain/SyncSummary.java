package com.jobs.newjobs.sync.domain;

import java.util.List;

public record SyncSummary(
	int received,
	int created,
	int updated,
	int unchanged,
	int invalid,
	int conflicted,
	int uncertain,
	int notProcessed
) {
	public static SyncSummary from(List<SyncItemOutcome> items) {
		return new SyncSummary(
			items.size(),
			count(items, SyncItemStatus.CREATED),
			count(items, SyncItemStatus.UPDATED),
			count(items, SyncItemStatus.UNCHANGED),
			count(items, SyncItemStatus.INVALID),
			count(items, SyncItemStatus.CONFLICTED),
			count(items, SyncItemStatus.UNCERTAIN),
			count(items, SyncItemStatus.NOT_PROCESSED)
		);
	}

	public boolean complete() {
		return invalid + conflicted + uncertain + notProcessed == 0;
	}

	private static int count(List<SyncItemOutcome> items, SyncItemStatus status) {
		return (int) items.stream().filter(item -> item.status() == status).count();
	}
}
