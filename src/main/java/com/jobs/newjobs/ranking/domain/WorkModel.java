package com.jobs.newjobs.ranking.domain;

import java.util.Locale;

public enum WorkModel {
	REMOTE,
	HYBRID,
	ONSITE,
	UNKNOWN;

	public static WorkModel from(String value) {
		if (value == null || value.isBlank()) {
			return UNKNOWN;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.contains("remote")) {
			return REMOTE;
		}
		if (normalized.contains("hybrid")) {
			return HYBRID;
		}
		if (normalized.contains("on-site") || normalized.contains("onsite") || normalized.contains("office")) {
			return ONSITE;
		}
		return UNKNOWN;
	}
}
