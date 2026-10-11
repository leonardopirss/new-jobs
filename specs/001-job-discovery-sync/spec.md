# Feature Specification: Job Discovery and Synchronization

**Feature Branch**: `main`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "Discover recent software engineering jobs through provider-defined
filters, preview or synchronize matching opportunities to Notion, and persist reusable filter
configurations for scheduled discovery."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Configure and Preview a Job Search (Priority: P1)

As the user, I can submit provider-specific search criteria for a one-time search or save them as a
reusable filter configuration, then preview matching jobs without changing Notion.

**Why this priority**: The user must be able to express and validate the desired search before any
persistent job records are created.

**Independent Test**: Submit an authenticated HiringCafe search containing common and
provider-specific filters and verify that the returned preview contains only matching jobs while
neither the saved configurations nor Notion records change unless saving was explicitly requested.

**Acceptance Scenarios**:

1. **Given** valid common and HiringCafe-specific filters, **When** the user requests a transient
   preview, **Then** matching normalized jobs are returned and the filters are not saved.
2. **Given** valid filters and a requested profile name, **When** the user saves the configuration,
   **Then** it can be retrieved, updated, used, and deleted through authenticated operations.
3. **Given** a filter group with `ALL` matching, **When** a job lacks any required value, **Then** the
   job is excluded.
4. **Given** a filter group with `ANY` matching, **When** a job matches at least one requested value,
   **Then** that group matches.
5. **Given** a filter that HiringCafe cannot execute directly, **When** sufficient returned job data
   exists, **Then** the filter is evaluated locally.
6. **Given** a filter that cannot be executed by HiringCafe or evaluated from returned data, **When**
   the request is validated, **Then** the request is rejected with the unsupported filter identified.

---

### User Story 2 - Synchronize Matching Jobs to Notion (Priority: P1)

As the user, I can explicitly synchronize the results of a transient or saved search into one shared
Notion job database and receive a summary of the outcome.

**Why this priority**: Centralizing relevant opportunities while avoiding manual copying is the core
product outcome.

**Independent Test**: Run a synchronization against controlled provider results and an empty Notion
database, then verify that each matching job is represented once with its complete available source
content and that the summary reports created, updated, skipped, and failed items.

**Acceptance Scenarios**:

1. **Given** a matching job not present in Notion, **When** synchronization runs, **Then** one job
   page is created with its normalized properties, full available description, source link, and
   application link.
2. **Given** a previously synchronized job whose source data changed, **When** synchronization runs,
   **Then** source-controlled fields are updated without overwriting `Status` or `User Notes`.
3. **Given** the same provider job appears repeatedly or across overlapping searches, **When**
   synchronization runs, **Then** only one Notion job record exists for that provider identity.
4. **Given** some jobs fail to synchronize, **When** other jobs can still be processed safely,
   **Then** successful items remain synchronized and the summary identifies each failed item and
   retryable outcome.
5. **Given** the user requests preview mode, **When** results are returned, **Then** no Notion content
   is created or updated.

---

### User Story 3 - Run Scheduled Discovery (Priority: P2)

As the user, I can enable recurring discovery using all saved provider filter configurations at a
globally configured interval.

**Why this priority**: Recurring discovery reduces manual searching after the end-to-end manual flow
has been proven.

**Independent Test**: Configure a controlled schedule with multiple saved HiringCafe filter
configurations and verify that every configuration is processed, matching jobs are synchronized, and
a second run for the same configuration cannot overlap the first.

**Acceptance Scenarios**:

1. **Given** saved filter configurations and an enabled schedule, **When** the interval is reached,
   **Then** each saved configuration is searched and synchronized.
2. **Given** a synchronization for one configuration is still active, **When** another trigger for
   that configuration occurs, **Then** the second execution does not overlap and its disposition is
   reported.
3. **Given** a scheduled run fails partway, **When** the next scheduled run occurs, **Then** it can
   safely retry discovery without duplicating previously synchronized jobs.
4. **Given** two saved configurations produce the same provider job, **When** both are processed,
   **Then** the job is represented once in Notion.

---

### User Story 4 - Track Applications Without Losing User Data (Priority: P2)

