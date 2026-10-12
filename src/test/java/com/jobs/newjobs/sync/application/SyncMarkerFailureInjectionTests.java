package com.jobs.newjobs.sync.application;

import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SYNC_OPERATION_ID;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.jobs.newjobs.notion.domain.NotionFailure;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPageSnapshot;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;
import com.jobs.newjobs.sync.application.SyncMarkerProtocol.CreateOutcome;
import com.jobs.newjobs.sync.application.SyncMarkerProtocol.ReconciliationOutcome;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository.CreateIntent;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository.PrepareResult;
import com.jobs.newjobs.sync.domain.SyncMarkerState;
import com.jobs.newjobs.sync.domain.SyncSafetyMarker;
import com.jobs.newjobs.sync.infrastructure.JdbcSyncSafetyMarkerRepository;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Testcontainers
class SyncMarkerFailureInjectionTests {
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
	private static final String DESTINATION = "notion-data-source";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery")
		.withUsername("job_discovery")
		.withPassword("test-only-password");

	private static DataSource dataSource;
	private JdbcSyncSafetyMarkerRepository repository;

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
	void resetMarkers() {
		new JdbcTemplate(dataSource).update("truncate table sync_safety_markers");
		repository = repository();
	}

	@Test
	void markerCommitFailureStopsBeforePost() {
		RecordingGateway gateway = new RecordingGateway();
		SyncSafetyMarkerRepository failing = new DelegatingRepository(repository) {
			@Override
			public PrepareResult prepare(CreateIntent intent) {
				throw new IllegalStateException("injected commit failure");
			}
		};
		UUID operationId = UUID.randomUUID();
		try (Lease lease = lock()) {
			SyncMarkerProtocol protocol = protocol(failing, gateway);
			assertThatThrownBy(() -> protocol.create(lease, intent(operationId, "job-1", "1"), draft(operationId)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("commit failure");
		}

		assertThat(gateway.createCalls).hasValue(0);
		assertThat(repository.find(operationId)).isEmpty();
	}

	@Test
	void crashBeforePostLeavesPreparedMarkerForANewRepositoryInstance() {
		UUID operationId = UUID.randomUUID();
		PrepareResult result = repository.prepare(intent(operationId, "job-crash", "crash"));

		assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
		JdbcSyncSafetyMarkerRepository restarted = repository();
		assertThat(restarted.findUnresolved(DESTINATION)).singleElement().satisfies(marker -> {
			assertThat(marker.operationId()).isEqualTo(operationId);
			assertThat(marker.state()).isEqualTo(SyncMarkerState.PREPARED);
		});
	}

	@Test
	void connectionLossOrAmbiguousFiveHundredAfterSendStaysUncertainAndIsNotRetried() {
		RecordingGateway gateway = new RecordingGateway();
		gateway.createResult = new NotionGateway.CreateResult.Ambiguous(new NotionFailure(
			NotionFailure.Code.TRANSIENT_PROVIDER, 503, "request-id", "ambiguous after send"));
		UUID operationId = UUID.randomUUID();

		CreateOutcome outcome;
		try (Lease lease = lock()) {
			outcome = protocol(repository, gateway).create(lease,
				intent(operationId, "job-ambiguous", "ambiguous"), draft(operationId));
		}

		assertThat(outcome).isInstanceOf(CreateOutcome.Uncertain.class);
		assertThat(gateway.createCalls).hasValue(1);
		assertThat(repository.find(operationId)).get().extracting(SyncSafetyMarker::state)
			.isEqualTo(SyncMarkerState.UNCERTAIN);
		assertThat(repository.findUnresolved(DESTINATION)).extracting(SyncSafetyMarker::operationId)
			.containsExactly(operationId);
	}

	@Test
	void negativeLookupNeverReleasesUncertainMarkerButOperationIdMatchReconcilesIt() {
		RecordingGateway gateway = new RecordingGateway();
		UUID operationId = UUID.randomUUID();
		SyncSafetyMarker uncertain = prepareUncertain(operationId);

		try (Lease lease = lock()) {
			SyncMarkerProtocol protocol = protocol(repository, gateway);
			ReconciliationOutcome negative = protocol.reconcile(lease, uncertain, new NotionGateway.IdentityResult.None());
			assertThat(negative).isInstanceOf(ReconciliationOutcome.Unresolved.class);
			assertThat(repository.find(operationId)).get().extracting(SyncSafetyMarker::state)
				.isEqualTo(SyncMarkerState.UNCERTAIN);

			NotionPageSnapshot page = new NotionPageSnapshot("page-42", false, false, Map.of(
				SYNC_OPERATION_ID, new NotionPropertyValue.Text(NotionPropertyType.RICH_TEXT, operationId.toString())
			));
			ReconciliationOutcome reconciled = protocol.reconcile(lease, uncertain,
				new NotionGateway.IdentityResult.One(page));
			assertThat(reconciled).isInstanceOf(ReconciliationOutcome.Confirmed.class);
		}

		assertThat(repository.find(operationId)).get().satisfies(marker -> {
			assertThat(marker.state()).isEqualTo(SyncMarkerState.CONFIRMED);
			assertThat(marker.notionPageId()).isEqualTo("page-42");
			assertThat(marker.resolvedAt()).isNotNull();
		});
	}

	@Test
	void dualIdentityDisagreementIsRejectedBeforeAnotherPost() {
		RecordingGateway gateway = new RecordingGateway();
		UUID first = UUID.randomUUID();
		repository.prepare(intent(first, "same-id", "url-a"));

		UUID second = UUID.randomUUID();
		CreateOutcome outcome;
		try (Lease lease = lock()) {
			outcome = protocol(repository, gateway).create(lease,
				intent(second, "same-id", "url-b"), draft(second));
		}

		assertThat(outcome).isInstanceOf(CreateOutcome.Conflict.class);
		assertThat(gateway.createCalls).hasValue(0);
		assertThat(repository.find(second)).isEmpty();
	}

	private SyncSafetyMarker prepareUncertain(UUID operationId) {
		repository.prepare(intent(operationId, "job-reconcile", "reconcile"));
		repository.markInFlight(operationId, NOW.plusSeconds(1));
		return repository.markUncertain(operationId, "AMBIGUOUS", NOW.plusSeconds(2));
	}

	private Lease lock() {
		return new PostgresDestinationLock(dataSource).tryAcquire(DESTINATION, Duration.ofSeconds(1)).orElseThrow();
	}

	private static SyncMarkerProtocol protocol(SyncSafetyMarkerRepository markers, NotionGateway gateway) {
		return new SyncMarkerProtocol(markers, gateway, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
	}

	private static CreateIntent intent(UUID operationId, String externalId, String urlSuffix) {
		return new CreateIntent(operationId, DESTINATION, JobSource.HIRING_CAFE, externalId,
			URI.create("https://example.test/jobs/" + urlSuffix), NOW);
	}

	private static NotionPageDraft draft(UUID operationId) {
		return new NotionPageMapper(new JobIdentityCanonicalizer()).createDraft(job(), operationId, NOW);
	}

	private static NormalizedJob job() {
		return new NormalizedJob(JobSource.HIRING_CAFE, "job", "Backend Engineer", "Example",
			"Brazil", "Remote", List.of("Full Time"), null, NOW.minusSeconds(60), "Java", "Senior",
			BigDecimal.valueOf(5), List.of("Build services"), List.of("Java"),
			URI.create("https://example.test/jobs/job"));
	}

	private static JdbcSyncSafetyMarkerRepository repository() {
		return new JdbcSyncSafetyMarkerRepository(JdbcClient.create(dataSource),
			new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
	}

	private static class RecordingGateway implements NotionGateway {
		private final AtomicInteger createCalls = new AtomicInteger();
		private CreateResult createResult = new CreateResult.ReliablyNotSent(new NotionFailure(
			NotionFailure.Code.VALIDATION, 400, null, "not sent"));

		@Override public SchemaResult retrieveSchema() { throw new UnsupportedOperationException(); }
		@Override public IdentityResult queryByIdentity(JobSource source, String externalId, URI canonicalUrl) {
			return new IdentityResult.None();
		}
		@Override public QuotaResult scanAutomationCreations(Instant fromInclusive) {
			return new QuotaResult.Complete(List.of());
		}
		@Override public CreateResult createPage(NotionPageDraft draft, UUID operationId) {
			createCalls.incrementAndGet();
			return createResult;
		}
		@Override public UpdateResult updatePage(String pageId, NotionPagePatch patch) {
			throw new UnsupportedOperationException();
		}
	}

	private static class DelegatingRepository implements SyncSafetyMarkerRepository {
		private final SyncSafetyMarkerRepository delegate;
		private DelegatingRepository(SyncSafetyMarkerRepository delegate) { this.delegate = delegate; }
		@Override public PrepareResult prepare(CreateIntent intent) { return delegate.prepare(intent); }
		@Override public SyncSafetyMarker markInFlight(UUID operationId, Instant at) { return delegate.markInFlight(operationId, at); }
		@Override public SyncSafetyMarker confirm(UUID operationId, String notionPageId, Instant at) { return delegate.confirm(operationId, notionPageId, at); }
		@Override public SyncSafetyMarker markNotSent(UUID operationId, String failureCode, Instant at) { return delegate.markNotSent(operationId, failureCode, at); }
		@Override public SyncSafetyMarker markUncertain(UUID operationId, String failureCode, Instant at) { return delegate.markUncertain(operationId, failureCode, at); }
		@Override public SyncSafetyMarker markConflict(UUID operationId, String failureCode, Instant at) { return delegate.markConflict(operationId, failureCode, at); }
		@Override public Optional<SyncSafetyMarker> find(UUID operationId) { return delegate.find(operationId); }
		@Override public List<SyncSafetyMarker> findUnresolved(String destinationId) { return delegate.findUnresolved(destinationId); }
	}
}
