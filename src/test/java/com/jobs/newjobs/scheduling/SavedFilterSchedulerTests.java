package com.jobs.newjobs.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jobs.newjobs.filters.application.PreviewService;
import com.jobs.newjobs.filters.application.RankedPreviewResult;
import com.jobs.newjobs.filters.application.SavedFilterService;
import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.observability.OperationalTelemetry;
import com.jobs.newjobs.ranking.domain.RankingPreferences;
import com.jobs.newjobs.ranking.domain.SearchMode;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown;
import com.jobs.newjobs.sync.application.SyncCoordinator;
import com.jobs.newjobs.sync.domain.SyncRunResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Testcontainers
class SavedFilterSchedulerTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
		.withDatabaseName("job_discovery").withUsername("job_discovery").withPassword("test-only-password");
	private static DataSource dataSource;

	@BeforeAll
	static void dataSource() {
		DriverManagerDataSource configured = new DriverManagerDataSource();
		configured.setUrl(POSTGRES.getJdbcUrl()); configured.setUsername(POSTGRES.getUsername());
		configured.setPassword(POSTGRES.getPassword()); dataSource = configured;
	}

	@Test
	void validatesGlobalIntervalTimezoneAndShutdownBounds() {
		assertThatThrownBy(() -> properties(true, true, Duration.ZERO, ZoneId.of("UTC")))
			.hasMessageContaining("interval");
		assertThatThrownBy(() -> new SchedulerProperties(true, true, Duration.ofHours(1), null,
			Duration.ofSeconds(30))).hasMessageContaining("timezone");
		assertThatThrownBy(() -> new SchedulerProperties(true, true, Duration.ofHours(1), ZoneId.of("UTC"),
			Duration.ofMinutes(6))).hasMessageContaining("shutdown grace");
	}

	@Test
	void snapshotsEverySavedFilterAndRunsDiscoveryBeforeSharedCoordinator() {
		SavedFilterService filters = mock(SavedFilterService.class);
		PreviewService preview = mock(PreviewService.class);
		SyncCoordinator coordinator = mock(SyncCoordinator.class);
		PostgresFilterRunLock lock = mock(PostgresFilterRunLock.class);
		PostgresFilterRunLock.Lease lease = mock(PostgresFilterRunLock.Lease.class);
		SavedFilter first = filter("first"); SavedFilter second = filter("second");
		when(filters.list()).thenReturn(List.of(first, second));
		when(lock.tryAcquire(any())).thenReturn(Optional.of(lease));
		when(preview.preview(any(), any(), any())).thenReturn(previewResult());
		when(coordinator.synchronize(any(), any())).thenAnswer(invocation ->
			SyncRunResult.from(UUID.randomUUID(), List.of()));
		SavedFilterScheduler scheduler = scheduler(properties(true, true, Duration.ofHours(6), ZoneId.of("UTC")),
			filters, preview, coordinator, lock, new SimpleMeterRegistry());

		scheduler.runCycle();

		verify(filters).list();
		verify(preview, org.mockito.Mockito.times(2)).preview(any(), any(), any());
		verify(coordinator, org.mockito.Mockito.times(2)).synchronize(any(), any());
	}

	@Test
	void remainsFailClosedWhenProviderAuthorizationGateIsOpen() {
		SavedFilterService filters = mock(SavedFilterService.class);
		PreviewService preview = mock(PreviewService.class);
		SavedFilterScheduler scheduler = scheduler(properties(true, false, Duration.ofHours(1),
			ZoneId.of("America/Sao_Paulo")), filters, preview, mock(SyncCoordinator.class),
			mock(PostgresFilterRunLock.class), new SimpleMeterRegistry());

		scheduler.runCycle();

		verifyNoInteractions(filters, preview);
	}

	@Test
	void twoInstancesCannotHoldTheSameFilterLockConcurrently() {
		PostgresFilterRunLock first = new PostgresFilterRunLock(dataSource);
		PostgresFilterRunLock second = new PostgresFilterRunLock(dataSource);
		UUID filterId = UUID.randomUUID();
		try (PostgresFilterRunLock.Lease lease = first.tryAcquire(filterId).orElseThrow()) {
			assertThat(second.tryAcquire(filterId)).isEmpty();
		}
		assertThat(second.tryAcquire(filterId)).isPresent().get().satisfies(PostgresFilterRunLock.Lease::close);
	}

	@Test
	void overlappingTickIsRecordedAsMisfireInsteadOfCatchUp() throws Exception {
		SavedFilterService filters = mock(SavedFilterService.class);
		CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
		when(filters.list()).thenAnswer(invocation -> { entered.countDown(); release.await(); return List.of(); });
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		SavedFilterScheduler scheduler = scheduler(properties(true, true, Duration.ofHours(1), ZoneId.of("UTC")),
			filters, mock(PreviewService.class), mock(SyncCoordinator.class), mock(PostgresFilterRunLock.class), meters);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var first = executor.submit(scheduler::runCycle);
			entered.await(); scheduler.runCycle(); release.countDown(); first.get();
		}
		assertThat(meters.get("jobs.scheduler.cycles").tag("outcome", "MISFIRE").counter().count()).isOne();
		verify(filters).list();
	}

	private static SavedFilterScheduler scheduler(SchedulerProperties properties, SavedFilterService filters,
		PreviewService preview, SyncCoordinator coordinator, PostgresFilterRunLock lock, SimpleMeterRegistry meters) {
		return new SavedFilterScheduler(properties, filters, preview, coordinator, notion(), lock,
			new OperationalTelemetry(meters), new GracefulWorkShutdown(properties));
	}

	private static SchedulerProperties properties(boolean enabled, boolean authorized, Duration interval, ZoneId zone) {
		return new SchedulerProperties(enabled, authorized, interval, zone, Duration.ofSeconds(1));
	}

	private static SavedFilter filter(String name) {
		return new SavedFilter(UUID.randomUUID(), name, JobSource.HIRING_CAFE,
			new FilterSpecification(List.of(), List.of()),
			new RankingPreferences(SearchMode.BOTH, List.of(), List.of(), List.of()), 0,
			Instant.parse("2026-10-12T12:00:00Z"), Instant.parse("2026-10-12T12:00:00Z"));
	}

	private static RankedPreviewResult previewResult() {
		return new RankedPreviewResult(List.of(), new RankingPreferences(SearchMode.BOTH, List.of(), List.of(), List.of()),
			0, 0, 0, null, 0, false, List.of());
	}

	private static NotionProperties notion() {
		return new NotionProperties(URI.create("https://api.notion.com"), "2026-03-11", "token", "database",
			"data-source", Duration.ofSeconds(1), Duration.ofSeconds(3));
	}
}
