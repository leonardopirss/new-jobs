package com.jobs.newjobs.filters.domain;

import java.util.List;

public record ProviderFilterGroup(String name, MatchMode matchMode, List<String> values) {
	public ProviderFilterGroup {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("provider filter name is required");
		}
		name = name.trim();
		if (name.length() > 80) {
			throw new IllegalArgumentException("provider filter name must not exceed 80 characters");
		}
		if (matchMode == null) {
			throw new IllegalArgumentException("provider filter matchMode is required");
		}
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("provider filter values must contain at least one item");
		}
		if (values.size() > 50) {
			throw new IllegalArgumentException("provider filter values must contain at most 50 items");
		}
		values = values.stream().map(value -> {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("provider filter values must not be blank");
			}
			String normalized = value.trim();
			if (normalized.length() > 120) {
				throw new IllegalArgumentException("provider filter values must not exceed 120 characters");
			}
			return normalized;
		}).distinct().toList();
	}
}
