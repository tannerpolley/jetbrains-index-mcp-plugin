# Repo-Scoped Agent Workspace

This fixture gives the plugin a single master project with three nested repos so repo-scoped MCP endpoints can be tested without opening multiple IDE windows.

## Layout

- `master-project/inventory-repo`
- `master-project/billing-repo`
- `master-project/analytics-repo`
- `agent-briefs/`

Each repo contains:

- one shared file query target: `SharedScopeProbe`
- one shared text query target: `workspacescopetoken`
- one repo-unique token

That combination makes scope leaks obvious:

- `ide_find_file` with `SharedScopeProbe` should only return the file in the targeted repo
- `ide_search_text` with `workspacescopetoken` should only return matches from the targeted repo
- each repo's unique token should only return matches from its own repo
- the repo `README.md` files intentionally contain the shared and unique text tokens, so text-search checks should expect `README.md` in addition to source-file matches

Repo-scoped tool results are expected to be relative to the targeted repo root, such as `src/inventory/SharedScopeProbe.kt`, not prefixed with the repo folder name.

## Setup

Run:

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-repo-scoped-agent-workspace.ps1
```

That script:

- verifies the fixture files exist
- initializes each subfolder as a Git repo if needed
- creates an initial commit in each repo if none exists yet
- prints the repo IDs, Codex server names, and repo-scoped endpoint URLs

## IDE Workflow

1. Open `testing/repo-scoped-agent-workspace/master-project` in one IntelliJ window.
2. Start the MCP plugin server in that IDE window.
3. Use **Install on Coding Agents** -> **Codex CLI**.
4. Use the setup script JSON output to confirm the expected repo IDs, server names, and endpoint URLs before testing.
5. Assign one agent brief per repo from `agent-briefs/`.

## Expected Server Names

If the IDE server name is `intellij-index`, the repo-scoped Codex entries should be:

- `intellij-index-inventory-repo`
- `intellij-index-billing-repo`
- `intellij-index-analytics-repo`
