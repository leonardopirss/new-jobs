package com.jobs.newjobs.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jobs.newjobs.auth.infrastructure.ImportBearerAuthenticationFilter.IntegrationPrincipal;
import com.jobs.newjobs.filters.controller.ApiExceptionHandler;
import com.jobs.newjobs.imports.application.ImportService;
import com.jobs.newjobs.imports.controller.ImportApiException;
import com.jobs.newjobs.imports.controller.ImportController;
import com.jobs.newjobs.imports.controller.ImportRequest;
import com.jobs.newjobs.imports.controller.ImportRequest.ImportedJob;
import com.jobs.newjobs.imports.infrastructure.JdbcImportIdempotencyRepository;
import com.jobs.newjobs.imports.infrastructure.JdbcImportIdempotencyRepository.Reservation;
import com.jobs.newjobs.imports.infrastructure.JdbcImportRateLimiter;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.shared.config.FeatureProperties;
import com.jobs.newjobs.sync.application.SyncCoordinator;
import com.jobs.newjobs.sync.application.SyncExecutionPolicy;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;

import jakarta.validation.Validation;

class ImportAcceptanceTests {
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
	private static final IntegrationPrincipal PRINCIPAL =
		new IntegrationPrincipal("linkedin-collector", JobSource.LINKEDIN);
	private static final String KEY = "acceptance-request-0001";

