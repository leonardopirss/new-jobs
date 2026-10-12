---
description: "Dependency-ordered implementation tasks for phases 4–8, grouped into five-task PR batches"
---

# Tasks: Job Discovery & Notion Sync — Phases 4–8

**Input**: Design documents from `/specs/001-job-discovery-sync/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`, Constitution 2.0.0, approved decisions N1–N26 and R1–R7

**Tests**: Required by the feature specification and Constitution IV. Test tasks precede their implementation where practical.

**PR rule**: Each batch below contains exactly five sequential task IDs. Create its branch from the latest `develop`, push it, and open one PR targeting `develop`. Do not mix task IDs from adjacent batches in the same PR. Ranking batch 3 cannot begin until T011 records explicit approval.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel after its declared prerequisites because it uses different files.
- **[Story]**: Maps to specification user stories; US6 is the governed Import API added by N21–N26.
- Paths are repository-relative.

## Phase 1: Setup — PR Batch 1 (Manual Authentication)

**Purpose**: Protect all existing manual operations before adding write-capable features.

- [X] T001 Add `spring-boot-starter-security` and matching test support to `pom.xml`, preserving Spring Boot 4.1.1 dependency management and Java 25
- [X] T002 [P] Write failing manual-authentication/default-deny tests for saved-filter CRUD, preview, anonymous sanitized health, JSON `401`/`403`, no session, and no provider/service invocation on failure in `src/test/java/com/jobs/newjobs/auth/ManualApiSecurityTests.java`
- [X] T003 [P] Implement validated `ManualCredentialProperties`, current/previous key overlap, SHA-256 digest preparation, and fixed-length `MessageDigest.isEqual` verification without logging secrets in `src/main/java/com/jobs/newjobs/auth/config/ManualCredentialProperties.java` and `src/main/java/com/jobs/newjobs/auth/application/ManualCredentialVerifier.java`
- [X] T004 Implement stateless bearer authentication, `MANUAL_API` authority, sanitized entry-point/access-denied bodies, explicit protected route matchers, anonymous health only, and default deny in `src/main/java/com/jobs/newjobs/auth/config/ApiSecurityConfiguration.java` and `src/main/java/com/jobs/newjobs/auth/infrastructure/ManualBearerAuthenticationFilter.java`
- [X] T005 Configure manual-key startup validation and document separate local secrets in `src/main/resources/application.yaml`, `.env.example`, and `README.md`; run `./mvnw verify` and record no real credential in fixtures/logs

**Checkpoint**: All existing manual endpoints reject absent/invalid keys without invoking application services; the authenticated compatibility suite passes.

---

## Phase 2: Foundational Safety — PR Batch 2

**Purpose**: Establish shared contracts/configuration used by preview, sync, import, and scheduling.

- [X] T006 [P] Write canonical identity tests covering source+external-ID, conservative URL normalization, known tracking removal, identity-bearing query retention, ID/URL disagreement, and stable lexical identity in `src/test/java/com/jobs/newjobs/jobs/application/JobIdentityCanonicalizerTests.java`
- [X] T007 Implement one shared `JobIdentityCanonicalizer` used by deduplication, marker hashes, Notion lookup, and ranking tie-breaks in `src/main/java/com/jobs/newjobs/jobs/application/JobIdentityCanonicalizer.java`, then replace `URI.normalize()` identity logic in `src/main/java/com/jobs/newjobs/ranking/application/JobRankingService.java`
- [X] T008 [P] Add stable request IDs and RFC-9457-style bounded problem responses without credentials, idempotency keys, raw provider bodies, private notes, or job descriptions in `src/main/java/com/jobs/newjobs/shared/web/RequestIdFilter.java` and `src/main/java/com/jobs/newjobs/filters/controller/ApiExceptionHandler.java`
- [X] T009 [P] Add typed, startup-validated feature flags and Notion/scheduler configuration (`writes=false`, `imports=false`, scheduler `enabled=false`, positive global interval/timezone) in `src/main/java/com/jobs/newjobs/shared/config/FeatureProperties.java`, `src/main/java/com/jobs/newjobs/notion/config/NotionProperties.java`, and `src/main/java/com/jobs/newjobs/scheduling/SchedulerProperties.java`
- [X] T010 Align public DTO/error conventions with `contracts/openapi.yaml`, retaining existing preview counters and defining malformed JSON as `400` versus semantic validation as `422`, in `src/test/java/com/jobs/newjobs/contracts/OpenApiContractTests.java` and `docs/api/filter-api.md`

