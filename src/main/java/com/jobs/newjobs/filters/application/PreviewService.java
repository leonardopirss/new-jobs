package com.jobs.newjobs.filters.application;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.hiringcafe.application.HiringCafeDiscoveryResult;
import com.jobs.newjobs.hiringcafe.application.HiringCafeDiscoveryService;
import com.jobs.newjobs.jobs.domain.JobSource;

@Service
public class PreviewService {
	private final FilterCapabilityPolicy capabilityPolicy;
	private final HiringCafeDiscoveryService discoveryService;

	public PreviewService(FilterCapabilityPolicy capabilityPolicy, HiringCafeDiscoveryService discoveryService) {
		this.capabilityPolicy = capabilityPolicy;
		this.discoveryService = discoveryService;
	}

	public HiringCafeDiscoveryResult preview(JobSource source, FilterSpecification specification) {
		capabilityPolicy.validate(source, specification);
		return discoveryService.discover(specification);
	}
}
