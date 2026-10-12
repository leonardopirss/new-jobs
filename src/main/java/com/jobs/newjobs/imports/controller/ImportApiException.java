package com.jobs.newjobs.imports.controller;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class ImportApiException extends RuntimeException {
	private final HttpStatus status;
	private final String reasonCode;
	private final Duration retryAfter;
	private final UUID requestId;

	private ImportApiException(HttpStatus status, String reasonCode, String message, Duration retryAfter,
		UUID requestId) {
		super(message);
		this.status = status;
		this.reasonCode = reasonCode;
		this.retryAfter = retryAfter;
		this.requestId = requestId;
	}

	public static ImportApiException badRequest(String code, String message) {
		return new ImportApiException(HttpStatus.BAD_REQUEST, code, message, null, null);
	}

	public static ImportApiException unprocessable(String code, String message) {
		return new ImportApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, null, null);
	}

	public static ImportApiException conflict(String code, String message, Duration retryAfter) {
		return new ImportApiException(HttpStatus.CONFLICT, code, message, retryAfter, null);
	}

	public static ImportApiException inProgress(UUID requestId, Duration retryAfter) {
		return new ImportApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
			"An identical import is still in progress", retryAfter, requestId);
	}

	public static ImportApiException rateLimited(Duration retryAfter) {
		return new ImportApiException(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_RATE_LIMITED",
			"Import admission limit reached", retryAfter, null);
	}

	public static ImportApiException unavailable(String code, String message) {
		return new ImportApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, null, null);
	}

	public HttpStatus status() { return status; }
	public String reasonCode() { return reasonCode; }
	public Duration retryAfter() { return retryAfter; }
	public UUID requestId() { return requestId; }
}
