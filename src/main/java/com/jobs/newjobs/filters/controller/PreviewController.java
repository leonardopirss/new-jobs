package com.jobs.newjobs.filters.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobs.newjobs.filters.application.PreviewService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/jobs/preview")
public class PreviewController {
	private final PreviewService service;

	public PreviewController(PreviewService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<Void> preview(@Valid @RequestBody PreviewRequest request) {
		service.validatePreview(request.source(), request.specification().toDomain());
		return ResponseEntity.noContent().build();
	}
}
