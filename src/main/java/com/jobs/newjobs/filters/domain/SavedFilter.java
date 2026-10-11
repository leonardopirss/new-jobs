package com.jobs.newjobs.filters.domain;

import java.time.Instant;
import java.util.UUID;

import com.jobs.newjobs.jobs.domain.JobSource;

public record SavedFilter(
	UUID id,
	String name,
	JobSource source,
	FilterSpecification specification,
	long version,
	Instant createdAt,
	Instant updatedAt
) {
}
