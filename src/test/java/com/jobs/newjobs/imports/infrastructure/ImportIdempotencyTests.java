package com.jobs.newjobs.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.jobs.newjobs.imports.infrastructure.JdbcImportIdempotencyRepository.Reservation;
import com.jobs.newjobs.imports.controller.ImportRequest;
import com.jobs.newjobs.imports.controller.ImportRequest.ImportedJob;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;

@Testcontainers
class ImportIdempotencyTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery").withUsername("job_discovery").withPassword("test-only-password");
	private static DataSource dataSource;
	private JdbcTemplate jdbc;
	private JdbcImportIdempotencyRepository first;
	private JdbcImportIdempotencyRepository second;

	@BeforeAll
	static void migrate() {
		DriverManagerDataSource configured = new DriverManagerDataSource();
		configured.setUrl(POSTGRES.getJdbcUrl()); configured.setUsername(POSTGRES.getUsername());
		configured.setPassword(POSTGRES.getPassword()); dataSource = configured;
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@BeforeEach
	void setUp() {
		jdbc = new JdbcTemplate(dataSource);
		jdbc.update("delete from import_idempotency_records");
		jdbc.update("delete from sync_safety_markers");
		first = repository(); second = repository();
	}

	@Test
	void grantsOneReservationOwnerAndBoundsAnIdenticalConcurrentRetry() {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Reservation> one = executor.submit(() -> {
				start.await();
				return first.reserve("collector", "request-0001", "a".repeat(64), 1);
			});
			Future<Reservation> two = executor.submit(() -> {
				start.await();
				return second.reserve("collector", "request-0001", "a".repeat(64), 1);
			});
			start.countDown();
			List<Reservation> results = List.of(one.get(), two.get());
			Reservation.Owner owner = (Reservation.Owner) results.stream()
				.filter(Reservation.Owner.class::isInstance).findFirst().orElseThrow();
			Reservation.InProgress retry = (Reservation.InProgress) results.stream()
				.filter(Reservation.InProgress.class::isInstance).findFirst().orElseThrow();
			assertThat(owner.ownerToken()).isNotBlank();
			assertThat(retry.requestId()).isEqualTo(owner.requestId());
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	@Test
	void rejectsSameKeyWithDifferentHash() {
		first.reserve("collector", "request-0001", "a".repeat(64), 1);
		assertThat(second.reserve("collector", "request-0001", "b".repeat(64), 1))
			.isInstanceOf(Reservation.Conflict.class);
	}

	@Test
	void canonicalHashIsFramedSourceBoundAndOrderSensitive() {
		ImportedJob firstJob = job("a", new BigDecimal("5.0"));
		ImportedJob equivalentFirstJob = job("a", new BigDecimal("5.00"));
		ImportedJob secondJob = job("b", null);
		String first = JdbcImportIdempotencyRepository.canonicalPayloadHash(JobSource.LINKEDIN,
			new ImportRequest(List.of(firstJob, secondJob)));
		String equivalent = JdbcImportIdempotencyRepository.canonicalPayloadHash(JobSource.LINKEDIN,
			new ImportRequest(List.of(equivalentFirstJob, secondJob)));

		assertThat(equivalent).isEqualTo(first);
		assertThat(JdbcImportIdempotencyRepository.canonicalPayloadHash(JobSource.HIRING_CAFE,
			new ImportRequest(List.of(firstJob, secondJob)))).isNotEqualTo(first);
		assertThat(JdbcImportIdempotencyRepository.canonicalPayloadHash(JobSource.LINKEDIN,
			new ImportRequest(List.of(secondJob, firstJob)))).isNotEqualTo(first);
	}

	@Test
	void restartTakesOverOnlyStaleReservedWorkAndReconcilesStaleProcessing() {
		Reservation.Owner reserved = (Reservation.Owner) first.reserve("collector", "request-0001", "a".repeat(64), 1);
		jdbc.update("""
			update import_idempotency_records
			set created_at = created_at - interval '6 seconds', updated_at = updated_at - interval '6 seconds'
			""");
		Reservation.Owner takeover = (Reservation.Owner) second.reserve("collector", "request-0001", "a".repeat(64), 1);
		assertThat(takeover.requestId()).isEqualTo(reserved.requestId());
		assertThat(takeover.reconciliation()).isFalse();

		second.markProcessing("collector", "request-0001", takeover.ownerToken());
		jdbc.update("""
			update import_idempotency_records
			set created_at = created_at - interval '6 seconds', updated_at = updated_at - interval '6 seconds'
			""");
		assertThat(first.reserve("collector", "request-0001", "a".repeat(64), 1))
			.isInstanceOf(Reservation.InProgress.class);
		jdbc.update("""
			update import_idempotency_records
			set created_at = created_at - interval '100 seconds', updated_at = updated_at - interval '100 seconds'
			""");
		Reservation.Owner reconciliation = (Reservation.Owner) first.reserve("collector", "request-0001", "a".repeat(64), 1);
		assertThat(reconciliation.reconciliation()).isTrue();
	}

	@Test
	void completedOutcomeIsReplayedWithExactItemAccounting() {
		Reservation.Owner owner = (Reservation.Owner) first.reserve("collector", "request-0001", "a".repeat(64), 2);
		first.markProcessing("collector", "request-0001", owner.ownerToken());
		SyncRunResult outcome = SyncRunResult.from(owner.requestId(), List.of(
			new SyncItemOutcome(0, SyncItemStatus.UPDATED, "1", "page-1", null),
			new SyncItemOutcome(1, SyncItemStatus.NOT_PROCESSED, "2", null, "DEADLINE_BUDGET_EXHAUSTED")));
		first.complete("collector", "request-0001", owner.ownerToken(), outcome);

		Reservation.Replay replay = (Reservation.Replay) second.reserve(
			"collector", "request-0001", "a".repeat(64), 2);
		assertThat(replay.result()).isEqualTo(outcome);
	}

	@Test
	void idempotencyExpiryDoesNotDeleteOrResolveSafetyMarkers() {
		first.reserve("collector", "request-0001", "a".repeat(64), 1);
		jdbc.update("""
			update import_idempotency_records
			set created_at = created_at - interval '49 hours',
			    updated_at = updated_at - interval '49 hours',
			    expires_at = expires_at - interval '49 hours'
			""");
		jdbc.update("""
			insert into sync_safety_markers (
			 operation_id,destination_id,source,external_job_id,external_id_hash,operation_type,state,created_at,updated_at
			) values (?, 'destination', 'LINKEDIN', 'job-1', ?, 'CREATE', 'UNCERTAIN', transaction_timestamp(), transaction_timestamp())
			""", UUID.randomUUID(), "c".repeat(64));

		assertThat(second.reserve("collector", "request-0001", "a".repeat(64), 1))
			.isInstanceOf(Reservation.Owner.class);
		assertThat(jdbc.queryForObject("select count(*) from sync_safety_markers", Long.class)).isEqualTo(1L);
	}

	private static JdbcImportIdempotencyRepository repository() {
		return new JdbcImportIdempotencyRepository(JdbcClient.create(dataSource),
			new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
	}

	private static ImportedJob job(String id, BigDecimal years) {
		return new ImportedJob(id, "Backend Engineer", "Example", URI.create("https://example.test/" + id),
			"Brazil", "Remote", List.of("Full Time"), Instant.parse("2026-10-12T12:00:00Z"),
			"Java", "Senior", years, List.of("Build services"), List.of("Java"));
	}
}
