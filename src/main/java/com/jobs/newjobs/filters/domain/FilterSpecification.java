package com.jobs.newjobs.filters.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record FilterSpecification(
	List<FilterGroup> commonFilters,
	List<ProviderFilterGroup> providerSpecificFilters
) {
	public FilterSpecification {
		commonFilters = commonFilters == null ? List.of() : List.copyOf(commonFilters);
		providerSpecificFilters = providerSpecificFilters == null ? List.of() : List.copyOf(providerSpecificFilters);
		if (commonFilters.size() > 30) {
			throw new IllegalArgumentException("commonFilters must contain at most 30 groups");
		}
		if (providerSpecificFilters.size() > 30) {
			throw new IllegalArgumentException("providerSpecificFilters must contain at most 30 groups");
		}

		Set<String> commonKeys = new HashSet<>();
		for (FilterGroup group : commonFilters) {
			String key = group.field() + ":" + group.operation();
			if (!commonKeys.add(key)) {
				throw new IllegalArgumentException("common filter groups must be unique by field and operation");
			}
		}

		Set<String> providerKeys = new HashSet<>();
		for (ProviderFilterGroup group : providerSpecificFilters) {
			if (!providerKeys.add(group.name().toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("provider filter names must be unique ignoring case");
			}
		}
	}
}
