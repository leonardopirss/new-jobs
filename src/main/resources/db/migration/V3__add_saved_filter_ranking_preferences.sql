alter table saved_filter_configurations
    add column ranking_preferences jsonb not null default
        '{"searchMode":"BOTH","requiredTechnologies":[],"desiredTechnologies":[],"acceptedSeniorities":[]}'::jsonb;

alter table saved_filter_configurations
    add constraint saved_filter_ranking_preferences_object
        check (jsonb_typeof(ranking_preferences) = 'object');
