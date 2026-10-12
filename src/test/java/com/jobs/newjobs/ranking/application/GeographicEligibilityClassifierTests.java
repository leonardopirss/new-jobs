package com.jobs.newjobs.ranking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.ranking.domain.GeographicEvidence;
import com.jobs.newjobs.ranking.domain.ResidenceRequirement;
import com.jobs.newjobs.ranking.domain.SearchMode;
import com.jobs.newjobs.ranking.domain.SupportEvidence;
import com.jobs.newjobs.ranking.domain.WorkAuthorization;
import com.jobs.newjobs.ranking.domain.WorkModel;

class GeographicEligibilityClassifierTests {
	private final GeographicEligibilityClassifier classifier = new GeographicEligibilityClassifier();

	@Test
	void classifiesRemoteFromBrazilOnlyFromExplicitCompatibleEvidence() {
		assertThat(classifier.classify(SearchMode.REMOTE_FROM_BRAZIL, evidence(
			WorkModel.REMOTE, Set.of(), true, SupportEvidence.UNKNOWN, SupportEvidence.UNKNOWN,
			WorkAuthorization.UNKNOWN
		)).status()).isEqualTo(EligibilityStatus.ELIGIBLE);

		assertThat(classifier.classify(SearchMode.REMOTE_FROM_BRAZIL, evidence(
			WorkModel.REMOTE, Set.of("united states"), false, SupportEvidence.UNKNOWN,
			SupportEvidence.UNKNOWN, WorkAuthorization.UNKNOWN
		)).status()).isEqualTo(EligibilityStatus.INELIGIBLE);

		assertThat(classifier.classify(SearchMode.REMOTE_FROM_BRAZIL, evidence(
			WorkModel.REMOTE, Set.of(), false, SupportEvidence.UNKNOWN, SupportEvidence.UNKNOWN,
			WorkAuthorization.UNKNOWN
		)).status()).isEqualTo(EligibilityStatus.UNKNOWN);
	}

	@Test
	void keepsSponsorshipUnknownUnlessTheAuthorizationPathIsExplicit() {
		assertThat(classifier.classify(SearchMode.INTERNATIONAL_RELOCATION, evidence(
			WorkModel.ONSITE, Set.of("germany"), false, SupportEvidence.UNKNOWN,
			SupportEvidence.CONFIRMED, WorkAuthorization.REQUIRES_SPONSORSHIP
		)).status()).isEqualTo(EligibilityStatus.UNKNOWN);

		var eligible = classifier.classify(SearchMode.INTERNATIONAL_RELOCATION, evidence(
			WorkModel.ONSITE, Set.of("germany"), false, SupportEvidence.CONFIRMED,
			SupportEvidence.CONFIRMED, WorkAuthorization.REQUIRES_SPONSORSHIP
		));

		assertThat(eligible.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
		assertThat(eligible.reasons()).anyMatch(reason -> reason.contains("does not guarantee visa"));
	}

	@Test
	void bothModeChoosesTheBestValidPathWithoutDuplicatingTheCandidate() {
		var assessment = classifier.classify(SearchMode.BOTH, evidence(
			WorkModel.REMOTE, Set.of("brazil"), false, SupportEvidence.DENIED,
			SupportEvidence.DENIED, WorkAuthorization.COMPATIBLE
		));

		assertThat(assessment.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
		assertThat(assessment.selectedPath()).isEqualTo(SearchMode.REMOTE_FROM_BRAZIL);
	}

	private static GeographicEvidence evidence(
		WorkModel workModel,
		Set<String> countries,
		boolean worldwide,
		SupportEvidence sponsorship,
		SupportEvidence relocation,
		WorkAuthorization authorization
	) {
		return new GeographicEvidence(
			workModel, countries, worldwide, ResidenceRequirement.UNKNOWN, sponsorship, relocation, authorization
		);
	}
}
