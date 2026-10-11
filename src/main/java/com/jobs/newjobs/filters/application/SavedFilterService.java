package com.jobs.newjobs.filters.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.jobs.domain.JobSource;

@Service
public class SavedFilterService {
	private final SavedFilterRepository repository;
	private final FilterCapabilityPolicy capabilityPolicy;
	private final Clock clock;

	public SavedFilterService(
		SavedFilterRepository repository,
		FilterCapabilityPolicy capabilityPolicy,
		Clock clock
	) {
		this.repository = repository;
		this.capabilityPolicy = capabilityPolicy;
		this.clock = clock;
	}

	@Transactional
	public SavedFilter create(String name, JobSource source, FilterSpecification specification) {
		String normalizedName = validateName(name);
		capabilityPolicy.validate(source, specification);
		Instant now = clock.instant();
		return repository.insert(new SavedFilter(
			UUID.randomUUID(), normalizedName, source, specification, 0, now, now
		));
	}

	@Transactional(readOnly = true)
	public SavedFilter get(UUID id) {
		return repository.findById(id).orElseThrow(() -> new FilterNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public List<SavedFilter> list() {
		return repository.findAll();
	}

	@Transactional
	public SavedFilter update(UUID id, String name, JobSource source, FilterSpecification specification) {
		SavedFilter current = get(id);
		String normalizedName = validateName(name);
		capabilityPolicy.validate(source, specification);
		SavedFilter updated = new SavedFilter(
			current.id(), normalizedName, source, specification, current.version() + 1,
			current.createdAt(), clock.instant()
		);
		if (!repository.update(updated)) {
			throw new FilterNotFoundException(id);
		}
		return updated;
	}

	@Transactional
	public void delete(UUID id) {
		if (!repository.deleteById(id)) {
			throw new FilterNotFoundException(id);
		}
	}

	private static String validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		String normalized = name.trim();
		if (normalized.length() > 120) {
			throw new IllegalArgumentException("name must not exceed 120 characters");
		}
		return normalized;
	}
}
