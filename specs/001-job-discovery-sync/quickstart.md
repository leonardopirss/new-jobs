# Quickstart: Phases 4–8 Development and Validation

This is the target developer workflow for the phase 4–8 implementation. Until the corresponding phase is implemented, its variables/routes remain intentionally unavailable.

## Prerequisites

- Java 25
- Docker with Compose
- `curl`
- a local-only PostgreSQL password
- for sandbox tests only: a Notion integration token plus dedicated test database/data-source IDs

Never use production credentials in automated tests or commit them to `.env`.

## 1. Start PostgreSQL

Copy `.env.example` to `.env`, replace the password, then:

```shell
docker compose up -d --wait postgres

export DB_URL=jdbc:postgresql://localhost:5432/job_discovery
export DB_USERNAME=job_discovery
export DB_PASSWORD='<local password>'
```

Flyway applies V3–V5 in order. Do not edit or roll back V1/V2 after they have been applied.

## 2. Configure local authentication

Generate distinct high-entropy values outside shell history where practical, then expose them through the environment/secret mechanism used locally:

```shell
export MANUAL_API_KEY='<local manual key>'
export IMPORT_CREDENTIALS_JSON='<local JSON secret configuration>'
```

The intended integration configuration shape is:

```json
[
  {
    "integrationId": "linkedin-local-test",
    "source": "LINKEDIN",
    "scopes": ["jobs:import"],
    "currentSecret": "<separate high-entropy key>",
    "enabled": true
  }
]
```

Do not reuse `MANUAL_API_KEY` as an import secret. A previous secret is accepted only when an explicit `previousValidUntil` is still in the future.

## 3. Configure a Notion sandbox

Use a database dedicated to destructive-free tests, share it with the integration, and configure opaque IDs rather than UI URLs:

```shell
export NOTION_TOKEN='<sandbox integration token>'
export NOTION_DATABASE_ID='<opaque database id>'
export NOTION_DATA_SOURCE_ID='<opaque data source id>'
export NOTION_WRITES_ENABLED=false
export JOB_SCHEDULER_ENABLED=false
export JOB_SCHEDULER_INTERVAL=PT6H
export JOB_SCHEDULER_TIMEZONE=America/Sao_Paulo
```

Keep writes disabled for startup/schema validation. The target adapter sends `Notion-Version: 2026-03-11`. Configure the actual property names/types described in [notion-adapter.md](./contracts/notion-adapter.md), including `New` as a valid creation status.

## 4. Run automated verification

```shell
./mvnw verify
```

The phase-complete suite must include:

- manual/import authentication and default-deny route tests;
- ranking golden tests plus preview/saved-filter integration;
- Notion pagination, schema, ownership, limit, retry, and ambiguous-response contract tests;
- Testcontainers tests for marker commit order, advisory-lock serialization/loss, quota, idempotency, and exact rolling-window rate limits;
- failure injection at every create boundary;
- scheduler overlap/shutdown and telemetry redaction tests.

No test calls real HiringCafe or Notion unless it is explicitly tagged as a sandbox test.

## 5. Start the service

```shell
./mvnw spring-boot:run
```

Verify the anonymous sanitized probe:

```shell
curl --fail http://localhost:8080/actuator/health
```

Verify that a protected route rejects an absent credential, then succeeds with the manual key:

```shell
curl -i http://localhost:8080/api/v1/filters

curl --fail \
  -H "Authorization: Bearer ${MANUAL_API_KEY}" \
  http://localhost:8080/api/v1/filters
```

## 6. Exercise ranked preview

```shell
curl --fail \
  -H "Authorization: Bearer ${MANUAL_API_KEY}" \
  -H 'Content-Type: application/json' \
  --data @preview-request.json \
  http://localhost:8080/api/v1/jobs/preview
```

`preview-request.json` follows `TransientDiscoveryRequest` in [openapi.yaml](./contracts/openapi.yaml). Confirm that:

- no Notion write occurs;
- `INELIGIBLE` results are absent;
- all `ELIGIBLE` results precede `UNKNOWN`;
- score/evidence/explanations are present;
- identical fixture input and clock produce identical order.

## 7. Run a sandbox manual sync

After schema validation and adapter contract tests pass:

```shell
export NOTION_WRITES_ENABLED=true

curl --fail \
  -H "Authorization: Bearer ${MANUAL_API_KEY}" \
  -H 'Content-Type: application/json' \
  --data '{"savedFilterId":"<saved-filter-uuid>"}' \
  http://localhost:8080/api/v1/jobs/sync
```

Before the first run, put sentinel values in `Status`, `User Notes`, `Applied At`, and an extra property on an existing sandbox page. After syncing, verify all sentinel values remain unchanged. Confirm `First Synced At` is immutable and repeated synchronization updates rather than duplicates.

Never manually delete an unresolved marker to make the test pass. Reconcile it with conclusive sandbox evidence using the documented operator procedure.

## 8. Exercise import and idempotency

```shell
curl --fail \
  -H 'Authorization: Bearer <local import key>' \
  -H 'Idempotency-Key: local-test-0001' \
  -H 'Content-Type: application/json' \
  --data '{"jobs":[{"externalJobId":"123","title":"Java Backend Developer","url":"https://example.test/jobs/123"}]}' \
  http://localhost:8080/api/v1/jobs/import
```

Repeat the same request and verify it does not start independent work. Then reuse the key with a changed body and expect `409`. Eleven distinct valid admissions inside 60 seconds must result in ten admitted responses and one `429` with `Retry-After`.

## 9. Production activation checklist

Keep each capability independently disabled until its gate passes:

- manual key and separate import credentials provisioned/rotation tested;
- ingress TLS enforced;
- Notion sandbox schema, paging, archived visibility, view capability, and ambiguous-write recovery verified;
- ranking v1 formula details outside R1–R7 explicitly approved before ranking implementation, then reconfirmed for release;
- unresolved-marker alert and runbook tested;
- PostgreSQL backup/restore tested without losing unresolved markers;
- HiringCafe scheduled automation remains off until provider authorization and supported integration are documented.

Enable in order: authentication/read-only preview, Notion manual sync, import, then scheduler. A rollback disables admission/writes and deploys the previous compatible application; it does not delete Flyway schema or safety state.
