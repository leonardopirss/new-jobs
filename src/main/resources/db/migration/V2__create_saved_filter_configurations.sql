create table saved_filter_configurations (
    id uuid primary key,
    name varchar(120) not null,
    source varchar(32) not null,
    filter_specification jsonb not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint saved_filter_name_not_blank check (btrim(name) <> ''),
    constraint saved_filter_source_not_blank check (btrim(source) <> ''),
    constraint saved_filter_specification_object check (jsonb_typeof(filter_specification) = 'object'),
    constraint saved_filter_version_non_negative check (version >= 0)
);

create unique index saved_filter_name_unique_ci
    on saved_filter_configurations (lower(name));
