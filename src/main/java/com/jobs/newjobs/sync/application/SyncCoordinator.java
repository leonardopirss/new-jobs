package com.jobs.newjobs.sync.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer.CanonicalIdentity;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer.IdentityRelationship;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.application.NotionSchemaValidator;
import com.jobs.newjobs.ranking.domain.RankedJob;
import com.jobs.newjobs.sync.application.NotionCreationQuotaService.QuotaAvailability;
import com.jobs.newjobs.sync.application.SyncMarkerProtocol.CreateOutcome;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository.CreateIntent;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;
import com.jobs.newjobs.sync.domain.SyncSafetyMarker;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.DestinationLockException;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.DestinationLockLostException;
import com.jobs.newjobs.sync.infrastructure.PostgresDestinationLock.Lease;

@Service
public final class SyncCoordinator {
	private static final Duration LOCK_BUDGET = Duration.ofSeconds(5);

	private final PostgresDestinationLock destinationLock;
	private final NotionGateway notion;
	private final NotionSchemaValidator schemaValidator;
	private final JobIdentityCanonicalizer canonicalizer;
	private final NotionPageMapper pageMapper;
	private final NotionUpdateService updateService;
	private final SyncSafetyMarkerRepository markers;
	private final SyncMarkerProtocol markerProtocol;
	private final NotionCreationQuotaService quotaService;
	private final Clock clock;

	public SyncCoordinator(
		PostgresDestinationLock destinationLock,
		NotionGateway notion,
		NotionSchemaValidator schemaValidator,
		JobIdentityCanonicalizer canonicalizer,
		NotionPageMapper pageMapper,
		NotionUpdateService updateService,
		SyncSafetyMarkerRepository markers,
		SyncMarkerProtocol markerProtocol,
		NotionCreationQuotaService quotaService,
		Clock clock
	) {
		this.destinationLock = destinationLock;
		this.notion = notion;
		this.schemaValidator = schemaValidator;
		this.canonicalizer = canonicalizer;
		this.pageMapper = pageMapper;
		this.updateService = updateService;
		this.markers = markers;
		this.markerProtocol = markerProtocol;
		this.quotaService = quotaService;
		this.clock = clock;
	}

	public SyncRunResult synchronize(String destinationId, List<RankedJob> rankedJobs) {
		return synchronize(destinationId, rankedJobs,
			new SyncExecutionPolicy(UUID.randomUUID(), null, LOCK_BUDGET, Duration.ZERO));
	}

	public SyncRunResult synchronize(String destinationId, List<RankedJob> rankedJobs, SyncExecutionPolicy policy) {
		return synchronizeJobs(destinationId, rankedJobs.stream().map(RankedJob::job).toList(), policy);
	}

	public SyncRunResult synchronizeImported(String destinationId, List<NormalizedJob> jobs,
		SyncExecutionPolicy policy) {
		return synchronizeJobs(destinationId, jobs, policy);
	}

	private SyncRunResult synchronizeJobs(String destinationId, List<NormalizedJob> requestedJobs,
		SyncExecutionPolicy policy) {
		if (destinationId == null || destinationId.isBlank()) {
			throw new SyncPreflightUnavailableException("DESTINATION_NOT_CONFIGURED",
				"Notion destination is not configured");
		}
		List<NormalizedJob> jobs = List.copyOf(requestedJobs);
		try (Lease lease = acquire(destinationId, policy)) {
			validateSchema(lease);
			reconcileUnresolved(lease);
			return execute(policy, lease, jobs);
		}
		catch (SyncPreflightUnavailableException exception) {
			throw exception;
		}
		catch (DestinationLockException exception) {
			throw new SyncPreflightUnavailableException("DESTINATION_LOCK_UNAVAILABLE",
				"Notion destination lock is unavailable", exception);
		}
	}

	private Lease acquire(String destinationId, SyncExecutionPolicy policy) {
		Duration budget = policy.effectiveLockBudget(clock.instant());
		return destinationLock.tryAcquire(destinationId, budget)
			.orElseThrow(() -> new SyncPreflightUnavailableException("DESTINATION_LOCK_BUSY",
				"Notion destination is currently synchronized by another operation"));
	}

	private void validateSchema(Lease lease) {
		lease.assertHeld();
		NotionGateway.SchemaResult schema = notion.retrieveSchema();
		if (schema instanceof NotionGateway.SchemaResult.Failed) {
			throw new SyncPreflightUnavailableException("NOTION_SCHEMA_UNAVAILABLE",
				"Notion schema could not be retrieved");
		}
		NotionSchemaValidator.ValidationResult validation = schemaValidator.validate(
			((NotionGateway.SchemaResult.Available) schema).schema());
		if (validation instanceof NotionSchemaValidator.ValidationResult.Invalid) {
			throw new SyncPreflightUnavailableException("NOTION_SCHEMA_INVALID",
				"Notion schema is incompatible with synchronization");
		}
	}