As the user, I can organize and update application-tracking information in Notion while later
synchronizations refresh provider data without changing my tracking state or notes.

**Why this priority**: The synchronized database is useful only if automation does not damage the
user's ongoing application workflow.

**Independent Test**: Modify `Status` and `User Notes` for an existing job, synchronize changed
provider data, and verify that both user-owned values remain byte-for-byte unchanged.

**Acceptance Scenarios**:

1. **Given** user-maintained status and notes, **When** the job is updated from its provider, **Then**
   those properties are preserved exactly.
2. **Given** the provider explicitly reports that a synchronized job is no longer available, **When**
   availability is reconciled, **Then** the job remains in Notion and is marked unavailable.
3. **Given** a job is absent from a recent-results search without an explicit availability signal,
   **When** synchronization completes, **Then** the job is not marked unavailable merely because of
   that absence.

---

### User Story 5 - Operate Safely During Provider Failures (Priority: P3)

As the user, I receive a clear outcome when HiringCafe or Notion is unavailable, throttles requests,
returns partial data, or limits result completeness.

**Why this priority**: External failures must not silently lose opportunities, duplicate records, or
misrepresent a partial run as complete.

**Independent Test**: Simulate timeouts, throttling, malformed items, partial pages, and result caps,
then verify bounded processing, safe retries, retained successes, and accurate summaries.

**Acceptance Scenarios**:

1. **Given** a transient external failure, **When** retry conditions are met, **Then** bounded retries
   occur and the final outcome is reported.
2. **Given** the provider indicates a result cap or incomplete retrieval, **When** discovery finishes,
   **Then** the summary states that completeness is not guaranteed.
3. **Given** one malformed provider item among valid items, **When** the response is processed, **Then**
   valid items continue and the malformed item is reported without invented values.
4. **Given** an invalid or missing API key, **When** a manual operation is requested, **Then** no
   discovery, configuration change, or synchronization is performed.

### Edge Cases

- A job published exactly at the rolling 24-hour cutoff is evaluated against one consistent clock and
  a documented inclusive boundary.
- A provider's calendar-based "one day" filter differs from an exact rolling 24-hour interval; local
  evaluation is applied when timestamps are available, and the limitation is reported otherwise.
- Salary, sponsorship, relocation, eligibility, experience, or language data is missing; an unknown
  value never satisfies a filter requiring positive confirmation.
- The provider returns the same external identifier with conflicting URLs or content; the item is
  treated as one provider identity and the conflict is reported.
- An external identifier is absent; a canonical source URL may provide provider-level identity, and
  an item lacking both is not synchronized automatically.
- Two jobs from different providers appear similar; they may be flagged as a possible match but are
  never automatically merged.
- A job description exceeds the destination's per-block or per-request limits; all available content
  is preserved across supported content units without silently truncating it.
- A Notion schema is missing, incompatible, or contains unexpected property types; synchronization is
  blocked before job writes and the mismatch is reported.
- Notion is changed between duplicate lookup and update; the operation must avoid creating an
  additional page and must report any conflict it cannot resolve safely.
- A saved filter configuration is updated or deleted while a run is active; the active run uses one
  consistent snapshot and later runs use the new state.
- No jobs match; the operation succeeds with an empty result and an explicit zero-count summary.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST support one authenticated personal user and reject manual operations
  that do not present the configured API key.
- **FR-002**: The user MUST be able to create, retrieve, update, and delete named provider filter
  configurations.
- **FR-003**: Persistent application storage MUST contain only provider filter configurations and the
  minimum identifiers required to manage them; it MUST NOT contain job content, job fingerprints,
  synchronization history, or execution state.
- **FR-004**: The user MUST be able to submit filters for a transient search without persisting them.
- **FR-005**: Each search MUST identify exactly one provider and MAY contain normalized common filters
  plus filters specific to that provider.
- **FR-006**: The system MUST expose every search filter supported and documented by the active
  provider version and MUST reject undocumented provider options.
- **FR-007**: Multi-value filter groups for titles, keywords, seniorities, technologies, employment
  types, countries, work models, languages, and other applicable fields MUST accept explicit `ALL` or
  `ANY` matching semantics where both meanings are valid.
