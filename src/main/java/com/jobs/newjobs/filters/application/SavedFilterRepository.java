package com.jobs.newjobs.filters.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jobs.newjobs.filters.domain.SavedFilter;

public interface SavedFilterRepository {
	SavedFilter insert(SavedFilter filter);

	Optional<SavedFilter> findById(UUID id);

	List<SavedFilter> findAll();

	boolean update(SavedFilter filter);

	boolean deleteById(UUID id);

	long count();
}
