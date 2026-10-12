# Deployment and rollback runbook

Owner: application operator. Approver: release owner.

## Deploy

1. Back up PostgreSQL and record the image/commit being deployed.
2. Verify the release checklist and keep unresolved capabilities disabled.
3. Apply Flyway migrations by starting one application instance against the target database. Never edit an applied migration.
4. Confirm `/actuator/health` is `UP` without component details and inspect authenticated `/actuator/metrics`.
5. Enable capabilities in order: manual preview, Notion writes/manual sync, imports, then scheduler. Observe each stage before proceeding.

## Roll back

1. Set `JOB_SCHEDULER_ENABLED=false`, `IMPORTS_ENABLED=false`, and `NOTION_WRITES_ENABLED=false`.
2. Allow the configured shutdown grace to finish accepted work; retain logs and request IDs.
3. Deploy the previous application artifact only if it is compatible with the current schema.
4. Do not reverse Flyway migrations, truncate operational tables, or delete unresolved markers/idempotency records.
5. If compatibility is uncertain, roll forward with a corrective release instead of changing data destructively.
6. Re-run health, authentication, read-only preview, and marker-count checks before restoring any capability.

Rollback is successful only when admissions are disabled, health is stable, durable safety state remains present, and no new external write starts.
