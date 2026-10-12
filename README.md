# Job Discovery & Notion Sync

Java/Spring Boot service for discovering, ranking, and safely synchronizing jobs to Notion. Manual synchronization, the governed import API, safe Notion writes, deterministic ranking, and distributed scheduled execution are implemented. LinkedIn collection is intentionally external and scheduled HiringCafe automation remains authorization-gated.

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
export MANUAL_API_KEY='<a separate random key with at least 32 characters>'
```

## Run and verify

```shell
./mvnw spring-boot:run
curl --fail http://localhost:8080/actuator/health
curl --fail -H "Authorization: Bearer ${MANUAL_API_KEY}" http://localhost:8080/api/v1/filters
./mvnw verify
```

The integration test uses Testcontainers and automatically starts an isolated PostgreSQL instance. Docker must be available; the build fails rather than silently skipping the Phase 0 database gate.

Configuration fails at startup when `DB_PASSWORD` or the manual API key is absent/short. The optional
`MANUAL_API_PREVIOUS_KEY` and `MANUAL_API_PREVIOUS_VALID_UNTIL` must be configured together and are
accepted only during the bounded rotation overlap. Do not reuse this operator key for import clients,
commit `.env`, or log real credentials. All `/api/v1/filters/**` and `/api/v1/jobs/preview` calls require
`Authorization: Bearer <manual-key>`; health remains anonymously available without component details.
See `docs/BACKEND_PROFILE.md` for architectural and operational decisions.

## Scheduled synchronization

Scheduling is fail-closed and disabled by default. It may be activated only after Notion manual/import
validation succeeds and HiringCafe authorization or a supported automated feed is documented:

```shell
export NOTION_WRITES_ENABLED=true
export JOB_SCHEDULER_ENABLED=true
export HIRINGCAFE_SCHEDULED_AUTHORIZED=true
export JOB_SCHEDULER_INTERVAL=PT6H
export JOB_SCHEDULER_TIMEZONE=America/Sao_Paulo
export JOB_SHUTDOWN_GRACE=PT30S
```

Every tick snapshots all saved filters. A PostgreSQL session advisory lock prevents the same filter
from overlapping across instances, while the destination lock continues to serialize Notion writes.
Overlapping/missed ticks are skipped rather than replayed. Shutdown immediately stops new manual,
import, and scheduled admissions, then allows already-started work a bounded grace period. Keep
`HIRINGCAFE_SCHEDULED_AUTHORIZED=false` until the provider gate is closed; enabling only the scheduler
does not bypass it.

## Safe Notion synchronization and imports

Notion writes are independently disabled by default. A write-capable environment requires a dedicated integration,
opaque sandbox database/data-source IDs, the validated conceptual schema, and `NOTION_WRITES_ENABLED=true`.
Manual sync and imports both use the same destination lock, identity reconciliation, immutable safety markers,
300-page rolling quota, user-field ownership rules, and ambiguous-write recovery. Neither route archives, trashes,
recreates, or clears user-managed tracking fields.

The import API additionally requires `IMPORTS_ENABLED=true` and source-bound credentials configured through
`IMPORT_CREDENTIALS_JSON`. Import credentials must be distinct from `MANUAL_API_KEY`; requests are limited to
50 items and 512 KiB, ten admissions per integration per rolling minute, and a required idempotency key.

## Operations and release status

Deployment, compatible rollback, backup/restore, secret rotation, unresolved-marker reconciliation, and incident
procedures live under `docs/operations/`. The release gate status is recorded in
`docs/operations/release-checklist.md`. `/actuator/health` is anonymous and sanitized; `/actuator/metrics` requires
the manual operator credential.

Current limitations are explicit:

- HiringCafe returns one bounded, non-pageable result set and documents conversational rather than scheduled use;
- scheduled HiringCafe discovery remains disabled until provider authorization or a supported feed exists;
- Notion writes and imports remain disabled until the authenticated sandbox, secret, TLS, backup/restore, and
  reconciliation gates are closed for the target environment;
- LinkedIn collection is not implemented; only a separately authenticated, source-bound import contract exists;
- Notion view provisioning is optional and falls back to the documented manual view checklist.

The saved-filter and preview contracts are documented in `docs/api/filter-api.md`; the verified provider contract
and its pagination/automation limitations are in `docs/integrations/hiringcafe.md`. Ranking behavior is documented
in `docs/ranking.md`, with approved scoring decisions and release reconfirmation in
`docs/architecture/decisions.md`.
