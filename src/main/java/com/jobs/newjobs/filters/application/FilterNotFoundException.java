package com.jobs.newjobs.filters.application;

import java.util.UUID;

public class FilterNotFoundException extends RuntimeException {
	public FilterNotFoundException(UUID id) {
		super("Saved filter not found: " + id);
	}
}
