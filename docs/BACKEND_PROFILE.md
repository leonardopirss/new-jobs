# Backend Profile — Job Discovery & Notion Sync

- Runtime/framework: Java 25 and Spring Boot 4.1.1. Maven is the build tool.
- Architecture: single-process modular monolith, organized package-by-feature. Features are `filters`, `hiringcafe`, `jobs`, `ranking`, `notion`, `sync`, `importapi`, `scheduler`, and `observability`; packages are created only when their phase gives them an active responsibility.
- Database/migrations: PostgreSQL 17 and append-only Flyway SQL migrations. Applied migrations must never be edited. Phase 1 stores only saved filter configurations.
- Transport: Spring MVC REST; Actuator health plus provisional Phase 1 saved-filter CRUD and preview routes.
- Authentication/authorization: manual endpoint authentication remains blocked on open gate #9; product endpoints are not production-exposable yet.
- Integrations: none in Phase 0. HiringCafe and Notion contract verification remain gated.
- Configuration/secrets: environment variables; `.env` is ignored and only `.env.example` is committed. A database password is required at startup.
- Observability/deployment: Actuator health only, with details hidden. Local PostgreSQL runs through Docker Compose and binds to loopback.
- Tests/CI: JUnit Jupiter and Testcontainers PostgreSQL; `./mvnw verify` on Java 25 in GitHub Actions.

## Decision notes

The user explicitly selected Java 25 after Phase 0, superseding the Java 21 entry in the earlier development plan and restoring alignment with the project constitution. Spring Boot 4.1.1 officially supports Java 25. No business tables were introduced in the baseline migration; each feature owns its later schema.
