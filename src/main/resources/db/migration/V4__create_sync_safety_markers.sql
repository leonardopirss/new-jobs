create table sync_safety_markers (
    operation_id uuid primary key,
    destination_id varchar(128) not null,
    source varchar(32) not null,
    external_job_id varchar(512),
    canonical_url varchar(2048),
    external_id_hash char(64),
    canonical_url_hash char(64),
    operation_type varchar(16) not null,
    state varchar(16) not null,
    notion_page_id varchar(128),
    failure_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    resolved_at timestamptz,
    constraint sync_marker_destination_not_blank check (btrim(destination_id) <> ''),
    constraint sync_marker_source_not_blank check (btrim(source) <> ''),
    constraint sync_marker_identity_required check (
        (external_job_id is not null and btrim(external_job_id) <> '')
        or (canonical_url is not null and btrim(canonical_url) <> '')
    ),
    constraint sync_marker_external_hash_consistent check (
        (external_job_id is null and external_id_hash is null)
        or (external_job_id is not null and external_id_hash ~ '^[0-9a-f]{64}$')
    ),
    constraint sync_marker_url_hash_consistent check (
        (canonical_url is null and canonical_url_hash is null)
        or (canonical_url is not null and canonical_url_hash ~ '^[0-9a-f]{64}$')
    ),
    constraint sync_marker_operation_type check (operation_type in ('CREATE')),
    constraint sync_marker_state check (
        state in ('PREPARED', 'IN_FLIGHT', 'CONFIRMED', 'NOT_SENT', 'UNCERTAIN', 'CONFLICT')
    ),
    constraint sync_marker_resolution_consistent check (
        (state in ('CONFIRMED', 'NOT_SENT') and resolved_at is not null)
        or (state not in ('CONFIRMED', 'NOT_SENT') and resolved_at is null)
    ),
    constraint sync_marker_confirmed_page_required check (
        state <> 'CONFIRMED' or notion_page_id is not null
    ),
    constraint sync_marker_timestamps_ordered check (
        updated_at >= created_at and (resolved_at is null or resolved_at >= created_at)
    )
);

create unique index sync_marker_active_external_identity
    on sync_safety_markers (destination_id, source, external_id_hash)
    where external_id_hash is not null
      and state in ('PREPARED', 'IN_FLIGHT', 'UNCERTAIN', 'CONFLICT');

create unique index sync_marker_active_url_identity
    on sync_safety_markers (destination_id, source, canonical_url_hash)
    where canonical_url_hash is not null
      and state in ('PREPARED', 'IN_FLIGHT', 'UNCERTAIN', 'CONFLICT');

create index sync_marker_unresolved_destination
    on sync_safety_markers (destination_id, created_at, operation_id)
    where state in ('PREPARED', 'IN_FLIGHT', 'UNCERTAIN', 'CONFLICT');
