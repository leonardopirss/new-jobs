# Job Discovery & Notion Sync

Java/Spring Boot service for discovering, ranking, and safely synchronizing jobs to Notion. The foundation, filter contracts, bounded HiringCafe MCP preview connector, and deterministic Ranking v1 domain service are implemented. Notion writes, imports, scheduling, and LinkedIn collection are not implemented.

## Requirements

- Java 25
- Docker with Compose

Spring Boot 4.1.1 is the selected stable framework version and supports Java 25. Maven is provided through `mvnw`.

## Local database

Copy `.env.example` to `.env`, replace the local-only password placeholders, then start PostgreSQL:

```shell
docker compose up -d --wait postgres
```

Export the matching application variables (Compose reads `.env`, but the Java process does not):

```shell
export DB_URL=jdbc:postgresql://localhost:5432/job_discovery
export DB_USERNAME=job_discovery
export DB_PASSWORD='<the local password from .env>'
```

## Run and verify

```shell
./mvnw spring-boot:run
curl --fail http://localhost:8080/actuator/health
./mvnw verify
```

The integration test uses Testcontainers and automatically starts an isolated PostgreSQL instance. Docker must be available; the build fails rather than silently skipping the Phase 0 database gate.

Configuration fails at startup when `DB_PASSWORD` is absent. Do not commit `.env` or real credentials. See `docs/BACKEND_PROFILE.md` for architectural and operational decisions.

The provisional saved-filter and preview contracts are documented in `docs/api/filter-api.md`; the verified provider contract and its pagination/automation limitations are in `docs/integrations/hiringcafe.md`. Ranking behavior is documented in `docs/ranking.md`, with provisional scoring decisions recorded in `docs/architecture/decisions.md`. Manual API authentication and product approval of the ranking defaults remain production gates, so these endpoints must stay inside a trusted development environment.
