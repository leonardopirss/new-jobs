package com.jobs.newjobs.ranking.domain;

import java.util.List;
import java.util.Objects;

public record TechnologyAssessment(
	String technology,
	TechnologyEvidence evidence,
	List<String> reasons
) {
	public TechnologyAssessment {
		Objects.requireNonNull(technology, "technology is required");
		Objects.requireNonNull(evidence, "evidence is required");
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
	}
}
