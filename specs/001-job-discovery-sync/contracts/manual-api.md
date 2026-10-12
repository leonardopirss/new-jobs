# Manual API and Ranking Contract

The machine-readable HTTP contract is [openapi.yaml](./openapi.yaml). This document defines security and ordering rules that schemas alone cannot express.

## Authentication

- All `/api/v1/filters/**`, `/api/v1/jobs/preview`, and `/api/v1/jobs/sync` requests require `Authorization: Bearer <manual-key>`.
- The manual key is not an import key and never grants `jobs:import`.
- Missing/malformed/invalid credentials return `401`, `WWW-Authenticate: Bearer`, and a bounded problem body.
- An authenticated principal without the route's authority returns `403`.
- Authentication is stateless: no cookie, server session, form login, or CSRF token.
- Only configured liveness/readiness endpoints may be anonymous, and they expose no component details or secrets.
- TLS is mandatory outside localhost.

## Ranking preferences

```json
{
  "searchMode": "BOTH",
  "requiredTechnologies": ["java", "postgresql"],
  "desiredTechnologies": ["kubernetes"],
  "acceptedSeniorities": ["senior", "staff"]
}
```

Rules:

1. Preferences are additive. During the v1 compatibility window an omitted field maps to `BOTH` and empty arrays; responses always expose the effective preferences.
2. Existing saved filters migrate to the same explicit compatibility profile.
3. Values are trimmed and case-normalized; duplicates and alias overlap between required/desired sets are validation errors.
4. Ranking happens after source normalization, local filter evaluation, and same-source identity deduplication.
5. Proven `INELIGIBLE` jobs are removed. `ELIGIBLE` always precedes `UNKNOWN`, regardless of score.
6. Within an eligibility tier: total score descending, technology points descending, seniority points descending, actual publication timestamp descending with missing last, then canonical source identity ascending.
7. Preview returns the score dimensions, eligibility evidence, technology evidence, and explanations. No derived result is represented as provider-supplied fact.
8. Approved R1–R7 fix eligibility tiering, base dimensions `40/30/15/15`, technology required/desired `75/25`, evidence factors `CONFIRMED=1`, `MENTIONED=.5`, `UNKNOWN=0`, `NEGATED=0`, missing-dimension redistribution, and tie-break order. Editable seniority, exact seniority scoring, within-tier geography redistribution, and the recency curve remain disabled until the Phase 4 approval gate records a decision.
9. Discovery accepts only `HIRING_CAFE`; `LINKEDIN` is rejected before provider dispatch and exists only as a common/import source binding.

## Preview

`POST /api/v1/jobs/preview` performs no Notion mutation. The response includes only the ranked/eligible output and discovery warnings. `summary.discovered` counts the normalized deduplicated set before eligibility removal; `summary.returned` counts the final ranked list.

Incomplete discovery remains explicit through `summary.complete=false` and warnings. It must never be silently presented as complete.

## Saved filters

CRUD semantics remain compatible, with `rankingPreferences` added to create, update, and response bodies. `version` changes whenever filtering or ranking configuration changes. Saved configuration is used without reinterpretation by preview, manual sync, and scheduler.

## Manual synchronization

`POST /api/v1/jobs/sync` accepts exactly one mode:

- `savedFilterId`; or
- transient `source`, `specification`, and `rankingPreferences`.

The call is synchronous. It returns a bounded run summary and item outcomes but creates no local execution-history record. A lock/preflight failure before any item starts returns `503`. After item processing begins, item-level invalidity, conflict, uncertainty, or quota exhaustion is represented in a `200` `PARTIAL` result so every discovered input is accounted for.

The coordinator updates proven matches before considering new creates. Create candidates use the ranked order and stop at the remaining global Notion quota. `UNCERTAIN` means a create may have reached Notion and requires reconciliation; it is never automatically retried.

## Error format

All application errors use:

```json
{
  "type": "https://new-jobs.local/problems/validation",
  "title": "Request validation failed",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "requestId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "detail": "Bounded actionable message"
}
```

The body never includes credentials, raw Notion bodies, private notes, idempotency keys, or full job descriptions.
