# Billing Repo Agent Brief

## Target

- Repo: `billing-repo`
- Expected repo ID: `billing-repo`
- Expected Codex server name: `intellij-index-billing-repo`
- Expected endpoint: `/index-mcp/repos/billing-repo/streamable-http`

## Required Checks

1. Run `ide_find_file` with query `SharedScopeProbe`.
2. Run `ide_search_text` with query `workspacescopetoken`.
3. Run `ide_search_text` with query `billingrepouniquetoken`.

## Expected Keep Results

Treat all returned paths as repo-root-relative.

- `ide_find_file` with `SharedScopeProbe`:
  - `src/billing/SharedScopeProbe.py`
- `ide_search_text` with `workspacescopetoken`:
  - `README.md`
  - `src/billing/SharedScopeProbe.py`
- `ide_search_text` with `billingrepouniquetoken`:
  - `README.md`
  - `src/billing/SharedScopeProbe.py`
  - `src/billing/billing_ledger.py`

## Expected Exclusions

No results from:

- `inventory-repo`
- `analytics-repo`

If either sibling repo appears in any repo-scoped result, the scoped MCP server failed.