- **FR-008**: Excluded keywords and excluded companies MUST reject a job when any configured excluded
  value matches.
- **FR-009**: The system MUST support filtering by every available provider dimension relevant to the
  job model, including publication time, title, keywords, seniority, experience, technologies,
  employment type, geography, work arrangement, Brazil remote eligibility, international
  eligibility, visa sponsorship, relocation, compensation, currency, language, and company.
- **FR-010**: A filter unsupported by the provider MUST be evaluated after retrieval when the returned
  data is sufficient; otherwise the request MUST be rejected before presenting results as filtered.
- **FR-011**: An unknown or missing value MUST NOT satisfy a filter that requires positive
  confirmation, including eligibility, sponsorship, relocation, compensation, or language criteria.
- **FR-012**: The system MUST discover jobs published within an exact rolling 24-hour interval using a
  consistent time reference and MUST identify any provider limitation that prevents exact evaluation.
- **FR-013**: The system MUST preserve the distinction between provider-supplied information, explicit
  unknown values, and derived classifications.
- **FR-014**: The user MUST be able to preview normalized matching results without writing to Notion.
- **FR-015**: The user MUST be able to explicitly synchronize either transient search criteria or a
  saved filter configuration.
- **FR-016**: HiringCafe MUST be the only automatically queried provider in the MVP.
- **FR-017**: The common job model MUST recognize LinkedIn as a future source, but the MVP MUST NOT
  search, scrape, authenticate with, or manually import LinkedIn jobs.
- **FR-018**: For every result, the system MUST retrieve and retain all available provider information,
  including the original job URL and original application URL when supplied.
- **FR-019**: Each synchronized job MUST have normalized attributes for position, company, source,
  provider identity, seniority, work model, country, location, technology stack, compensation,
  experience, employment type, languages, sponsorship, publication time, discovery time, last
  synchronization time, URLs, availability, and application status when those values are known.
- **FR-020**: Each synchronized job page MUST preserve the full available original description and
  distinguish available overview, responsibilities, required qualifications, preferred
  qualifications, technologies, compensation and benefits, location and work arrangement, language
  requirements, application instructions, and original links.
- **FR-021**: The system MUST use one shared Notion job database for all current and future providers.
- **FR-022**: The user MUST be able to configure creation of a new `Job Opportunities` database or use
  an existing database, and the required schema MUST be validated before synchronization.
- **FR-023**: The application MUST control source-derived job properties while `Status` and
  `User Notes` remain exclusively user-controlled and MUST never be overwritten by synchronization.
- **FR-024**: The desired logical views MUST include All Opportunities, New Jobs - Last 24 Hours,
  HiringCafe Jobs, LinkedIn Jobs, Junior Opportunities, Mid-Level Opportunities, Senior
  Opportunities, Remote Opportunities, International Opportunities, and Application Tracker.
- **FR-025**: When a logical view cannot be provisioned through supported destination capabilities,
  the system documentation MUST provide accurate manual setup instructions.
- **FR-026**: Provider-level identity MUST prefer the pair of source and external job identifier, with
  canonical source URL as a fallback when no external identifier exists.
- **FR-027**: A job lacking both an external provider identifier and a usable canonical source URL
  MUST NOT be synchronized automatically and MUST be reported.
- **FR-028**: Repeated and overlapping runs MUST update an existing provider job rather than create a
  duplicate.
- **FR-029**: Possible matches across different providers MUST only be flagged and MUST NOT be merged
  automatically.
- **FR-030**: A synchronized job MUST be marked unavailable only when the provider supplies explicit,
  reliable evidence; absence from a recent-results query alone MUST NOT change availability.
- **FR-031**: Jobs that become unavailable MUST remain in Notion with user-maintained tracking data
  preserved.
- **FR-032**: Scheduled discovery MUST process every saved filter configuration at one globally
  configured interval.
- **FR-033**: The same saved filter configuration MUST NOT have overlapping synchronization runs.
- **FR-034**: A failure in one filter configuration or job MUST NOT roll back successfully completed,
  independent job synchronizations.
