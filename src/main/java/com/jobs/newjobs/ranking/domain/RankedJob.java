package com.jobs.newjobs.ranking.domain;

import java.util.List;
import java.util.Objects;

import com.jobs.newjobs.jobs.domain.NormalizedJob;

public record RankedJob(
	NormalizedJob job,
	EligibilityAssessment eligibility,
	RankingScore score,
	List<TechnologyAssessment> technologyEvidence,
	List<String> explanations
) {
	public RankedJob {
		Objects.requireNonNull(job, "job is required");
		Objects.requireNonNull(eligibility, "eligibility is required");
		Objects.requireNonNull(score, "score is required");
		technologyEvidence = technologyEvidence == null ? List.of() : List.copyOf(technologyEvidence);
		explanations = explanations == null ? List.of() : List.copyOf(explanations);
	}
}
