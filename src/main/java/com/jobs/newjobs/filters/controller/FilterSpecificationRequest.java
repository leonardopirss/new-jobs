package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.filters.domain.FilterSpecification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record FilterSpecificationRequest(
	@Size(max = 30) List<@Valid FilterGroupRequest> commonFilters,
	@Size(max = 30) List<@Valid ProviderFilterGroupRequest> providerSpecificFilters
) {
	public FilterSpecification toDomain() {
		return new FilterSpecification(
			commonFilters == null ? List.of() : commonFilters.stream().map(FilterGroupRequest::toDomain).toList(),
			providerSpecificFilters == null
				? List.of()
				: providerSpecificFilters.stream().map(ProviderFilterGroupRequest::toDomain).toList()
		);
	}
}
