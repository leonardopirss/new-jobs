package com.jobs.newjobs.imports.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.auth.infrastructure.ImportBearerAuthenticationFilter.IntegrationPrincipal;
import com.jobs.newjobs.imports.controller.ImportApiException;
import com.jobs.newjobs.imports.controller.ImportRequest;
import com.jobs.newjobs.imports.controller.ImportRequest.ImportedJob;
import com.jobs.newjobs.imports.infrastructure.JdbcImportIdempotencyRepository;
import com.jobs.newjobs.imports.infrastructure.JdbcImportIdempotencyRepository.Reservation;
import com.jobs.newjobs.imports.infrastructure.JdbcImportRateLimiter;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.shared.config.FeatureProperties;
import com.jobs.newjobs.sync.application.SyncCoordinator;
import com.jobs.newjobs.sync.application.SyncExecutionPolicy;
import com.jobs.newjobs.sync.application.SyncPreflightUnavailableException;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;

import jakarta.validation.Validator;

@Service
public final class ImportService {
	private static final Duration DEADLINE = Duration.ofSeconds(100);
	private static final Duration LOCK_BUDGET = Duration.ofSeconds(5);
	private static final Duration MINIMUM_START_BUDGET = Duration.ofSeconds(1);
	private static final Duration CONCURRENT_RETRY_WAIT = Duration.ofMillis(250);

	private final FeatureProperties features;
	private final NotionProperties notion;
	private final JdbcImportRateLimiter rateLimiter;
	private final JdbcImportIdempotencyRepository idempotency;
	private final SyncCoordinator coordinator;
	private final Validator validator;
	private final Clock clock;

	public ImportService(FeatureProperties features, NotionProperties notion, JdbcImportRateLimiter rateLimiter,
		JdbcImportIdempotencyRepository idempotency, SyncCoordinator coordinator, Validator validator, Clock clock) {
		this.features = features; this.notion = notion; this.rateLimiter = rateLimiter;
		this.idempotency = idempotency; this.coordinator = coordinator; this.validator = validator; this.clock = clock;
	}

	public SyncRunResult importJobs(IntegrationPrincipal principal, String idempotencyKey, ImportRequest request) {
		Instant deadline = clock.instant().plus(DEADLINE);
		request.validateBatch();
		validateKey(idempotencyKey);
		if (!features.importsEnabled() || !features.notionWritesEnabled()) {
			throw ImportApiException.unavailable("IMPORTS_DISABLED", "Job imports are disabled");
		}
		String hash = JdbcImportIdempotencyRepository.canonicalPayloadHash(principal.source(), request);

		JdbcImportRateLimiter.AdmissionDecision admission =
			rateLimiter.tryAdmit(principal.integrationId(), UUID.randomUUID());
		if (!admission.admitted()) throw ImportApiException.rateLimited(admission.retryAfter());

		Reservation reservation = idempotency.reserve(
			principal.integrationId(), idempotencyKey, hash, request.jobs().size());
		if (reservation instanceof Reservation.InProgress) {
			reservation = idempotency.await(principal.integrationId(), idempotencyKey, hash, CONCURRENT_RETRY_WAIT);
		}
		if (reservation instanceof Reservation.Replay replay) return replay.result();
		if (reservation instanceof Reservation.Conflict) {
			throw ImportApiException.conflict("IDEMPOTENCY_KEY_REUSED",
				"Idempotency key was already used with a different payload", null);
		}
		if (reservation instanceof Reservation.InProgress inProgress) {
			throw ImportApiException.inProgress(inProgress.requestId(), Duration.ofSeconds(1));
		}
		if (reservation instanceof Reservation.FailedPrestart failed) {
			throw ImportApiException.unavailable(failed.reasonCode(), "The original import failed before processing");
		}

		Reservation.Owner owner = (Reservation.Owner) reservation;
		if (!owner.reconciliation()) {
			idempotency.markProcessing(principal.integrationId(), idempotencyKey, owner.ownerToken());
		}
		try {
			SyncRunResult result = process(principal, request, owner.requestId(), deadline);
			idempotency.complete(principal.integrationId(), idempotencyKey, owner.ownerToken(), result);
			return result;
		}
		catch (SyncPreflightUnavailableException exception) {
			if (owner.reconciliation()) {
				idempotency.markReconciliationRequired(
					principal.integrationId(), idempotencyKey, owner.ownerToken());
			}
			else {
				idempotency.failPrestart(principal.integrationId(), idempotencyKey, owner.ownerToken(),
					owner.requestId(), exception.reasonCode());
			}
			throw exception;
		}
		catch (RuntimeException exception) {
			idempotency.markReconciliationRequired(
				principal.integrationId(), idempotencyKey, owner.ownerToken());
			throw exception;
		}
	}

	private SyncRunResult process(IntegrationPrincipal principal, ImportRequest request, UUID requestId,
		Instant deadline) {
		List<SyncItemOutcome> outcomes = new ArrayList<>();
		List<NormalizedJob> valid = new ArrayList<>();
		List<Integer> originalIndices = new ArrayList<>();
		for (int index = 0; index < request.jobs().size(); index++) {
			ImportedJob item = request.jobs().get(index);
			if (item == null || !validator.validate(item).isEmpty()) {
				outcomes.add(new SyncItemOutcome(index, SyncItemStatus.INVALID,
					item == null ? null : item.externalJobId(), null, "INVALID_ITEM"));
				continue;
			}
			valid.add(normalized(principal, item));
			originalIndices.add(index);
		}

		if (!valid.isEmpty()) {
			SyncExecutionPolicy policy = new SyncExecutionPolicy(
				requestId, deadline, LOCK_BUDGET, MINIMUM_START_BUDGET);
			SyncRunResult synchronizedResult = coordinator.synchronizeImported(notion.dataSourceId(), valid, policy);
			for (SyncItemOutcome item : synchronizedResult.items()) {
				int original = originalIndices.get(item.index());
				outcomes.add(new SyncItemOutcome(original, item.status(), item.externalJobId(),
					item.notionPageId(), item.reasonCode()));
			}
		}
		outcomes.sort(Comparator.comparingInt(SyncItemOutcome::index));
		return SyncRunResult.from(requestId, outcomes);
	}

	private static NormalizedJob normalized(IntegrationPrincipal principal, ImportedJob item) {
		NormalizedJob job = new NormalizedJob(principal.source(), item.externalJobId(), item.title(), item.company(),
			item.location(), item.workplaceType(), item.employmentTypes(), null, item.publishedAt(),
			item.requirementsSummary(), item.seniorityLevel(), item.minimumYearsExperience(),
			item.responsibilities(), item.technologies(), item.url());
		return job;
	}

	private static void validateKey(String key) {
		if (key == null || key.isBlank() || key.length() < 8 || key.length() > 255) {
			throw ImportApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
				"Idempotency-Key must contain between 8 and 255 characters");
		}
	}
}
