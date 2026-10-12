# Phase 1 Data Model: Phases 4–8

PostgreSQL remains configuration and safety state, not a job database. `NormalizedJob`, ranking results, sync plans, and run summaries are transient Java values.

## Relationship overview

```text
SavedFilterConfiguration 1 ── contains ── 1 RankingPreferences

IntegrationCredential (configuration only, never DB)
       │
       ├── namespaces ── * ImportIdempotencyRecord
       └── admits ────── * ImportRateAdmission

SyncCoordinator ── owns ── * SyncSafetyMarker ── references opaque Notion destination/page identity
```

There is deliberately no foreign key from operational records to a persisted job: jobs are never stored locally.

## Persisted entities

### `saved_filter_configurations` (existing, extended in V3)

Purpose: user-approved discovery and ranking configuration used by preview, manual sync, and scheduler.

| Column | Type | Rules |
|---|---|---|
| `id` | UUID | Existing primary key. |
| `name` | varchar(120) | Existing non-blank, case-insensitive unique name. |
| `source` | varchar(32) | Existing provider enum text. |
| `filter_specification` | JSONB | Existing object validated into `FilterSpecification`. |
| `ranking_preferences` | JSONB | New non-null object validated into `RankingPreferences`. |
| `version` | bigint | Existing monotonic configuration revision, non-negative; not a concurrency token until a request precondition is added. |
| `created_at` | timestamptz | Existing immutable creation instant. |
| `updated_at` | timestamptz | Existing last configuration update. |

Canonical `ranking_preferences` shape:

```json
{
  "searchMode": "BOTH",
  "requiredTechnologies": [],
  "desiredTechnologies": [],
  "acceptedSeniorities": []
}
```

Validation:

- `searchMode`: `REMOTE_FROM_BRAZIL`, `INTERNATIONAL_RELOCATION`, or `BOTH`.
- Arrays contain trimmed, lower-cased, unique, non-blank strings.
- Required and desired technologies cannot overlap after alias canonicalization.
- Existing rows migrate to the explicit neutral shape above; the application never relies on an implicit null default.
- Updating either filter or ranking preferences increments `version` atomically.

Retention: until the user deletes the saved filter.

### `sync_safety_markers` (V4)

Purpose: close crash/ambiguity windows around external Notion creates. This is not execution history.

| Column | Type | Rules |
|---|---|---|
| `operation_id` | UUID | Primary key, generated before external I/O. |
| `destination_id` | varchar(128) | Opaque configured Notion data-source identifier. |
| `source` | varchar(32) | Effective trusted source. |
| `external_job_id` | varchar(512), nullable | Stable source ID when supplied. |
| `canonical_url` | varchar(2048), nullable | Conservative canonical URL fallback. |
| `external_id_hash` | char(64), nullable | SHA-256 over `source + external_job_id`; present whenever the external ID exists. |
| `canonical_url_hash` | char(64), nullable | SHA-256 over `source + canonical_url`; present whenever the URL exists. |
| `operation_type` | varchar(16) | Initially only `CREATE`. |
| `state` | varchar(16) | `PREPARED`, `IN_FLIGHT`, `CONFIRMED`, `NOT_SENT`, `UNCERTAIN`, or `CONFLICT`. |
| `notion_page_id` | varchar(128), nullable | Set only after confirmed/reconciled identity. |
| `failure_code` | varchar(64), nullable | Bounded non-secret diagnostic code, never raw body. |
| `created_at` | timestamptz | Marker creation. |
| `updated_at` | timestamptz | Last state transition. |
| `resolved_at` | timestamptz, nullable | Present only for `CONFIRMED`/`NOT_SENT`. |

Constraints and indexes:

