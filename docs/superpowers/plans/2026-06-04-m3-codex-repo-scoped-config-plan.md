# Codex Repo-Scoped Multi-Repo Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make one JetBrains master window able to generate a Codex install flow that registers one repo-scoped MCP entry per discovered Git repo instead of only the existing broad server entry.

**Architecture:** Keep repo discovery and endpoint ownership in the existing repo-scope server layer, then expose a pure Codex-oriented config generator path that derives `ide-name + repo-id` server names and `/index-mcp/repos/<repo-id>/streamable-http` URLs for every discovered repo. Keep UI work thin by letting `CopyClientConfigAction` call the tested generator output, and update README examples to match the generated command shape.

**Tech Stack:** Kotlin IntelliJ plugin code, existing Ktor repo-scoped endpoint contract, Swing action popup wiring, README documentation, JUnit unit tests.

---

## Source And Decisions

- Source spec: `docs/superpowers/specs/2026-06-04-repo-scoped-endpoints.md`
- Source completed plan: `docs/superpowers/plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md`
- Source design note: `REPO_SCOPED_ENDPOINTS_SPIKE.md`
- Milestone: `M3 - Transport And Config Integration`
- User decision: the next plan should target the multi-repo config flow, not broader tool-scope audit or publication strategy.
- User decision: the first client surface is Codex only.
- User decision: this slice should include plugin behavior plus docs.
- User decision: the generated Codex flow should install all discovered repos, not a subset picker or one repo at a time.
- User decision: the generated multi-repo command should install repo-scoped entries only and should not keep the broad Codex entry in the same command.
- User decision: generated server names should use `ide-name + repo-id`, for example `intellij-index-myrepo`.
- TDD policy: use `superpowers:test-driven-development` for generator and command-shape logic, and keep UI wiring thin enough that the tested generator path proves the behavior.
- Completion policy: use `superpowers:verification-before-completion` before claiming the slice is ready.

## Acceptance Criteria

- Codex install generation can produce one repo-scoped server entry per discovered Git repo in the current IDE window.
- Generated server names use the IDE default server name plus repo ID, for example `intellij-index-myrepo`.
- Generated URLs target `/index-mcp/repos/<repo-id>/streamable-http` and do not use the broad `/index-mcp/streamable-http` URL in the multi-repo Codex flow.
- The Codex multi-repo command removes and re-adds repo-scoped entries predictably for reinstall behavior.
- When no repo scopes are available, the plugin fails loudly with a clear user-facing message instead of silently falling back to the broad entry.
- The "Install on Coding Agents" flow exposes the new Codex behavior without requiring users to hand-edit commands.
- README Codex examples explain the repo-scoped multi-repo path from one IDE window and stay aligned with the generated command shape.

## Non-Goals

- Do not add repo-scoped multi-entry flows for Claude Code, Cursor, Gemini, or generic MCP clients in this slice.
- Do not add a repo picker or subset-selection UI.
- Do not change the broad MCP transport routes or remove the existing broad server behavior outside the new Codex path.
- Do not expand repo-scope enforcement across additional tool families in this slice.
- Do not choose the upstream PR versus maintained fork publication route here.

## File Map

- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/McpConstants.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/McpServerService.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGenerator.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/actions/CopyClientConfigAction.kt`
- Modify: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGeneratorUnitTest.kt`
- Modify: `README.md`
- Modify: `docs/superpowers/milestones/m3-transport-and-config-integration.md`

## Proof Oracle

