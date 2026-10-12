package com.jobs.newjobs.filters.application;

import java.util.List;

import com.jobs.newjobs.hiringcafe.application.DiscoveryWarning;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.ranking.domain.RankingPreferences;

public record RankedPreviewResult(
	List<RankedJob> jobs,
	RankingPreferences rankingPreferences,
	int discovered,
	int excludedIneligible,
	int providerReturned,
	Long providerTotalMatches,
	int excludedOutsidePublicationWindow,
	boolean complete,
	List<DiscoveryWarning> warnings
) {
	public RankedPreviewResult {
		jobs = List.copyOf(jobs);
		warnings = List.copyOf(warnings);
	}
}
