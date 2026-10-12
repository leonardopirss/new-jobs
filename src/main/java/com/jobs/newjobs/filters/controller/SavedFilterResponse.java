package com.jobs.newjobs.filters.controller;

import java.time.Instant;
import java.util.UUID;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.ranking.domain.RankingPreferences;

public record SavedFilterResponse(
	UUID id,
	String name,
	JobSource source,
	FilterSpecification specification,
	RankingPreferences rankingPreferences,
	long version,
	Instant createdAt,
	Instant updatedAt
) {
	static SavedFilterResponse from(SavedFilter filter) {
		return new SavedFilterResponse(
			filter.id(), filter.name(), filter.source(), filter.specification(), filter.rankingPreferences(),
			filter.version(),
			filter.createdAt(), filter.updatedAt()
		);
	}
}
