create table import_idempotency_records (
    integration_id varchar(128) not null,
    idempotency_key varchar(255) not null,
    payload_hash char(64) not null,
    state varchar(32) not null,
    request_id uuid not null,
    owner_token_hash char(64),
    received_count smallint not null,
    outcome_document jsonb,
    created_at timestamptz not null default transaction_timestamp(),
    updated_at timestamptz not null default transaction_timestamp(),
    expires_at timestamptz not null,
    primary key (integration_id, idempotency_key),
    constraint import_idempotency_integration_not_blank check (btrim(integration_id) <> ''),
    constraint import_idempotency_key_not_blank check (btrim(idempotency_key) <> ''),
    constraint import_idempotency_payload_hash check (payload_hash ~ '^[0-9a-f]{64}$'),
    constraint import_idempotency_owner_hash check (
        owner_token_hash is null or owner_token_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint import_idempotency_active_owner_required check (
        state not in ('RESERVED', 'PROCESSING') or owner_token_hash is not null
    ),
    constraint import_idempotency_state check (
        state in ('RESERVED', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED_PRESTART', 'RECONCILIATION_REQUIRED')
    ),
    constraint import_idempotency_received_count check (received_count between 1 and 50),
    constraint import_idempotency_outcome_object check (
        outcome_document is null or jsonb_typeof(outcome_document) = 'object'
    ),
    constraint import_idempotency_outcome_bounded check (
        outcome_document is null or octet_length(outcome_document::text) <= 65536
    ),
    constraint import_idempotency_timestamps_ordered check (updated_at >= created_at),
    constraint import_idempotency_minimum_expiry check (expires_at >= created_at + interval '48 hours')
);

create index import_idempotency_expiry
    on import_idempotency_records (expires_at);

create table import_rate_admissions (
    admission_id uuid primary key,
    integration_id varchar(128) not null,
    admitted_at timestamptz not null default transaction_timestamp(),
    constraint import_rate_integration_not_blank check (btrim(integration_id) <> '')
);

create index import_rate_integration_admitted_at
    on import_rate_admissions (integration_id, admitted_at);
