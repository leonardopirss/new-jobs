package com.jobs.newjobs.hiringcafe.application;

import java.math.BigDecimal;
import java.util.List;

import com.jobs.newjobs.jobs.domain.JobCompensation;

public record HiringCafeProviderJob(
	String id,
	String title,
	String company,
	String location,
	String workplaceType,
	List<String> employmentTypes,
	JobCompensation compensation,
	String datePosted,
	String requirementsSummary,
	String seniorityLevel,
	BigDecimal minimumYearsExperience,
	List<String> responsibilities,
	List<String> technologies,
	String applyUrl
) {
}
