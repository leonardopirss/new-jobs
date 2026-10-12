# Phase 0 Research: Phases 4–8

**Date**: 2026-10-11
**Scope**: manual API authentication, ranking integration, Notion adapter, synchronization safety, imports, scheduling, and release readiness.

## Artifact precedence and resolved conflicts

### Decision

Use Constitution 2.0.0 and decisions N17–N25 as authoritative over conflicting original feature statements. Ranking is an approved core capability and governed imports are permitted. Durable state remains narrowly limited to saved configuration and operational safety.

### Rationale

The decision package explicitly records that original FR-003, FR-017, FR-042, and SC-011 were superseded. Following the old wording would make crash-safe Notion writes, HTTP idempotency, distributed limits, and the requested ranking integration impossible.

### Rejected alternatives

- Silently edit history: rejected because it hides why behavior changed.
- Follow the oldest spec: rejected because it contradicts ratified governance and the current request.
- Treat every document equally: rejected because it leaves implementation nondeterministic.

## Manual authentication boundary

### Decision

Use stateless bearer-key authentication through Spring Security for operator endpoints. Configure one current manual key plus an optional previous key with a bounded expiry. Hash each presented value with SHA-256 and compare fixed-length digests using `MessageDigest.isEqual`. Use a manual principal/authority distinct from import principals. Permit only sanitized health probes anonymously and deny every unmatched request.

### Rationale

This is a non-interactive backend with no user directory or UI. Session login, OAuth authorization-code flow, and credential persistence would add systems not required by the MVP. Spring Security provides a standard request filter chain, authentication entry points, and route authorization while keeping the key verification adapter small. Bearer tokens belong in the `Authorization` header and failed authentication can consistently return `WWW-Authenticate: Bearer`.

References:

