package com.jobs.newjobs.sync.controller;

import java.util.List;
import java.util.UUID;

import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncRunResult;
import com.jobs.newjobs.sync.domain.SyncSummary;

public record SyncResponse(
	UUID requestId,
	SyncRunResult.Status status,
	SyncSummary summary,
	List<SyncItemOutcome> items
) {
	public static SyncResponse from(SyncRunResult result) {
		return new SyncResponse(result.requestId(), result.status(), result.summary(), result.items());
	}
}
