# Backend Engineering Standards

> Reusable guidelines for backend projects, regardless of language, framework, database, deployment model, or business domain.
>
> Version: 3.1 | Scope: backend services, APIs, workers, integration services, monoliths, modular monoliths, and microservices.

## 0. How to apply this document

**MUST** = baseline requirement, unless a documented exception is approved. **SHOULD** = recommended default, adjustable to context. **MAY** = optional practice selected when justified. **N/A** = not relevant to the project.

Before implementation, record the project's decisions in a short `docs/architecture/decisions.md` or individual ADRs:

| Decision | Project-specific value |
|---|---|
| Runtime and supported version | Define per project |
| Framework and dependency management | Define per project |
| Architecture | Layered / modular monolith / hexagonal / microservices / other |
| Database(s) and migration strategy | Define per project |
| API protocols and contract strategy | REST / GraphQL / gRPC / events / other |
| Authentication and authorization | Define per project and threat model |
| Communication patterns | In-process / HTTP / messaging / other |
| Deployment, configuration, and secrets | Define per environment |
| Observability and SLOs | Define per project |
| Test strategy, quality gates, and CI/CD | Define per project |

**Priority order:** security and regulatory requirements → explicit project ADRs → these standards → framework conventions. An exception must describe the trade-off, owner, and review date. Do not introduce dependencies or infrastructure solely to satisfy an example in this guide. For existing systems, prefer incremental improvements over disruptive rewrites.

## 1. Dependencies, versions, and runtime

- **MUST** use supported, security-maintained runtime/framework/dependency versions compatible with each other.
- **MUST** use reproducible builds, a committed lockfile or equivalent dependency resolution, and dependency vulnerability scanning.
- **MUST** handle secrets outside source control; never include credentials in code, examples, build logs, or artifacts.
- **SHOULD** prefer the framework's dependency-management mechanism (BOM, lockfile, constraints, etc.) and avoid unnecessary explicit pinning.
- **SHOULD** document the upgrade policy, supported platforms, and migration strategy; prohibit unstable or preview features in critical production paths unless justified.
- **MUST** keep frontend/build tooling out of backend-only repositories **unless** the repository actually requires it (e.g., a Node/NestJS service or full-stack monorepo).
- **MAY** adopt new language features when stable, maintainable, and supported by the chosen runtime.

## 2. Code quality and language conventions

- **MUST** use intention-revealing names, cohesive units, clear error handling, and consistent formatting.
- **SHOULD** apply SOLID, DRY, KISS, composition over inheritance, early returns, and small focused functions pragmatically; do not create abstractions without a demonstrated need.
- **MUST** avoid hard-coded secrets, unreviewed magic values, dead/commented-out code, and unchecked unsafe operations.
- **SHOULD** favor immutable data, explicit types, dependency injection at meaningful boundaries, and null-safe APIs using the language's idioms.
- **MUST** keep domain rules independent of HTTP handling, storage technology, and external-service implementation wherever practical.
- **MUST** follow existing repository conventions unless the team intentionally migrates them.
- **SHOULD** use automated formatter, linter, static analysis, and type/null analysis supported by the chosen stack.

## 3. Architecture and boundaries

**Choose architecture based on complexity, scale, ownership, deployment independence, and operational cost — not by defaulting to microservices or a modular monolith.**

### 3.1 Responsibility boundaries

- **MUST** separate transport/interface concerns, application use cases, domain/business logic, and infrastructure concerns conceptually; physical folders may differ.
- **MUST** prevent circular dependencies across architectural modules or services.
- **SHOULD** organize by business capability/domain when it improves ownership and discoverability.
- **MUST** expose contracts at boundaries; avoid leaking persistence entities, ORM models, or internal infrastructure types through public APIs.
- **SHOULD** avoid layers that merely forward calls without adding value.
- **MUST** validate architectural boundaries using code reviews and automated checks where feasible.

Example **only for a domain-oriented backend**, not a mandatory directory layout across all stacks (the optional Java profile below defines Package by Feature as its default):

```text
src/
├── shared/                 # Technical cross-cutting concerns, no domain ownership
└── <domain>/
    ├── api/                # Transport adapters and public contracts
    ├── application/        # Use cases and orchestration
    ├── domain/             # Business entities, policies, invariants
    └── infrastructure/     # Persistence and external adapters
```

### 3.2 Architecture-specific guidance

