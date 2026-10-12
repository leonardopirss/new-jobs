<!--
Sync Impact Report
- Version change: 1.1.0 -> 2.0.0
- Modified principles:
  - III. Data Ownership and Safe Synchronization ->
    III. Data Ownership, Minimal Durable State, and Safe Synchronization
  - IV. Verification at External Boundaries -> expanded for import and recovery guarantees
  - V. Simplicity, Security, and Operability -> expanded for integration credentials and deadlines
- Modified sections:
  - Project Constraints -> database exceptions, Import API, and LinkedIn boundary aligned with N17-N25
  - Development Workflow and Quality Gates -> security and failure-injection review made explicit
- Added sections: none
- Removed sections: none
- Follow-up TODOs: none
-->
# Job Discovery Backend Constitution

## Core Principles

### I. Specification and Approval Gates

The Spec Kit constitution, specification, clarification record, plan, tasks, and analysis MUST be
the source of truth for development. Application implementation MUST NOT begin until the user has
reviewed and explicitly approved both the functional specification and implementation plan.
Requirements MUST remain technology-neutral unless a technology choice is itself an approved
constraint. Every implementation task and test MUST be traceable to an approved requirement or
documented architectural decision.

### II. Provider Fidelity and Explicit Uncertainty

Provider capabilities, filters, parameters, limits, authorization, pagination, and usage conditions
MUST be verified against current official documentation before planning their implementation.
Undocumented endpoints or behaviors MUST NOT be invented. The system MUST distinguish
source-provided data from derived classifications and MUST represent unavailable or uncertain data
explicitly. A filter that requires positive evidence MUST NOT match an unknown value. A filter not
supported natively MAY be evaluated locally only when the provider returns sufficient data;
otherwise the request MUST be rejected with an actionable explanation.

### III. Data Ownership, Minimal Durable State, and Safe Synchronization

Notion MUST remain the system of record for synchronized job opportunities and user-maintained
application tracking. PostgreSQL MUST NOT store job copies, a complete execution ledger, job
descriptions, or user-managed Notion content. It MAY persist provider filter configurations and only
the minimum operational state required for safety: per-operation synchronization markers, HTTP
idempotency records retained for at least 48 hours, and distributed rate-limit state. Each such
record MUST have a documented safety purpose, retention or reconciliation rule, and bounded data
shape; operational persistence MUST NOT become an implicit job database or analytics history.

The backend Java service MUST be the exclusive automated writer to Notion. A durable operation
marker MUST be committed before an external create request can be sent. A marker for an ambiguous
or possibly delivered request MUST remain unresolved and block new creations for that destination
until creation is confirmed or non-delivery is reliably proven. Time passage, retry exhaustion, or
a negative lookup alone MUST NOT release an uncertain marker or authorize another create request.
Synchronization MUST run under the approved destination-scoped session advisory lock, and loss of
that lock MUST stop new external writes.

Synchronization MUST use provider-level identity and preserve user-controlled `Status`,
`User Notes`, `Applied At`, and all additional user properties. Missing or empty source values MUST
NOT erase valid stored values. The application MUST NOT automatically delete, archive, unarchive,
or recreate a user-managed page. A job absent from recent search results MUST remain in Notion and
MUST NOT be marked unavailable without explicit reliable provider evidence. Cross-provider matches
MUST only be flagged for review and MUST NOT be merged automatically. These constraints exist
because preventing duplicate or destructive external writes takes precedence over throughput and
automatic recovery.

### IV. Verification at External Boundaries

Filtering, normalization, identity, deduplication, time-window evaluation, and missing-value rules
MUST have deterministic unit tests. HiringCafe and Notion integrations MUST have realistic simulated
tests covering pagination, partial responses, timeouts, retry eligibility, rate limiting, and partial
failure. Import API tests MUST cover authentication, source binding, request limits, idempotency-key
conflicts, distributed rate limiting, deadline exhaustion, item reconciliation, client disconnects,
and all approved acceptance scenarios. Failure-injection tests MUST prove crash recovery, ambiguous
Notion responses, advisory-lock loss, and two-instance serialization. Where non-delivery cannot be
proven, tests MUST expect fail-closed behavior rather than automatic retry or marker release. Tests
MUST use a controllable clock and MUST NOT use real credentials. External contract assumptions MUST
be documented and covered by contract-focused tests where practical.

### V. Simplicity, Security, and Operability

The application MUST use the simplest single-process design that satisfies approved requirements.
Microservices, message brokers, mandatory LLM calls, speculative provider integrations, and empty or
pass-through layers MUST NOT be introduced without a demonstrated requirement and approved
rationale. Configuration and secrets MUST remain outside source control and be validated at startup.
Manual single-user endpoints MUST be authenticated according to an explicitly approved credential
boundary. Import clients MUST use separate high-entropy integration credentials unless an approved
decision explicitly permits sharing. Import credentials MUST be stored in environment configuration
or a secret manager, MUST support operational rotation and revocation, and MUST NOT be persisted in
a credentials table. Verification MUST use a secure comparison strategy, and planned rotation MUST
permit a bounded overlap window without weakening revocation. Each credential MUST bind the
effective source and `jobs:import` scope so a request cannot spoof its source. Import traffic MUST
use HTTPS except on localhost.

