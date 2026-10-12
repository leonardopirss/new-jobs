# PostgreSQL backup and restore runbook

Owner: database operator. Witness: release owner.

## Backup

Use a least-privilege account and an encrypted operator-controlled destination:

```shell
pg_dump --format=custom --no-owner --no-privileges --dbname="$DB_URL" --file=new-jobs.dump
pg_restore --list new-jobs.dump
```

Record the database version, Flyway version, timestamp, encrypted artifact checksum, retention, and operator. Never commit the dump.

## Restore exercise

1. Create an isolated PostgreSQL database running the same major version.
2. Restore with `pg_restore --clean --if-exists --no-owner --dbname="$RESTORE_DB_URL" new-jobs.dump`.
3. Start the same application release with all write/import/scheduler flags disabled.
4. Run `select version, success from flyway_schema_history order by installed_rank;` and require every row to be successful.
5. Compare counts for `saved_filter_configurations`, `sync_safety_markers`, `import_idempotency`, and `import_rate_admissions`.
6. Verify every unresolved marker and non-expired idempotency outcome is present, then run read-only application health and repository tests.
7. Destroy the isolated database according to the environment retention policy.

Production activation remains blocked until a dated restore exercise is attached to the release checklist.
