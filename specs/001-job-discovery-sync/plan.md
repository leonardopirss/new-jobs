# Implementation Plan: Job Discovery & Notion Sync — Phases 4–8

**Feature ID**: `001-job-discovery-sync` | **Implementation branches**: `feature/job-discovery-phase4-batch-*` from latest `develop` | **Date**: 2026-10-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification plus the approved MVP1 decision package, with phases 1–3 already implemented.

## Summary

Complete phases 4–8 of the job discovery MVP: secure every operator-facing endpoint with manual bearer authentication, connect the existing deterministic ranking engine to preview/saved-filter/sync flows, implement the Notion adapter and a crash-safe shared synchronization coordinator, expose the governed batch import API, add scheduling and observability, and finish production-readiness validation.

The implementation remains a single Java/Spring Boot service. PostgreSQL stores saved configuration and only the minimal operational records expressly allowed by Constitution 2.0. Notion writes are serialized by a destination-scoped PostgreSQL session advisory lock and guarded by durable pre-write markers. Both manual synchronization and imports reuse the same coordinator. Ranking is performed after normalization/deduplication and before quota-based creation selection.

### Requirements precedence

When artifacts disagree, implementation follows this order:

1. `.specify/memory/constitution.md` version 2.0.0.
2. `.specify/job-discovery-mvp1/docs/01-approved-decisions.md` (including N17–N25).
3. The current request to integrate ranking and manual authentication.
4. MVP1 architecture, contracts, acceptance matrix, and failure-injection plan.
5. The original `spec.md` only where it does not conflict with later decisions.

Therefore FR-003, FR-017, FR-042, SC-011, and the original ranking assumptions are superseded where they prohibit the minimal safety state, governed imports, or ranking integration. This plan does not authorize LinkedIn scraping, LinkedIn authentication, or a Java-based LinkedIn collector.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1.1, Spring MVC, Spring Validation, Spring JDBC, Flyway; add Spring Security for stateless bearer authentication. Use JDK HTTP Client and Jackson for Notion rather than an additional production SDK.

**Storage**: PostgreSQL 17; JSONB for filter/ranking configuration; relational tables for synchronization markers, import idempotency, and exact rate-limit admissions. No job mirror, credential table, or full execution ledger.

**Testing**: JUnit 5, Spring Boot test slices, Testcontainers PostgreSQL, existing fake MCP transport, and a controllable local HTTP server for Notion contract/failure tests.

**Target Platform**: Linux container/process with PostgreSQL and outbound HTTPS access to Notion and the supported discovery provider.

**Project Type**: Single backend web service.

**Performance Goals**: preview/sync ordering deterministic for identical input and clock; import admits at most 10 requests per credential in any rolling 60 seconds; import accepts at most 50 items/512 KiB; import stops starting work by its 100-second internal deadline; destination lock acquisition budget at most 5 seconds.

**Constraints**: default-deny API security; TLS outside localhost; Notion API version `2026-03-11`; complete cursor pagination; provider `Retry-After` honored within remaining deadline; no destructive Notion mutations; user-owned Notion properties preserved; no blind retry after an ambiguous write; global automated create quota of 300 distinct pages per rolling UTC 24 hours; no concurrent automated writers for one destination.

**Scale/Scope**: personal/small-team MVP, one Notion destination, multiple saved filters and import credentials, batch sizes up to 50. Correctness and recoverability take priority over throughput.

## Constitution Check

*GATE: evaluated before research and re-evaluated after design.*

| Constitutional principle / constraint | Design evidence | Result |
|---|---|---|
| I. Specification and Approval Gates | Artifacts record precedence and all tasks remain blocked until this plan is explicitly approved. Ranking R1–R7 is approved; editable seniority and the exact recency/within-tier formulas are presented as a named pre-Phase-4 approval gate, not silently adopted. | PASS for planning; implementation gate open |
| II. Provider Fidelity and Explicit Uncertainty | Current Notion contracts were verified against official documentation. HiringCafe is the sole discovery source; LinkedIn is rejected by discovery routes and reserved only for model/import binding. Unknown eligibility/evidence stays explicit. | PASS with provider authorization release gate |
| III. Data Ownership, Minimal Durable State, and Safe Synchronization | Only saved configuration, safety markers, 48-hour idempotency, and distributed admissions persist. Marker precedes POST; ambiguous writes fail closed; update allowlist preserves all user-owned properties. | PASS |
| IV. Verification at External Boundaries | Contract, deterministic, two-instance, and failure-injection tests are required, including explicit traceability to all N26 scenarios AC-01 through AC-17. | PASS by design; execution evidence pending |
| V. Simplicity, Security, and Operability | One stateless Spring Boot service; manual/import credentials are separate external secrets; calls/deadlines/retries are bounded; telemetry is redacted. | PASS |

