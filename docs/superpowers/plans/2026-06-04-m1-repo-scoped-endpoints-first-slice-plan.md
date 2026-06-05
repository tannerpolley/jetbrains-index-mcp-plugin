# Repo-Scoped Endpoints First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Execution Status:** Completed locally on `codex/repo-scoped-endpoints-prep` in consolidated commit `b638cdb` (`feat: add repo-scoped mcp boundaries`). The implementation went slightly beyond the original first-slice boundary by also landing repo-root-aware `ide_find_file` and `ide_search_text` scoping plus their tests.

**Goal:** Add the first upstream Kotlin slice for repo-scoped MCP endpoint identities by introducing repo-scoped transport routes and handler-level pinned-root behavior without breaking existing endpoints.

**Architecture:** Keep existing broad `/index-mcp` behavior unchanged and add repo-scoped endpoint variants that resolve a repo ID to a pinned Git root. Thread a `RepoScopeContext` from `KtorMcpServer` into `JsonRpcHandler`, inject the pinned root when `project_path` is omitted, reject conflicting `project_path` values, and enforce repo-root-aware search scopes in `ide_find_file` and `ide_search_text`.

**Tech Stack:** Kotlin IntelliJ plugin code, Ktor routing, JSON-RPC handler logic, IntelliJ VCS root APIs, JUnit unit tests, IntelliJ platform tests.

---

## Source And Decisions

- Source design note: `REPO_SCOPED_ENDPOINTS_SPIKE.md`
- Source canonical spec: `docs/superpowers/specs/2026-06-04-repo-scoped-endpoints.md`
- Source project context: `docs/superpowers/PROJECT_CONTEXT.md`
- Milestone: `M1 - Repo-Scoped Endpoint Design`
- User decision: first slice should be transport plus handler.
- User decision: include the eventual platform search-scope tests in the plan, even if the first code edits stop short of search-tool implementation.
- User decision: prepare this plan for direct local implementation on the current branch.
- Execution result: the branch landed transport, handler, registry, repo-root scope helpers, and the first search-tool enforcement pass in one local commit.

## File Map

- Create: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeContext.kt`
- Create: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeRegistry.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServer.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandler.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ErrorMessages.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServerUnitTest.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandlerUnitTest.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeRegistryUnitTest.kt`
- Later test target: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/FindFileToolScopeTest.kt`
- Later test target: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/SearchTextToolScopeTest.kt`

## Acceptance Criteria

- Additive repo-scoped Streamable HTTP and SSE routes exist under `/index-mcp/repos/<repo-id>/...`.
- Repo IDs resolve to pinned Git roots through a registry based on IntelliJ VCS roots.
- Calls through repo-scoped routes use the pinned Git root when `project_path` is absent.
- Calls through repo-scoped routes fail loudly when `project_path` conflicts with the pinned root.
- Existing broad endpoints remain unchanged.
- Unit tests cover repo route resolution and handler conflict behavior.
- `ide_find_file` and `ide_search_text` enforce repo-root-aware scope boundaries and carry focused scope tests.

## Non-Goals

- Do not change existing broad endpoint paths or default MCP behavior.
- Do not create a local proxy.
- Do not publish an upstream PR from this slice.
- Do not broaden repo scoping across every tool family before the publish strategy is chosen.

## Proof Oracle

Run these commands from the upstream repo root:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServerUnitTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandlerUnitTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistryUnitTest"
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileToolScopeTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SearchTextToolScopeTest"
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\hooks\codex-cleanup.ps1" -RepoRoot .
```

Expected: the targeted repo-scope test set passes and cleanup reports no matching leftover Codex processes.

## Risks And Dependencies

- IntelliJ VCS root discovery may not be available in pure unit tests without a seam. The registry should be written with an injectable provider so path normalization and ID logic can be unit tested without a full IDE environment.
- SSE route changes must stay additive to avoid breaking current clients.
- Adjacent tools beyond `ide_find_file` and `ide_search_text` still need review before calling repo scoping complete across the plugin.

## Task 1: Add Repo Scope Types And Registry

**Files:**
- Create: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeContext.kt`
- Create: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeRegistry.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/RepoScopeRegistryUnitTest.kt`

- [x] **Step 1: Create the scope model**

Add `RepoScopeContext.kt` with a minimal data class:

```kotlin
package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

data class RepoScopeContext(
    val repoId: String,
    val gitRootPath: String
)
```

- [x] **Step 2: Create the registry seam**

Add `RepoScopeRegistry.kt` with:

- a normalized `RepoScopeEntry(repoId, gitRootPath, projectBasePath)` model
- a discovery seam that can later use IntelliJ VCS roots
- pure functions for path normalization, repo ID derivation, and collision suffix handling
- a lookup method `findByRepoId(repoId: String): RepoScopeContext?`

- [x] **Step 3: Write unit tests**

Add `RepoScopeRegistryUnitTest.kt` covering:

- Windows and Unix-style path normalization
- repo ID derivation from directory names
- collision suffix behavior when two roots have the same leaf name
- null lookup for unknown repo ID

- [x] **Step 4: Run the targeted registry tests**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistryUnitTest"
```

