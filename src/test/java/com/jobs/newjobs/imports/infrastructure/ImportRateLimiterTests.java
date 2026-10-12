package com.jobs.newjobs.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ImportRateLimiterTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery")
		.withUsername("job_discovery")
		.withPassword("test-only-password");

	private static DataSource dataSource;
	private JdbcTemplate jdbc;

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
	void clearAdmissions() {
		jdbc = new JdbcTemplate(dataSource);
		jdbc.update("delete from import_rate_admissions");
		jdbc.update("delete from import_idempotency_records");
	}

	@Test
	void migrationEnforcesIdempotencyNamespaceAndMinimumFortyEightHourExpiry() {
		UUID requestId = UUID.randomUUID();
		jdbc.update("""
			insert into import_idempotency_records (
			  integration_id, idempotency_key, payload_hash, state, request_id, owner_token_hash,
			  received_count, expires_at
			) values (?, ?, ?, 'RESERVED', ?, ?, 1, transaction_timestamp() + interval '48 hours')
			""", "linkedin-collector", "request-0001", "a".repeat(64), requestId, "d".repeat(64));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
			insert into import_idempotency_records (
			  integration_id, idempotency_key, payload_hash, state, request_id, owner_token_hash,
			  received_count, expires_at
			) values (?, ?, ?, 'RESERVED', ?, ?, 1, transaction_timestamp() + interval '48 hours')
			""", "linkedin-collector", "request-0001", "b".repeat(64), UUID.randomUUID(), "e".repeat(64)))
			.isInstanceOf(DataIntegrityViolationException.class);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
			insert into import_idempotency_records (
			  integration_id, idempotency_key, payload_hash, state, request_id, owner_token_hash,
			  received_count, expires_at
			) values (?, ?, ?, 'RESERVED', ?, ?, 1, transaction_timestamp() + interval '47 hours')
			""", "linkedin-collector", "request-0002", "c".repeat(64), UUID.randomUUID(), "f".repeat(64)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void twoInstancesAtomicallyAdmitOnlyTenRequestsInOneRollingWindow() throws Exception {
		JdbcImportRateLimiter first = new JdbcImportRateLimiter(dataSource);
		JdbcImportRateLimiter second = new JdbcImportRateLimiter(dataSource);
		try (var executor = Executors.newFixedThreadPool(20)) {
			List<Callable<JdbcImportRateLimiter.AdmissionDecision>> attempts = java.util.stream.IntStream.range(0, 20)
				.mapToObj(index -> (Callable<JdbcImportRateLimiter.AdmissionDecision>) () ->
					(index % 2 == 0 ? first : second).tryAdmit("linkedin-collector", UUID.randomUUID()))
				.toList();
			long admitted = executor.invokeAll(attempts).stream()
				.map(future -> {
					try { return future.get(); }
					catch (Exception exception) { throw new AssertionError(exception); }
				})
				.filter(JdbcImportRateLimiter.AdmissionDecision::admitted)
				.count();

			assertThat(admitted).isEqualTo(10);
			assertThat(jdbc.queryForObject(
				"select count(*) from import_rate_admissions where integration_id = ?",
				Long.class, "linkedin-collector")).isEqualTo(10L);
		}
	}

	@Test
	void rejectedRequestDoesNotConsumeCapacityAndRetryAfterUsesOldestActiveAdmission() {
		JdbcImportRateLimiter limiter = new JdbcImportRateLimiter(dataSource);
		for (int index = 0; index < 10; index++) {
			assertThat(limiter.tryAdmit("linkedin-collector", UUID.randomUUID()).admitted()).isTrue();
		}

		JdbcImportRateLimiter.AdmissionDecision rejected =
			limiter.tryAdmit("linkedin-collector", UUID.randomUUID());
		assertThat(rejected.admitted()).isFalse();
		assertThat(rejected.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(60));
		assertThat(jdbc.queryForObject("select count(*) from import_rate_admissions", Long.class)).isEqualTo(10L);
	}

	@Test
	void admissionOlderThanSixtySecondsLeavesTheRollingWindow() {
		JdbcImportRateLimiter limiter = new JdbcImportRateLimiter(dataSource);
		for (int index = 0; index < 10; index++) {
			assertThat(limiter.tryAdmit("linkedin-collector", UUID.randomUUID()).admitted()).isTrue();
		}
		jdbc.update("update import_rate_admissions set admitted_at = clock_timestamp() - interval '61 seconds'");

		assertThat(limiter.tryAdmit("linkedin-collector", UUID.randomUUID()).admitted()).isTrue();
		assertThat(jdbc.queryForObject(
			"select count(*) from import_rate_admissions where admitted_at > clock_timestamp() - interval '60 seconds'",
			Long.class)).isEqualTo(1L);
	}
}
