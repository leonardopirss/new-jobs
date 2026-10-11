# Backend Profile — Job Discovery & Notion Sync

- Runtime/framework: Java 21 and Spring Boot 4.1.1. Maven is the build tool.
- Architecture: single-process modular monolith, organized package-by-feature. Features are `filters`, `hiringcafe`, `jobs`, `ranking`, `notion`, `sync`, `importapi`, `scheduler`, and `observability`; packages are created only when their phase gives them an active responsibility.
- Database/migrations: PostgreSQL 17 and append-only Flyway SQL migrations. Applied migrations must never be edited.
- Transport: Spring MVC REST; Phase 0 exposes only Actuator health. Product endpoint routes remain proposals until their owning phase.
- Authentication/authorization: deferred to the owning API phases; no product endpoint exists in Phase 0.
- Integrations: none in Phase 0. HiringCafe and Notion contract verification remain gated.
- Configuration/secrets: environment variables; `.env` is ignored and only `.env.example` is committed. A database password is required at startup.
- Observability/deployment: Actuator health only, with details hidden. Local PostgreSQL runs through Docker Compose and binds to loopback.
- Tests/CI: JUnit Jupiter and Testcontainers PostgreSQL; `./mvnw verify` on Java 21 in GitHub Actions.

## Decision notes

The approved Phase 0 plan specifies Java 21, which supersedes the older Java 25 runtime statement in the project constitution. Spring Boot 4.1.1 remains the selected stable release and officially supports Java 21. No business tables are introduced in the baseline migration; each feature owns its later schema.