- At least one of `external_job_id` and `canonical_url` is present.
- Unique active marker per `(destination_id, source, external_id_hash)` and independently per `(destination_id, source, canonical_url_hash)` for unresolved states, implemented with partial unique indexes. When both forms exist, both hashes are recorded so a later ID-only or URL-only attempt collides with the same protected operation.
- Before inserting a marker, query both identity hashes; hashes resolving to different operations/pages produce an item-scoped `CONFLICT` and no POST.
- Resolved timestamp/state consistency is checked.
- No title, company, description, notes, full request, or response payload is stored.

State transitions:

```text
PREPARED ── safe local proof ─────────────> NOT_SENT
    │
    └── immediately before POST ──────────> IN_FLIGHT
                                                ├── confirmed identity ─> CONFIRMED
                                                ├── ambiguous boundary ─> UNCERTAIN
                                                └── identity collision ─> CONFLICT

UNCERTAIN/CONFLICT ── reconciliation with conclusive evidence ─> CONFIRMED or NOT_SENT
```

There is no time-based transition out of `UNCERTAIN` or `CONFLICT`. Resolved markers may be purged after an operationally configured audit window only if doing so cannot affect quota or reconciliation; unresolved markers are retained until conclusively resolved.

### `import_idempotency_records` (V5)

Purpose: prevent a retried HTTP request from starting a second independent batch for at least 48 hours.

| Column | Type | Rules |
|---|---|---|
| `integration_id` | varchar(128) | Trusted credential configuration ID. |
| `idempotency_key` | varchar(255) | Client-provided, never logged. |
| `payload_hash` | char(64) | SHA-256 of canonical validated request representation. |
| `state` | varchar(24) | `RESERVED`, `PROCESSING`, `COMPLETED`, `PARTIAL`, `FAILED_PRESTART`, or `RECONCILIATION_REQUIRED`. |
| `request_id` | UUID | Server trace identifier safe to return/log. |
| `owner_token_hash` | char(64), nullable | Identifies the sole processing owner without storing a bearer/idempotency secret. |
| `received_count` | smallint | 1–50 after validation. |
| `outcome_document` | JSONB, nullable | Bounded summary plus up to 50 indexed statuses, opaque page/external IDs, and reason codes; no title, URL, description, or other job content. |
| `created_at` | timestamptz | Reservation time. |
| `updated_at` | timestamptz | Last reconciliation/update. |
| `expires_at` | timestamptz | At least `created_at + 48 hours`. |

Primary key: `(integration_id, idempotency_key)`.

Validation:

- Same primary key and different `payload_hash` is a conflict.
- Outcome, when present, has only fixed numeric buckets and exactly one item for every index `0..received_count-1`; buckets sum to `received_count`.
- Expired rows may be purged by bounded maintenance, but never cascade or release a safety marker.
- The bounded item outcomes are the minimum state needed to reconcile an identical retry without retaining job payloads or starting a second batch.

State transitions and retry ownership:

```text
new --atomic insert by one owner--> RESERVED --> PROCESSING --> COMPLETED | PARTIAL
                                          └--> FAILED_PRESTART
                                          └--> RECONCILIATION_REQUIRED
RECONCILIATION_REQUIRED --conclusive coordinator reconciliation--> COMPLETED | PARTIAL | FAILED_PRESTART
```

- Only the transaction that inserts `RESERVED` receives the owner token and may transition to `PROCESSING`.
- An identical concurrent retry never becomes a second owner. It waits within a bounded budget, then returns the stored complete outcome or a bounded `409 IDEMPOTENCY_IN_PROGRESS` with the stable `requestId` and retry guidance.
- After restart, `RESERVED` can be taken over only when no sync marker/work was started. `PROCESSING` or `RECONCILIATION_REQUIRED` must reconcile markers and Notion identities under the destination lock before finalizing; it is never blindly restarted.
- `FAILED_PRESTART` proves no item write began and may be returned as the same pre-start failure. Completed/partial retry responses are reconstructed from `outcome_document`, not promised byte-identical serialization.

### `import_rate_admissions` (V5)

Purpose: exact, cross-instance rolling-window admission control.

