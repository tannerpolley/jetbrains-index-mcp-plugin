# Repo-Scoped Agent Workspace

This workspace exists to validate repo-scoped MCP identities from one IntelliJ master window.

Rules for any agent working under this tree:

- Treat `master-project/` as one IDE project window containing three separate Git repos.
- Do not test the broad `/index-mcp/streamable-http` server when the task is repo-scoped validation.
- Always use the matching brief under `agent-briefs/` before running checks.
- A repo-scoped test fails if results from the other two repos appear.
- Prefer `ide_find_file` with `SharedScopeProbe` and `ide_search_text` with `workspacescopetoken` first, because those queries should leak immediately if scoping is broken.