- **FR-035**: Every manual and scheduled operation MUST produce a structured summary containing the
  configuration or transient request reference, timing, retrieved and matched counts, created,
  updated, unchanged, skipped, unavailable, and failed counts, retry outcomes, and completeness
  limitations.
- **FR-036**: Execution summaries MUST be returned or logged for operational use but MUST NOT be
  persisted in the filter configuration store.
- **FR-037**: Retries MUST be bounded, limited to safe or explicitly retryable operations, and respect
  provider or destination throttling instructions.
- **FR-038**: Logs and user-visible errors MUST identify actionable failures without exposing API
  keys, access tokens, full private notes, or unnecessary job-description content.
- **FR-039**: Configuration required for providers, Notion, authentication, scheduling, and time
  evaluation MUST be validated before dependent operations are accepted.
- **FR-040**: Provider result limits, pagination behavior, and other known completeness constraints
  MUST be included in user documentation and relevant execution summaries.

### Key Entities

- **Provider Filter Configuration**: A named, reusable set of common and provider-specific filter
  values for exactly one provider. It contains only the identity needed to manage the configuration
  and the filter values themselves.
- **Transient Search**: One provider and one unsaved set of filter values used for preview or explicit
  synchronization.
- **Filter Group**: A collection of requested values and an `ALL` or `ANY` match mode; exclusion
  groups reject when any value matches.
- **Job Opportunity**: The normalized representation of a provider job, including identity, source
  facts, explicit unknowns, derived classifications, availability, content, and original links.
- **Notion Job Record**: The persistent representation of a job opportunity. Source-derived fields
  are automation-owned; `Status` and `User Notes` are user-owned.
- **Synchronization Summary**: A non-persistent outcome for one operation, including counts,
  failures, retry results, and completeness warnings.
- **Provider**: A documented job source with its own filter vocabulary and retrieval limitations.
  HiringCafe is active in the MVP; LinkedIn is reserved for a future version.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The user can save a provider filter configuration and complete a no-write preview in
  fewer than two minutes, excluding external provider response time.
- **SC-002**: In controlled acceptance tests, 100% of jobs that satisfy all requested filters are
  included and 100% of jobs that demonstrably violate a requested filter are excluded.
- **SC-003**: In controlled boundary tests, jobs at, before, and after the rolling 24-hour cutoff are
  classified consistently with the documented boundary rule.
- **SC-004**: Repeating the same synchronization three times produces zero additional Notion records
  for jobs with a stable provider identity.
- **SC-005**: Across all update and retry acceptance tests, 100% of existing `Status` and `User Notes`
  values remain unchanged.
- **SC-006**: Every synchronization returns or emits a summary whose item counts reconcile with all
  retrieved items and whose completeness warnings identify every simulated provider limit.
- **SC-007**: A partial failure affecting one job or filter configuration leaves 100% of previously
  successful independent job writes available and safe to encounter again.
- **SC-008**: Scheduled runs begin within five minutes of the configured interval under normal
  operating conditions, and acceptance tests produce zero overlapping runs for the same saved
  configuration.
- **SC-009**: All manual operations reject missing or invalid credentials, with zero provider,
  configuration, or Notion changes.
- **SC-010**: For every provider field returned in contract tests, the normalized result either
  preserves the source value, records an explicit unknown, or labels a derived value; no value is
  silently invented.

## Assumptions

- The MVP serves one user and operates with one active scheduler.
- The user supplies authorized HiringCafe and Notion access where the verified provider contracts
  require it and grants the minimum permissions needed for the selected Notion parent and database.
- The provider may cap or rank results; the product reports such limitations and does not guarantee
  discovery completeness beyond the provider's documented capabilities.
- Exact status options, select values, and visual layout within Notion may be adapted to supported
  destination capabilities as long as required information and ownership rules remain intact.
- Saved filter configurations remain until the user deletes them. Synchronized job records are never
  automatically deleted by this feature.
- The globally configured schedule applies to all saved filter configurations; per-profile schedules
  are outside the MVP.
- Execution summaries and active-run coordination are operational and ephemeral; they are not a
  durable audit history.
- Cross-provider similarity signaling has no observable matches in the MVP because HiringCafe is the
  sole active provider; the non-merging rule governs future providers.