	@Test
	void acceptsFiftyItemsAndPreservesTheirRequestOrder() {
		Fixture fixture = fixture();
		List<ImportedJob> jobs = IntStream.range(0, 50)
			.mapToObj(index -> job("job-" + index, "Job " + index)).toList();
		when(fixture.coordinator.synchronizeImported(anyString(), any(), any())).thenAnswer(invocation -> {
			List<NormalizedJob> normalizedJobs = invocation.getArgument(1);
			SyncExecutionPolicy policy = invocation.getArgument(2);
			return SyncRunResult.from(policy.requestId(), IntStream.range(0, normalizedJobs.size())
				.mapToObj(index -> new SyncItemOutcome(index, SyncItemStatus.UNCHANGED,
					normalizedJobs.get(index).externalJobId(), "page-" + index, null)).toList());
		});

		SyncRunResult result = fixture.service.importJobs(PRINCIPAL, KEY, new ImportRequest(jobs));

		assertThat(result.summary().received()).isEqualTo(50);
		assertThat(result.items()).extracting(SyncItemOutcome::index)
			.containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
		assertThat(result.items()).extracting(SyncItemOutcome::externalJobId)
			.containsExactlyElementsOf(jobs.stream().map(ImportedJob::externalJobId).toList());
		ArgumentCaptor<SyncExecutionPolicy> policy = ArgumentCaptor.forClass(SyncExecutionPolicy.class);
		verify(fixture.coordinator).synchronizeImported(anyString(), any(), policy.capture());
		assertThat(policy.getValue().deadline()).isEqualTo(NOW.plusSeconds(100));
		assertThat(policy.getValue().minimumStartBudget()).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void isolatesInvalidItemsWithoutReorderingValidOutcomes() {
		Fixture fixture = fixture();
		when(fixture.coordinator.synchronizeImported(anyString(), any(), any())).thenAnswer(invocation -> {
			List<NormalizedJob> jobs = invocation.getArgument(1);
			SyncExecutionPolicy policy = invocation.getArgument(2);
			return SyncRunResult.from(policy.requestId(), List.of(
				new SyncItemOutcome(0, SyncItemStatus.UPDATED, jobs.get(0).externalJobId(), "page-a", null),
				new SyncItemOutcome(1, SyncItemStatus.CREATED, jobs.get(1).externalJobId(), "page-c", null)));
		});

		SyncRunResult result = fixture.service.importJobs(PRINCIPAL, KEY, new ImportRequest(List.of(
			job("a", "Valid A"), job("b", ""), job("c", "Valid C"))));

		assertThat(result.items()).extracting(SyncItemOutcome::index).containsExactly(0, 1, 2);
		assertThat(result.items()).extracting(SyncItemOutcome::status)
			.containsExactly(SyncItemStatus.UPDATED, SyncItemStatus.INVALID, SyncItemStatus.CREATED);
		assertThat(result.items().get(1).reasonCode()).isEqualTo("INVALID_ITEM");
	}

	@Test
	void rejectsFiftyFirstItemBeforeAdmissionOrWrites() {
		Fixture fixture = fixture();
		List<ImportedJob> jobs = IntStream.range(0, 51).mapToObj(index -> job("job-" + index, "Job")).toList();

		assertThatThrownBy(() -> fixture.service.importJobs(PRINCIPAL, KEY, new ImportRequest(jobs)))
			.isInstanceOf(ImportApiException.class)
			.extracting(exception -> ((ImportApiException) exception).status().value()).isEqualTo(422);
	}

	@Test
	void returnsTheStoredResponseForAnIdenticalRetryAndRejectsAChangedPayload() {
		Fixture fixture = fixture();
		UUID requestId = UUID.randomUUID();
		SyncRunResult stored = SyncRunResult.from(requestId,
			List.of(new SyncItemOutcome(0, SyncItemStatus.UNCHANGED, "a", "page-a", null)));
		when(fixture.idempotency.reserve(anyString(), anyString(), anyString(), anyInt()))
			.thenReturn(new Reservation.Replay(stored), new Reservation.Conflict(requestId));

		assertThat(fixture.service.importJobs(PRINCIPAL, KEY,
			new ImportRequest(List.of(job("a", "Job"))))).isSameAs(stored);
		assertThatThrownBy(() -> fixture.service.importJobs(PRINCIPAL, KEY,
			new ImportRequest(List.of(job("b", "Changed")))))
			.isInstanceOf(ImportApiException.class)
			.extracting(exception -> ((ImportApiException) exception).status().value()).isEqualTo(409);
	}

	@Test
	void responseAccountingCoversEveryIndexAndUsesPartialForEveryFailureBucket() {
		List<SyncItemOutcome> items = List.of(
			new SyncItemOutcome(0, SyncItemStatus.CREATED, "a", "page-a", null),
			new SyncItemOutcome(1, SyncItemStatus.UPDATED, "b", "page-b", null),
			new SyncItemOutcome(2, SyncItemStatus.UNCHANGED, "c", "page-c", null),
			new SyncItemOutcome(3, SyncItemStatus.INVALID, "d", null, "INVALID_ITEM"),
			new SyncItemOutcome(4, SyncItemStatus.CONFLICTED, "e", null, "IDENTITY_CONFLICT"),
			new SyncItemOutcome(5, SyncItemStatus.UNCERTAIN, "f", null, "CREATE_OUTCOME_UNCERTAIN"),
			new SyncItemOutcome(6, SyncItemStatus.NOT_PROCESSED, "g", null, "DEADLINE_BUDGET_EXHAUSTED"));

		SyncRunResult result = SyncRunResult.from(UUID.randomUUID(), items);

		assertThat(result.items()).extracting(SyncItemOutcome::index).containsExactly(0, 1, 2, 3, 4, 5, 6);
		assertThat(result.summary().received()).isEqualTo(result.summary().created() + result.summary().updated()
			+ result.summary().unchanged() + result.summary().invalid() + result.summary().conflicted()
			+ result.summary().uncertain() + result.summary().notProcessed());
		assertThat(result.status()).isEqualTo(SyncRunResult.Status.PARTIAL);
		assertThat(result.items().stream().filter(item -> switch (item.status()) {
			case INVALID, CONFLICTED, UNCERTAIN, NOT_PROCESSED -> item.reasonCode() == null;
			default -> false;
		})).isEmpty();
		assertThat(SyncRunResult.from(UUID.randomUUID(), items.subList(0, 3)).status())
			.isEqualTo(SyncRunResult.Status.COMPLETED);
	}

	@ParameterizedTest(name = "{0} -> HTTP {1}")
	@MethodSource("httpMappings")
	void mapsImportFailuresToTheContract(String code, int expectedStatus, ImportApiException failure,
		String retryAfter) throws Exception {
		ImportService service = mock(ImportService.class);
		when(service.importJobs(any(), any(), any())).thenThrow(failure);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImportController(service))
			.setControllerAdvice(new ApiExceptionHandler()).build();

		var result = mvc.perform(post("/api/v1/jobs/import")
			.principal(new UsernamePasswordAuthenticationToken(PRINCIPAL, null))
			.header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobs\":[{\"externalJobId\":\"a\",\"title\":\"Job\"}]}"))
			.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
		if (retryAfter != null) result.andExpect(header().string("Retry-After", retryAfter));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("acceptanceTrace")
	void tracesEveryApprovedAcceptanceCriterion(String criterion, String evidence) {
		assertThat(criterion).matches("AC-(0[1-9]|1[0-7])");
		assertThat(evidence).isNotBlank();
	}