**Checkpoint**: Shared identity, errors, and disabled-by-default external capabilities are deterministic and tested.

---

## Phase 3: User Story 1 — Ranked Configure and Preview (Priority: P1) — PR Batch 3

**Goal**: Persist ranking preferences and return explainable ranked HiringCafe previews without any Notion write.

**Independent Test**: Save/reload a HiringCafe filter with effective ranking preferences, preview deterministic controlled jobs, verify `ELIGIBLE` before `UNKNOWN`, proven `INELIGIBLE` absent, evidence/explanations present, and zero Notion calls.

- [X] T011 [US1] Record explicit user approval or replacement for editable accepted seniorities, within-tier geography weight handling, and the recency curve before encoding R8+ behavior in `docs/architecture/decisions.md`; stop this batch if approval is absent
- [X] T012 [P] [US1] Write failing migration/repository/API tests for `ranking_preferences JSONB NOT NULL`, existing-row compatibility profile `{BOTH,[],[],[]}`, optional additive request input, effective response output, and `LINKEDIN` discovery rejection in `src/test/java/com/jobs/newjobs/filters/controller/RankingPreferencesApiTests.java`
- [X] T013 [US1] Add `V3__add_saved_filter_ranking_preferences.sql` with object-type constraint in `src/main/resources/db/migration/`, extend `SavedFilter` and JDBC mappings in `src/main/java/com/jobs/newjobs/filters/domain/SavedFilter.java` and `src/main/java/com/jobs/newjobs/filters/infrastructure/JdbcSavedFilterRepository.java`
- [X] T014 [US1] Add validated additive ranking request/response DTOs, map omitted preferences to the documented compatibility profile, restrict discovery source to `HIRING_CAFE`, and preserve existing preview summary fields in `src/main/java/com/jobs/newjobs/filters/controller/`
- [X] T015 [US1] Integrate `JobRankingService` after normalization/local filtering/deduplication and return explicit eligibility, score, technology evidence, and explanations in `src/main/java/com/jobs/newjobs/filters/application/PreviewService.java`; add R1–R7 and approved-default golden coverage in `src/test/java/com/jobs/newjobs/ranking/application/JobRankingGoldenTests.java`

**Checkpoint**: User Story 1 is independently usable, authenticated, deterministic, backward-compatible for omitted preferences, and read-only.

---

## Phase 4: User Story 2 — Notion Adapter (Priority: P1) — PR Batch 4

**Goal**: Implement the verified outbound Notion boundary without yet exposing synchronization.

**Independent Test**: Against a controllable HTTP server, validate schema, fully paginate identity/quota reads, create one marked page, and patch only backend-owned fields while preserving sentinel user properties.

- [X] T016 [P] [US2] Write failing Notion transport/schema tests for `Notion-Version: 2026-03-11`, opaque IDs, cursor passthrough, `request_status.incomplete_reason`, active/archived visibility, property types, and required `New` status in `src/test/java/com/jobs/newjobs/notion/infrastructure/NotionContractTests.java`
- [X] T017 [P] [US2] Define typed Notion port results for schema, identity `NONE/ONE/CONFLICT/INCOMPLETE`, quota evidence, confirmed/not-sent/ambiguous creates, and reconciled updates in `src/main/java/com/jobs/newjobs/notion/application/NotionGateway.java` and `src/main/java/com/jobs/newjobs/notion/domain/`
- [X] T018 [US2] Implement JDK `HttpClient` transport, Jackson mapping, complete opaque-cursor pagination, bounded reads, `Retry-After`, payload limits, and typed failures in `src/main/java/com/jobs/newjobs/notion/infrastructure/HttpNotionGateway.java`
- [X] T019 [US2] Implement upfront conceptual schema validation including immutable `Sync Operation ID`/`First Synced At`, user-owned `Status`/`User Notes`/`Applied At`, and allowance for extra user properties in `src/main/java/com/jobs/newjobs/notion/application/NotionSchemaValidator.java`
- [X] T020 [US2] Implement dual ID/URL identity lookup, create drafts with `Status=New` plus immutable operation markers, and an update allowlist that never sends archive/trash or blank erasure in `src/main/java/com/jobs/newjobs/notion/application/NotionPageMapper.java`; complete preservation/ambiguity tests in `src/test/java/com/jobs/newjobs/notion/infrastructure/NotionWriteContractTests.java`

