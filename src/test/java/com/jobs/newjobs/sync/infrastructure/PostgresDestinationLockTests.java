package com.jobs.newjobs.sync.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.DestinationLockLostException;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Testcontainers
class PostgresDestinationLockTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery")
		.withUsername("job_discovery")
		.withPassword("test-only-password");

	private DataSource dataSource;

	@BeforeEach
	void configureDataSource() {
		DriverManagerDataSource configured = new DriverManagerDataSource();
		configured.setUrl(POSTGRES.getJdbcUrl());
		configured.setUsername(POSTGRES.getUsername());
		configured.setPassword(POSTGRES.getPassword());
		dataSource = configured;
	}

	@Test
	void dedicatedSessionSerializesTwoInstancesAndExplicitReleaseAllowsTheNextOwner() {
		PostgresDestinationLock firstInstance = new PostgresDestinationLock(dataSource);
		PostgresDestinationLock secondInstance = new PostgresDestinationLock(dataSource);

		Lease first = firstInstance.tryAcquire("notion-source-a", Duration.ofSeconds(1)).orElseThrow();
		try {
			first.assertHeld();
			assertThat(secondInstance.tryAcquire("notion-source-a", Duration.ofMillis(150))).isEmpty();
			assertThat(secondInstance.tryAcquire("notion-source-b", Duration.ofMillis(150))).isPresent()
				.get().satisfies(Lease::close);
		}
		finally {
			first.close();
		}

		assertThat(secondInstance.tryAcquire("notion-source-a", Duration.ofSeconds(1))).isPresent()
			.get().satisfies(Lease::close);
	}

	@Test
	void acquisitionUsesTheCallerBudgetAndNeverWaitsUnbounded() {
		PostgresDestinationLock lock = new PostgresDestinationLock(dataSource);
		try (Lease ignored = lock.tryAcquire("bounded", Duration.ofSeconds(1)).orElseThrow()) {
			Instant started = Instant.now();
			assertThat(lock.tryAcquire("bounded", Duration.ofMillis(120))).isEmpty();
			assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(1));
		}
	}

	@Test
	void terminatedDedicatedConnectionIsDetectedBeforeAnotherWriteCanStart() {
		PostgresDestinationLock lock = new PostgresDestinationLock(dataSource);
		try (Lease lease = lock.tryAcquire("connection-loss", Duration.ofSeconds(1)).orElseThrow()) {
			Boolean terminated = new JdbcTemplate(dataSource).queryForObject(
				"select pg_terminate_backend(?)", Boolean.class, lease.backendPid());
			assertThat(terminated).isTrue();
			assertThatThrownBy(lease::assertHeld).isInstanceOf(DestinationLockLostException.class);
		}

		assertThat(lock.tryAcquire("connection-loss", Duration.ofSeconds(1))).isPresent()
			.get().satisfies(Lease::close);
	}
}
