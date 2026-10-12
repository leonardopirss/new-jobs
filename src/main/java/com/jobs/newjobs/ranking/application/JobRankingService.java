package com.jobs.newjobs.ranking.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.ranking.domain.EligibilityAssessment;
import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.ranking.domain.RankingCandidate;
import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.ranking.domain.RankingScore;
import com.jobs.newjobs.ranking.domain.TechnologyAssessment;

@Service
public class JobRankingService {
	private static final BigDecimal TECHNOLOGY_BASE_WEIGHT = new BigDecimal("40");
	private static final BigDecimal SENIORITY_BASE_WEIGHT = new BigDecimal("15");
	private static final BigDecimal RECENCY_BASE_WEIGHT = new BigDecimal("15");
	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
	private static final BigDecimal REQUIRED_SHARE = new BigDecimal("0.75");
	private static final BigDecimal DESIRED_SHARE = new BigDecimal("0.25");
	private static final Duration RECENCY_WINDOW = Duration.ofHours(24);
	private static final MathContext MATH = MathContext.DECIMAL128;

	private final GeographicEligibilityClassifier eligibilityClassifier;
	private final TechnologyEvidenceExtractor evidenceExtractor;
	private final Clock clock;
	private final JobIdentityCanonicalizer identityCanonicalizer;

	public JobRankingService(
		GeographicEligibilityClassifier eligibilityClassifier,
		TechnologyEvidenceExtractor evidenceExtractor,
		Clock clock,
		JobIdentityCanonicalizer identityCanonicalizer
	) {
		this.eligibilityClassifier = eligibilityClassifier;
		this.evidenceExtractor = evidenceExtractor;
		this.clock = clock;
		this.identityCanonicalizer = identityCanonicalizer;
	}

	public List<RankedJob> rank(List<RankingCandidate> candidates, RankingPreferences preferences) {
		Objects.requireNonNull(candidates, "candidates are required");
		Objects.requireNonNull(preferences, "preferences are required");
		validateCanonicalTechnologySets(preferences);
		Instant now = clock.instant();
		return candidates.stream()
			.map(candidate -> rank(candidate, preferences, now))
			.filter(ranked -> ranked.eligibility().status() != EligibilityStatus.INELIGIBLE)
			.sorted(order())
			.toList();
	}

	public List<RankedJob> rankJobs(List<NormalizedJob> jobs, RankingPreferences preferences) {
		Objects.requireNonNull(jobs, "jobs are required");
		return rank(jobs.stream().map(RankingCandidate::from).toList(), preferences);
	}

	private RankedJob rank(RankingCandidate candidate, RankingPreferences preferences, Instant now) {
		EligibilityAssessment eligibility = eligibilityClassifier.classify(
			preferences.searchMode(), candidate.geographicEvidence()
		);
		List<String> requested = new ArrayList<>(preferences.requiredTechnologies());
		requested.addAll(preferences.desiredTechnologies());
		List<TechnologyAssessment> technologyEvidence = evidenceExtractor.assess(candidate.job(), requested);
		BigDecimal technologyRatio = technologyRatio(technologyEvidence, preferences);
		BigDecimal seniorityRatio = seniorityRatio(candidate.job(), preferences.acceptedSeniorities());
		BigDecimal recencyRatio = recencyRatio(candidate.job().publishedAt(), now);

		boolean technologyActive = !requested.isEmpty();
		boolean seniorityActive = !preferences.acceptedSeniorities().isEmpty();
		BigDecimal activeBaseWeight = RECENCY_BASE_WEIGHT
			.add(technologyActive ? TECHNOLOGY_BASE_WEIGHT : BigDecimal.ZERO)
			.add(seniorityActive ? SENIORITY_BASE_WEIGHT : BigDecimal.ZERO);
		BigDecimal technologyPoints = technologyActive
			? points(technologyRatio, TECHNOLOGY_BASE_WEIGHT, activeBaseWeight)
			: BigDecimal.ZERO;
		BigDecimal seniorityPoints = seniorityActive
			? points(seniorityRatio, SENIORITY_BASE_WEIGHT, activeBaseWeight)
			: BigDecimal.ZERO;
		BigDecimal recencyPoints = points(recencyRatio, RECENCY_BASE_WEIGHT, activeBaseWeight);
		BigDecimal total = normalized(technologyPoints.add(seniorityPoints).add(recencyPoints));
		RankingScore score = new RankingScore(
			total, normalized(technologyPoints), BigDecimal.ZERO, normalized(seniorityPoints), normalized(recencyPoints)
		);

		List<String> explanations = new ArrayList<>(eligibility.reasons());
		explanations.add("geography is represented by the eligibility tier; its 30 points are redistributed");
		if (!technologyActive) {
			explanations.add("technology dimension is not configured and its weight is redistributed");
		}
		if (!seniorityActive) {
			explanations.add("seniority dimension is not configured and its weight is redistributed");
		}
		explanations.add("recency uses a linear 24-hour decay; missing or older timestamps receive zero");
		explanations.add(seniorityExplanation(candidate.job(), preferences.acceptedSeniorities()));

		return new RankedJob(candidate.job(), eligibility, score, technologyEvidence, explanations);
	}