**Checkpoint**: The adapter satisfies the outbound contract, but feature flags still prevent application writes.

---

## Phase 5: User Story 2 — Safe Coordinator (Priority: P1) — PR Batch 5

**Goal**: Serialize one Notion destination and protect every create across crashes.

**Independent Test**: Two service instances contend for one sandbox destination; one owns the session lock, marker commit always precedes POST, response loss leaves `UNCERTAIN`, and no second POST occurs.

- [X] T021 [P] [US2] Write Testcontainers tests for dedicated-connection `pg_try_advisory_lock`, five-second bounded acquisition, two-instance serialization, explicit release, and connection-loss stop behavior in `src/test/java/com/jobs/newjobs/sync/infrastructure/PostgresDestinationLockTests.java`
- [X] T022 [P] [US2] Add `V4__create_sync_safety_markers.sql` with separate nullable external-ID/URL hashes, at least one identity required, state/timestamp consistency, and partial unique active indexes per destination+source+hash in `src/main/resources/db/migration/`
- [X] T023 [US2] Implement the dedicated-session destination lock and lock-loss checks in `src/main/java/com/jobs/newjobs/sync/infrastructure/PostgresDestinationLock.java`, never holding a database transaction across Notion HTTP calls
- [X] T024 [US2] Implement marker repository/state transitions `PREPARED→IN_FLIGHT→CONFIRMED|NOT_SENT|UNCERTAIN|CONFLICT`, commit-before-POST, no timeout release, and dual-identity collision checks in `src/main/java/com/jobs/newjobs/sync/infrastructure/JdbcSyncSafetyMarkerRepository.java`
- [X] T025 [US2] Write failure-injection tests for marker commit failure, crash before POST, connection loss during/after POST, ambiguous `5xx`, negative lookup, operation-ID reconciliation, and unresolved-marker survival in `src/test/java/com/jobs/newjobs/sync/application/SyncMarkerFailureInjectionTests.java`

**Checkpoint**: No Notion create can occur without a committed durable marker and destination lock.

---

## Phase 6: User Story 2 — Manual Synchronization (Priority: P1) — PR Batch 6

**Goal**: Deliver synchronous ranked transient/saved-filter synchronization with global quota and complete outcomes.

**Independent Test**: An authenticated manual request remains open, processes a controlled ranked batch, creates/updates safely, preserves user fields, respects 300 distinct automated pages/24h, and returns reconciling item counts.

- [X] T026 [P] [US2] Write coordinator tests for full-batch deduplication, safe updates before creates, ranking-ordered create selection, pre-write conflict isolation, incomplete-quota fail-closed behavior, manual-page exclusion, free updates, and the 300th/301st boundaries in `src/test/java/com/jobs/newjobs/sync/application/SyncCoordinatorTests.java`
- [X] T027 [US2] Implement immutable automation ownership scan and global 300-distinct-page rolling 24-hour quota using one controllable clock under the destination lock in `src/main/java/com/jobs/newjobs/sync/application/NotionCreationQuotaService.java`
- [X] T028 [US2] Implement the single `SyncCoordinator` for reconciliation, dual-identity planning, user-safe updates, ranked creates, markers, quota, lock-loss checks, and transient structured summary in `src/main/java/com/jobs/newjobs/sync/application/SyncCoordinator.java`
- [X] T029 [P] [US2] Write authenticated contract tests for exactly one of `savedFilterId` or transient HiringCafe criteria, synchronous response, `503` only before work, and exact item/summary accounting in `src/test/java/com/jobs/newjobs/sync/controller/ManualSyncApiTests.java`
- [X] T030 [US2] Implement `POST /api/v1/jobs/sync`, saved-filter snapshotting, transient ranked discovery, bounded response DTOs, and feature-flag enforcement in `src/main/java/com/jobs/newjobs/sync/controller/ManualSyncController.java` and `src/main/java/com/jobs/newjobs/sync/application/ManualSyncService.java`