**Post-design re-check**: PASS for task generation. Implementation MUST NOT cross the ranking-default approval task, enable external writes, or enable provider automation until the corresponding explicit gates close. No constitutional complexity exception is required.

## Project Structure

### Documentation (this feature)

```text
specs/001-job-discovery-sync/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   ├── manual-api.md
│   ├── import-api.md
│   └── notion-adapter.md
└── tasks.md                  # generated later by speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/com/jobs/newjobs/
├── auth/                     # manual/import authentication and SecurityFilterChain
├── filters/                  # existing saved filter and preview modules
├── hiringcafe/               # existing discovery adapter; authorization-gated
├── imports/                  # import admission, endpoint, idempotency and rate state
├── jobs/                     # normalized job model
├── notion/                   # port, schema/identity rules, current HTTP adapter
├── ranking/                  # existing deterministic ranking engine
├── scheduling/               # scheduled saved-filter runs
└── sync/                     # coordinator, endpoint, lock, markers and quota

src/main/resources/
├── application.yml
└── db/migration/
    ├── V3__add_saved_filter_ranking_preferences.sql
    ├── V4__create_sync_safety_markers.sql
    └── V5__create_import_admission_state.sql

src/test/java/com/jobs/newjobs/
├── auth/
├── filters/
├── imports/
├── notion/
├── scheduling/
├── sync/
└── ranking/                  # existing golden and evidence tests
```

**Structure Decision**: extend the existing package-by-feature monolith. Controllers depend on application services; external and JDBC implementations remain infrastructure adapters. The shared coordinator is the only route to automated Notion mutations.

## Delivery Plan: Phases 4–8

### Phase 4 — Secure API, integrate ranking, implement Notion adapter

**Objective**: establish secure operator access and the complete read/write boundary before synchronization exists.

1. Add stateless Spring Security configuration.
   - Authenticate `Authorization: Bearer <manual-key>` for saved-filter CRUD, preview, and manual sync endpoints.
   - Load the high-entropy manual key and optional previous key from environment/secret manager; compare digests in constant time; never log credentials.
   - Keep manual credentials separate from import integrations and authorities.
   - Permit only liveness/readiness health without credentials and without sensitive details; deny all other unmatched routes by default.
   - Disable sessions, form login, generated passwords, request cache, and CSRF for this bearer-only API; return bounded JSON `401`/`403` responses.

2. Integrate ranking into existing discovery flows.
   - **Approval gate before ranking implementation**: record explicit user approval or replacement of the proposed v1 defaults that are outside R1–R7: editable accepted-seniority inputs and matching rule, geography-weight redistribution inside an eligibility tier, and the 24-hour linear recency curve. No task that encodes these defaults may start before the gate closes.
   - Add `rankingPreferences` additively to preview and saved-filter request/response contracts; during the compatibility window, an omitted request field maps to the documented neutral profile and responses always expose the effective profile.
   - Persist validated preferences in new `ranking_preferences JSONB NOT NULL`; migrate existing filters to an explicit neutral default (`BOTH`, empty technology and seniority sets).
   - After discovery, normalization, local filtering, and deduplication, call `JobRankingService`; remove only `INELIGIBLE`, order `ELIGIBLE` before `UNKNOWN`, then apply the existing deterministic score/tie-break rules.
   - Return score components, eligibility evidence, technology evidence, and explanations in preview results.
   - Preserve approved R1–R7 exactly: technology/geography/seniority/recency base weights 40/30/15/15, technology required/desired split 75/25, evidence factors `1/.5/0/0`, missing-dimension redistribution, and deterministic tie-breaks. Golden tests trace each rule. Gated formula details enter the contract only after approval.
   - Restrict every saved-filter, preview, transient-sync, and scheduler discovery source to `HIRING_CAFE`; reject `LINKEDIN` before provider dispatch.

