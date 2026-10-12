package com.jobs.newjobs.ranking.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public record RankingPreferences(
	SearchMode searchMode,
	List<String> requiredTechnologies,
	List<String> desiredTechnologies,
	List<String> acceptedSeniorities
) {
	public RankingPreferences {
		if (searchMode == null) {
			throw new IllegalArgumentException("searchMode is required");
		}
		requiredTechnologies = normalize(requiredTechnologies, "requiredTechnologies");
		desiredTechnologies = normalize(desiredTechnologies, "desiredTechnologies");
		acceptedSeniorities = normalize(acceptedSeniorities, "acceptedSeniorities");
		HashSet<String> overlap = new HashSet<>(requiredTechnologies);
		overlap.retainAll(desiredTechnologies);
		if (!overlap.isEmpty()) {
			throw new IllegalArgumentException("a technology cannot be both required and desired: " + overlap);
		}
	}

	private static List<String> normalize(List<String> values, String field) {
		if (values == null) {
			return List.of();
		}
		HashSet<String> seen = new HashSet<>();
		return values.stream().map(value -> {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(field + " must not contain blank values");
			}
			String normalized = value.trim().toLowerCase(Locale.ROOT);
			if (!seen.add(normalized)) {
				throw new IllegalArgumentException(field + " must contain unique values");
			}
			return normalized;
		}).toList();
	}
}
