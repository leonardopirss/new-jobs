package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.filters.domain.MatchMode;
import com.jobs.newjobs.filters.domain.ProviderFilterGroup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProviderFilterGroupRequest(
	@NotBlank @Size(max = 80) String name,
	@NotNull MatchMode matchMode,
	@NotEmpty @Size(max = 50) List<@Size(max = 120) String> values
) {
	ProviderFilterGroup toDomain() {
		return new ProviderFilterGroup(name, matchMode, values);
	}
}
