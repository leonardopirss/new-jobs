package com.jobs.newjobs.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.application.NotionSchemaValidator;
import com.jobs.newjobs.notion.domain.NotionFailure;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPageSnapshot;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionQuotaEvidence;
import com.jobs.newjobs.notion.domain.NotionSchema;
import com.jobs.newjobs.ranking.domain.EligibilityAssessment;
import com.jobs.newjobs.ranking.domain.EligibilityStatus;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.ranking.domain.RankingScore;
import com.jobs.newjobs.ranking.domain.SearchMode;
import com.jobs.newjobs.observability.OperationalTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.infrastructure.JdbcSyncSafetyMarkerRepository;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock;

@Testcontainers
class SyncCoordinatorTests {
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
	private static final String DESTINATION = "notion-destination";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery")
		.withUsername("job_discovery")
		.withPassword("test-only-password");

	private static DataSource dataSource;
	private RecordingGateway gateway;
	private SyncCoordinator coordinator;

	@BeforeAll
	static void migrate() {
		DriverManagerDataSource configured = new DriverManagerDataSource();
		configured.setUrl(POSTGRES.getJdbcUrl());
		configured.setUsername(POSTGRES.getUsername());
		configured.setPassword(POSTGRES.getPassword());
		dataSource = configured;
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@BeforeEach
	void setUp() {
		new JdbcTemplate(dataSource).update("truncate table sync_safety_markers");
		gateway = new RecordingGateway();
		coordinator = coordinator(gateway);
	}

	@Test
	void deduplicatesFullBatchPlansEveryIdentityUpdatesFirstAndCreatesInRankingOrder() {
		gateway.identities.put("existing", new NotionGateway.IdentityResult.One(page("page-existing")));
		gateway.identities.put("conflict", new NotionGateway.IdentityResult.Conflict(
			List.of(page("page-a"), page("page-b"))));
		List<RankedJob> ranked = List.of(
			ranked("existing", "existing"),
			ranked("rank-one", "rank-one"),
			ranked("rank-one", "rank-one"),
			ranked("rank-two", "rank-two"),
			ranked("conflict", "conflict")
		);

		var result = coordinator.synchronize(DESTINATION, ranked);

		assertThat(result.items()).extracting(item -> item.status()).containsExactly(
			SyncItemStatus.UPDATED, SyncItemStatus.CREATED, SyncItemStatus.UNCHANGED,
			SyncItemStatus.CREATED, SyncItemStatus.CONFLICTED);
		assertThat(gateway.events).containsSubsequence(
			"update:page-existing", "create:rank-one", "create:rank-two");
		int firstWrite = firstWrite(gateway.events);
		assertThat(gateway.events.subList(0, firstWrite)).allMatch(event -> event.startsWith("query:"));
		assertThat(result.summary().received()).isEqualTo(5);
		assertThat(result.summary().created()).isEqualTo(2);
		assertThat(result.summary().updated()).isEqualTo(1);
		assertThat(result.summary().unchanged()).isEqualTo(1);
		assertThat(result.summary().conflicted()).isEqualTo(1);
	}

	@Test
	void incompleteQuotaFailsClosedForCreatesButAllowsFreeUpdates() {
		gateway.identities.put("existing", new NotionGateway.IdentityResult.One(page("manual-page")));
		gateway.quotaResult = new NotionGateway.QuotaResult.Incomplete("pagination incomplete", null);

		var result = coordinator.synchronize(DESTINATION,
			List.of(ranked("existing", "existing"), ranked("new", "new")));

		assertThat(result.items()).extracting(item -> item.status()).containsExactly(
			SyncItemStatus.UPDATED, SyncItemStatus.NOT_PROCESSED);
		assertThat(result.items().get(1).reasonCode()).isEqualTo("QUOTA_EVIDENCE_INCOMPLETE");
		assertThat(gateway.events).contains("update:manual-page");
		assertThat(gateway.events).noneMatch(event -> event.startsWith("create:"));
	}

	@Test
	void onlyAutomationOwnershipEvidenceConsumesQuotaSoManualPagesAreExcluded() {
		gateway.identities.put("manual", new NotionGateway.IdentityResult.One(page("manual-page")));
		gateway.quotaResult = new NotionGateway.QuotaResult.Complete(evidence(300));

		var result = coordinator.synchronize(DESTINATION,
			List.of(ranked("manual", "manual"), ranked("new", "new")));

		assertThat(result.items()).extracting(item -> item.status()).containsExactly(
			SyncItemStatus.UPDATED, SyncItemStatus.NOT_PROCESSED);
		assertThat(result.items().get(1).reasonCode()).isEqualTo("CREATE_QUOTA_EXHAUSTED");
		assertThat(result.summary().updated()).isOne();
	}

	@Test
	void allowsTheThreeHundredthDistinctPageAndRejectsTheThreeHundredAndFirst() {
		gateway.quotaResult = new NotionGateway.QuotaResult.Complete(evidence(299));

		var result = coordinator.synchronize(DESTINATION,
			List.of(ranked("three-hundred", "three-hundred"), ranked("three-oh-one", "three-oh-one")));

		assertThat(result.items()).extracting(item -> item.status()).containsExactly(
			SyncItemStatus.CREATED, SyncItemStatus.NOT_PROCESSED);
		assertThat(result.items().get(1).reasonCode()).isEqualTo("CREATE_QUOTA_EXHAUSTED");
		assertThat(gateway.events).contains("create:three-hundred");
		assertThat(gateway.events).doesNotContain("create:three-oh-one");
	}

	@Test
	void marksRemainingItemsNotProcessedWhenDeadlineBudgetIsExhausted() {
		MutableClock clock = new MutableClock(NOW);
		gateway.afterFirstQuery = () -> clock.advance(Duration.ofSeconds(2));
		coordinator = coordinator(gateway, clock);
		UUID requestId = UUID.randomUUID();

		var result = coordinator.synchronize(DESTINATION,
			List.of(ranked("first", "first"), ranked("second", "second")),
			new SyncExecutionPolicy(requestId, NOW.plusSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(1)));

		assertThat(result.requestId()).isEqualTo(requestId);
		assertThat(result.items()).extracting(item -> item.status())
			.containsExactly(SyncItemStatus.NOT_PROCESSED, SyncItemStatus.NOT_PROCESSED);
		assertThat(result.items()).extracting(item -> item.reasonCode())
			.containsOnly("DEADLINE_BUDGET_EXHAUSTED");
		assertThat(gateway.events).containsExactly("query:first");
	}

	private static SyncCoordinator coordinator(RecordingGateway gateway) {
		return coordinator(gateway, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static SyncCoordinator coordinator(RecordingGateway gateway, Clock clock) {
		JdbcSyncSafetyMarkerRepository markers = new JdbcSyncSafetyMarkerRepository(
			JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
		JobIdentityCanonicalizer canonicalizer = new JobIdentityCanonicalizer();
		NotionPageMapper mapper = new NotionPageMapper(canonicalizer);
		return new SyncCoordinator(
			new PostgresDestinationLock(dataSource), gateway, new NotionSchemaValidator(), canonicalizer, mapper,
			new NotionUpdateService(gateway, mapper, clock),
			markers, new SyncMarkerProtocol(markers, gateway, clock),
			new NotionCreationQuotaService(gateway, markers, clock,
				new OperationalTelemetry(new SimpleMeterRegistry())), clock);
	}

	private static int firstWrite(List<String> events) {
		for (int index = 0; index < events.size(); index++) {
			if (!events.get(index).startsWith("query:")) return index;
		}
		return events.size();
	}

	private static RankedJob ranked(String externalId, String suffix) {
		NormalizedJob job = new NormalizedJob(JobSource.HIRING_CAFE, externalId, "Backend Engineer", "Example",
			"Brazil", "Remote", List.of("Full Time"), null, NOW.minusSeconds(60), "Java", "Senior",
			BigDecimal.valueOf(5), List.of("Build services"), List.of("Java"),
			URI.create("https://example.test/jobs/" + suffix));
		BigDecimal zero = BigDecimal.ZERO;
		return new RankedJob(job,
			new EligibilityAssessment(EligibilityStatus.ELIGIBLE, SearchMode.REMOTE_FROM_BRAZIL, List.of()),
			new RankingScore(zero, zero, zero, zero, zero), List.of(), List.of());
	}

	private static NotionPageSnapshot page(String id) {
		return new NotionPageSnapshot(id, false, false, Map.of());
	}

	private static List<NotionQuotaEvidence> evidence(int count) {
		List<NotionQuotaEvidence> pages = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			pages.add(new NotionQuotaEvidence("owned-" + index, UUID.randomUUID(), NOW.minus(Duration.ofHours(1)), false));
		}
		return pages;
	}

	private static NotionSchema validSchema() {
		NotionSchemaValidator validator = new NotionSchemaValidator();
		Map<String, NotionSchema.PropertyDefinition> properties = new LinkedHashMap<>();
		validator.conceptualRules().forEach((name, rule) -> {
			NotionPropertyType type = rule.types().iterator().next();
			List<String> options = name.equals(NotionSchemaValidator.STATUS)
				? List.of(NotionSchemaValidator.NEW_STATUS) : List.of();
			properties.put(name, new NotionSchema.PropertyDefinition(name, name, type, options));
		});
		return new NotionSchema(DESTINATION, properties);
	}

	private static final class RecordingGateway implements NotionGateway {
		private final Map<String, IdentityResult> identities = new LinkedHashMap<>();
		private final List<String> events = new ArrayList<>();
		private QuotaResult quotaResult = new QuotaResult.Complete(List.of());
		private Runnable afterFirstQuery = () -> { };

		@Override public SchemaResult retrieveSchema() { return new SchemaResult.Available(validSchema()); }

		@Override
		public IdentityResult queryByIdentity(JobSource source, String externalId, URI canonicalUrl) {
			events.add("query:" + externalId);
			if (events.stream().filter(event -> event.startsWith("query:")).count() == 1) afterFirstQuery.run();
			return identities.getOrDefault(externalId, new IdentityResult.None());
		}

		@Override public QuotaResult scanAutomationCreations(Instant fromInclusive) { return quotaResult; }

		@Override
		public CreateResult createPage(NotionPageDraft draft, UUID operationId) {
			String externalId = draft.properties().values().stream()
				.filter(value -> value instanceof com.jobs.newjobs.notion.domain.NotionPropertyValue.Text)
				.map(value -> ((com.jobs.newjobs.notion.domain.NotionPropertyValue.Text) value).value())
				.filter(identities::containsKey)
				.findFirst().orElseGet(() -> draft.properties().entrySet().stream()
					.filter(entry -> entry.getKey().equals(NotionSchemaValidator.EXTERNAL_JOB_ID))
					.map(entry -> ((com.jobs.newjobs.notion.domain.NotionPropertyValue.Text) entry.getValue()).value())
					.findFirst().orElseThrow());
			events.add("create:" + externalId);
			NotionPageSnapshot snapshot = page("created-" + externalId);
			return new CreateResult.Confirmed(snapshot.pageId(), snapshot);
		}

		@Override
		public UpdateResult updatePage(String pageId, NotionPagePatch patch) {
			events.add("update:" + pageId);
			return new UpdateResult.Reconciled(page(pageId));
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) { this.instant = instant; }
		void advance(Duration duration) { instant = instant.plus(duration); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return instant; }
	}
}
