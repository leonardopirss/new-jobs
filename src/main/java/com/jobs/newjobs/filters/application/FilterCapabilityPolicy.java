package com.jobs.newjobs.filters.application;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.ProviderFilterGroup;
import com.jobs.newjobs.jobs.domain.JobSource;

@Component
public class FilterCapabilityPolicy {
	private static final Set<String> VERIFIED_HIRING_CAFE_FILTERS = Set.of(
		"search",
		"location",
		"workplace_types",
		"employment_types",
		"seniority_levels",
		"role_type",
		"min_salary",
		"salary_frequency",
		"posted_salary_only",
		"max_years_experience",
		"company_names",
		"exclude_company_names",
		"departments",
		"language_requirements",
		"benefits_and_perks"
	);

	public void validate(JobSource source, FilterSpecification specification) {
		if (source != JobSource.HIRING_CAFE) {
			throw new IllegalArgumentException(source + " is reserved but is not an active discovery source");
		}

		for (ProviderFilterGroup group : specification.providerSpecificFilters()) {
			if (!VERIFIED_HIRING_CAFE_FILTERS.contains(group.name())) {
				throw new IllegalArgumentException(
					"Provider-specific filter '" + group.name() + "' is not verified for HIRING_CAFE"
				);
			}
		}
	}
}
