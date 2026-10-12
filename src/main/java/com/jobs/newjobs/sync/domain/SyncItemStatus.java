package com.jobs.newjobs.sync.domain;

public enum SyncItemStatus {
	CREATED,
	UPDATED,
	UNCHANGED,
	INVALID,
	CONFLICTED,
	UNCERTAIN,
	NOT_PROCESSED
}
