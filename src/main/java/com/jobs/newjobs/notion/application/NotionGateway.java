package com.jobs.newjobs.notion.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.domain.NotionFailure;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPageSnapshot;
import com.jobs.newjobs.notion.domain.NotionQuotaEvidence;
import com.jobs.newjobs.notion.domain.NotionSchema;

public interface NotionGateway {
	SchemaResult retrieveSchema();

	IdentityResult queryByIdentity(JobSource source, String externalId, URI canonicalUrl);

	QuotaResult scanAutomationCreations(Instant fromInclusive);

	CreateResult createPage(NotionPageDraft draft, UUID operationId);

	UpdateResult updatePage(String pageId, NotionPagePatch patch);

	sealed interface SchemaResult permits SchemaResult.Available, SchemaResult.Failed {
		record Available(NotionSchema schema) implements SchemaResult { }
		record Failed(NotionFailure failure) implements SchemaResult { }
	}

	sealed interface IdentityResult permits IdentityResult.None, IdentityResult.One,
		IdentityResult.Conflict, IdentityResult.Incomplete {
		record None() implements IdentityResult { }
		record One(NotionPageSnapshot page) implements IdentityResult { }
		record Conflict(List<NotionPageSnapshot> pages) implements IdentityResult {
			public Conflict { pages = List.copyOf(pages); }
		}
		record Incomplete(String reason, NotionFailure failure) implements IdentityResult { }
	}

	sealed interface QuotaResult permits QuotaResult.Complete, QuotaResult.Incomplete {
		record Complete(List<NotionQuotaEvidence> pages) implements QuotaResult {
			public Complete { pages = List.copyOf(pages); }
		}
		record Incomplete(String reason, NotionFailure failure) implements QuotaResult { }
	}

	sealed interface CreateResult permits CreateResult.Confirmed, CreateResult.ReliablyNotSent,
		CreateResult.Ambiguous {
		record Confirmed(String pageId, NotionPageSnapshot snapshot) implements CreateResult { }
		record ReliablyNotSent(NotionFailure failure) implements CreateResult { }
		record Ambiguous(NotionFailure failure) implements CreateResult { }
	}

	sealed interface UpdateResult permits UpdateResult.Reconciled, UpdateResult.ConclusiveNotApplied,
		UpdateResult.ReliablyNotSent, UpdateResult.Ambiguous {
		record Reconciled(NotionPageSnapshot snapshot) implements UpdateResult { }
		record ConclusiveNotApplied(NotionPageSnapshot snapshot, NotionFailure failure) implements UpdateResult { }
		record ReliablyNotSent(NotionFailure failure) implements UpdateResult { }
		record Ambiguous(NotionFailure failure) implements UpdateResult { }
	}
}