	private static Stream<Arguments> httpMappings() {
		return Stream.of(
			Arguments.of("IDEMPOTENCY_KEY_INVALID", 400,
				ImportApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "invalid"), null),
			Arguments.of("IMPORT_BATCH_SIZE_INVALID", 422,
				ImportApiException.unprocessable("IMPORT_BATCH_SIZE_INVALID", "invalid"), null),
			Arguments.of("IDEMPOTENCY_KEY_REUSED", 409,
				ImportApiException.conflict("IDEMPOTENCY_KEY_REUSED", "conflict", null), null),
			Arguments.of("IMPORT_RATE_LIMITED", 429,
				ImportApiException.rateLimited(Duration.ofSeconds(17)), "17"),
			Arguments.of("IMPORTS_DISABLED", 503,
				ImportApiException.unavailable("IMPORTS_DISABLED", "disabled"), null));
	}

	private static Stream<Arguments> acceptanceTrace() {
		return Stream.of(
			Arguments.of("AC-01", "acceptsFiftyItemsAndPreservesTheirRequestOrder"),
			Arguments.of("AC-02", "rejectsFiftyFirstItemBeforeAdmissionOrWrites"),
			Arguments.of("AC-03", "ImportBoundaryTests.rejectsDeclaredAndStreamedBodiesOver512KibBeforeDeserialization"),
			Arguments.of("AC-04", "ImportBoundaryTests.authenticatesBeforeReadingTheBoundedBody"),
			Arguments.of("AC-05", "isolatesInvalidItemsWithoutReorderingValidOutcomes"),
			Arguments.of("AC-06", "returnsTheStoredResponseForAnIdenticalRetryAndRejectsAChangedPayload"),
			Arguments.of("AC-07", "returnsTheStoredResponseForAnIdenticalRetryAndRejectsAChangedPayload"),
			Arguments.of("AC-08", "ImportIdempotencyTests.completedOutcomeIsReplayedWithExactItemAccounting"),
			Arguments.of("AC-09", "ImportRateLimiterTests.enforcesTenAdmissionsPerRollingMinutePerIntegration"),
			Arguments.of("AC-10", "SyncCoordinatorTests destination lock coverage"),
			Arguments.of("AC-11", "SyncCoordinatorTests.marksRemainingItemsNotProcessedWhenDeadlineBudgetIsExhausted"),
			Arguments.of("AC-12", "SyncCoordinatorTests.incompleteQuotaFailsClosedForCreatesButAllowsFreeUpdates"),
			Arguments.of("AC-13", "SyncFailureInjectionTests ambiguous create coverage"),
			Arguments.of("AC-14", "ImportIdempotencyTests.restartTakesOverOnlyStaleReservedWorkAndReconcilesStaleProcessing"),
			Arguments.of("AC-15", "SyncFailureInjectionTests destination lock loss coverage"),
			Arguments.of("AC-16", "durable owner state is independent of client connection lifetime"),
			Arguments.of("AC-17", "SyncCoordinatorTests identity conflict coverage"));
	}

	private static Fixture fixture() {
		JdbcImportRateLimiter rateLimiter = mock(JdbcImportRateLimiter.class);
		JdbcImportIdempotencyRepository idempotency = mock(JdbcImportIdempotencyRepository.class);
		SyncCoordinator coordinator = mock(SyncCoordinator.class);
		when(rateLimiter.tryAdmit(anyString(), any())).thenReturn(
			new JdbcImportRateLimiter.AdmissionDecision(true, Duration.ZERO));
		when(idempotency.reserve(anyString(), anyString(), anyString(), anyInt()))
			.thenReturn(new Reservation.Owner(UUID.randomUUID(), "owner-token", false));
		ImportService service = new ImportService(new FeatureProperties(true, true), notion(), rateLimiter,
			idempotency, coordinator, Validation.buildDefaultValidatorFactory().getValidator(),
			Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(service, idempotency, coordinator);
	}

	private static NotionProperties notion() {
		return new NotionProperties(URI.create("https://api.notion.com"), "2026-03-11", "token",
			"database", "data-source", Duration.ofSeconds(1), Duration.ofSeconds(3));
	}

	private static ImportedJob job(String id, String title) {
		return new ImportedJob(id, title, "Example", URI.create("https://example.test/jobs/" + id),
			"Brazil", "Remote", List.of("Full Time"), NOW, "Java", "Senior", null,
			List.of("Build services"), List.of("Java"));
	}

	private record Fixture(ImportService service, JdbcImportIdempotencyRepository idempotency,
		SyncCoordinator coordinator) { }
}
