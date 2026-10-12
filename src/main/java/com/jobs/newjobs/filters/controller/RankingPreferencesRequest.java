package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.ranking.domain.SearchMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RankingPreferencesRequest(
	@NotNull SearchMode searchMode,
	@NotNull @Size(max = 50) List<@NotBlank @Size(max = 80) String> requiredTechnologies,
	@NotNull @Size(max = 50) List<@NotBlank @Size(max = 80) String> desiredTechnologies,
	@NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> acceptedSeniorities
) {
	public RankingPreferences toDomain() {
		return new RankingPreferences(
			searchMode, requiredTechnologies, desiredTechnologies, acceptedSeniorities
		);
	}

	public static RankingPreferences effective(RankingPreferencesRequest request) {
		return request == null ? RankingPreferences.compatibilityProfile() : request.toDomain();
	}
}
