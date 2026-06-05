# Analytics Repo Agent Brief

## Target

- Repo: `analytics-repo`
- Expected repo ID: `analytics-repo`
- Expected Codex server name: `intellij-index-analytics-repo`
- Expected endpoint: `/index-mcp/repos/analytics-repo/streamable-http`

## Required Checks

1. Run `ide_find_file` with query `SharedScopeProbe`.
2. Run `ide_search_text` with query `workspacescopetoken`.
3. Run `ide_search_text` with query `analyticsrepouniquetoken`.

## Expected Keep Results

Treat all returned paths as repo-root-relative.

- `ide_find_file` with `SharedScopeProbe`:
  - `src/analytics/SharedScopeProbe.ts`
- `ide_search_text` with `workspacescopetoken`:
  - `README.md`
  - `src/analytics/SharedScopeProbe.ts`
- `ide_search_text` with `analyticsrepouniquetoken`:
  - `README.md`
  - `src/analytics/SharedScopeProbe.ts`
  - `src/analytics/analyticsLedger.ts`

## Expected Exclusions

No results from:

- `inventory-repo`
- `billing-repo`

If either sibling repo appears in any repo-scoped result, the scoped MCP server failed.
