package com.jobs.newjobs.ranking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.ranking.domain.TechnologyEvidence;

class TechnologyEvidenceExtractorTests {
	private final TechnologyEvidenceExtractor extractor = new TechnologyEvidenceExtractor();

	@Test
	void usesAliasesAndWordBoundariesWithoutMatchingJavaInsideJavascript() {
		NormalizedJob job = job(
			"JavaScript and K8s experience", List.of("JavaScript"), List.of("Operate Kubernetes workloads")
		);

		assertThat(extractor.assess(job, List.of("Java", "Kubernetes")))
			.extracting(assessment -> assessment.evidence())
			.containsExactly(TechnologyEvidence.UNKNOWN, TechnologyEvidence.MENTIONED);
	}

	@Test
	void distinguishesTechnologyNegationFromExperienceNotRequired() {
		assertThat(extractor.assess(
			job("No Kubernetes is used by this team", List.of(), List.of()), List.of("K8s")
		).getFirst().evidence()).isEqualTo(TechnologyEvidence.NEGATED);

		assertThat(extractor.assess(
			job("Kubernetes experience not required", List.of(), List.of()), List.of("K8s")
		).getFirst().evidence()).isEqualTo(TechnologyEvidence.MENTIONED);
	}

	@Test
	void turnsEquivalentContradictoryJobEvidenceIntoUnknownWithAReason() {
		var assessment = extractor.assess(
			job("Kubernetes is required", List.of(), List.of("Kubernetes is not used")), List.of("K8s")
		).getFirst();

		assertThat(assessment.evidence()).isEqualTo(TechnologyEvidence.UNKNOWN);
		assertThat(assessment.reasons()).containsExactly("equally relevant job evidence is contradictory");
	}

	private static NormalizedJob job(String requirements, List<String> technologies, List<String> responsibilities) {
		return new NormalizedJob(
			JobSource.HIRING_CAFE, "job-1", "Engineer", "Example", "Worldwide", "Remote",
			List.of("Full Time"), null, Instant.parse("2026-10-11T06:00:00Z"), requirements,
			"Senior", BigDecimal.valueOf(5), responsibilities, technologies,
			URI.create("https://example.test/jobs/1")
		);
	}
}
