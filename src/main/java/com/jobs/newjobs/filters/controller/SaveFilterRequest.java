package com.jobs.newjobs.filters.controller;

import com.jobs.newjobs.jobs.domain.JobSource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SaveFilterRequest(
	@NotBlank @Size(max = 120) String name,
	@NotNull JobSource source,
	@NotNull @Valid FilterSpecificationRequest specification
) {
}