- **Single-process / modular monolith:** prefer direct in-process calls through explicit interfaces or application contracts; use local transactions when one consistency boundary supports them. Events may decouple notifications, but are not mandatory for every module interaction.
- **Microservices:** define service ownership, API/event contracts, independent deployment, data ownership, tracing, timeout policies, and failure handling. Use eventual consistency, outbox, sagas, or compensation only when business requirements justify them.
- **Workers / scheduled processes:** define retry behavior, concurrency, locking/lease strategy, recovery, and duplicate-processing semantics.
- **All models:** use messaging, brokers, API gateways, service discovery, and distributed locks only when their operational purpose is clear.

## 4. Configuration and secrets

- **MUST** separate configuration from code and use environment-specific values without embedding production secrets.
- **MUST** validate required configuration at startup; fail fast for missing or invalid critical settings.
- **MUST** restrict access to secrets and support safe rotation; do not expose secrets in logs or metrics.
- **SHOULD** favor typed/validated configuration objects and clear defaults for non-sensitive settings.
- **SHOULD** document profiles, feature flags, rollout strategy, and ownership of configuration values.
- **MAY** use environment variables, secret managers, orchestration secrets, or configuration services according to deployment needs.

## 5. Persistence and data integrity

- **MUST** define schema ownership and manage production schema changes through reviewed, versioned migrations or an equivalent controlled process.
- **MUST** use parameterized queries and enforce data constraints in the storage layer when supported.
- **MUST** define transaction boundaries explicitly for multi-step state changes requiring atomicity.
- **SHOULD** apply optimistic/pessimistic concurrency control based on actual contention; do not assume application-level checks prevent races.
- **SHOULD** use explicit DTO-to-domain/persistence mapping where crossing boundaries; manual mapping or a generator are both valid.
- **MUST** choose data types carefully: exact decimal representation for money, explicit time zones/time instants, identifiers suitable for the domain.
- **SHOULD** profile queries and indexes; choose offset or keyset pagination based on access patterns and data size.
- **MUST** document backups, restore testing, retention, deletion, and personal-data handling for persistent production data.
- **MAY** use relational, document, key-value, or other databases as justified by data access requirements.

## 6. API and integration contracts

Applies to HTTP APIs where relevant; for gRPC, GraphQL, queues, and event streams, apply the equivalent principles of explicit contracts, validation, compatibility, observability, and error semantics.

### 6.1 REST design

- **SHOULD** use resource-oriented, consistently named paths (e.g. `/orders`, `/orders/{id}/attempts`).
- **MUST** respect HTTP semantics: safe `GET`/`HEAD`, idempotent `PUT`/`DELETE`, documented `PATCH`, and appropriate `POST` behavior.
- **MUST** document success and failure status codes; use `201` plus `Location` for new addressable resources, and `204` without a body where appropriate.
- **SHOULD** avoid verbs in collection paths; represent domain actions explicitly when they do not fit CRUD.
- **MUST** keep response format consistent within a public API. A `data`/`meta`/`links` envelope is **one option**, not a universal requirement; do not wrap responses when a standard/protocol mandates another format.
- **MUST** document timestamps, money, enum values, nullability, and serialization conventions. Prefer ISO-8601/RFC 3339 timestamps with unambiguous offsets; use UTC internally when practical.
- **SHOULD** implement filtering, documented sort fields, bounded page sizes, and cursor/keyset pagination when suited to large datasets.

### 6.2 Compatibility and versioning

- **MUST** document API contracts with OpenAPI, AsyncAPI, protobuf, GraphQL schema, or the appropriate protocol specification.
- **MUST** assess backward compatibility before changing externally consumed contracts.
- **SHOULD** choose a single versioning strategy per API when versioning is required (path, header, media type, or schema evolution); do not enforce path-based versioning on all projects.
- **SHOULD** document deprecations and migration windows, using standard HTTP deprecation/sunset headers when applicable.
- **SHOULD** add contract tests for important producer-consumer relationships.

### 6.3 Input validation and errors

- **MUST** validate untrusted inputs at trust boundaries (shape, size, range, ownership, format, and permissions).
- **MUST** centralize consistent error translation for each transport, without exposing stack traces, secrets, SQL, or internal details.
- **SHOULD** use RFC 9457 Problem Details for HTTP APIs, or a documented alternative.
- **SHOULD** include stable machine-readable error codes, user-safe messages, optional field violations, and a trace/correlation reference.
- **MUST** distinguish authentication (401), authorization (403), missing resources (404), conflicts (409), precondition failures (412), rate limits (429), and unexpected server failures (500) when applicable. Use 400/422 consistently and document the distinction.

### 6.4 Idempotency, concurrency, and caching

