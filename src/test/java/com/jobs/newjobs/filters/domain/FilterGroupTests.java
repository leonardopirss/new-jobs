package com.jobs.newjobs.filters.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class FilterGroupTests {
	@Test
	void allRequiresEveryConfiguredValue() {
		FilterGroup group = new FilterGroup(
			CommonFilterField.TECHNOLOGY, FilterOperation.INCLUDE, MatchMode.ALL, List.of("Java", "PostgreSQL")
		);

		assertThat(group.matches(List.of("java", "PostgreSQL", "Spring"))).isTrue();
		assertThat(group.matches(List.of("Java"))).isFalse();
		assertThat(group.matches(List.of())).isFalse();
	}

	@Test
	void anyRequiresAtLeastOneConfiguredValue() {
		FilterGroup group = new FilterGroup(
			CommonFilterField.COUNTRY, FilterOperation.INCLUDE, MatchMode.ANY, List.of("Brazil", "Portugal")
		);

		assertThat(group.matches(List.of("Portugal"))).isTrue();
		assertThat(group.matches(List.of("Germany"))).isFalse();
		assertThat(group.matches(null)).isFalse();
	}

	@Test
	void exclusionRejectsAnyExactValueButAllowsUnknown() {
		FilterGroup group = new FilterGroup(
			CommonFilterField.COMPANY, FilterOperation.EXCLUDE, MatchMode.ANY, List.of("Blocked Corp")
		);

		assertThat(group.matches(List.of("Blocked Corp"))).isFalse();
		assertThat(group.matches(List.of("Allowed Corp"))).isTrue();
		assertThat(group.matches(List.of())).isTrue();
	}

	@Test
	void exclusionIsLimitedToApprovedFieldsAndAnySemantics() {
		assertThatThrownBy(() -> new FilterGroup(
			CommonFilterField.TECHNOLOGY, FilterOperation.EXCLUDE, MatchMode.ANY, List.of("Java")
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("KEYWORD and COMPANY");

		assertThatThrownBy(() -> new FilterGroup(
			CommonFilterField.KEYWORD, FilterOperation.EXCLUDE, MatchMode.ALL, List.of("agency")
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ANY");
	}

	@Test
	void valuesAreTrimmedAndMustBeUniqueIgnoringCase() {
		FilterGroup group = new FilterGroup(
			CommonFilterField.TITLE, FilterOperation.INCLUDE, MatchMode.ANY, List.of(" Backend Engineer ")
		);
		assertThat(group.values()).containsExactly("Backend Engineer");

		assertThatThrownBy(() -> new FilterGroup(
			CommonFilterField.TITLE, FilterOperation.INCLUDE, MatchMode.ANY, List.of("Backend", "backend")
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unique");
	}
}
