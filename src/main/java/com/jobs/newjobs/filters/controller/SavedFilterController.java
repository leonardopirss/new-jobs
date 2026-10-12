package com.jobs.newjobs.filters.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobs.newjobs.filters.application.SavedFilterService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/filters")
public class SavedFilterController {
	private final SavedFilterService service;

	public SavedFilterController(SavedFilterService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<SavedFilterResponse> create(@Valid @RequestBody SaveFilterRequest request) {
		SavedFilterResponse response = SavedFilterResponse.from(service.create(
			request.name(), request.source(), request.specification().toDomain(),
			RankingPreferencesRequest.effective(request.rankingPreferences())
		));
		return ResponseEntity.created(URI.create("/api/v1/filters/" + response.id())).body(response);
	}

	@GetMapping("/{id}")
	SavedFilterResponse get(@PathVariable UUID id) {
		return SavedFilterResponse.from(service.get(id));
	}

	@GetMapping
	List<SavedFilterResponse> list() {
		return service.list().stream().map(SavedFilterResponse::from).toList();
	}

	@PutMapping("/{id}")
	SavedFilterResponse update(@PathVariable UUID id, @Valid @RequestBody SaveFilterRequest request) {
		return SavedFilterResponse.from(service.update(
			id, request.name(), request.source(), request.specification().toDomain(),
			RankingPreferencesRequest.effective(request.rankingPreferences())
		));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable UUID id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
