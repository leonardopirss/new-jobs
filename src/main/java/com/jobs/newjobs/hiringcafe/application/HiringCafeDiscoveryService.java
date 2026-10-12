package com.jobs.newjobs.hiringcafe.application;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.hiringcafe.config.HiringCafeProperties;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;

@Service
public class HiringCafeDiscoveryService {
	private static final Duration PUBLICATION_WINDOW = Duration.ofHours(24);

	private final HiringCafeGateway gateway;
	private final HiringCafeQueryFactory queryFactory;
	private final HiringCafeProperties properties;
	private final Clock clock;

	public HiringCafeDiscoveryService(
		HiringCafeGateway gateway,
		HiringCafeQueryFactory queryFactory,
		HiringCafeProperties properties,
		Clock clock
	) {
		this.gateway = gateway;
		this.queryFactory = queryFactory;
		this.properties = properties;
		this.clock = clock;
	}

	public HiringCafeDiscoveryResult discover(FilterSpecification specification) {
		HiringCafeProviderPage page = gateway.search(queryFactory.create(specification));
		Instant cutoff = clock.instant().minus(PUBLICATION_WINDOW);
		List<NormalizedJob> jobs = new ArrayList<>();
		int excluded = 0;
		int invalid = 0;

		for (HiringCafeProviderJob providerJob : page.jobs()) {
			try {
				Instant publishedAt = Instant.parse(providerJob.datePosted());
				if (publishedAt.isBefore(cutoff)) {
					excluded++;
					continue;
				}
				jobs.add(normalize(providerJob, publishedAt));
			}
			catch (DateTimeException | NullPointerException | IllegalArgumentException exception) {
				invalid++;
				excluded++;
			}
		}

		List<DiscoveryWarning> warnings = new ArrayList<>();
		warnings.add(new DiscoveryWarning(
			"PROVIDER_PAGINATION_UNAVAILABLE",
			"The verified HiringCafe MCP contract exposes no cursor or page parameter; results may be partial"
		));
		warnings.add(new DiscoveryWarning(
			"PROVIDER_ONE_RESULT_PER_COMPANY",
			"HiringCafe documents that search returns at most one job per company"
		));
		if (page.totalMatches() != null && page.totalMatches() > page.count()) {
			warnings.add(new DiscoveryWarning(
				"PROVIDER_RESULT_LIMIT",
				"HiringCafe returned " + page.count() + " of " + page.totalMatches() + " matching jobs"
			));
		}
		else if (page.count() >= properties.resultLimit()) {
			warnings.add(new DiscoveryWarning(
				"PROVIDER_RESULT_LIMIT",
				"HiringCafe reached the configured result limit of " + properties.resultLimit()
			));
		}
		if (invalid > 0) {
			warnings.add(new DiscoveryWarning(
				"INVALID_PROVIDER_JOB",
				invalid + " provider job(s) were excluded because publication time or URL was invalid"
			));
		}

		return new HiringCafeDiscoveryResult(
			jobs, page.jobs().size(), page.totalMatches(), excluded, false, warnings
		);
	}

	private static NormalizedJob normalize(HiringCafeProviderJob job, Instant publishedAt) {
		return new NormalizedJob(
			JobSource.HIRING_CAFE,
			job.id(),
			job.title(),
			job.company(),
			job.location(),
			job.workplaceType(),
			job.employmentTypes(),
			job.compensation(),
			publishedAt,
			job.requirementsSummary(),
			job.seniorityLevel(),
			job.minimumYearsExperience(),
			job.responsibilities(),
			job.technologies(),
			URI.create(job.applyUrl())
		);
	}
}