Run these commands from the upstream repo root:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGeneratorUnitTest"
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\hooks\codex-cleanup.ps1" -RepoRoot .
```

Manual IDE proof before completion:

1. Run the plugin in a sandbox IDE with one master window that has multiple Git repos loaded.
2. Use **Install on Coding Agents** -> **Codex CLI**.
3. Verify the generated or executed command contains one `codex mcp add` per discovered repo with names like `intellij-index-myrepo`.
4. Verify each URL points at `/index-mcp/repos/<repo-id>/streamable-http`.
5. Verify the broad `/index-mcp/streamable-http` URL is not included in the multi-repo Codex flow.
6. Verify the no-repo case reports a clear failure message instead of silently installing the broad entry.

Expected: unit tests pass, cleanup passes, and the UI produces the repo-scoped Codex command shape described above.

## Risks And Dependencies

- `ClientConfigGenerator` currently pulls only one broad server URL from `McpServerService`; the plan should add a pure, testable repo-scoped representation instead of burying list logic in the UI.
- Repo ordering should be deterministic so copied commands and README examples stay stable across runs.
- Repo IDs already derive from directory names with collision suffixes; the config plan should reuse that contract instead of inventing a second naming scheme.
- UI tests do not currently exist for `CopyClientConfigAction`, so the plan should keep UI logic thin and prove behavior in generator tests plus one manual sandbox check.

## Task 1: Add Repo-Scoped Codex Config Generation

**Files:**
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/McpConstants.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/McpServerService.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGenerator.kt`
- Modify: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGeneratorUnitTest.kt`

- [ ] **Step 1: Write failing Codex multi-repo generator tests**

Add failing tests that pin:

- server names use `McpConstants.getServerName()` plus `repoId`
- generated URLs use `/index-mcp/repos/<repo-id>/streamable-http`
- generated Codex commands include one remove/add pair per repo-scoped server name
- the broad `/index-mcp/streamable-http` URL is absent from the multi-repo Codex path
- the zero-repo case fails loudly with a clear error path

- [ ] **Step 2: Run the generator tests and verify the expected failure**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGeneratorUnitTest"
```

Expected: the new multi-repo assertions fail before implementation.

- [ ] **Step 3: Implement the minimal repo-scoped generator path**

Add the smallest clean surface that:

- exposes discovered repo scopes from `McpServerService`
- centralizes repo-scoped endpoint path building in a reusable constant or helper
- generates deterministic Codex repo-scoped install commands and copyable command text
- keeps the broad single-server generator path intact for unchanged flows

- [ ] **Step 4: Run the tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGeneratorUnitTest"
```

Expected: the generator tests pass with the new repo-scoped Codex command behavior.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/McpConstants.kt src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/McpServerService.kt src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGenerator.kt src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGeneratorUnitTest.kt
git commit -m "feat: generate repo scoped codex config"
```

## Task 2: Wire the Codex Multi-Repo Flow Into the Plugin UI and Docs

**Files:**
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/actions/CopyClientConfigAction.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGenerator.kt`
- Modify: `README.md`
- Modify: `docs/superpowers/milestones/m3-transport-and-config-integration.md`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGeneratorUnitTest.kt`

- [ ] **Step 1: Add the failing regression assertions for the final Codex UX contract**

Extend the generator test file so it also locks:

- copied terminal text for Windows and POSIX with multiple repo-scoped Codex entries
- broad-entry exclusion in the Codex multi-repo flow
- README example command shape stays aligned with the generated command contract

- [ ] **Step 2: Run the tests and verify the expected failure**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGeneratorUnitTest"
```

Expected: the new UX-shape assertions fail before wiring and docs updates.

- [ ] **Step 3: Implement the thin UI wiring and documentation updates**

Update `CopyClientConfigAction.kt` so the Codex path uses the new repo-scoped generator output, surfaces a clear no-repo error, and keeps the UI behavior thin. Update `README.md` and the `M3` milestone page so the documented Codex examples and the project roadmap match the implemented multi-repo flow.

- [ ] **Step 4: Run verification and the manual IDE check**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGeneratorUnitTest"
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\hooks\codex-cleanup.ps1" -RepoRoot .
```

Then perform the manual IDE proof from the Proof Oracle section.

Expected: tests pass, cleanup passes, and the Codex install flow shows one repo-scoped entry per discovered repo with no broad entry in that generated command.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/actions/CopyClientConfigAction.kt src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/ClientConfigGenerator.kt README.md docs/superpowers/milestones/m3-transport-and-config-integration.md
git commit -m "feat: add codex repo scoped install flow"
```

## Implementation Route

Use `$project:implement-plan` on the current branch after this plan is reviewed. Keep the result local until the publication route is chosen.
