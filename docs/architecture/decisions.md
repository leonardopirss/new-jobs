# Architecture decisions

## ADR-003 — Ranking v1 defaults

- Status: approved for implementation and production exposure.
- Date: 2026-10-11.
- Approved: explicitly confirmed by the product owner on 2026-10-11.
- Scope: Ranking preferences, ranked preview, and downstream ranking-ordered selection.

### Context

Ranking decisions R1–R7 approve eligibility tiers, technology evidence, top-level weights and deterministic tie breakers. The source contract intentionally leaves seniority distance, recency decay and the numeric geography component open. Phase 3 needs deterministic behavior without presenting those open choices as approved product policy.

### Decision

1. Geography is a strict tier (`ELIGIBLE`, then `UNKNOWN`); `INELIGIBLE` candidates are removed. The 30 geographic points are not scored again inside a tier. They are redistributed proportionally across configured technology, seniority and recency dimensions.
2. Seniority is exact-category matching only. A configured category match scores 1; an explicit mismatch or missing value scores 0. No adjacent-level, experience-year or gradual R8 rule is inferred. When no accepted seniority is configured, its weight is redistributed.
3. Recency uses a linear UTC decay from 1 at publication time to 0 at the inclusive 24-hour boundary. Future timestamps clamp to 1; missing or older timestamps score 0. The discovery connector still excludes items older than the exact 24-hour cutoff.
4. Technology keeps 75% required and 25% desired weighting. A missing required or desired category gives the entire technology weight to the configured category. With no configured technologies, the technology dimension is removed and its weight redistributed.
5. Scores use `BigDecimal` with DECIMAL128 operations and are compared without display rounding. Ordering is eligibility tier, total score, technology points, seniority points, publication time and canonical source identity.

### Consequences

- Geography cannot influence ordering twice, and an `UNKNOWN` candidate cannot outrank an `ELIGIBLE` candidate through numeric score.
- The defaults are deterministic and covered by golden tests, but changing them after product sign-off can intentionally change ordering.
- The ranker exposes explanations for eligibility, weight redistribution, seniority and recency. Technology evidence retains per-technology reasons.
- Provider fields that are not available remain `UNKNOWN`; missing sponsorship is never treated as denial, and sponsorship support is not presented as a visa guarantee.

### Approval record

The product owner explicitly approved exact-category seniority matching, proportional redistribution of the
30-point geography weight inside each eligibility tier, and linear 24-hour recency decay. Any future change
requires a replacement decision and updated golden fixtures before implementation.
