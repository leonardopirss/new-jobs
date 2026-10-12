package com.jobs.newjobs.ranking.domain;

import java.math.BigDecimal;

public enum TechnologyEvidence {
	CONFIRMED("1.0"),
	MENTIONED("0.5"),
	UNKNOWN("0.0"),
	NEGATED("0.0");

	private final BigDecimal factor;

	TechnologyEvidence(String factor) {
		this.factor = new BigDecimal(factor);
	}

	public BigDecimal factor() {
		return factor;
	}
}
