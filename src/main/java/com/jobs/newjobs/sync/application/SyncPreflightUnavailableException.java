package com.jobs.newjobs.sync.application;

public final class SyncPreflightUnavailableException extends RuntimeException {
	private final String reasonCode;

	public SyncPreflightUnavailableException(String reasonCode, String message) {
		super(message);
		this.reasonCode = reasonCode;
	}

	public SyncPreflightUnavailableException(String reasonCode, String message, Throwable cause) {
		super(message, cause);
		this.reasonCode = reasonCode;
	}

	public String reasonCode() {
		return reasonCode;
	}
}