**Checkpoint**: The authenticated end-to-end manual flow is independently demonstrable in a Notion sandbox.

---

## Phase 7: User Story 4 — Preserve Tracking Data and Views (Priority: P2) — PR Batch 7

**Goal**: Prove ongoing synchronization cannot damage user-managed tracking data and provide supported views/fallback.

**Independent Test**: Modify status, notes, applied date, and an extra property; resync changed source data and verify byte-for-byte preservation with no archive/unarchive/delete/recreate.

- [X] T031 [P] [US4] Add sentinel integration tests for `Status`, `User Notes`, `Applied At`, extra properties, immutable operation/first-sync markers, null/blank non-erasure, and absence-without-signal behavior in `src/test/java/com/jobs/newjobs/notion/UserPropertyOwnershipTests.java`
- [X] T032 [US4] Harden update reconciliation so `Last Synced At` is atomic with the owned patch and ambiguous updates are reread before retry in `src/main/java/com/jobs/newjobs/sync/application/NotionUpdateService.java`
- [X] T033 [P] [US4] Add contract tests for current Notion Views API capability and non-destructive fallback behavior in `src/test/java/com/jobs/newjobs/notion/infrastructure/NotionViewProvisionerTests.java`
- [X] T034 [US4] Implement optional idempotent supported view provisioning without making synchronization depend on view success in `src/main/java/com/jobs/newjobs/notion/application/NotionViewProvisioner.java`
- [X] T035 [US4] Document the ten logical views and exact manual fallback setup when capability/permission is unavailable in `docs/integrations/notion-views.md`; run the User Story 4 preservation suite

**Checkpoint**: User tracking state is preserved and views never compromise data synchronization.

---

## Phase 8: User Story 6 — Governed Import Admission (N21–N26) — PR Batch 8

**Goal**: Authenticate source-bound collectors and admit requests exactly once within configured limits.

**Independent Test**: Separate LINKEDIN-bound test credential imports at most 50 items/512 KiB, cannot spoof source, supports bounded rotation, rejects the 11th rolling-window admission, and never shares the manual key.

- [X] T036 [P] [US6] Write import authentication/body-limit tests for separate high-entropy credentials, `jobs:import`, source binding, revoke/disable, bounded previous-key overlap, fixed-length digest comparison, 512 KiB declared/chunked enforcement, and no secret logging in `src/test/java/com/jobs/newjobs/imports/controller/ImportBoundaryTests.java`
- [X] T037 [P] [US6] Add `V5__create_import_admission_state.sql` with idempotency primary key `(integration_id,idempotency_key)`, 48-hour minimum expiry, owner token hash, bounded item outcome document, and indexed `(integration_id,admitted_at)` rate rows in `src/main/resources/db/migration/`
- [X] T038 [US6] Implement typed environment/secret-manager integration credentials, source-bound principal, scope checks, rotation expiry, and import-only bearer chain in `src/main/java/com/jobs/newjobs/auth/config/IntegrationCredentialProperties.java` and `src/main/java/com/jobs/newjobs/auth/infrastructure/ImportBearerAuthenticationFilter.java`
- [X] T039 [US6] Implement pre-deserialization declared/counting-stream 512 KiB enforcement and 1–50 item validation without accepting a body source field in `src/main/java/com/jobs/newjobs/imports/infrastructure/BoundedImportBodyFilter.java` and `src/main/java/com/jobs/newjobs/imports/controller/ImportRequest.java`
- [X] T040 [US6] Implement atomic PostgreSQL rolling 10-admissions/60s decisions under a per-integration transaction advisory lock, database time, non-consuming rejects, and computed `Retry-After` in `src/main/java/com/jobs/newjobs/imports/infrastructure/JdbcImportRateLimiter.java`; add two-instance tests in `src/test/java/com/jobs/newjobs/imports/infrastructure/ImportRateLimiterTests.java`

