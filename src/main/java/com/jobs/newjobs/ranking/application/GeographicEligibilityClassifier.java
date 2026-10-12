package com.jobs.newjobs.ranking.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.ranking.domain.EligibilityAssessment;
import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.ranking.domain.GeographicEvidence;
import com.jobs.newjobs.ranking.domain.ResidenceRequirement;
import com.jobs.newjobs.ranking.domain.SearchMode;
import com.jobs.newjobs.ranking.domain.SupportEvidence;
import com.jobs.newjobs.ranking.domain.WorkAuthorization;
import com.jobs.newjobs.ranking.domain.WorkModel;

@Component
public class GeographicEligibilityClassifier {
	public EligibilityAssessment classify(SearchMode mode, GeographicEvidence evidence) {
		return switch (mode) {
			case REMOTE_FROM_BRAZIL -> remoteFromBrazil(evidence);
			case INTERNATIONAL_RELOCATION -> internationalRelocation(evidence);
			case BOTH -> best(remoteFromBrazil(evidence), internationalRelocation(evidence));
		};
	}

	private static EligibilityAssessment remoteFromBrazil(GeographicEvidence evidence) {
		List<String> reasons = new ArrayList<>();
		if (evidence.workModel() == WorkModel.ONSITE || evidence.workModel() == WorkModel.HYBRID) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL,
				"work model is explicitly not fully remote");
		}
		if (evidence.workAuthorization() == WorkAuthorization.INCOMPATIBLE) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL,
				"work authorization is explicitly incompatible");
		}
		if (evidence.residenceRequirement() == ResidenceRequirement.OUTSIDE_BRAZIL) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL,
				"residence is explicitly required outside Brazil");
		}
		if (!evidence.allowedCountries().isEmpty() && !evidence.allowsBrazil()) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL,
				"allowed countries explicitly exclude Brazil");
		}
		if (evidence.workModel() == WorkModel.REMOTE && evidence.allowsBrazil()) {
			reasons.add("fully remote work explicitly allows Brazil or worldwide applicants");
			if (evidence.workAuthorization() == WorkAuthorization.REQUIRES_SPONSORSHIP
				&& evidence.sponsorship() != SupportEvidence.CONFIRMED) {
				return new EligibilityAssessment(EligibilityStatus.UNKNOWN, SearchMode.REMOTE_FROM_BRAZIL,
					List.of("remote work allows Brazil, but required sponsorship is not confirmed"));
			}
			return new EligibilityAssessment(EligibilityStatus.ELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL, reasons);
		}
		if (evidence.workModel() == WorkModel.UNKNOWN) {
			reasons.add("work model is not known");
		}
		if (!evidence.allowsBrazil()) {
			reasons.add("Brazil or worldwide eligibility is not explicitly confirmed");
		}
		return new EligibilityAssessment(EligibilityStatus.UNKNOWN, SearchMode.REMOTE_FROM_BRAZIL, reasons);
	}

	private static EligibilityAssessment internationalRelocation(GeographicEvidence evidence) {
		if (evidence.workAuthorization() == WorkAuthorization.INCOMPATIBLE) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.INTERNATIONAL_RELOCATION,
				"work authorization is explicitly incompatible");
		}
		if (evidence.relocation() == SupportEvidence.DENIED) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.INTERNATIONAL_RELOCATION,
				"relocation support is explicitly denied");
		}
		if (evidence.workAuthorization() == WorkAuthorization.REQUIRES_SPONSORSHIP
			&& evidence.sponsorship() == SupportEvidence.DENIED) {
			return assessment(EligibilityStatus.INELIGIBLE, SearchMode.INTERNATIONAL_RELOCATION,
				"sponsorship is required and explicitly unavailable");
		}
		if (evidence.relocation() == SupportEvidence.CONFIRMED
			&& (evidence.workAuthorization() == WorkAuthorization.COMPATIBLE
				|| evidence.sponsorship() == SupportEvidence.CONFIRMED)) {
			return assessment(EligibilityStatus.ELIGIBLE, SearchMode.INTERNATIONAL_RELOCATION,
				"relocation is confirmed and the authorization path is supported",
				"sponsorship support does not guarantee visa approval");
		}
		if (evidence.sponsorship() == SupportEvidence.CONFIRMED
			&& evidence.relocation() != SupportEvidence.DENIED) {
			return assessment(EligibilityStatus.ELIGIBLE, SearchMode.INTERNATIONAL_RELOCATION,
				"sponsorship is explicitly supported",
				"sponsorship support does not guarantee visa approval");
		}
		return assessment(EligibilityStatus.UNKNOWN, SearchMode.INTERNATIONAL_RELOCATION,
			"relocation and sponsorship eligibility are not sufficiently confirmed");
	}

	private static EligibilityAssessment best(EligibilityAssessment first, EligibilityAssessment second) {
		return List.of(first, second).stream()
			.min(Comparator.comparingInt(assessment -> rank(assessment.status())))
			.orElseThrow();
	}

	private static int rank(EligibilityStatus status) {
		return switch (status) {
			case ELIGIBLE -> 0;
			case UNKNOWN -> 1;
			case INELIGIBLE -> 2;
		};
	}

	private static EligibilityAssessment assessment(
		EligibilityStatus status, SearchMode path, String... reasons
	) {
		return new EligibilityAssessment(status, path, List.of(reasons));
	}
}
