# Inventory Repo Agent Brief

## Target

- Repo: `inventory-repo`
- Expected repo ID: `inventory-repo`
- Expected Codex server name: `intellij-index-inventory-repo`
- Expected endpoint: `/index-mcp/repos/inventory-repo/streamable-http`

## Required Checks

1. Run `ide_find_file` with query `SharedScopeProbe`.
2. Run `ide_search_text` with query `workspacescopetoken`.
3. Run `ide_search_text` with query `inventoryrepouniquetoken`.

## Expected Keep Results

Treat all returned paths as repo-root-relative.

- `ide_find_file` with `SharedScopeProbe`:
  - `src/inventory/SharedScopeProbe.kt`
- `ide_search_text` with `workspacescopetoken`:
  - `README.md`
  - `src/inventory/SharedScopeProbe.kt`
- `ide_search_text` with `inventoryrepouniquetoken`:
  - `README.md`
  - `src/inventory/SharedScopeProbe.kt`
  - `src/inventory/InventoryLedger.kt`

## Expected Exclusions

No results from:

- `billing-repo`
- `analytics-repo`

If either sibling repo appears in any repo-scoped result, the scoped MCP server failed.