- **MUST** make operations with duplicate-delivery risk safe according to business semantics (idempotency keys, deduplication, uniqueness, or other controls).
- **SHOULD** use request idempotency keys for retryable creation/payment/integration commands that must not duplicate effects.
- **SHOULD** use optimistic concurrency (`ETag` / `If-Match`, row versions, etc.) when lost updates matter.
- **MUST** prevent caching of sensitive responses where disclosure is a risk; configure cache controls explicitly where applicable.
- **MAY** use conditional requests (`ETag` / `If-None-Match`) and caching for suitable resources.

### 6.5 Outbound integrations

- **MUST** configure connection, read/response, and overall deadlines where supported.
- **MUST** define retry eligibility, bounded attempts, backoff/jitter, and behavior for non-idempotent calls.
- **SHOULD** propagate tracing context and stable correlation identifiers across service boundaries.
- **SHOULD** separate external DTOs and client code from the domain model.
- **MAY** choose declarative HTTP clients, imperative clients, reactive clients, SDKs, or message clients based on needs; no single library is mandatory.

## 7. Security and privacy

- **MUST** use encryption in transit for sensitive or public network traffic and apply the platform's TLS termination model correctly.
- **MUST** authenticate callers where required and enforce authorization server-side at the correct resource/operation boundary.
- **MUST** apply least privilege to identities, databases, infrastructure, and CI/CD credentials.
- **MUST** validate, sanitize, or safely encode untrusted data for its destination; prevent SQL injection, command injection, SSRF, unsafe deserialization, and similar risks relevant to the system.
- **MUST** avoid logging credentials, tokens, payment data, and sensitive personal information.
- **SHOULD** implement bounded request bodies, rate limiting/quotas, abuse detection, security headers, and CORS allow-lists for browser-facing APIs as applicable.
- **SHOULD** use maintained authentication standards (OAuth2/OIDC, mTLS, sessions, signed tokens) appropriate to the architecture; **JWT is not mandatory**.
- **MUST** follow applicable organizational and legal data-handling requirements; document retention and incident-response ownership.
- **SHOULD** evaluate OWASP API Security risks and perform threat modeling for sensitive workflows.

## 8. Reliability, asynchronous processing, and resilience

- **MUST** define failure behavior at external/dependency boundaries and ensure bounded resource consumption.
- **SHOULD** apply backoff/jitter and timeouts before adding complex resilience frameworks.
- **SHOULD** implement circuit breakers, bulkheads, concurrency limits, and load shedding only where failure patterns justify them.
- **MUST** make message consumers resilient to redelivery or clearly document at-most-once trade-offs.
- **SHOULD** use dead-letter queues, replay procedures, and poison-message handling for durable queues.
- **SHOULD** use transactional outbox/inbox patterns when reliable coupling of database state and event publication is required.
- **MUST** define safe cancellation, graceful shutdown, readiness, and recovery behavior for long-running services.

## 9. Logging, metrics, and tracing

- **MUST** log operational events with appropriate levels and redact sensitive data.
- **SHOULD** emit structured logs with a request/correlation ID and meaningful domain identifiers when non-sensitive.
- **SHOULD** instrument latency, traffic, failures, saturation, background job outcomes, and business-critical workflows.
- **SHOULD** use distributed tracing and context propagation for multi-component flows; OpenTelemetry is a recommended interoperable option, not a mandatory SDK.
- **MUST** protect operational endpoints and diagnostics from unauthorized access.
- **SHOULD** define alerts and service-level objectives for production systems proportional to impact.

## 10. Testing strategy

- **MUST** test business rules, edge cases, authorization constraints, and important failure paths.
- **SHOULD** keep unit tests fast, deterministic, isolated from unnecessary infrastructure, and focused on observable outcomes.
- **MUST** verify critical persistence/integration behavior against realistic implementations where mocks cannot demonstrate correctness.
- **SHOULD** use containers or ephemeral test environments when realistic dependencies are necessary; do not start them for every trivial unit test.
- **SHOULD** mock external boundaries selectively; use fakes, stubs, and real implementations when they provide clearer tests. Do not universally ban service/repository mocks.
- **MUST** avoid flaky tests dependent on uncontrolled time, randomness, ordering, or shared state.
- **SHOULD** cover schema migrations, contract compatibility, architectural boundaries, and security behavior.
- **SHOULD** use changed-code coverage trends and targeted mutation testing where costs are justified; avoid treating a single coverage percentage as proof of quality.
- **MUST** document disabled/flaky tests and track remediation.

## 11. CI/CD, supply chain, and releases

