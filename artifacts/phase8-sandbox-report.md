# Phase 8 sandbox report

- Date: 2026-10-12
- Environment: dedicated Notion sandbox and disposable local PostgreSQL 17.6
- Status: PASS
- Secret handling: no token, key, digest, database ID, data-source ID, page ID, request ID, payload, note content, or raw provider response is recorded here

## Configuration and access

- The database and data source both returned `200` to authenticated API reads.
- All 18 required properties matched their contractual types.
- `Source` contained the exact `HIRING_CAFE` and `LINKEDIN` options; `Status` contained `New`.
- Health returned `200 UP` without details, an anonymous protected request returned `401`, and authenticated filter and metrics requests returned `200`.
- Manual and import boundaries used distinct sandbox credentials.

## Discovery and manual sync

- The saved `Phase 8 Sandbox` filter loaded successfully with ranking mode `BOTH`.
- The controlled HiringCafe no-match preview returned `200`, zero jobs, `complete=false`, and two provider warnings. No Notion write occurred.
- Manual synchronization returned `200 COMPLETED` with zero received items, as expected for the controlled no-match filter.
- Scheduled HiringCafe automation remained disabled because provider authorization is still unresolved.

## Notion create, reconciliation, and update

- The first controlled create exposed a current-API compatibility defect: Notion omitted the legacy `archived` boolean and the Jackson 3 parser treated the missing value as malformed.
- The durable marker moved to `UNCERTAIN`; a conclusive identity lookup found exactly one matching page with the same immutable operation marker. No blind retry or duplicate create occurred.
- The parser was corrected to default optional booleans safely, and a regression test now covers current responses that omit `archived`.
- A subsequent controlled import returned `CREATED`; its marker was `CONFIRMED`.
- Repeating the same key and body returned the same request identity without independent work. Reusing the key with a changed body returned `409 IDEMPOTENCY_KEY_REUSED`.
- After sentinel values were placed in `Status`, `User Notes`, and `Applied At`, a changed backend-owned payload returned `UPDATED`.
- The direct read confirmed all three sentinel fields were preserved, while `Sync Operation ID` and immutable `First Synced At` remained present.
- Final marker evidence was two total, both `CONFIRMED`, with zero `PREPARED`, `IN_FLIGHT`, `UNCERTAIN`, or `CONFLICT` markers.

## Admission, observability, and safety

- Eleven distinct valid requests inside one rolling minute produced exactly ten `200` responses followed by one `429 IMPORT_RATE_LIMITED` response with `Retry-After`; the database recorded ten rolling admissions.
- Import idempotency state had zero `RESERVED`, `PROCESSING`, or `RECONCILIATION_REQUIRED` records after the primary flow.
- Application-log scans found no Notion token, manual key, import key, or sentinel content.
- Protected metrics returned `200` only with the manual credential.
- Automated failure injection covers Notion `429/5xx`, pagination incompleteness, malformed provider data, lock loss, crash boundaries, client disconnect, retry exhaustion, quota, and advisory-lock behavior.

## Remaining external gates

- TLS policy/probe and PostgreSQL isolated restore evidence remain target-environment operator gates.
- The Notion Views API is not required for synchronization; the documented manual-view fallback remains in effect.
- HiringCafe scheduled use remains blocked and the scheduler remains disabled until provider authorization is documented.