	private void reconcileUnresolved(Lease lease) {
		for (SyncSafetyMarker marker : markers.findUnresolved(lease.destinationId())) {
			lease.assertHeld();
			NotionGateway.IdentityResult identity = notion.queryByIdentity(
				marker.source(), marker.externalJobId(), marker.canonicalUrl());
			markerProtocol.reconcile(lease, marker, identity);
		}
	}

	private SyncRunResult execute(SyncExecutionPolicy policy, Lease lease, List<NormalizedJob> jobs) {
		UUID requestId = policy.requestId();
		SyncItemOutcome[] outcomes = new SyncItemOutcome[jobs.size()];
		CanonicalIdentity[] identities = new CanonicalIdentity[jobs.size()];
		NotionGateway.IdentityResult[] destinationMatches = new NotionGateway.IdentityResult[jobs.size()];

		canonicalizeAndDeduplicate(jobs, identities, outcomes);
		for (int index = 0; index < jobs.size(); index++) {
			if (outcomes[index] != null) continue;
			if (!policy.canStart(clock.instant())) {
				outcomes[index] = outcome(index, jobs.get(index), SyncItemStatus.NOT_PROCESSED,
					null, "DEADLINE_BUDGET_EXHAUSTED");
				continue;
			}
			CanonicalIdentity identity = identities[index];
			lease.assertHeld();
			destinationMatches[index] = notion.queryByIdentity(
				identity.source(), identity.externalJobId(), identity.canonicalUrl());
			if (destinationMatches[index] instanceof NotionGateway.IdentityResult.Conflict) {
				outcomes[index] = outcome(index, jobs.get(index), SyncItemStatus.CONFLICTED,
					null, "IDENTITY_CONFLICT");
			}
			else if (destinationMatches[index] instanceof NotionGateway.IdentityResult.Incomplete) {
				outcomes[index] = outcome(index, jobs.get(index), SyncItemStatus.NOT_PROCESSED,
					null, "IDENTITY_VISIBILITY_INCOMPLETE");
			}
		}

		for (int index = 0; index < jobs.size(); index++) {
			if (outcomes[index] != null || !(destinationMatches[index] instanceof NotionGateway.IdentityResult.One one)) {
				continue;
			}
			if (!policy.canStart(clock.instant())) {
				outcomes[index] = outcome(index, jobs.get(index), SyncItemStatus.NOT_PROCESSED,
					one.page().pageId(), "DEADLINE_BUDGET_EXHAUSTED");
				continue;
			}
			try {
				lease.assertHeld();
			}
			catch (DestinationLockLostException lost) {
				return finishAfterLockLoss(requestId, jobs, outcomes);
			}
			NormalizedJob job = jobs.get(index);
			NotionGateway.UpdateResult update = updateService.update(one.page().pageId(), job);
			outcomes[index] = switch (update) {
				case NotionGateway.UpdateResult.Reconciled reconciled ->
					outcome(index, job, SyncItemStatus.UPDATED, reconciled.snapshot().pageId(), null);
				case NotionGateway.UpdateResult.ConclusiveNotApplied notApplied ->
					outcome(index, job, SyncItemStatus.NOT_PROCESSED,
						notApplied.snapshot().pageId(), "UPDATE_NOT_APPLIED");
				case NotionGateway.UpdateResult.ReliablyNotSent ignored ->
					outcome(index, job, SyncItemStatus.NOT_PROCESSED, one.page().pageId(), "UPDATE_NOT_SENT");
				case NotionGateway.UpdateResult.Ambiguous ignored ->
					outcome(index, job, SyncItemStatus.UNCERTAIN, one.page().pageId(), "UPDATE_OUTCOME_UNCERTAIN");
			};
		}

		boolean hasCreateCandidates = false;
		for (int index = 0; index < jobs.size(); index++) {
			if (outcomes[index] == null && destinationMatches[index] instanceof NotionGateway.IdentityResult.None) {
				hasCreateCandidates = true;
				break;
			}
		}
		if (!hasCreateCandidates) {
			return SyncRunResult.from(requestId, Arrays.asList(outcomes));
		}

		QuotaAvailability quota;
		try {
			quota = quotaService.inspect(lease);
		}
		catch (DestinationLockLostException lost) {
			return finishAfterLockLoss(requestId, jobs, outcomes);
		}
		int remaining = quota instanceof QuotaAvailability.Available available ? available.remaining() : 0;
		String unavailableReason = quota instanceof QuotaAvailability.Incomplete incomplete
			? incomplete.reasonCode() : "CREATE_QUOTA_EXHAUSTED";

		for (int index = 0; index < jobs.size(); index++) {
			if (outcomes[index] != null || !(destinationMatches[index] instanceof NotionGateway.IdentityResult.None)) {
				continue;
			}
			NormalizedJob job = jobs.get(index);
			if (!policy.canStart(clock.instant())) {
				outcomes[index] = outcome(index, job, SyncItemStatus.NOT_PROCESSED,
					null, "DEADLINE_BUDGET_EXHAUSTED");
				continue;
			}
			if (remaining == 0) {
				outcomes[index] = outcome(index, job, SyncItemStatus.NOT_PROCESSED, null, unavailableReason);
				continue;
			}
			try {
				lease.assertHeld();
				outcomes[index] = create(lease, index, job, identities[index]);
				if (outcomes[index].status() == SyncItemStatus.CREATED) remaining--;
			}
			catch (DestinationLockLostException lost) {
				outcomes[index] = outcome(index, job, SyncItemStatus.NOT_PROCESSED, null, "DESTINATION_LOCK_LOST");
				unavailableReason = "DESTINATION_LOCK_LOST";
				remaining = 0;
			}
		}

		return SyncRunResult.from(requestId, Arrays.asList(outcomes));
	}