3. Implement the Notion outbound adapter.
   - Pin and send `Notion-Version: 2026-03-11`; identify resources by opaque database/data-source/page IDs, never generated URLs.
   - Validate the destination schema and allowed status values before any mutation.
   - Query the data source with complete cursor pagination and explicit archived visibility when supported; fail closed on incomplete result status.
   - Match `Source + External Job ID`, falling back to `Source + Canonical URL`; isolate multiple/contradictory matches.
   - Create with `Status=New`, immutable `First Synced At`, immutable `Sync Operation ID` for reconciliation/automation ownership, backend-owned fields, and supported description blocks.
   - Patch only an explicit backend-owned property allowlist. Never send `Status`, `User Notes`, `Applied At`, extra user properties, `is_archived`, or `in_trash`; blank source data never erases a known value.
   - Enforce Notion payload/property limits locally and handle `429`/`Retry-After`, `5xx`, timeouts, and ambiguous writes without unsafe blind retries.

**Exit criteria**: protected manual endpoints, persisted ranking preferences, ranked previews, adapter contract tests for pagination/schema/identity/property ownership/errors, and no Notion write reachable outside the adapter port.

### Phase 5 — Shared synchronization coordinator and manual sync

**Objective**: deliver safe end-to-end manual discovery-to-Notion synchronization.

1. Create `SyncCoordinator` as the single orchestration path for manual, scheduled, and imported jobs.
2. Acquire a destination-scoped PostgreSQL session advisory lock on a dedicated JDBC connection; poll only within the caller's lock budget, retain the connection through reconciliation/quota/writes, and stop new writes if the lock is lost.
3. Reconcile all unresolved markers before planning new creates. Negative search alone is never proof that a request was not sent.
4. Compute the global 300-distinct-page rolling 24-hour create quota under the lock from immutable automation ownership plus `First Synced At`, with complete visibility. Manual pages are excluded and updates are free. Any uncertain post-send marker, malformed quota evidence, or incomplete pagination blocks new creates while proven-safe updates may continue. A pre-write identity conflict isolates only its items/pages and does not block independent work.
5. Deduplicate and identity-match the full batch before writes. Update known pages first, then choose create candidates in ranking order until the global remaining quota is exhausted.
6. Before each create, commit a minimal `PREPARED` marker with separate hashes for external ID and canonical URL; transition to `IN_FLIGHT` immediately before POST; write its operation ID into the new Notion page; resolve only as `CONFIRMED` or reliably `NOT_SENT`; preserve post-send `UNCERTAIN`/`CONFLICT` without timeout expiry.
7. Add synchronous `POST /api/v1/jobs/sync` for either a saved filter ID or transient discovery filter plus ranking preferences. Authenticate with the manual principal and return a structured, bounded per-run summary.
8. Add integration and crash-window tests for lock contention/loss, marker commit failure, connection loss before and after POST, ambiguous timeout, duplicate/conflicting identity, the 300-page quota boundary, archived pages, and user-owned field preservation.

**Exit criteria**: a manual authenticated sync can rank and safely upsert a discovered batch into a sandbox destination; all acceptance and failure-injection cases for coordinator safety pass.

### Phase 6 — Governed import API

**Objective**: allow an external collector to submit normalized jobs without sharing operator credentials or bypassing synchronization safety.

1. Implement `POST /api/v1/jobs/import` exactly as the import contract specifies.
2. Configure integration credentials from environment/secret manager with ID, source binding, `jobs:import` scope, current digest, optional previous digest, and bounded overlap expiry. Do not create a credentials table.
3. Reject over 512 KiB before JSON materialization using both declared-length and counting-stream enforcement; validate 1–50 items and derive effective source only from the authenticated integration.
4. Reserve the idempotency key atomically by `(integration_id, key)`, canonical payload hash, owner token, state, and expiry of at least 48 hours. Only the successful reservation owner may start work. Same key/different hash is `409`; an identical concurrent retry performs bounded waiting then reconstructs/reconciles the current itemized outcome without starting work; an abandoned owner may be taken over only after reconciliation proves no unprotected work remains. A byte-identical response is not promised.
5. Enforce an exact shared rolling window in PostgreSQL: under a per-integration transaction advisory lock, count admissions in the previous 60 seconds and insert only if below 10. Rejected requests do not consume capacity; return computed `Retry-After`.
6. Apply the 100-second internal deadline with at most 5 seconds for destination lock acquisition. Stop starting items when the remaining budget is unsafe; classify untouched items `NOT_PROCESSED` while protecting any started external write.
7. Preserve request index/order in item results. Imported batches are not re-ranked from untrusted client preferences; they reuse the coordinator's identity, quota, marker, and write rules in submitted order.
8. Add concurrent multi-instance tests for idempotency/rate admission and itemized response accounting, authentication/source binding, rotation overlap, payload limits, disconnects, and partial/uncertain outcomes. Trace and pass all N26 cases `AC-01` through `AC-17`, including a concurrent retry while the owner is `RESERVED`/`PROCESSING`.

