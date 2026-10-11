# Job Discovery & Notion Sync

Foundation for a Java/Spring Boot service that discovers, ranks, and safely synchronizes jobs to Notion. Only Phase 0 infrastructure is implemented. HiringCafe, ranking, Notion writes, imports, scheduling, and LinkedIn collection are not implemented.

## Requirements

- Java 21
- Docker with Compose

Spring Boot 4.1.1 is the selected stable framework version and supports Java 21. Maven is provided through `mvnw`.

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