	private BigDecimal technologyRatio(
		List<TechnologyAssessment> assessments, RankingPreferences preferences
	) {
		int requiredCount = canonicalSet(preferences.requiredTechnologies()).size();
		int desiredCount = canonicalSet(preferences.desiredTechnologies()).size();
		if (requiredCount == 0 && desiredCount == 0) {
			return BigDecimal.ZERO;
		}
		Set<String> required = canonicalSet(preferences.requiredTechnologies());
		Set<String> desired = canonicalSet(preferences.desiredTechnologies());
		BigDecimal requiredRatio = average(assessments, required);
		BigDecimal desiredRatio = average(assessments, desired);
		if (required.isEmpty()) {
			return desiredRatio;
		}
		if (desired.isEmpty()) {
			return requiredRatio;
		}
		return requiredRatio.multiply(REQUIRED_SHARE, MATH)
			.add(desiredRatio.multiply(DESIRED_SHARE, MATH));
	}

	private static BigDecimal average(List<TechnologyAssessment> assessments, Set<String> selected) {
		if (selected.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = assessments.stream()
			.filter(assessment -> selected.contains(assessment.technology()))
			.map(assessment -> assessment.evidence().factor())
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return total.divide(BigDecimal.valueOf(selected.size()), MATH);
	}

	private static BigDecimal seniorityRatio(NormalizedJob job, List<String> acceptedSeniorities) {
		if (acceptedSeniorities.isEmpty() || job.seniorityLevel() == null) {
			return BigDecimal.ZERO;
		}
		String actual = canonicalSeniority(job.seniorityLevel());
		return acceptedSeniorities.stream().map(JobRankingService::canonicalSeniority).anyMatch(actual::equals)
			? BigDecimal.ONE
			: BigDecimal.ZERO;
	}

	private static String seniorityExplanation(NormalizedJob job, List<String> acceptedSeniorities) {
		if (acceptedSeniorities.isEmpty()) {
			return "seniority has no configured preference";
		}
		if (job.seniorityLevel() == null || job.seniorityLevel().isBlank()) {
			return "seniority evidence is missing and receives zero points";
		}
		return seniorityRatio(job, acceptedSeniorities).signum() > 0
			? "seniority exactly matches an accepted level"
			: "seniority does not exactly match an accepted level and receives zero points";
	}

	private static String canonicalSeniority(String value) {
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.contains("intern")) {
			return "intern";
		}
		if (normalized.contains("junior") || normalized.matches(".*\\bjr\\.?\\b.*")) {
			return "junior";
		}
		if (normalized.contains("mid") || normalized.contains("intermediate")) {
			return "mid";
		}
		if (normalized.contains("senior") || normalized.matches(".*\\bsr\\.?\\b.*")) {
			return "senior";
		}
		if (normalized.contains("staff")) {
			return "staff";
		}
		if (normalized.contains("principal")) {
			return "principal";
		}
		return normalized;
	}

	private static BigDecimal recencyRatio(Instant publishedAt, Instant now) {
		if (publishedAt == null) {
			return BigDecimal.ZERO;
		}
		Duration age = Duration.between(publishedAt, now);
		if (age.isNegative() || age.isZero()) {
			return BigDecimal.ONE;
		}
		if (age.compareTo(RECENCY_WINDOW) >= 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal elapsedNanos = BigDecimal.valueOf(age.toNanos());
		BigDecimal windowNanos = BigDecimal.valueOf(RECENCY_WINDOW.toNanos());
		return BigDecimal.ONE.subtract(elapsedNanos.divide(windowNanos, MATH));
	}

	private static BigDecimal points(BigDecimal ratio, BigDecimal baseWeight, BigDecimal activeBaseWeight) {
		BigDecimal effectiveWeight = baseWeight.multiply(ONE_HUNDRED, MATH).divide(activeBaseWeight, MATH);
		return ratio.multiply(effectiveWeight, MATH);
	}

	private static BigDecimal normalized(BigDecimal value) {
		return value.stripTrailingZeros();
	}

	private Set<String> canonicalSet(List<String> technologies) {
		Set<String> values = new HashSet<>();
		technologies.stream().map(evidenceExtractor::canonical).forEach(values::add);
		return values;
	}

	private void validateCanonicalTechnologySets(RankingPreferences preferences) {
		Set<String> overlap = canonicalSet(preferences.requiredTechnologies());
		overlap.retainAll(canonicalSet(preferences.desiredTechnologies()));
		if (!overlap.isEmpty()) {
			throw new IllegalArgumentException("technology aliases overlap required and desired sets: " + overlap);
		}
	}

	private Comparator<RankedJob> order() {
		return Comparator.comparingInt((RankedJob ranked) -> eligibilityRank(ranked.eligibility().status()))
			.thenComparing(ranked -> ranked.score().total(), Comparator.reverseOrder())
			.thenComparing(ranked -> ranked.score().technologyPoints(), Comparator.reverseOrder())
			.thenComparing(ranked -> ranked.score().seniorityPoints(), Comparator.reverseOrder())
			.thenComparing(ranked -> ranked.job().publishedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(ranked -> identityCanonicalizer.stableKey(ranked.job()));
	}

	private static int eligibilityRank(EligibilityStatus status) {
		return switch (status) {
			case ELIGIBLE -> 0;
			case UNKNOWN -> 1;
			case INELIGIBLE -> 2;
		};
	}

}
