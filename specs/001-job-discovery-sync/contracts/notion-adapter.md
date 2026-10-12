# Notion Adapter Contract

This is an outbound port contract. Only `SyncCoordinator` may invoke mutation methods.

## Transport

- Base URL: official Notion API.
- Required headers: bearer integration token, `Notion-Version: 2026-03-11`, and JSON content type where applicable.
- Resource identity: configured opaque database/data-source IDs and returned opaque page IDs. Generated URLs are never stored as provider identity.
- All connect/request timeouts and retries are bounded by the caller deadline.

## Required operations

### `validateSchema(destination)`

Retrieve the data source and verify the configured conceptual properties and types before a sync can write. Extra properties are allowed and user-owned. Missing properties, wrong types, or missing `New` status produce a typed preflight failure.

Minimum conceptual map:

| Concept | Expected type | Owner |
|---|---|---|
| Job Title | title | backend on create/update when non-empty |
| Source | select | backend |
| External Job ID | rich text | backend |
| Canonical URL | url or bounded rich text | backend |
| Job URL | url | backend |
| Company | rich text | backend |
| Status | status/select | user after create; backend sets `New` only on create |
| User Notes | rich text | user only |
| Applied At | date | user only |
| Published At | date | backend |
| First Synced At | date | backend, immutable |
| Sync Operation ID | rich text | backend on create, immutable correlation/automation ownership marker |
| Last Synced At | date | backend after confirmed sync |
| Work Mode / Location / Eligibility / Seniority / Technologies | configured compatible types | backend |

Real property names live in typed external configuration; the conceptual ownership above is invariant.

### `queryByIdentity(destination, source, externalId?, canonicalUrl?)`

Query both supplied identity forms and paginate to completion. Cursor values are opaque and relayed unchanged. Search active and archived visibility supported by the API. A non-complete query response is `INCOMPLETE`, never an empty successful result.

Outcomes: `NONE`, `ONE(page snapshot)`, `CONFLICT(pages)`, or `INCOMPLETE(reason)`.

### `scanAutomationCreations(destination, window)`

Return all automation-owned pages relevant to the global 300-distinct-page rolling 24-hour quota, including archived visibility where supported, with immutable `First Synced At` and `Sync Operation ID`. Manual pages lack these creation markers and are excluded; updates are free. Missing/malformed timestamps, incomplete pagination, or ambiguous ownership are explicit failures that block new creates.

### `createPage(destination, draft, operationId)`

Precondition: the coordinator holds the destination lock and has committed the corresponding marker. The draft contains identity, backend-owned fields, `Status=New`, `First Synced At`, and immutable `Sync Operation ID=operationId`. Description blocks are bounded to provider limits.

Return `CONFIRMED(pageId, snapshot)` only when the response conclusively identifies the page. Distinguish `RELIABLY_NOT_SENT` from `AMBIGUOUS`; timeouts, connection loss during/after send, and relevant provider `5xx` after a possible send are ambiguous.

### `updatePage(pageId, patch)`

The adapter serializes only a backend-owned allowlist. It never emits:

- `Status` after creation;
- `User Notes`;
- `Applied At`;
- unknown/additional properties;
- `is_archived` or `in_trash`;
- null/empty source values intended to erase existing values;
- changes to `First Synced At`.

`Last Synced At` is sent atomically in the safe patch. It is considered updated only after a successful response or conclusive read reconciliation; an ambiguous update is re-read before any retry.

## Retry matrix

| Operation/failure | Behavior |
|---|---|
| Read `429` | Honor `Retry-After` if within deadline; bounded retry. |
| Read transient `5xx`/connect failure | Bounded exponential backoff within deadline. |
| Create rejected before any bytes can be sent | May return `RELIABLY_NOT_SENT`. |
| Create `429` known not accepted | Honor `Retry-After` only when provider semantics conclusively indicate no write and deadline permits. |
| Create timeout/connection loss/ambiguous `5xx` | No blind retry; return `AMBIGUOUS`, retain marker. |
| Update ambiguous | Re-read page and reconcile owned fields before deciding whether another patch is safe. |
| Validation/`4xx` | No generic retry; typed bounded failure. |

## Safety invariants

1. A negative query is not proof that an earlier create was not sent.
2. Existing archived/trashed pages are never unarchived, recreated, or mutated destructively.
3. Conflicting identities isolate all involved pages from automatic mutation.
4. Quota counts one page once even if multiple filters discover it.
5. Adapter errors expose provider request IDs/status/reason codes where safe, never tokens or raw user content.
6. View creation is optional operational setup. Failure to create a view never changes synchronized data and falls back to a documented manual checklist.
