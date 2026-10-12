package com.jobs.newjobs.jobs.domain;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public record NormalizedJob(
	JobSource source,
	String externalJobId,
	String title,
	String company,
	String location,
	String workplaceType,
	List<String> employmentTypes,
	JobCompensation compensation,
	Instant publishedAt,
	String requirementsSummary,
	String seniorityLevel,
	BigDecimal minimumYearsExperience,
	List<String> responsibilities,
	List<String> technologies,
	URI jobUrl
) {
	public NormalizedJob {
		employmentTypes = employmentTypes == null ? List.of() : List.copyOf(employmentTypes);
		responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
		technologies = technologies == null ? List.of() : List.copyOf(technologies);
	}
}