- **MUST** automate build, relevant tests, lint/format, static/security scans, and artifact verification in CI.
- **MUST** protect production deployment and credentials with appropriate access, approvals, and audit records.
- **SHOULD** separate fast checks from longer integration/end-to-end suites; run the latter before relevant releases.
- **SHOULD** produce versioned, traceable, reproducible artifacts and record dependency inventory/SBOM when appropriate.
- **SHOULD** use staging or equivalent validation, health checks, rollback/roll-forward procedures, and safe database rollout plans.
- **SHOULD** use Conventional Commits (`feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, etc.) when the team adopts this convention; CI, not local hooks, is the authoritative gate.
- **MAY** use canary, blue/green, progressive delivery, or feature flags based on deployment and risk profile.

## 12. Naming and documentation

- **MUST** use one consistent naming convention for each language, protocol, and persistence technology; do not impose Java casing conventions on Python, Go, or SQL indiscriminately.
- **SHOULD** name modules after business capabilities, not generic technical buckets only.
- **MUST** maintain a README with purpose, local setup, required dependencies, tests, configuration, and run commands.
- **SHOULD** document externally visible contracts, runbooks, operational assumptions, and significant architecture decisions.
- **SHOULD** keep documentation near the code and update it alongside behavior changes.

## 13. Definition of Done (per change)

- [ ] Behavior and acceptance criteria are clear; edge/failure paths considered.
- [ ] Change respects the project's architectural decisions and public contracts.
- [ ] Security, authorization, data integrity, and sensitive-data handling assessed.
- [ ] Tests added/updated at appropriate levels and pass.
- [ ] Migrations and backward compatibility assessed if data/contracts changed.
- [ ] Observability added or updated for important new operations.
- [ ] Automated checks pass; dependency and supply-chain risks reviewed.
- [ ] Documentation, config, deployment, and rollback impacts addressed.
- [ ] New infrastructure/abstractions have a documented justification.

## Appendix A. Optional Java / Spring implementation profile

Use **only** when the project actually uses Java and Spring. Adapt dependency versions to its chosen compatible release train instead of copying hard-coded versions.

| Concern | Potential implementation |
|---|---|
| Build and dependency management | Maven or Gradle; Spring Boot dependency management/BOM |
| Domain/API DTOs | Records where suitable; Bean Validation for inbound DTOs |
| DI and configuration | Constructor injection; `@ConfigurationProperties` |
| Persistence | Spring Data/JPA, JDBC, or another suitable strategy; Flyway or Liquibase migrations |
| Mapping | Explicit manual mapping or MapStruct with unmapped-field checks |
| HTTP controllers | Spring MVC or WebFlux according to workload |
| HTTP clients | `RestClient`, HTTP interfaces, `WebClient`, or supported SDKs as applicable |
| Errors | `@RestControllerAdvice`; Problem Details where applicable |
| Security | Spring Security with an appropriate OAuth2/OIDC/session/mTLS model |
| Transactions and events | `@Transactional`; transactional event listeners where suitable |
| Async and schedules | Framework scheduling/executors; locking if replicated schedules require it |
| Observability | Micrometer, tracing bridge, OpenTelemetry if selected |
| Tests | JUnit Jupiter, Mockito, Testcontainers, HTTP/contract test tools |
| Architecture checks | ArchUnit; Spring Modulith for suitable modular monoliths |
| Quality gates | Spotless/Checkstyle, Error Prone/NullAway, JaCoCo, Sonar, PIT as warranted |

### A.1 Java package organization — Package by Feature

**Default for new Java/Spring backend projects: Package by Feature** (business capability/domain first), with the depth of internal layering adapted to actual complexity. This is a **Java profile convention**, not a constraint on backends written in other languages. Existing projects MAY keep a consistent Package by Layer structure and migrate incrementally when there is a clear benefit.

Recommended layout for a backend with multiple business features:

```text
backend-project/
├── src/
│   ├── main/
│   │   ├── java/com/example/app/
│   │   │   ├── Application.java
│   │   │   ├── config/                 # Application-wide configuration
│   │   │   ├── shared/                 # Truly shared technical components
│   │   │   │   ├── exception/
│   │   │   │   ├── validation/
│   │   │   │   └── observability/
│   │   │   ├── customer/               # Business feature/domain
│   │   │   │   ├── controller/
│   │   │   │   │   ├── request/
│   │   │   │   │   └── response/
│   │   │   │   ├── service/
│   │   │   │   ├── domain/             # Business rules and models
│   │   │   │   ├── repository/
│   │   │   │   ├── entity/
│   │   │   │   ├── mapper/
│   │   │   │   └── infrastructure/     # External clients/adapters
│   │   │   └── order/
│   │   │       ├── controller/
│   │   │       ├── service/
│   │   │       ├── domain/
│   │   │       └── repository/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/com/example/app/       # Mirror feature/package structure
├── pom.xml                              # Or build.gradle(.kts)
└── README.md
```

**Package responsibilities and rules:**

- **MUST** group business code by feature (`customer`, `order`, `payment`) instead of placing all controllers, services, entities, and repositories in global technical directories for new multi-feature projects.
- **SHOULD** keep `controller` for inbound HTTP concerns and request/response DTOs; controllers delegate use cases and never directly orchestrate persistence.
- **SHOULD** keep `service` for use cases and application orchestration; complex business invariants belong in domain models/services rather than controllers.
- **MAY** use `domain` for rules, policies, value objects, domain exceptions and models; use `entity` for JPA persistence entities when separating them clarifies ownership. Avoid two competing representations without a need.
- **SHOULD** keep `repository` for persistence contracts or Spring Data interfaces and `mapper` for meaningful mapping between API, domain, and persistence types; do not create mappers merely to pass values through.
- **SHOULD** use `infrastructure` for external HTTP/SOAP clients, messaging, file storage, and persistence adapters as needed. Avoid duplicate repository ownership between `repository/` and `infrastructure/persistence/`.
- **MAY** use `enums`, `dto`, `exception`, `event`, or other subpackages *inside a feature* when those elements are numerous enough to warrant grouping; do not create empty folders.
- **MUST** keep `shared` limited to genuinely reusable cross-cutting code; business logic belongs to the feature that owns it. Feature-to-feature access SHOULD go through intentional public contracts and must avoid dependency cycles.
- **MUST** use lowercase package names in English; types use PascalCase and methods/fields camelCase. Keep `Application.java` in the root package for component scanning, unless explicitly configured otherwise.
- **SHOULD** mirror the feature structure in `src/test/java`, separating unit and integration tests through naming, packages, or source sets as appropriate.

**Adjust to project complexity:**

| Project shape | Preferred layout |
|---|---|
| Small CRUD API / single capability | One feature package with only the necessary `controller`, `service`, `repository`, `entity`, and DTOs; no empty or pass-through layers |
| Medium API / several domains | Multiple feature packages, each containing the relevant technical subpackages (default above) |
| Modular monolith | Feature packages treated as modules; public contracts and module boundary tests; internals encapsulated |
| Complex domain / Clean or Hexagonal Architecture | Within each feature, use `api`, `application`, `domain`, `infrastructure`; put repository ports in the core and their JPA implementations in infrastructure |
| Microservices | Apply Package by Feature **inside each service**, with service boundaries, independent contracts, and deployment choices recorded separately |
| Workers / batch jobs | Organize by job or business capability, adding scheduling/processing adapters only when needed |

**Dependency direction:** Controllers call application services/use cases, not repositories directly. Domain logic should not depend on HTTP or external clients. In a pragmatic Spring/JPA project, JPA entities and Spring Data repositories MAY live within the feature; in strict Clean/Hexagonal architecture, domain models and ports stay framework-independent while JPA adapters stay in infrastructure. Choose one convention per feature and document it, rather than mixing alternatives accidentally.

Java-specific guidance: records are suitable for many immutable contracts but not generally ORM entities; use `BigDecimal` for decimal money values with an explicit rounding policy; use `Instant` / appropriate temporal types consistently; keep JPA entities internal; choose stable language features supported by the runtime. Virtual threads, reactive programming, scoped values, partitioning, and distributed messaging are **opt-in architectural decisions**, never universal requirements.

## Appendix B. Project-specific standard overlay

Projects MAY create `docs/BACKEND_PROFILE.md` with the selected runtime/framework versions, API conventions, persistence scheme, directory structure, approved libraries, test commands, and deployment constraints. This core standard stays unchanged; the overlay states project choices and deviations with rationale.

```markdown
# Backend Profile — <project-name>
- Runtime / framework:
- Architecture / ownership boundaries:
- Java package organization (when applicable): Package by Feature / justified exception
- Database / migration tooling:
- Transport / contract format:
- Authentication / authorization:
- Messaging / scheduled jobs:
- Observability / deployment:
- Testing / CI quality gates:
- Exceptions to Backend Engineering Standards (ADR links):
```

---

*Standards define engineering principles; project profiles define technical choices. Prefer the simplest solution that satisfies correctness, security, operability, and maintainability.*
