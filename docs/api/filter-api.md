# Filter and preview API v1 — provisional contract

The route names below follow the development-plan recommendations and remain provisional until explicitly approved. The preview route calls HiringCafe's verified read-only MCP search tool. It does not call Notion.

## Saved filters

- `POST /api/v1/filters` creates a named configuration and returns `201` with `Location`.
- `GET /api/v1/filters` lists configurations ordered by name.
- `GET /api/v1/filters/{id}` returns one configuration or `404`.
- `PUT /api/v1/filters/{id}` replaces the name, source, and specification and increments `version`.
- `DELETE /api/v1/filters/{id}` deletes one configuration and returns `204`.

Names are unique ignoring case. Only `HIRING_CAFE` is accepted for discovery; `LINKEDIN` is reserved in the common source enum but cannot be saved or previewed. A specification contains `commonFilters` and `providerSpecificFilters`. Common filter groups have `field`, `operation` (`INCLUDE` or `EXCLUDE`), `matchMode` (`ALL` or `ANY`), and string `values`. Exclusions are limited to `KEYWORD` and `COMPANY` and always use `ANY` semantics.

Verified HiringCafe-specific names are `search`, `location`, `workplace_types`, `employment_types`, `seniority_levels`, `role_type`, `min_salary`, `salary_frequency`, `posted_salary_only`, `max_years_experience`, `company_names`, `exclude_company_names`, `departments`, `language_requirements`, and `benefits_and_perks`. Other names return `422 UNSUPPORTED_FILTER` before any provider request. Except for `language_requirements`, whose AND/OR operator is explicitly documented, multi-value provider matching is rejected when the vendor has not documented its semantics.

Common filters are translated only where the official tool exposes a corresponding field. Unmapped fields, values outside the provider's documented enums, and ambiguous multi-value semantics return `422` during both saved-filter writes and previews; they are never silently stored or ignored. `PUBLICATION_TIME` is owned by the Phase 2 fixed 24-hour discovery window rather than accepting a caller-defined value.

## Preview

`POST /api/v1/jobs/preview` accepts `source` and `specification`. It validates the same DSL, invokes one bounded HiringCafe search, normalizes the documented structured response, applies an exact inclusive cutoff of `now - 24h`, and never persists jobs or filter changes.

The response contains `jobs`, `summary`, and `warnings`. `summary.complete` is currently always `false`: the verified MCP contract returns at most 25 jobs, one per company, and exposes no pagination input. Jobs with missing/invalid publication timestamps or malformed URLs are excluded and counted. Warnings distinguish the pagination limitation, per-company limitation, provider truncation, and invalid provider records. A provider transport failure returns `502 HIRINGCAFE_UNAVAILABLE` without leaking the upstream response.

Errors use Problem Details with a stable `code`. Request-shape failures return `400`; unsupported sources or filter semantics return `422`; duplicate names return `409`; provider unavailability returns `502`.

## Security gate

Manual-endpoint authentication is not implemented because open gate #9 has not decided whether manual operations use an admin secret or a distinct credential. These endpoints must not be exposed outside a trusted development environment until that decision is approved and implemented.