**Checkpoint**: Import admission is secure, source-bound, bounded, and exact across instances; it still cannot bypass coordinator safety.

---

## Phase 9: User Story 6 — Import Idempotency and Processing (N21–N26) — PR Batch 9

**Goal**: Process admitted batches through the shared coordinator with crash-safe HTTP idempotency and exact response accounting.

**Independent Test**: Run AC-01 through AC-17, including concurrent/restart retries, ambiguous create, disconnect, deadline exhaustion, lock loss, and identity conflict; every index is accounted once.

- [X] T041 [P] [US6] Write idempotency state-machine tests for one reservation owner, same-key/different-hash `409`, bounded concurrent retry, restart takeover only when no work started, processing reconciliation, 48-hour expiry independent of markers, and itemized replay in `src/test/java/com/jobs/newjobs/imports/infrastructure/ImportIdempotencyTests.java`
- [X] T042 [US6] Implement canonical payload hashing and atomic `RESERVED→PROCESSING→COMPLETED|PARTIAL|FAILED_PRESTART|RECONCILIATION_REQUIRED` ownership/transitions in `src/main/java/com/jobs/newjobs/imports/infrastructure/JdbcImportIdempotencyRepository.java`
- [X] T043 [US6] Implement the 100-second deadline, at most five-second destination-lock budget, stop-before-unsafe-start, in-flight protection, request-order processing, and `NOT_PROCESSED` classification in `src/main/java/com/jobs/newjobs/imports/application/ImportService.java`
- [X] T044 [US6] Implement `POST /api/v1/jobs/import` with required `Idempotency-Key`, feature flag, exact status mapping, `Retry-After`, stable reason codes, and response invariants in `src/main/java/com/jobs/newjobs/imports/controller/ImportController.java`
- [X] T045 [US6] Implement and pass the traceability suite for N26 `AC-01` through `AC-17`, requiring indices `0..received-1` exactly once, bucket sum equality, `COMPLETED` only with no failure buckets, and reason codes for failed statuses in `src/test/java/com/jobs/newjobs/imports/ImportAcceptanceTests.java`

**Checkpoint**: The governed Import API is complete without a LinkedIn collector or direct external Notion writer.

---

## Phase 10: User Story 3 — Scheduled Discovery and Observability (Priority: P2) — PR Batch 10

**Goal**: Run every saved filter at one external interval without overlap and with actionable redacted telemetry.

**Independent Test**: Two instances trigger the same saved filter; only one holds the per-filter lock across discovery+sync, misfire is skipped/reported, and no secret/job text appears in telemetry.

- [ ] T046 [P] [US3] Write scheduler tests for global external enabled/interval/timezone validation, all-filter snapshotting, distributed same-filter non-overlap across instances, skip-not-catch-up misfire, and provider-disabled behavior in `src/test/java/com/jobs/newjobs/scheduling/SavedFilterSchedulerTests.java`
- [ ] T047 [US3] Implement PostgreSQL advisory lock keyed by saved-filter ID across discovery plus coordinator invocation and the globally configured scheduler in `src/main/java/com/jobs/newjobs/scheduling/SavedFilterScheduler.java` and `src/main/java/com/jobs/newjobs/scheduling/PostgresFilterRunLock.java`
- [ ] T048 [P] [US3] Add bounded-cardinality metrics and structured-log tests for run/source/filter IDs, outcome counts, lock wait, quota, Notion status/latency, unresolved markers, ranking tiers, and import admissions with secret/content redaction in `src/test/java/com/jobs/newjobs/observability/OperationalTelemetryTests.java`
- [ ] T049 [US3] Implement operational metrics/log events, database/config readiness, and degraded external-boundary reporting without sensitive health details in `src/main/java/com/jobs/newjobs/observability/OperationalTelemetry.java` and `src/main/java/com/jobs/newjobs/observability/ExternalBoundaryHealthIndicator.java`
- [ ] T050 [US3] Implement graceful shutdown that stops admissions/triggers and gives bounded completion/reconciliation to started writes in `src/main/java/com/jobs/newjobs/shared/lifecycle/GracefulWorkShutdown.java`; document scheduler activation in `README.md`

