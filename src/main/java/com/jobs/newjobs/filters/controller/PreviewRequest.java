package com.jobs.newjobs.filters.controller;

import com.jobs.newjobs.jobs.domain.JobSource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PreviewRequest(
	@NotNull JobSource source,
	@NotNull @Valid FilterSpecificationRequest specification,
	@Valid RankingPreferencesRequest rankingPreferences
) {
}
