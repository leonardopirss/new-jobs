# Import API v1 Contract

The machine-readable HTTP shape is in [openapi.yaml](./openapi.yaml).

## Request

`POST /api/v1/jobs/import`

Required headers:

- `Authorization: Bearer <integration-key>`
- `Idempotency-Key: <opaque-key>`
- `Content-Type: application/json`

Optional `X-Request-Id` is tracing input only. TLS is mandatory except localhost.

The authenticated integration binds the effective source and must have `jobs:import`. A body source field, if sent, is rejected as unknown; it can never override the credential binding. Manual credentials are invalid on this route.

Limits:

- 1–50 jobs;
- maximum raw body 512 KiB, including chunked transfer;
- 10 admitted requests per credential in every rolling 60 seconds;
- 100-second server deadline, including no more than 5 seconds for destination-lock acquisition;
- clients should allow at least 120 seconds.

Each job requires non-blank `title` and at least one trustworthy identity: `externalJobId` or stable `url`. Optional fields remain bounded by OpenAPI and Notion limits.

## Authentication and rotation

Credentials come only from environment/secret manager configuration. Each has a non-secret integration ID, source binding, scopes, current key, and optional previous key with an expiry. Disabled/revoked/expired credentials fail immediately. Verification uses fixed-length digest comparison; raw credentials are never persisted or logged.

## Admission and idempotency order

1. Authenticate and authorize credential/source.
2. Enforce media type and byte limit while reading.
3. Validate batch-wide shape and canonicalize it.
4. Enforce the exact distributed rolling rate limit. A rejected request does not consume capacity.
5. Atomically reserve `(integration, Idempotency-Key)` with the canonical payload hash for at least 48 hours.
6. Acquire the shared Notion destination lock and process through `SyncCoordinator`.

Same integration/key with a different hash returns `409`. Only the atomic reservation owner starts execution. An identical concurrent retry waits for a bounded interval; if the owner is still active it returns `409 IDEMPOTENCY_IN_PROGRESS` with the stable `requestId` and retry guidance, and if complete it reconstructs the itemized outcome. After restart, takeover is allowed only after proving a `RESERVED` owner started no sync work; `PROCESSING`/`RECONCILIATION_REQUIRED` must reconcile under the destination lock. A byte-identical replay response is not guaranteed.

## Response accounting

Processing responses are `200` with `status` `COMPLETED` or `PARTIAL`:

```json
{
  "requestId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "status": "PARTIAL",
  "summary": {
    "received": 2,
    "created": 1,
    "updated": 0,
    "unchanged": 0,
    "invalid": 0,
    "conflicted": 0,
    "uncertain": 0,
    "notProcessed": 1
  },
  "items": [
    {"index": 0, "status": "CREATED", "externalJobId": "123", "notionPageId": "opaque-id"},
    {"index": 1, "status": "NOT_PROCESSED", "reasonCode": "DEADLINE_BUDGET_EXHAUSTED"}
  ]
}
```

Every request index occurs exactly once and covers `0..received-1`. Summary buckets sum to `received`. `COMPLETED` is valid only when `invalid + conflicted + uncertain + notProcessed == 0`; otherwise status is `PARTIAL`. `INVALID`, `CONFLICTED`, `UNCERTAIN`, and `NOT_PROCESSED` require a stable `reasonCode`. `NOT_PROCESSED` means no external write was attempted for that item. `UNCERTAIN` means a write may have started or completed and its durable marker remains unresolved.

## HTTP outcomes before processing

| Status | Meaning |
|---|---|
| `400` | malformed request or missing/invalid idempotency key |
| `401` | absent/invalid/revoked credential |
| `403` | valid credential without scope/source permission |
| `409` | idempotency key reused with different canonical body |
| `413` | raw payload over 512 KiB |
| `415` | unsupported content type |
| `422` | batch-wide semantic failure, including zero/>50 jobs |
| `429` | distributed admission limit reached; includes `Retry-After` |
| `503` | destination lock/preflight unavailable before any item began |

Per-item identity conflicts return `200 PARTIAL`, not HTTP `409`. If transport disconnects after work starts, the caller may receive no response; reconciliation uses idempotency and sync markers.

## Ordering

Imported items retain request order. The API does not accept ranking preferences and does not fabricate them. Imports reuse identity, quota, locking, and safe-write behavior but do not pass through discovery ranking.
