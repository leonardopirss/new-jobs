package com.jobs.newjobs.filters.application;

public class ProviderConnectorUnavailableException extends RuntimeException {
	public ProviderConnectorUnavailableException() {
		super("HiringCafe preview is unavailable until the verified Phase 2 connector is configured");
	}
}