	private static SyncRunResult finishAfterLockLoss(UUID requestId, List<NormalizedJob> jobs,
		SyncItemOutcome[] outcomes) {
		for (int index = 0; index < outcomes.length; index++) {
			if (outcomes[index] == null) {
				outcomes[index] = outcome(index, jobs.get(index), SyncItemStatus.NOT_PROCESSED,
					null, "DESTINATION_LOCK_LOST");
			}
		}
		return SyncRunResult.from(requestId, Arrays.asList(outcomes));
	}

	private void canonicalizeAndDeduplicate(List<NormalizedJob> jobs, CanonicalIdentity[] identities,
		SyncItemOutcome[] outcomes) {
		for (int index = 0; index < jobs.size(); index++) {
			NormalizedJob job = jobs.get(index);
			try {
				identities[index] = canonicalizer.canonicalize(job);
			}
			catch (IllegalArgumentException exception) {
				outcomes[index] = outcome(index, job, SyncItemStatus.INVALID, null, "INVALID_IDENTITY");
				continue;
			}
			boolean matchesPrior = false;
			for (int prior = 0; prior < index; prior++) {
				if (identities[prior] == null) continue;
				IdentityRelationship relationship = identities[index].relationshipTo(identities[prior]);
				if (relationship == IdentityRelationship.MATCH) {
					matchesPrior = true;
				}
				else if (relationship == IdentityRelationship.CONFLICT) {
					outcomes[index] = outcome(index, job, SyncItemStatus.CONFLICTED, null, "BATCH_IDENTITY_CONFLICT");
					outcomes[prior] = outcome(prior, jobs.get(prior), SyncItemStatus.CONFLICTED,
						null, "BATCH_IDENTITY_CONFLICT");
				}
			}
			if (outcomes[index] == null && matchesPrior) {
				outcomes[index] = outcome(index, job, SyncItemStatus.UNCHANGED, null, "DUPLICATE_IN_BATCH");
			}
		}
	}

	private SyncItemOutcome create(Lease lease, int index, NormalizedJob job, CanonicalIdentity identity) {
		UUID operationId = UUID.randomUUID();
		Instant now = clock.instant();
		CreateIntent intent = new CreateIntent(operationId, lease.destinationId(), identity.source(),
			identity.externalJobId(), identity.canonicalUrl(), now);
		CreateOutcome created = markerProtocol.create(lease, intent, pageMapper.createDraft(job, operationId, now));
		return switch (created) {
			case CreateOutcome.Confirmed confirmed -> outcome(index, job, SyncItemStatus.CREATED,
				confirmed.marker().notionPageId(), null);
			case CreateOutcome.NotSent ignored -> outcome(index, job, SyncItemStatus.NOT_PROCESSED,
				null, "CREATE_NOT_SENT");
			case CreateOutcome.Uncertain ignored -> outcome(index, job, SyncItemStatus.UNCERTAIN,
				null, "CREATE_OUTCOME_UNCERTAIN");
			case CreateOutcome.Existing ignored -> outcome(index, job, SyncItemStatus.UNCERTAIN,
				null, "UNRESOLVED_CREATE_MARKER");
			case CreateOutcome.Conflict ignored -> outcome(index, job, SyncItemStatus.CONFLICTED,
				null, "CREATE_MARKER_CONFLICT");
		};
	}

	private static SyncItemOutcome outcome(int index, NormalizedJob job, SyncItemStatus status,
		String pageId, String reason) {
		return new SyncItemOutcome(index, status, job.externalJobId(), pageId, reason);
	}
}