**Exit criteria**: contract and concurrency suites prove the endpoint cannot exceed its limits, impersonate another source, duplicate accepted HTTP work, or bypass coordinator safety. A `LINKEDIN`-bound test credential may exist; no LinkedIn collector is implemented.

### Phase 7 — Scheduling, observability, and operational views

**Objective**: automate approved saved filters with enough evidence to operate safely.

1. Add a timezone-aware scheduler over all saved filters, controlled by a validated global interval and global enabled flag supplied through external configuration. A scheduled run uses persisted ranking preferences and invokes `SyncCoordinator`; it never directly calls Notion.
2. Prevent overlap across instances with a PostgreSQL advisory lock scoped to each saved-filter ID and held across discovery plus synchronization; the destination lock still serializes Notion work. Misfire behavior is skip-and-record, not catch-up fan-out.
3. Keep HiringCafe automation disabled by default until provider authorization and a supported integration path are documented. Do not replace that gate with scraping or browser automation.
4. Emit structured logs and Micrometer metrics for run IDs, source, filter ID, outcome counts, lock wait, quota remaining, Notion latency/status, retry count, unresolved marker count, ranking eligibility counts, and import admissions. Redact API keys, full payloads, notes, and unnecessary job text.
5. Implement readiness checks for database connectivity and configuration validity; report Notion/provider degradation without exposing secrets. Graceful shutdown stops admitting new work and allows bounded completion/reconciliation of started writes.
6. Provision approved Notion views through the current Views API only when the integration has verified capability; otherwise generate an idempotent operator checklist for manual view creation. View failure must not compromise data synchronization.
7. Add scheduler concurrency, graceful-shutdown, metric-label cardinality, redaction, and view fallback tests.

**Exit criteria**: authorized automation runs without overlap, produces actionable low-cardinality telemetry, and shuts down without abandoning an unprotected write.

### Phase 8 — Release hardening and production gates

**Objective**: prove the complete behavior and document safe operation/rollback.

1. Run the full unit, contract, integration, golden-ranking, Testcontainers, and failure-injection suites.
2. Execute a sandbox end-to-end dry run: authenticate manually, preview ranked jobs, save preferences, sync, update without touching user fields, exercise import/idempotent retry, verify quota/lock behavior, and observe metrics/log redaction.
3. Reconfirm (do not defer initial approval of) the ranking v1 defaults approved at the Phase 4 entry gate. Any change requires a decision record, compatibility analysis, and golden fixture updates.
4. Close production gates:
   - manual and import secrets provisioned separately with rotation runbook;
   - TLS enforced at ingress;
   - real Notion schema/status/capabilities verified;
   - unresolved marker dashboard and reconciliation runbook tested;
   - backup/restore exercised for PostgreSQL operational state;
   - HiringCafe authorization/supported feed documented before enabling scheduled discovery.
5. Document deployment, migrations, configuration, secret rotation/revocation, incident handling, uncertain-write recovery, lock/quota troubleshooting, rollback, and known limitations.
6. Roll back application code only with backward-compatible schema retained. Never undo applied Flyway migrations destructively or discard unresolved markers/idempotency records during rollback.

**Exit criteria**: all automated suites pass, sandbox evidence is archived, every release gate has an owner/status, runbooks are executable, and production automation remains disabled for any unresolved external authorization gate.

## Dependency and Rollout Order

```text
manual authentication ─┐
ranking integration ───┼─> Notion adapter ─> SyncCoordinator/manual sync
Notion contract ───────┘                         │
                                                 ├─> Import API
                                                 └─> Scheduler/observability
                                                          │
                                                          └─> Release validation
```

- Phase 4 database migration deploys before code that requires ranking preferences.
- Phase 5 marker schema deploys before any write-capable coordinator is enabled.
- Phase 6 idempotency/rate schema deploys before the import route is exposed.
- Feature flags keep Notion writes, imports, and scheduler disabled independently until their exit criteria pass.
- Each implementation branch is created from the latest `develop`, contains exactly five task IDs, and is pushed as one PR targeting `develop`; the next batch starts only from `develop` after the preceding PR is merged or from an explicitly stacked branch/PR whose dependency is declared.
- Phase 8 gates activation; it does not introduce new behavior.

## Complexity Tracking

No constitutional violations. The three small operational tables are required by N17/N19/N24/N25 to prevent duplicate or unsafe external writes across crashes and instances; they are not a general execution-history subsystem.
