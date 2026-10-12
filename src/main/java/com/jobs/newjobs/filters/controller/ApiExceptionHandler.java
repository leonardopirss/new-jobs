package com.jobs.newjobs.filters.controller;

import java.net.URI;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.jobs.newjobs.filters.application.FilterNotFoundException;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderException;
import com.jobs.newjobs.imports.controller.ImportApiException;
import com.jobs.newjobs.shared.web.RequestIdFilter;
import com.jobs.newjobs.sync.application.SyncPreflightUnavailableException;

import jakarta.servlet.http.HttpServletRequest;
@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleValidation(
		MethodArgumentNotValidException exception,
		HttpServletRequest request
	) {
		ProblemDetail problem = problem(
			HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request
		);
		List<String> violations = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.toList();
		problem.setProperty("violations", violations);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadable(
		HttpMessageNotReadableException exception,
		HttpServletRequest request
	) {
		return ResponseEntity.badRequest()
			.body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is malformed", request));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ProblemDetail> handleSemanticValidation(
		IllegalArgumentException exception,
		HttpServletRequest request
	) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
			.body(problem(
				HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILTER", exception.getMessage(), request
			));
	}

	@ExceptionHandler(FilterNotFoundException.class)
	ResponseEntity<ProblemDetail> handleNotFound(FilterNotFoundException exception, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(problem(HttpStatus.NOT_FOUND, "FILTER_NOT_FOUND", exception.getMessage(), request));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleConflict(
		DataIntegrityViolationException exception,
		HttpServletRequest request
	) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(problem(
				HttpStatus.CONFLICT, "FILTER_NAME_CONFLICT", "A saved filter with this name already exists", request
			));
	}

	@ExceptionHandler(HiringCafeProviderException.class)
	ResponseEntity<ProblemDetail> handleProviderUnavailable(
		HiringCafeProviderException exception,
		HttpServletRequest request
	) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			.body(problem(
				HttpStatus.BAD_GATEWAY, "HIRINGCAFE_UNAVAILABLE", "HiringCafe is temporarily unavailable", request
			));
	}

	@ExceptionHandler(SyncPreflightUnavailableException.class)
	ResponseEntity<ProblemDetail> handleSyncPreflightUnavailable(
		SyncPreflightUnavailableException exception,
		HttpServletRequest request
	) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(problem(HttpStatus.SERVICE_UNAVAILABLE, exception.reasonCode(), exception.getMessage(), request));
	}

	@ExceptionHandler(ImportApiException.class)
	ResponseEntity<ProblemDetail> handleImportApi(ImportApiException exception, HttpServletRequest request) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
		if (exception.retryAfter() != null) {
			long seconds = Math.max(1, (long) Math.ceil(exception.retryAfter().toMillis() / 1000.0));
			response.header("Retry-After", Long.toString(seconds));
		}
		ProblemDetail problem = problem(exception.status(), exception.reasonCode(), exception.getMessage(), request);
		if (exception.requestId() != null) problem.setProperty("requestId", exception.requestId());
		return response.body(problem);
	}

	private static ProblemDetail problem(
		HttpStatus status,
		String code,
		String detail,
		HttpServletRequest request
	) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:problem:" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		problem.setProperty("requestId", RequestIdFilter.from(request));
		return problem;
	}
}
