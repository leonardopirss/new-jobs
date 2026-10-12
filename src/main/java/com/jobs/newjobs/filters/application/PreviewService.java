package com.jobs.newjobs.filters.application;

import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.hiringcafe.application.HiringCafeDiscoveryResult;
import com.jobs.newjobs.hiringcafe.application.HiringCafeDiscoveryService;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.ranking.application.JobRankingService;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.ranking.domain.RankingPreferences;

@Service
public class PreviewService {
	private final FilterCapabilityPolicy capabilityPolicy;
	private final HiringCafeDiscoveryService discoveryService;
	private final JobRankingService rankingService;
	private final JobIdentityCanonicalizer identityCanonicalizer;

	public PreviewService(
		FilterCapabilityPolicy capabilityPolicy,
		HiringCafeDiscoveryService discoveryService,
		JobRankingService rankingService,
		JobIdentityCanonicalizer identityCanonicalizer
	) {
		this.capabilityPolicy = capabilityPolicy;
		this.discoveryService = discoveryService;
		this.rankingService = rankingService;
		this.identityCanonicalizer = identityCanonicalizer;
	}

	public RankedPreviewResult preview(
		JobSource source,
		FilterSpecification specification,
		RankingPreferences rankingPreferences
	) {
		capabilityPolicy.validate(source, specification);
		HiringCafeDiscoveryResult discovery = discoveryService.discover(specification);
		LinkedHashMap<String, NormalizedJob> unique = new LinkedHashMap<>();
		for (NormalizedJob job : discovery.jobs()) {
			unique.putIfAbsent(identityCanonicalizer.stableKey(job), job);
		}
		List<RankedJob> ranked = rankingService.rankJobs(List.copyOf(unique.values()), rankingPreferences);
		return new RankedPreviewResult(
			ranked,
			rankingPreferences,
			discovery.jobs().size(),
			unique.size() - ranked.size(),
			discovery.providerReturned(),
			discovery.providerTotalMatches(),
			discovery.excludedOutsidePublicationWindow(),
			discovery.complete(),
			discovery.warnings()
		);
	}
}
