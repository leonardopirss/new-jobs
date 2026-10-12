# Secret rotation and access runbook

Owner: security/operator team.

- Store database, manual API, import integration, and Notion credentials in the environment secret manager; never in Git, images, logs, metrics, reports, or shell transcripts.
- Manual and import credentials must be different. Import credentials are source-bound and limited to `jobs:import`.
- Rotate the manual key by installing `MANUAL_API_PREVIOUS_KEY` plus a bounded `MANUAL_API_PREVIOUS_VALID_UNTIL`, deploying the new current key, migrating clients, then removing the previous key.
- Rotate an import key with the corresponding `previousKey` and `previousValidUntil` in `IMPORT_CREDENTIALS_JSON`; revoke immediately by disabling/removing that integration configuration and redeploying.
- Rotate the Notion token in the sandbox first. Validate schema and a read before enabling writes; revoke the old token after the new token succeeds.
- On suspected disclosure, disable the affected feature, revoke at the provider/secret manager, rotate, inspect request IDs and admission metrics, and reconcile any in-flight marker before re-enabling.

Rotation evidence records only credential identifiers, timestamps, owners, and success/failure. It never records secret values or digests.