External calls MUST have bounded timeouts and explicit retry rules. Import processing MUST enforce
the approved 100-second internal deadline, stop starting new items as the deadline approaches, and
protect work already in flight. Logs MUST be structured, privacy-conscious, and sufficient to
explain each synchronization outcome without exposing credentials, idempotency keys, private notes,
or unnecessary job content.

## Project Constraints

- The backend MUST use Java 25, Maven, and a stable compatible Spring Boot release verified during
  planning. Dependency versions MUST be security-maintained and reproducible.
- Java code MUST follow Package by Feature and create only packages and abstractions that have an
  active responsibility. Records SHOULD be used for suitable immutable contracts.
- HiringCafe is the only provider queried automatically by the backend in the MVP. The backend MUST
  NOT scrape, search, authenticate to, or collect from LinkedIn. `LINKEDIN` MAY be reserved in the
  common model, Notion source values, and a disabled or test Import API credential binding. Any
  future LinkedIn collector MUST remain external and submit through the shared Import API and sync
  coordinator; it MUST NOT write directly to Notion.
- The API MUST support transient searches and persistent provider filter profiles. Filter groups MAY
  select `ALL` or `ANY` semantics. Provider-specific filters MUST be isolated from normalized common
  filters.
- Scheduling MUST use a global interval supplied through external configuration. Overlapping runs of
  the same filter profile MUST be prevented.
- Manual discovery MUST offer a no-write preview and an explicit synchronization operation.
- The Import API MUST accept at most 50 jobs and 512 KiB per request. It MUST require a namespaced
  `Idempotency-Key`, enforce a distributed limit of 10 admitted requests per credential per rolling
  60 seconds, and return `429` with `Retry-After` when limited. Rate-limit decisions MUST be atomic
  across application instances through PostgreSQL.
- Import idempotency records MUST bind integration, key, canonical payload hash, processing state,
  and expiry for at least 48 hours. Reuse with a different payload MUST be rejected. An identical
  retry MUST reconcile current state without promising a byte-identical response. Expiry of the HTTP
  record MUST NOT expire an uncertain synchronization marker.
- Import responses MUST account for every input index exactly once and reconcile summary counts.
  Items not started before deadline exhaustion MUST be `NOT_PROCESSED`; possibly started external
  writes MUST be `UNCERTAIN` until reconciled. Lock acquisition MUST consume no more than five
  seconds of the 100-second internal deadline, and clients SHOULD allow at least 120 seconds.
- The Notion database MAY be created when configured or an existing database MAY be reused. Its
  schema MUST be validated before synchronization. Unsupported view automation MUST be documented
  as manual setup rather than simulated through undocumented behavior.
- The MVP MUST NOT include a frontend. It MUST NOT introduce job persistence, a full execution
  history, unnecessary infrastructure, or routine OpenAI or Anthropic API calls. The narrowly scoped
  operational records authorized by Principle III are the only exception to non-filter persistence.

## Development Workflow and Quality Gates

1. Constitution changes MUST be reviewed before they govern new specifications.
2. `$speckit-specify` MUST define behavior, scenarios, rules, edge cases, and measurable outcomes.
3. `$speckit-clarify` MUST resolve material ambiguities and record accepted decisions in the spec.
4. `$speckit-plan` MUST verify official integration capabilities, compatible versions, architecture,
   contracts, configuration, risks, and deployment assumptions.
5. The specification and plan MUST receive explicit user approval before implementation tasks are
   executed.
6. `$speckit-tasks` and `$speckit-analyze` MUST produce and validate dependency-ordered work with an
   end-to-end HiringCafe-to-Notion path prioritized.
7. Implementation changes MUST satisfy BACKEND_STANDARDS.md, approved architecture decisions,
   relevant tests, documented configuration, and reproducible build checks.
8. Changes to synchronization or import MUST include a persistence-purpose review, threat-boundary
   review, failure-injection plan, and proof that no credential or user-owned Notion data is stored.
9. Every task MUST use a dedicated `feature/<short-name>` or `fix/<short-name>` branch created from
   the latest `develop`. Completed task branches MUST be pushed and reviewed through a pull request
   whose base branch is `develop`; direct task commits to `develop` or `main` are prohibited.
10. Stable releases MUST move from `develop` to `main` through a separate reviewed pull request.

## Governance

This constitution governs project-specific decisions. Security or legal obligations take precedence,
followed by explicit approved architecture decisions, this constitution, BACKEND_STANDARDS.md, and
framework conventions. Any exception MUST document its scope, rationale, trade-offs, owner, and
review date.

Amendments require user approval and a Sync Impact Report. Versions follow semantic versioning:
MAJOR for incompatible governance changes, MINOR for new or materially expanded principles, and
PATCH for non-semantic clarification. Reviews of specifications, plans, tasks, and implementation
changes MUST check compliance. Unjustified complexity or an unapproved implementation gate breach
MUST block progression until resolved.

**Version**: 2.0.0 | **Ratified**: 2026-10-11 | **Last Amended**: 2026-10-11