| Column | Type | Rules |
|---|---|---|
| `integration_id` | varchar(128) | Trusted credential configuration ID. |
| `admission_id` | UUID | Primary key/request correlation. |
| `admitted_at` | timestamptz | Database-clock admission instant. |

Indexes: `(integration_id, admitted_at)`.

Atomic admission transaction:

1. Acquire a transaction advisory lock derived from `integration_id`.
2. Remove rows older than the maintenance retention horizon (never needed for the active 60-second window).
3. Count rows with `admitted_at > transaction_timestamp() - interval '60 seconds'`.
4. If count is below 10, insert using the database clock and commit.
5. Otherwise roll back/commit without insert and derive `Retry-After` from the oldest active row, rounded up to at least one second.

Rejected requests do not create rows. Retain only enough history for the active window plus a small cleanup margin.

## Configuration-only models

### `ManualCredentialProperties`

External configuration only:

- `currentSecret`
- optional `previousSecret`
- optional `previousValidUntil`
- enabled flag

At startup, validate high entropy/minimum length, ensure current and previous differ, precompute fixed-length digests, and fail startup when manual endpoints are enabled without a valid key.

### `IntegrationCredential`

External configuration/secret manager only:

- `integrationId`
- `source` (`LINKEDIN` may be configured for test/future external collector)
- scopes containing `jobs:import`
- current secret/digest
- optional previous secret/digest and overlap expiry
- enabled/revoked state

Secrets and digests are never stored in PostgreSQL, emitted in logs, used as metric labels, or included in error responses.

### `NotionDestinationProperties`

- integration token (secret)
- database ID and data-source ID (opaque identifiers)
- expected property names/types and status values
- API version fixed to `2026-03-11`
- HTTP connect/request timeout settings
- global create quota (`300` distinct automated pages in rolling 24 hours)

### `SchedulerProperties`

External configuration only:

- global `enabled` flag, default `false`;
- global positive interval/delay and timezone;
- bounded shutdown grace period.

When enabled, every saved filter is eligible. A distributed PostgreSQL advisory lock keyed by saved-filter ID spans discovery and synchronization so the same profile cannot overlap across instances.

## Transient domain models

### `RankedJob`

Existing model: normalized job, tri-state eligibility assessment, score components, technology evidence, and explanations. It is returned by preview and consumed by sync planning; it is never persisted.

### `SyncBatch`

- trusted source and destination
- ordered normalized jobs
- optional ranked ordering metadata for discovery flows
- caller kind: `MANUAL`, `SCHEDULED`, or `IMPORT`
- deadline/lock budget
- server request/run ID

### `SyncItemOutcome`

One transient result per input index: `CREATED`, `UPDATED`, `UNCHANGED`, `INVALID`, `CONFLICTED`, `UNCERTAIN`, or `NOT_PROCESSED`, with optional opaque page ID and bounded reason code.

### `SyncRunSummary`

Fixed counters that exactly partition the received items. It is returned and logged but not used as a persistent run-history table.

## Identity and ownership rules

1. Within a source, primary identity is `Source + External Job ID`.
2. Fallback is `Source + canonical stable URL`.
3. If both identities are supplied, both are queried and must resolve consistently.
4. Cross-source similarity is review information only; it never causes automatic merge.
5. Notion owns `Status`, `User Notes`, `Applied At`, and all unknown/additional properties.
6. Every automation-created page receives immutable `Sync Operation ID` and `First Synced At`; pre-existing/manual pages never gain these ownership markers merely because they are updated. `Sync Operation ID` is the durable correlation key for ambiguous create reconciliation.
7. Backend-owned fields include normalized discovery data and sync metadata, but a null/blank incoming value never erases a non-empty stored value.
8. `First Synced At` is immutable; `Last Synced At` is sent atomically with an otherwise safe patch and considered changed only after the patch is confirmed or conclusively reconciled.
