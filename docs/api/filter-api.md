# Filter API v1 — Phase 1 provisional contract

The route names below follow the development-plan recommendations and remain provisional until explicitly approved. Phase 1 does not call HiringCafe or Notion.

## Saved filters

- `POST /api/v1/filters` creates a named configuration and returns `201` with `Location`.
- `GET /api/v1/filters` lists configurations ordered by name.
- `GET /api/v1/filters/{id}` returns one configuration or `404`.
- `PUT /api/v1/filters/{id}` replaces the name, source, and specification and increments `version`.
- `DELETE /api/v1/filters/{id}` deletes one configuration and returns `204`.

Names are unique ignoring case. Only `HIRING_CAFE` is accepted for discovery; `LINKEDIN` is reserved in the common source enum but cannot be saved or previewed. A specification contains `commonFilters` and `providerSpecificFilters`. Common filter groups have `field`, `operation` (`INCLUDE` or `EXCLUDE`), `matchMode` (`ALL` or `ANY`), and string `values`. Exclusions are limited to `KEYWORD` and `COMPANY` and always use `ANY` semantics.

No HiringCafe-specific option has yet been verified, so every non-empty `providerSpecificFilters` entry is rejected with `422 UNSUPPORTED_FILTER`. Phase 2 may enable only options confirmed by the official provider contract.

## Preview

`POST /api/v1/jobs/preview` accepts `source` and `specification`. It validates the same DSL and never persists it. Until the Phase 2 connector exists, a valid request returns `501 PROVIDER_CONNECTOR_NOT_IMPLEMENTED`; it never returns a misleading empty success.

Errors use Problem Details with a stable `code`. Request-shape failures return `400`; unsupported sources or filter semantics return `422`; duplicate names return `409`.

## Security gate

Manual-endpoint authentication is not implemented because open gate #9 has not decided whether manual operations use an admin secret or a distinct credential. These endpoints must not be exposed outside a trusted development environment until that decision is approved and implemented.
