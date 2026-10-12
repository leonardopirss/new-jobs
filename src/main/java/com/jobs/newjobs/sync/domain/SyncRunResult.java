package com.jobs.newjobs.sync.domain;

import java.util.List;
import java.util.UUID;

public record SyncRunResult(
	UUID requestId,
	Status status,
	SyncSummary summary,
	List<SyncItemOutcome> items
) {
	public SyncRunResult {
		items = List.copyOf(items);
	}

	public static SyncRunResult from(UUID requestId, List<SyncItemOutcome> items) {
		SyncSummary summary = SyncSummary.from(items);
		return new SyncRunResult(requestId, summary.complete() ? Status.COMPLETED : Status.PARTIAL, summary, items);
	}

	public enum Status { COMPLETED, PARTIAL }
}
