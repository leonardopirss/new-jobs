package com.jobs.newjobs.sync.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobs.newjobs.sync.application.ManualSyncService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/jobs/sync")
public final class ManualSyncController {
	private final ManualSyncService service;

	public ManualSyncController(ManualSyncService service) {
		this.service = service;
	}

	@PostMapping
	SyncResponse synchronize(@Valid @RequestBody ManualSyncRequest request) {
		return SyncResponse.from(service.synchronize(request));
	}
}
