package com.jobs.newjobs.filters.application;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.jobs.domain.JobSource;

@Service
public class PreviewService {
	private final FilterCapabilityPolicy capabilityPolicy;

	public PreviewService(FilterCapabilityPolicy capabilityPolicy) {
		this.capabilityPolicy = capabilityPolicy;
	}

	public void validatePreview(JobSource source, FilterSpecification specification) {
		capabilityPolicy.validate(source, specification);
		throw new ProviderConnectorUnavailableException();
	}
}
