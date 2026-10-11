package com.jobs.newjobs.filters.domain;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record FilterGroup(
	CommonFilterField field,
	FilterOperation operation,
	MatchMode matchMode,
	List<String> values
) {
	private static final int MAX_VALUES = 50;
	private static final int MAX_VALUE_LENGTH = 120;

	public FilterGroup {
		Objects.requireNonNull(field, "field is required");
		Objects.requireNonNull(operation, "operation is required");
		Objects.requireNonNull(matchMode, "matchMode is required");
		values = normalizeValues(values);

		if (operation == FilterOperation.EXCLUDE) {
			if (field != CommonFilterField.KEYWORD && field != CommonFilterField.COMPANY) {
				throw new IllegalArgumentException("EXCLUDE is supported only for KEYWORD and COMPANY");
			}
			if (matchMode != MatchMode.ANY) {
				throw new IllegalArgumentException("EXCLUDE groups must use ANY semantics");
			}
		}
	}

	public boolean matches(Collection<String> actualValues) {
		Set<String> normalizedActual = new HashSet<>();
		if (actualValues != null) {
			actualValues.stream()
				.filter(Objects::nonNull)
				.map(FilterGroup::comparisonValue)
				.filter(value -> !value.isEmpty())
				.forEach(normalizedActual::add);
		}

		boolean anyMatch = values.stream()
			.map(FilterGroup::comparisonValue)
			.anyMatch(normalizedActual::contains);

		if (operation == FilterOperation.EXCLUDE) {
			return !anyMatch;
		}

		return matchMode == MatchMode.ANY
			? anyMatch
			: values.stream().map(FilterGroup::comparisonValue).allMatch(normalizedActual::contains);
	}

	private static List<String> normalizeValues(List<String> rawValues) {
		if (rawValues == null || rawValues.isEmpty()) {
			throw new IllegalArgumentException("values must contain at least one item");
		}
		if (rawValues.size() > MAX_VALUES) {
			throw new IllegalArgumentException("values must contain at most " + MAX_VALUES + " items");
		}

		Set<String> uniqueValues = new HashSet<>();
		return rawValues.stream().map(value -> {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("filter values must not be blank");
			}
			String normalized = value.trim();
			if (normalized.length() > MAX_VALUE_LENGTH) {
				throw new IllegalArgumentException("filter values must not exceed " + MAX_VALUE_LENGTH + " characters");
			}
			if (!uniqueValues.add(comparisonValue(normalized))) {
				throw new IllegalArgumentException("filter values must be unique ignoring case");
			}
			return normalized;
		}).toList();
	}

	private static String comparisonValue(String value) {
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
