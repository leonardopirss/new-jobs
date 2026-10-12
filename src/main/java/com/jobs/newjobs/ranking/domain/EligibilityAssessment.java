package com.jobs.newjobs.ranking.domain;

import java.util.List;
import java.util.Objects;

public record EligibilityAssessment(
	EligibilityStatus status,
	SearchMode selectedPath,
	List<String> reasons
) {
	public EligibilityAssessment {
		Objects.requireNonNull(status, "status is required");
		Objects.requireNonNull(selectedPath, "selectedPath is required");
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
	}
}
