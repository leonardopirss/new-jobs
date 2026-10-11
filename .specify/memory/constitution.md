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

### III. Data Ownership and Safe Synchronization

Notion MUST remain the system of record for synchronized job opportunities and user-maintained
application tracking. The relational database permitted for the MVP MUST store only provider filter
configurations; it MUST NOT store job copies, synchronization history, fingerprints, or execution
state. Synchronization MUST be repeatable and idempotent by consulting Notion for provider-level
identity. The application MUST preserve user-controlled `Status` and `User Notes`, retain jobs that
disappear from a provider, and mark those jobs unavailable rather than deleting them. Cross-provider
matches MUST only be flagged for review and MUST NOT be merged automatically.

### IV. Verification at External Boundaries

Filtering, normalization, identity, deduplication, time-window evaluation, and missing-value rules
MUST have deterministic unit tests. HiringCafe and Notion integrations MUST have realistic simulated
tests covering pagination, partial responses, timeouts, retry eligibility, rate limiting, and partial
failure. Tests MUST use a controllable clock and MUST NOT use real credentials. External contract
assumptions MUST be documented and covered by contract-focused tests where practical.

### V. Simplicity, Security, and Operability

The application MUST use the simplest single-process design that satisfies approved requirements.
Microservices, message brokers, mandatory LLM calls, speculative provider integrations, and empty or
pass-through layers MUST NOT be introduced without a demonstrated requirement and approved
rationale. Configuration and secrets MUST remain outside source control and be validated at startup.
The single-user API MUST be protected by an API key. External calls MUST have bounded timeouts and
explicit retry rules. Logs MUST be structured, privacy-conscious, and sufficient to explain each
synchronization outcome without exposing credentials or sensitive content.

## Project Constraints

- The backend MUST use Java 25, Maven, and a stable compatible Spring Boot release verified during
  planning. Dependency versions MUST be security-maintained and reproducible.
- Java code MUST follow Package by Feature and create only packages and abstractions that have an
  active responsibility. Records SHOULD be used for suitable immutable contracts.
- HiringCafe is the only automated job provider in the MVP. LinkedIn MUST be reserved in the common
  model and Notion source values but MUST NOT have automated or manual import in the MVP.
- The API MUST support transient searches and persistent provider filter profiles. Filter groups MAY
  select `ALL` or `ANY` semantics. Provider-specific filters MUST be isolated from normalized common
  filters.
- Scheduling MUST use a global interval supplied through external configuration. Overlapping runs of
  the same filter profile MUST be prevented.
- Manual discovery MUST offer a no-write preview and an explicit synchronization operation.
- The Notion database MAY be created when configured or an existing database MAY be reused. Its
  schema MUST be validated before synchronization. Unsupported view automation MUST be documented
  as manual setup rather than simulated through undocumented behavior.
- The MVP MUST NOT include a frontend. It MUST NOT introduce a database for job persistence,
  unnecessary infrastructure, or routine OpenAI or Anthropic API calls.

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
8. Every task MUST use a dedicated `feature/<short-name>` or `fix/<short-name>` branch created from
   the latest `develop`. Completed task branches MUST be pushed and reviewed through a pull request
   whose base branch is `develop`; direct task commits to `develop` or `main` are prohibited.
9. Stable releases MUST move from `develop` to `main` through a separate reviewed pull request.

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

**Version**: 1.1.0 | **Ratified**: 2026-10-11 | **Last Amended**: 2026-10-11
