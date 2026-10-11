package com.jobs.newjobs.hiringcafe.application;

import java.util.List;

import com.jobs.newjobs.jobs.domain.NormalizedJob;

public record HiringCafeDiscoveryResult(
	List<NormalizedJob> jobs,
	int providerReturned,
	Long providerTotalMatches,
	int excludedOutsidePublicationWindow,
	boolean complete,
	List<DiscoveryWarning> warnings
) {
	public HiringCafeDiscoveryResult {
		jobs = List.copyOf(jobs);
		warnings = List.copyOf(warnings);
	}
}
