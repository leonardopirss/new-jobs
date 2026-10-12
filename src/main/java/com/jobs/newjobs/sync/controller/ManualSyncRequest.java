package com.jobs.newjobs.sync.controller;

import java.util.UUID;

import com.jobs.newjobs.filters.controller.FilterSpecificationRequest;
import com.jobs.newjobs.filters.controller.RankingPreferencesRequest;
import com.jobs.newjobs.jobs.domain.JobSource;

import jakarta.validation.Valid;

public record ManualSyncRequest(
	UUID savedFilterId,
	JobSource source,
	@Valid FilterSpecificationRequest specification,
	@Valid RankingPreferencesRequest rankingPreferences
) {
	public boolean usesSavedFilter() {
		return savedFilterId != null;
	}

	public void validateMode() {
		boolean anyTransientField = source != null || specification != null || rankingPreferences != null;
		boolean completeTransient = source != null && specification != null;
		if (usesSavedFilter() == anyTransientField || (!usesSavedFilter() && !completeTransient)) {
			throw new IllegalArgumentException(
				"exactly one of savedFilterId or transient source and specification is required");
		}
	}
}
