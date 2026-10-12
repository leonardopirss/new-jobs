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

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
		List<String> violations = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.toList();
		problem.setProperty("violations", violations);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception) {
		return ResponseEntity.badRequest()
			.body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is malformed"));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ProblemDetail> handleSemanticValidation(IllegalArgumentException exception) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
			.body(problem(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILTER", exception.getMessage()));
	}

	@ExceptionHandler(FilterNotFoundException.class)
	ResponseEntity<ProblemDetail> handleNotFound(FilterNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(problem(HttpStatus.NOT_FOUND, "FILTER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(problem(HttpStatus.CONFLICT, "FILTER_NAME_CONFLICT", "A saved filter with this name already exists"));
	}

	@ExceptionHandler(HiringCafeProviderException.class)
	ResponseEntity<ProblemDetail> handleProviderUnavailable(HiringCafeProviderException exception) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			.body(problem(HttpStatus.BAD_GATEWAY, "HIRINGCAFE_UNAVAILABLE", "HiringCafe is temporarily unavailable"));
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:problem:" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}
}
