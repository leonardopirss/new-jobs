package com.jobs.newjobs.jobs.domain;

import java.math.BigDecimal;

public record JobCompensation(
	String currency,
	String frequency,
	BigDecimal minimum,
	BigDecimal maximum
) {
}
