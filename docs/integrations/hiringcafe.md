# HiringCafe contract verification

Verified on 2026-10-11 against HiringCafe's official documentation and live `tools/list` response.

## Supported contract

- Endpoint: `https://hiringcafe.com/api/chatgpt/mcp`
- Protocol: Model Context Protocol over Streamable HTTP using JSON-RPC 2.0 POST requests.
- Authentication: none.
- Tool used: `search_hiringcafe_jobs` only. It is declared read-only and idempotent by the provider.
- Structured response: job ID, title, company, location, workplace/employment types, compensation, publication time, requirement summary, seniority, minimum experience, responsibilities, technologies, HiringCafe URL, count and total matches.
- Search limit: 1–25, default 10. This service requests the configurable maximum of 25.

Primary sources:

- [HiringCafe Job Search for ChatGPT documentation](https://hiringcafe.com/documentation/hiringcafe-chatgpt-app)
- Live MCP `tools/list` at the endpoint above, queried with the documented JSON-RPC request.

## Deliberate limitations

The search input schema contains no cursor, offset or page parameter, and the provider states that search returns one job per company. The adapter does not invent pagination or call undocumented web endpoints. Every preview is therefore marked incomplete and includes actionable warnings. The fixed provider hint `posted_within_days=1` is followed by a local inclusive `now - 24h` cutoff; missing or malformed timestamps fail closed and are excluded.

The vendor documentation says heavy automated use is not supported and describes the integration as normal conversational search. Phase 2 permits one bounded call per manual preview only. Scheduled production discovery remains blocked until HiringCafe confirms an automation allowance or documents a suitable feed/API.

Retries are limited to three attempts and only cover I/O failures plus HTTP 429/502/503/504. This is safe for this POST because the provider explicitly marks the search tool read-only and idempotent. `Retry-After` seconds are honored up to a configured cap. JSON-RPC errors, malformed responses and other 4xx responses are not retried.

## Configuration

`HIRINGCAFE_MCP_URL`, connect/request timeouts, retry backoff/cap, maximum attempts and result limit are environment-configurable. The endpoint has no credential. Override the URL only for a verified replacement or a local test double.
