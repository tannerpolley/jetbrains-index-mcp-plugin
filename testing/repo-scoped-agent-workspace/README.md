# Repo-Scoped Agent Workspace

This fixture gives the plugin a single master project with sibling repos and one nested submodule-style repo so repo-scoped MCP endpoints can be tested without opening multiple IDE windows.

The committed `master-project/.idea/` metadata is intentional. It makes IntelliJ treat the fixture root as a real project when opened from the command line instead of falling back to a plain text-editor open.

## Layout

- `master-project/inventory-repo`
- `master-project/billing-repo`
- `master-project/analytics-repo`
- `master-project/submodules/shipping-repo`
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

## Attach-After-Open Workflow

1. Open the master project and start the MCP plugin server.
2. Add or verify a sibling repo or nested repo such as `submodules/shipping-repo`.
3. Call `ide_attach_repo_to_workspace` with the repo path if the repo is not already attached.
4. Call `ide_get_repo_scoped_client_config`.
5. Apply the returned Codex command, then smoke the new repo-scoped endpoint.

## Expected Server Names

If the IDE server name is `intellij-index`, the repo-scoped Codex entries should be:

- `intellij-index-inventory-repo`
- `intellij-index-billing-repo`
- `intellij-index-analytics-repo`
- `intellij-index-shipping-repo`
