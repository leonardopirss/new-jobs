package com.jobs.newjobs.ranking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.ranking.domain.GeographicEvidence;
import com.jobs.newjobs.ranking.domain.RankingCandidate;
import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.ranking.domain.ResidenceRequirement;
import com.jobs.newjobs.ranking.domain.SearchMode;
import com.jobs.newjobs.ranking.domain.SupportEvidence;
import com.jobs.newjobs.ranking.domain.WorkAuthorization;
import com.jobs.newjobs.ranking.domain.WorkModel;

class JobRankingGoldenTests {
	private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
	private final JobRankingService service = new JobRankingService(
		new GeographicEligibilityClassifier(),
		new TechnologyEvidenceExtractor(),
		Clock.fixed(NOW, ZoneOffset.UTC),
		new JobIdentityCanonicalizer()
	);

	@Test
	void goldenFixtureRanksByEligibilityThenWeightedScoreAndExplainsDefaults() {
		RankingPreferences preferences = new RankingPreferences(
			SearchMode.REMOTE_FROM_BRAZIL, List.of("Java"), List.of("K8s"), List.of("Senior")
		);
		List<RankingCandidate> fixture = List.of(
			candidate("unknown-perfect", NOW, "Senior", List.of("Java", "Kubernetes"), unknownGeo()),
			candidate("eligible-80357", NOW.minusSeconds(6 * 3600), "Senior", List.of("Java"), eligibleGeo()),
			candidate("ineligible", NOW, "Senior", List.of("Java", "Kubernetes"), ineligibleGeo())
		);

		var ranked = service.rank(fixture, preferences);

		assertThat(ranked).extracting(result -> result.job().externalJobId())
			.containsExactly("eligible-80357", "unknown-perfect");
		assertThat(ranked).extracting(result -> result.eligibility().status())
			.containsExactly(EligibilityStatus.ELIGIBLE, EligibilityStatus.UNKNOWN);
		assertThat(ranked.getFirst().score().total())
			.isCloseTo(new BigDecimal("80.35714285714285714285714285714286"), within(new BigDecimal("1E-30")));
		assertThat(ranked.getFirst().score().geographyPoints()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(ranked.getFirst().explanations())
			.anyMatch(value -> value.contains("30 points are redistributed"))
			.anyMatch(value -> value.contains("linear 24-hour decay"));
	}

	@Test
	void goldenFixtureAppliesPublicationAndCanonicalIdentityTieBreakersDeterministically() {
		RankingPreferences preferences = new RankingPreferences(
			SearchMode.REMOTE_FROM_BRAZIL, List.of(), List.of(), List.of()
		);
		List<RankingCandidate> fixture = List.of(
			candidate("b", NOW.minusSeconds(3600), null, List.of(), eligibleGeo()),
			candidate("old", NOW.minusSeconds(7200), null, List.of(), eligibleGeo()),
			candidate("a", NOW.minusSeconds(3600), null, List.of(), eligibleGeo())
		);

		assertThat(service.rank(fixture, preferences)).extracting(result -> result.job().externalJobId())
			.containsExactly("a", "b", "old");
	}

	@Test
	void goldenFixtureUsesTechnologyPointsWhenTotalsTie() {
		RankingPreferences preferences = new RankingPreferences(
			SearchMode.REMOTE_FROM_BRAZIL, List.of("Java"), List.of("K8s"), List.of("Senior")
		);
		RankingCandidate technologyFirst = candidate(
			"technology-first", NOW.minusSeconds(24 * 3600), "Junior", List.of("Java"), null, eligibleGeo()
		);
		RankingCandidate seniorityCompensates = candidate(
			"seniority-compensates", NOW.minusSeconds(24 * 3600), "Senior", List.of(), "Java", eligibleGeo()
		);

		var ranked = service.rank(List.of(seniorityCompensates, technologyFirst), preferences);

		assertThat(ranked.get(0).score().total()).isEqualByComparingTo(ranked.get(1).score().total());
		assertThat(ranked).extracting(result -> result.job().externalJobId())
			.containsExactly("technology-first", "seniority-compensates");
	}

	@Test
	void goldenFixtureUsesSeniorityPointsBeforePublicationWhenTotalsTie() {
		RankingPreferences preferences = new RankingPreferences(
			SearchMode.REMOTE_FROM_BRAZIL, List.of(), List.of(), List.of("Senior")
		);
		RankingCandidate seniorOld = candidate(
			"senior-old", NOW.minusSeconds(24 * 3600), "Senior", List.of(), null, eligibleGeo()
		);
		RankingCandidate juniorNew = candidate(
			"junior-new", NOW, "Junior", List.of(), null, eligibleGeo()
		);

		var ranked = service.rank(List.of(juniorNew, seniorOld), preferences);

		assertThat(ranked.get(0).score().total()).isEqualByComparingTo(ranked.get(1).score().total());
		assertThat(ranked).extracting(result -> result.job().externalJobId())
			.containsExactly("senior-old", "junior-new");
	}

	private static RankingCandidate candidate(
		String id, Instant publishedAt, String seniority, List<String> technologies, GeographicEvidence geo
	) {
		return candidate(id, publishedAt, seniority, technologies, null, geo);
	}

	private static RankingCandidate candidate(
		String id,
		Instant publishedAt,
		String seniority,
		List<String> technologies,
		String requirements,
		GeographicEvidence geo
	) {
		NormalizedJob job = new NormalizedJob(
			JobSource.HIRING_CAFE, id, "Engineer", "Example", "Remote", "Remote",
			List.of("Full Time"), null, publishedAt, requirements, seniority, null, List.of(), technologies,
			URI.create("https://example.test/jobs/" + id)
		);
		return new RankingCandidate(job, geo);
	}

	private static GeographicEvidence eligibleGeo() {
		return geo(Set.of("brazil"), false, ResidenceRequirement.UNKNOWN);
	}

	private static GeographicEvidence unknownGeo() {
		return geo(Set.of(), false, ResidenceRequirement.UNKNOWN);
	}

	private static GeographicEvidence ineligibleGeo() {
		return geo(Set.of("united states"), false, ResidenceRequirement.OUTSIDE_BRAZIL);
	}

	private static GeographicEvidence geo(
		Set<String> countries, boolean worldwide, ResidenceRequirement residence
	) {
		return new GeographicEvidence(
			WorkModel.REMOTE, countries, worldwide, residence, SupportEvidence.UNKNOWN,
			SupportEvidence.UNKNOWN, WorkAuthorization.UNKNOWN
		);
	}
}