Expected: the registry unit tests pass.

- [x] **Step 5: Record the implementation commit**

```powershell
git show --stat --oneline b638cdb
```

Result: registry work landed inside the consolidated commit `feat: add repo-scoped mcp boundaries`.

## Task 2: Add Repo-Scoped Transport Routes

**Files:**
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServer.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServerUnitTest.kt`

- [x] **Step 1: Inject the registry**

Extend `KtorMcpServer` to accept a repo-scope lookup dependency, defaulting to a registry-backed resolver.

- [x] **Step 2: Add additive routes**

Add Ktor routes for:

```text
/index-mcp/repos/{repoId}/streamable-http
/index-mcp/repos/{repoId}/sse
/index-mcp/repos/{repoId}
```

The legacy and current broad routes must remain unchanged.

- [x] **Step 3: Forward repo scope**

For repo-scoped routes, resolve `{repoId}` to `RepoScopeContext` before calling the existing request handling path. Unknown repo IDs should return a structured JSON-RPC-style error or a clear HTTP error response.

- [x] **Step 4: Add transport tests**

Extend `KtorMcpServerUnitTest.kt` with:

- initialize against repo-scoped streamable HTTP returns `200`
- unknown repo ID returns a non-success status or structured error
- repo-scoped SSE advertises the repo-scoped POST endpoint path

- [x] **Step 5: Run targeted transport tests**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServerUnitTest"
```

Expected: the transport unit tests pass.

- [x] **Step 6: Record the implementation commit**

```powershell
git show --stat --oneline b638cdb
```

Result: transport work landed inside the consolidated commit `feat: add repo-scoped mcp boundaries`.

## Task 3: Add Handler-Level Pinned Root Behavior

**Files:**
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandler.kt`
- Modify: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ErrorMessages.kt`
- Test: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandlerUnitTest.kt`

- [x] **Step 1: Thread repo scope through request handling**

Add a `repoScope: RepoScopeContext? = null` parameter to the handler entry points so route-specific scope can reach `processToolCall`.

- [x] **Step 2: Inject or validate `project_path`**

Inside `processToolCall`:

- if `repoScope` is null, keep current behavior
- if `repoScope` is present and `project_path` is missing, inject the pinned Git root
- if `repoScope` is present and `project_path` matches the pinned root after normalization, proceed
- if `repoScope` is present and `project_path` differs, return a structured tool error naming both values

- [x] **Step 3: Add handler unit tests**

Extend `JsonRpcHandlerUnitTest.kt` with tests for:

- missing `project_path` under repo scope uses the pinned root
- matching `project_path` under repo scope succeeds
- conflicting `project_path` under repo scope returns an error

- [x] **Step 4: Run targeted handler tests**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandlerUnitTest"
```

Expected: the handler unit tests pass.

- [x] **Step 5: Record the implementation commit**

```powershell
git show --stat --oneline b638cdb
```

Result: handler work landed inside the consolidated commit `feat: add repo-scoped mcp boundaries`.

## Task 4: Record The Follow-On Search Isolation Work

**Files:**
- Create: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/FindFileToolScopeTest.kt`
- Create: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/SearchTextToolScopeTest.kt`
- Modify: `REPO_SCOPED_ENDPOINTS_SPIKE.md`

- [x] **Step 1: Add scope tests for the first enforced tools**

Create or update tests with real fixture coverage for:

- sibling repo file exclusion in `ide_find_file`
- sibling repo text match exclusion in `ide_search_text`

The branch landed working scope tests rather than disabled placeholders.

- [x] **Step 2: Update the spike note**

Mark the first slice as implemented for transport and handler work, and explicitly note that search-tool scoping is the next slice.

- [x] **Step 3: Run the targeted first-slice test set**

Run:

```powershell
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServerUnitTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandlerUnitTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistryUnitTest"
.\gradlew.bat test --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileToolScopeTest" --tests "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SearchTextToolScopeTest"
```

Expected: the targeted repo-scope tests pass.

- [x] **Step 4: Record the implementation commit**

```powershell
git show --stat --oneline b638cdb
```

Result: the spike note, scope tests, and implementation all landed inside the consolidated commit `feat: add repo-scoped mcp boundaries`.

## Implementation Route

Use `$project:implement-plan` on the current branch. Do not push. Current result remains local until the publication route is chosen.
