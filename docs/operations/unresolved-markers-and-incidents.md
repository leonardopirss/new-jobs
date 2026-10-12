# Unresolved markers and incident response

Owner: application operator. Escalation: release owner and database operator.

## Reconcile an unresolved create

1. Disable new Notion writes and the scheduler; do not delete or age out the marker.
2. Locate `PREPARED`, `IN_FLIGHT`, `UNCERTAIN`, or `CONFLICT` rows by destination and operation ID.
3. Query the complete Notion destination for the immutable `Sync Operation ID`, external ID, and canonical URL, including archived visibility.
4. Exactly one matching page permits confirmation with its opaque page ID. No match after a conclusive, complete read may be classified according to the protocol; an incomplete/failed read remains unresolved.
5. Multiple or disagreeing identity matches become `CONFLICT` and require human resolution. Never retry a possibly delivered create blindly.
6. Preserve before/after evidence without job descriptions, notes, tokens, or raw provider bodies.

## Common incidents

- Notion `429/5xx`: keep creates fail-closed, inspect bounded retry metrics, and wait for provider recovery.
- Destination/filter lock contention: confirm the current owner is live; never terminate a healthy lock holder to force throughput.
- Lock loss: stop starting writes, preserve markers, and reconcile started work.
- Quota/read incompleteness: updates proven safe may continue, but block new creates until complete evidence returns.
- Import spike: retain exact rate limiting and idempotency state; do not bypass admissions manually.
- Suspected data damage: disable all write-capable flags, capture request IDs, restore only into isolation, and prefer corrective forward changes.

An incident closes only after safety state is reconciled, cause and affected request IDs are recorded, recovery tests pass, and the release owner approves re-enablement.
