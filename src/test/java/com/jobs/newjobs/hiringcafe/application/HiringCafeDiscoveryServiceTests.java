package com.jobs.newjobs.hiringcafe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.filters.domain.CommonFilterField;
import com.jobs.newjobs.filters.domain.FilterGroup;
import com.jobs.newjobs.filters.domain.FilterOperation;
import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.MatchMode;
import com.jobs.newjobs.filters.domain.ProviderFilterGroup;
import com.jobs.newjobs.hiringcafe.config.HiringCafeProperties;

class HiringCafeDiscoveryServiceTests {
	private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
	private static final HiringCafeProperties PROPERTIES = new HiringCafeProperties(
		URI.create("https://example.test/mcp"), Duration.ofSeconds(1), Duration.ofSeconds(2),
		Duration.ZERO, Duration.ZERO, 3, 25
	);

	@Test
	void keepsExact24HourBoundaryAndReportsExcludedOrTruncatedJobs() {
		HiringCafeProviderPage page = new HiringCafeProviderPage(List.of(
			job("boundary", "2026-10-10T12:00:00Z", "https://hiringcafe.com/job/boundary"),
			job("old", "2026-10-10T11:59:59.999999999Z", "https://hiringcafe.com/job/old"),
			job("invalid", null, "not a uri")
		), 3, 120L);
		CapturingGateway gateway = new CapturingGateway(page);
		HiringCafeDiscoveryService service = new HiringCafeDiscoveryService(
			gateway,
			new HiringCafeQueryFactory(PROPERTIES),
			PROPERTIES,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);

		HiringCafeDiscoveryResult result = service.discover(new FilterSpecification(List.of(), List.of()));

		assertThat(result.jobs()).extracting(job -> job.externalJobId()).containsExactly("boundary");
		assertThat(result.excludedOutsidePublicationWindow()).isEqualTo(2);
		assertThat(result.complete()).isFalse();
		assertThat(result.warnings()).extracting(DiscoveryWarning::code)
			.contains("PROVIDER_PAGINATION_UNAVAILABLE", "PROVIDER_ONE_RESULT_PER_COMPANY",
				"PROVIDER_RESULT_LIMIT", "INVALID_PROVIDER_JOB");
		assertThat(gateway.arguments).containsEntry("posted_within_days", 1).containsEntry("limit", 25);
	}

	@Test
	void mapsOnlyDocumentedSingleValueAndLanguageOperatorSemantics() {
		HiringCafeQueryFactory factory = new HiringCafeQueryFactory(PROPERTIES);
		FilterSpecification specification = new FilterSpecification(
			List.of(new FilterGroup(
				CommonFilterField.COMPANY, FilterOperation.EXCLUDE, MatchMode.ANY, List.of("Blocked Corp")
			)),
			List.of(new ProviderFilterGroup("language_requirements", MatchMode.ALL, List.of("English", "Portuguese")))
		);

		assertThat(factory.create(specification))
			.containsEntry("exclude_company_names", List.of("Blocked Corp"))
			.containsEntry("language_requirements", List.of("English", "Portuguese"))
			.containsEntry("language_requirements_operator", "AND");
	}

	@Test
	void rejectsUndocumentedMultiValueSemantics() {
		HiringCafeQueryFactory factory = new HiringCafeQueryFactory(PROPERTIES);
		FilterSpecification specification = new FilterSpecification(
			List.of(new FilterGroup(
				CommonFilterField.TECHNOLOGY, FilterOperation.INCLUDE, MatchMode.ALL, List.of("Java", "PostgreSQL")
			)),
			List.of()
		);

		assertThatThrownBy(() -> factory.create(specification))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not document ALL/ANY semantics");
	}

	@Test
	void rejectsValuesOutsideTheLiveProviderEnum() {
		HiringCafeQueryFactory factory = new HiringCafeQueryFactory(PROPERTIES);
		FilterSpecification specification = new FilterSpecification(
			List.of(),
			List.of(new ProviderFilterGroup("workplace_types", MatchMode.ANY, List.of("Mars")))
		);

		assertThatThrownBy(() -> factory.create(specification))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unsupported HiringCafe workplace_types value");
	}

	private static HiringCafeProviderJob job(String id, String datePosted, String url) {
		return new HiringCafeProviderJob(
			id, "Java Engineer", "Example", "Remote", "Remote", List.of("Full Time"),
			new com.jobs.newjobs.jobs.domain.JobCompensation("USD", "Yearly", BigDecimal.valueOf(100_000), null),
			datePosted, "Java", "Senior Level", BigDecimal.valueOf(5), List.of("Build services"),
			List.of("Java"), url
		);
	}

	private static final class CapturingGateway implements HiringCafeGateway {
		private final HiringCafeProviderPage response;
		private Map<String, Object> arguments;

		private CapturingGateway(HiringCafeProviderPage response) {
			this.response = response;
		}

		@Override
		public HiringCafeProviderPage search(Map<String, Object> arguments) {
			this.arguments = arguments;
			return response;
		}
	}
}
