# Phase 8 release checklist

Status date: 2026-10-12. Owners must attach environment-specific evidence before changing an `OPEN` gate to `CLOSED`.

| Gate | Owner | Status | Evidence / required action | Fail-closed control |
|---|---|---|---|---|
| Ranking v1 profile | Product owner | CLOSED | ADR-003 reconfirmed without formula changes; golden fixtures remain authoritative | Ranking change requires a replacement ADR and fixtures |
| Separate manual/import secrets and rotation | Security/operator | CLOSED (sandbox) | Distinct credentials passed authenticated manual/import boundaries; production provisioning and rotation remain an activation prerequisite | Imports remain disabled until target credentials are provisioned |
| TLS at public ingress | Platform operator | OPEN | Attach TLS policy and external probe evidence | No public deployment |
| Notion schema, status, paging and archived visibility | Application operator | CLOSED (sandbox) | `artifacts/phase8-sandbox-report.md`; current responses omitting legacy `archived` are covered by regression test | Notion writes/imports remain disabled until target configuration repeats the preflight |
| Notion Views API capability | Application operator | CLOSED (fallback) | Synchronization is view-independent; `docs/integrations/notion-views.md` defines the manual fallback | View failure never blocks or changes sync data |
| Telemetry/redaction | Application operator | CLOSED (automated) | `OperationalTelemetryTests` and protected metrics security test | Health details stay hidden; metrics require manual auth |
| Unresolved marker alert/reconciliation | Application operator | CLOSED (sandbox) | A real ambiguous create was reconciled by identity and operation marker; final unresolved count was zero | New creates remain blocked on uncertainty |
| PostgreSQL backup/restore | Database operator | OPEN | Complete dated isolated restore exercise | Deployment/write activation blocked |
| HiringCafe scheduled authorization | Product/vendor owner | BLOCKED | Provider currently documents conversational/manual use only | `HIRINGCAFE_SCHEDULED_AUTHORIZED=false` and scheduler disabled |
| Full automated suite and API contract | Release owner | CLOSED (automated) | 150 tests passed; `OpenApiContractTests` parsed OpenAPI 3.1 and resolved every internal reference | PR cannot merge on CI failure |

Production capability order is preview → manual Notion sync → import → scheduler. Each stage requires its own closed gate; closing one never implicitly closes another.
