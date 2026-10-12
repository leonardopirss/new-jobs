package com.jobs.newjobs.filters.controller;

import java.util.List;

import com.jobs.newjobs.hiringcafe.application.DiscoveryWarning;
import com.jobs.newjobs.hiringcafe.application.HiringCafeDiscoveryResult;
import com.jobs.newjobs.jobs.domain.NormalizedJob;

public record PreviewResponse(
	List<NormalizedJob> jobs,
	PreviewSummary summary,
	List<DiscoveryWarning> warnings
) {
	static PreviewResponse from(HiringCafeDiscoveryResult result) {
		return new PreviewResponse(
			result.jobs(),
			new PreviewSummary(
				result.jobs().size(),
				result.providerReturned(),
				result.providerTotalMatches(),
				result.excludedOutsidePublicationWindow(),
				result.complete()
			),
			result.warnings()
		);
	}

	public record PreviewSummary(
		int returned,
		int providerReturned,
		Long providerTotalMatches,
		int excludedOutsidePublicationWindow,
		boolean complete
	) {
	}
}
