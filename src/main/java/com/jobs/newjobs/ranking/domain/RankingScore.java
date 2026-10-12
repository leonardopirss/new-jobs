package com.jobs.newjobs.ranking.domain;

import java.math.BigDecimal;

public record RankingScore(
	BigDecimal total,
	BigDecimal technologyPoints,
	BigDecimal geographyPoints,
	BigDecimal seniorityPoints,
	BigDecimal recencyPoints
) {
}
