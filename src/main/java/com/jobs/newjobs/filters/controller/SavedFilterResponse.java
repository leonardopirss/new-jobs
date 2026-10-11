package com.jobs.newjobs.filters.controller;

import java.time.Instant;
import java.util.UUID;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.jobs.domain.JobSource;

public record SavedFilterResponse(
	UUID id,
	String name,
	JobSource source,
	FilterSpecification specification,
	long version,
	Instant createdAt,
	Instant updatedAt
) {
	static SavedFilterResponse from(SavedFilter filter) {
		return new SavedFilterResponse(
			filter.id(), filter.name(), filter.source(), filter.specification(), filter.version(),
			filter.createdAt(), filter.updatedAt()
		);
	}
}