**Checkpoint**: Authorized scheduled automation is non-overlapping, observable, redacted, and safely stoppable.

---

## Phase 11: User Story 5 — Resilience and Release Hardening (Priority: P3) — PR Batch 11

**Goal**: Close all production gates and prove the complete phase 4–8 behavior.

**Independent Test**: Run all automated/sandbox scenarios and verify every gate has evidence, owner, rollback, and fail-closed behavior.

- [ ] T051 [P] [US5] Complete the failure-injection matrix for Notion `429/5xx`, pagination incompleteness, malformed items, lock loss, crash boundaries, client disconnect, and retry exhaustion in `src/test/java/com/jobs/newjobs/resilience/ExternalFailureInjectionTests.java`
- [ ] T052 [P] [US5] Add backward-compatible deployment/rollback, Flyway, PostgreSQL backup/restore, secret rotation/revocation, unresolved-marker reconciliation, and incident runbooks in `docs/operations/`
- [ ] T053 [US5] Execute the authenticated sandbox flow from `specs/001-job-discovery-sync/quickstart.md` and record sanitized evidence in `artifacts/phase8-sandbox-report.md`
- [ ] T054 [US5] Reconfirm the approved ranking profile and close Notion schema/capability, TLS, telemetry, backup/restore, and HiringCafe authorization gates in `docs/architecture/decisions.md` and `docs/operations/release-checklist.md`
- [ ] T055 [US5] Run `./mvnw verify`, validate `contracts/openapi.yaml`, confirm AC-01–AC-17 and all story checkpoints, update implemented capabilities/limitations in `README.md`, and keep every capability with an unresolved gate disabled

**Checkpoint**: Phases 4–8 are release-ready; unresolved provider authorization prevents only the affected automation, never weakens safeguards.

## Dependencies & Execution Order

### PR batch dependencies

```text
Batch 1 auth (T001–T005)
  └─> Batch 2 shared safety (T006–T010)
       ├─> Batch 3 ranked preview (T011–T015; explicit approval gate)
       └─> Batch 4 Notion adapter (T016–T020)
             └─> Batch 5 coordinator (T021–T025)
                  └─> Batch 6 manual sync (T026–T030)
                       ├─> Batch 7 ownership/views (T031–T035)
                       ├─> Batch 8 import admission (T036–T040)
                       │    └─> Batch 9 import processing (T041–T045)
                       └─> Batch 10 scheduler/telemetry (T046–T050)
                                  └─> Batch 11 release (T051–T055)
```

- A batch branch starts from latest `develop` after its dependencies are merged. If PRs are stacked, declare the base/dependency and retarget after merge.
- T011 is a hard approval gate; batches not dependent on ranking formula details may proceed, but T012–T015 may not.
- Notion writes stay disabled through batches 4–5 and are enabled in a sandbox only for batch 6 validation.
- Import and scheduler remain disabled until their own checkpoint and release gates pass.

### Parallel opportunities

- Tasks marked `[P]` within a batch may be developed in parallel only after earlier non-parallel prerequisites are satisfied and only when they do not edit the same files.
- Batch 3 ranked preview and batch 4 Notion adapter can proceed independently after batch 2, subject to T011.
- After batch 6, ownership/views, import admission, and scheduler work are separable, but each PR still targets the merged `develop` baseline.

## Implementation Strategy

1. Deliver authenticated read-only compatibility first (batch 1).
2. Establish deterministic identity/errors/configuration (batch 2).
3. Integrate approved ranking into preview and filters (batch 3).
4. Build the Notion boundary and safety coordinator before exposing a write endpoint (batches 4–6).
5. Prove user ownership, then add governed import and scheduling through the same coordinator (batches 7–10).
6. Close failure-injection and operational gates last (batch 11).

## Format Validation

- 55 total tasks, numbered sequentially T001–T055.
- 11 PR batches, exactly five tasks per batch.
- Every task uses checkbox + ID + optional `[P]` + required story label in story phases + concrete file path.
- Every user story has an independent test and checkpoint; US6 traces all 17 N26 scenarios.
