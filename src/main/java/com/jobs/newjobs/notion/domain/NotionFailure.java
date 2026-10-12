package com.jobs.newjobs.notion.domain;

public record NotionFailure(Code code, Integer httpStatus, String providerRequestId, String reason) {
	public NotionFailure {
		if (code == null) {
			throw new IllegalArgumentException("failure code is required");
		}
		reason = reason == null || reason.isBlank() ? code.name() : bound(reason, 256);
		providerRequestId = providerRequestId == null ? null : bound(providerRequestId, 128);
	}

	private static String bound(String value, int maximum) {
		return value.length() <= maximum ? value : value.substring(0, maximum);
	}

	public enum Code {
		AUTHENTICATION,
		VALIDATION,
		RATE_LIMITED,
		PROVIDER_REJECTED,
		TRANSIENT_PROVIDER,
		TRANSPORT,
		MALFORMED_RESPONSE,
		PAYLOAD_LIMIT,
		INTERRUPTED
	}
}
