package com.jobs.newjobs.imports.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ImportRequest(
	@NotNull @Size(min = 1, max = MAX_ITEMS) List<ImportedJob> jobs
) {
	public static final int MAX_ITEMS = 50;

	public ImportRequest {
		jobs = jobs == null ? null : List.copyOf(jobs);
	}

	public void validateBatch() {
		if (jobs == null || jobs.isEmpty() || jobs.size() > MAX_ITEMS) {
			throw ImportApiException.unprocessable("IMPORT_BATCH_SIZE_INVALID",
				"Import batch must contain between 1 and 50 items");
		}
	}

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("unknown import request property: " + name);
	}

	public record ImportedJob(
		@Size(max = 512) String externalJobId,
		@NotBlank @Size(max = 500) String title,
		@Size(max = 500) String company,
		URI url,
		@Size(max = 500) String location,
		@Size(max = 80) String workplaceType,
		@Size(max = 20) List<@Size(max = 80) String> employmentTypes,
		Instant publishedAt,
		@Size(max = 10_000) String requirementsSummary,
		@Size(max = 120) String seniorityLevel,
		@DecimalMin("0") @DecimalMax("100") BigDecimal minimumYearsExperience,
		@Size(max = 100) List<@Size(max = 2_000) String> responsibilities,
		@Size(max = 100) List<@Size(max = 120) String> technologies
	) {
		public ImportedJob {
			employmentTypes = employmentTypes == null ? List.of() : List.copyOf(employmentTypes);
			responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
			technologies = technologies == null ? List.of() : List.copyOf(technologies);
		}

		@AssertTrue(message = "externalJobId or url is required")
		public boolean hasIdentity() {
			return (externalJobId != null && !externalJobId.isBlank()) || url != null;
		}

		@AssertTrue(message = "url must be an absolute HTTP(S) URI with at most 2000 characters")
		public boolean hasValidUrl() {
			if (url == null) return true;
			String scheme = url.getScheme();
			return url.toString().length() <= 2_000 && url.isAbsolute()
				&& ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		}
	}
}
