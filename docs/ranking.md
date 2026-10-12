# Ranking v1

Phase 3 provides a pure, deterministic ranking module under `com.jobs.newjobs.ranking`. It performs no database or external writes.

## Inputs

`RankingPreferences` selects one geographic mode and optional required technologies, desired technologies and accepted seniorities. `RankingCandidate` combines a `NormalizedJob` with explicit `GeographicEvidence` for work model, allowed countries/worldwide scope, residence requirement, sponsorship, relocation and work authorization.

When only a `NormalizedJob` is available, `rankJobs` derives conservative geographic evidence from `workplaceType` and clearly global/Brazilian locations. It does not infer sponsorship, relocation or authorization from missing provider fields. Integrations should supply explicit normalized evidence when the provider contract supports it.

## Output and filtering

The ranker returns `RankedJob` values containing:

- geographic status and selected path;
- decimal total and per-dimension points;
- per-technology evidence (`CONFIRMED`, `MENTIONED`, `UNKNOWN`, `NEGATED`);
- human-readable reasons for classifications and provisional defaults.

Proven incompatible jobs are excluded. `ELIGIBLE` always sorts before `UNKNOWN`, even when the unknown job has a higher numeric score. Remaining ties use technology points, seniority points, actual publication timestamp and canonical source identity.

## Evidence behavior

Technology matching uses token boundaries, so `Java` does not match `JavaScript`. The built-in deterministic aliases include `K8s`/`Kubernetes`, `Postgres`/`PostgreSQL`, common JavaScript/TypeScript names, Spring Boot and AWS. Structured technologies are confirmed; targeted requirements or responsibilities are mentioned; explicit technology-specific negation is negated. Equally relevant contradictory evidence becomes unknown with an explanation. Phrases such as `Kubernetes experience not required` are not treated as proof that Kubernetes is unused.

The scoring defaults and their approval gate are recorded in `docs/architecture/decisions.md`.
