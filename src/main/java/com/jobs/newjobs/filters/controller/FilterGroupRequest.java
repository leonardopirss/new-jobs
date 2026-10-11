package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.filters.domain.CommonFilterField;
import com.jobs.newjobs.filters.domain.FilterGroup;
import com.jobs.newjobs.filters.domain.FilterOperation;
import com.jobs.newjobs.filters.domain.MatchMode;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FilterGroupRequest(
	@NotNull CommonFilterField field,
	@NotNull FilterOperation operation,
	@NotNull MatchMode matchMode,
	@NotEmpty @Size(max = 50) List<@Size(max = 120) String> values
) {
	FilterGroup toDomain() {
		return new FilterGroup(field, operation, matchMode, values);
	}
}
