package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.hiringcafe.application.DiscoveryWarning;
import com.jobs.newjobs.filters.application.RankedPreviewResult;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.ranking.domain.RankingPreferences;

public record PreviewResponse(
	List<RankedJob> jobs,
	RankingPreferences rankingPreferences,
	PreviewSummary summary,
	List<DiscoveryWarning> warnings
) {
	static PreviewResponse from(RankedPreviewResult result) {
		return new PreviewResponse(
			result.jobs(),
			result.rankingPreferences(),
			new PreviewSummary(
				result.discovered(),
				result.jobs().size(),
				result.excludedIneligible(),
				result.providerReturned(),
				result.providerTotalMatches(),
				result.excludedOutsidePublicationWindow(),
				result.complete()
			),
			result.warnings()
		);
	}

	public record PreviewSummary(
		int discovered,
		int returned,
		int excludedIneligible,
		int providerReturned,
		Long providerTotalMatches,
		int excludedOutsidePublicationWindow,
		boolean complete
	) {
	}
}
