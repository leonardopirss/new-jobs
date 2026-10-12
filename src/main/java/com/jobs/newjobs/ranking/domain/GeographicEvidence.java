package com.jobs.newjobs.ranking.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.jobs.newjobs.jobs.domain.NormalizedJob;

public record GeographicEvidence(
	WorkModel workModel,
	Set<String> allowedCountries,
	boolean worldwide,
	ResidenceRequirement residenceRequirement,
	SupportEvidence sponsorship,
	SupportEvidence relocation,
	WorkAuthorization workAuthorization
) {
	public GeographicEvidence {
		workModel = Objects.requireNonNullElse(workModel, WorkModel.UNKNOWN);
		allowedCountries = allowedCountries == null ? Set.of() : allowedCountries.stream()
			.filter(Objects::nonNull)
			.map(GeographicEvidence::normalizeCountry)
			.filter(value -> !value.isEmpty())
			.collect(Collectors.toUnmodifiableSet());
		residenceRequirement = Objects.requireNonNullElse(residenceRequirement, ResidenceRequirement.UNKNOWN);
		sponsorship = Objects.requireNonNullElse(sponsorship, SupportEvidence.UNKNOWN);
		relocation = Objects.requireNonNullElse(relocation, SupportEvidence.UNKNOWN);
		workAuthorization = Objects.requireNonNullElse(workAuthorization, WorkAuthorization.UNKNOWN);
	}

	public static GeographicEvidence from(NormalizedJob job) {
		Objects.requireNonNull(job, "job is required");
		String location = job.location() == null ? "" : job.location().toLowerCase(Locale.ROOT);
		boolean worldwide = location.contains("worldwide") || location.contains("global") || location.contains("anywhere");
		Set<String> countries = location.contains("brazil") || location.contains("brasil")
			? Set.of("brazil")
			: Set.of();
		return new GeographicEvidence(
			WorkModel.from(job.workplaceType()), countries, worldwide, ResidenceRequirement.UNKNOWN,
			SupportEvidence.UNKNOWN, SupportEvidence.UNKNOWN, WorkAuthorization.UNKNOWN
		);
	}

	public boolean allowsBrazil() {
		return worldwide || allowedCountries.contains("brazil") || allowedCountries.contains("brasil");
	}

	private static String normalizeCountry(String value) {
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
