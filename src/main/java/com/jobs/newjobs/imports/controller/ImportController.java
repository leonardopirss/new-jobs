package com.jobs.newjobs.imports.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobs.newjobs.auth.infrastructure.ImportBearerAuthenticationFilter.IntegrationPrincipal;
import com.jobs.newjobs.imports.application.ImportService;
import com.jobs.newjobs.sync.controller.SyncResponse;

@RestController
@RequestMapping("/api/v1/jobs/import")
public final class ImportController {
	private final ImportService service;

	public ImportController(ImportService service) { this.service = service; }

	@PostMapping(consumes = "application/json", produces = "application/json")
	SyncResponse importJobs(
		@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
		@RequestBody ImportRequest request,
		Authentication authentication
	) {
		IntegrationPrincipal principal = (IntegrationPrincipal) authentication.getPrincipal();
		return SyncResponse.from(service.importJobs(principal, idempotencyKey, request));
	}
}
