package com.jobs.newjobs.ranking.domain;

import java.util.Objects;

import com.jobs.newjobs.jobs.domain.NormalizedJob;

public record RankingCandidate(NormalizedJob job, GeographicEvidence geographicEvidence) {
	public RankingCandidate {
		Objects.requireNonNull(job, "job is required");
		geographicEvidence = geographicEvidence == null ? GeographicEvidence.from(job) : geographicEvidence;
	}

	public static RankingCandidate from(NormalizedJob job) {
		return new RankingCandidate(job, GeographicEvidence.from(job));
	}
}
