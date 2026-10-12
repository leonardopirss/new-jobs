package com.jobs.newjobs.sync.domain;

public enum SyncMarkerState {
	PREPARED,
	IN_FLIGHT,
	CONFIRMED,
	NOT_SENT,
	UNCERTAIN,
	CONFLICT;

	public boolean unresolved() {
		return this != CONFIRMED && this != NOT_SENT;
	}
}
