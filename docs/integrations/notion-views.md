# Notion views: supported setup and manual fallback

Verified against the official Notion API documentation on 2026-10-12 using `Notion-Version: 2026-03-11`.

The public API documents `POST /v1/views` for creating a view and requires the connection to have both insert-content and update-content capabilities. It does not currently document a list, retrieve, or search operation for existing views. Because the service cannot prove that a named view already exists after a restart, automatic creation is not safely idempotent and remains disabled. The application returns `MANUAL_FALLBACK_REQUIRED` with `VIEW_DISCOVERY_UNAVAILABLE`; this never blocks or rolls back job synchronization.

Official reference: https://developers.notion.com/reference/create-view

## Manual setup

Open the configured `Job Opportunities` database in Notion. For every entry below, use **Add a view**, keep the exact name, and select the stated layout. Unless a different sort is listed, sort by `Published At` descending and then `Last Synced At` descending. Existing user-created views may remain; do not delete or rename them automatically.

| View | Layout | Filter/grouping |
|---|---|---|
| All Opportunities | Table | No filter. |
| New Jobs - Last 24 Hours | Table | `First Synced At` is within the past 24 hours. |
| HiringCafe Jobs | Table | `Source` equals `HIRING_CAFE`. |
| LinkedIn Jobs | Table | `Source` equals `LINKEDIN`. This remains empty until an approved external importer supplies jobs. |
| Junior Opportunities | Table | `Seniority` equals or contains the configured junior value. |
| Mid-Level Opportunities | Table | `Seniority` equals or contains the configured mid-level value. |
| Senior Opportunities | Table | `Seniority` equals or contains the configured senior value. |
| Remote Opportunities | Table | `Work Mode` equals or contains the configured remote value. |
| International Opportunities | Table | `Eligibility` equals the configured international-eligibility value. Unknown eligibility must not match. |
| Application Tracker | Board | Group by `Status`; do not filter out archived opportunities automatically. |

Property option text can differ between workspaces. When it does, select the exact existing option that corresponds to the semantic value above rather than creating a duplicate option solely for a view.

## Safety checks

After setup:

1. Confirm all ten names are present exactly once.
2. Put sentinel values in `Status`, `User Notes`, `Applied At`, and one custom property.
3. Run a manual synchronization for the same job.
4. Verify those sentinels, `First Synced At`, and `Sync Operation ID` are unchanged.
5. Verify no page was archived, unarchived, deleted, or recreated.

Changing a view is presentation-only. It must never change page identity, ownership markers, tracking properties, or whether synchronization succeeds.

## Future automatic provisioning gate

`NotionViewProvisioner` supports a read-before-create gateway and is idempotent when that gateway can return existing view names conclusively. Automation may be enabled only after the pinned official API documents such discovery (or another conclusive idempotency mechanism), contract tests cover it, and the integration permissions are verified in a sandbox. A create-only capability is intentionally treated as manual fallback.