- [Spring Security servlet architecture and custom filters](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [Spring Security bearer-token flow](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [Spring Security request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [Spring Boot management endpoint exposure](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)

### Rejected alternatives

- HTTP Basic: rejected because it suggests username/password semantics and can trigger unsafe generated-password defaults.
- Persisted users/API keys: rejected by the environment/secret-manager governance and unnecessary for a personal MVP.
- One shared key for manual and import traffic: rejected because it defeats source binding, scopes, independent revocation, and audit attribution.
- Custom authentication inside each controller: rejected because omissions would leave routes accidentally unauthenticated.

## Ranking integration point and defaults

### Decision

Persist `RankingPreferences` alongside each saved filter and accept them in transient preview/sync requests. Rank after provider normalization, local filtering, and canonical deduplication, but before display or quota-limited create selection. Drop only `INELIGIBLE`; sort `ELIGIBLE` ahead of `UNKNOWN`, then by full-precision score and the existing deterministic tie-breakers.

Existing filters receive the compatibility preference `{searchMode:BOTH, requiredTechnologies:[], desiredTechnologies:[], acceptedSeniorities:[]}`. Imported jobs are processed in request order and cannot inject ranking preferences. R1–R7 are approved. Editable seniority preferences/matching, geography-weight redistribution inside a tier, and the recency curve are not approved by R8+ and therefore form an explicit approval gate before any Phase 4 task encodes or exposes those defaults; Phase 8 only reconfirms an already approved profile.

### Rationale

Ranking before deduplication can score duplicate representations inconsistently; ranking after quota selection defeats the purpose of prioritization. Persisting preferences makes preview, manual sync, and scheduled sync reproduce the same order.

### Rejected alternatives

- Provider order: superseded by the approved ranking contract.
- Hard-filter missing required technologies: rejected because absence of evidence is not proof of incompatibility.
- Rank imports with server defaults: rejected because imports are already curated batches and their contract has no ranking preferences.
- Store only computed score: rejected because scores are derived, clock-sensitive, and would become stale.

## Notion API contract

### Decision

Pin `Notion-Version: 2026-03-11`. Store and configure opaque database/data-source IDs. Query with `POST /v1/data_sources/{data_source_id}/query`, propagate `next_cursor` unchanged until `has_more=false`, and treat `request_status.incomplete_reason` as incomplete visibility. Query non-archived and archived pages where supported; never infer that a missing page was never created. Create pages only after schema validation and durable marker commit. Patch only backend-owned properties and never send `is_archived` or `in_trash`.

Use JDK `HttpClient` plus Jackson behind a Notion port. Retry safe reads and explicitly safe failures with bounded backoff; honor `Retry-After`. A write whose send/response boundary is ambiguous transitions to durable `UNCERTAIN` and is reconciled under lock rather than blindly retried.

### Rationale

The current Notion contract separates databases and data sources, requires an API version header, uses opaque cursor pagination, and can return incomplete query status. URLs are not stable identity. Provider limits and error semantics make generic automatic POST retry unsafe.

References:

- [Notion API versioning](https://developers.notion.com/reference/versioning)
- [Query a data source](https://developers.notion.com/reference/query-a-data-source)
- [Create a data source](https://developers.notion.com/reference/create-a-data-source)
- [Retrieve a data source](https://developers.notion.com/reference/retrieve-a-data-source)
- [Update page properties](https://developers.notion.com/reference/patch-page)
- [Notion request and payload limits](https://developers.notion.com/reference/request-limits)
- [Create a view](https://developers.notion.com/reference/create-view)

### Rejected alternatives

- Third-party Notion SDK: rejected because the required calls are few and the native client keeps transport/error handling explicit.
- Use Notion URLs as identity: rejected because generated URLs may change.
- Search only active pages: rejected because an archived automation-created page could be duplicated.
- Retry every failed POST: rejected because a lost response may follow a successful create.
- Automatically restore/archive/delete pages: rejected by property ownership and non-destructive governance.

## Lock, marker, and quota protocol

### Decision

Acquire a destination-scoped PostgreSQL session advisory lock with `pg_try_advisory_lock` using one dedicated JDBC connection. Keep that connection open for reconciliation, quota calculation, and all Notion writes. Release explicitly in `finally`; connection loss also releases the database lock but must stop new writes in the application.

For each create, commit a marker carrying separate source+external-ID and source+canonical-URL hashes before HTTP begins, then write its operation ID into immutable `Sync Operation ID` on the page. `PREPARED` and `IN_FLIGHT` are treated as possibly sent after a crash unless reliable local evidence proves no bytes could have been sent. Only conclusive `CONFIRMED` or `NOT_SENT` outcomes are releasable. `UNCERTAIN` does not expire automatically. A pre-write identity conflict isolates that item; a conflict while reconciling a possibly delivered POST blocks new creates because delivery/ownership is uncertain. The global 300-page rolling quota is calculated under the same lock from automation-owned pages plus unresolved safety state and fails closed when evidence is incomplete.

### Rationale

Session advisory locks are cooperative and remain held until explicit unlock or session termination, matching the requirement to serialize one external destination across instances. A transaction lock cannot span multiple HTTP calls safely without an unnecessarily long database transaction. Durable markers close the crash window between local intent and external side effect.

References:

- [PostgreSQL 17 explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html)
- [PostgreSQL advisory-lock functions](https://www.postgresql.org/docs/17/functions-admin.html)

### Rejected alternatives

- In-memory mutex: rejected because it does not coordinate instances or survive process failure.
- Row lock held in a long transaction: rejected because network calls would hold transactional resources and increase contention.
- Marker after POST: rejected because a crash after the create but before the marker permits duplication.
- Timeout-based uncertain-marker release: rejected by N20.

## Import idempotency and exact distributed rate limiting

### Decision

Authenticate an import credential before body processing, bind it to a source and `jobs:import`, enforce 512 KiB while streaming, then atomically admit at most 10 requests per rolling 60 seconds. Serialize rate decisions per integration with a transaction advisory lock, count admission rows newer than `now()-60s`, insert the new admission only below capacity, and compute `Retry-After` from the oldest active admission. Rejected requests do not consume capacity.

Reserve `(integration_id, idempotency_key)` with canonical payload SHA-256, processing state, and expiry at least 48 hours. Same key/different body is `409`. Same key/same body consults/reconciles existing state and does not start a second independent execution. Marker lifetime is independent of HTTP idempotency expiry.

### Rationale

A database-backed rolling log is exact at the small mandated scale and works across service instances. A token bucket would allow bursts that do not satisfy the literal rolling-window limit. Namespace plus hash prevents key reuse from conflating different work.

### Rejected alternatives

- Per-process counters: rejected because multiple instances exceed the shared limit.
- PostgreSQL credentials table: rejected by N22.
- Cache-only idempotency: rejected because restart/crash can repeat external work.
- Rate rejected attempts: rejected because the stated limit is admitted requests; abusive invalid credentials are handled before this bucket and can be limited at ingress.

## Scheduler and automation gate

### Decision

Use Spring scheduling with persisted filter preferences and the shared coordinator. Skip overlap/misfires instead of spawning catch-up executions. Scheduled HiringCafe discovery is disabled by default until a supported provider integration and authorization are documented. Observability uses bounded-cardinality identifiers/statuses, never job text or secrets.

### Rationale

The service already uses Spring Boot, the expected scale does not justify an external scheduler, and the coordinator provides distributed destination serialization. Provider permission is an external release condition, not a technical assumption.

### Rejected alternatives

- Browser scraping as a fallback: outside scope and explicitly prohibited.
- Separate synchronization implementation for scheduler: rejected because it could bypass safety invariants.
- Persistent full run history: rejected by the minimal-state constitution; structured logs and metrics cover operations.

## Resolved unknowns

No `NEEDS CLARIFICATION` markers remain for implementation planning. Product sign-off on provisional ranking defaults, provider permission, and a real Notion sandbox verification are explicit Phase 8 gates; they do not alter the architecture above.
