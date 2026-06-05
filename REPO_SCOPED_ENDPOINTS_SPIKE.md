# Repo-Scoped Endpoints Spike

## Status

Current local implementation status:

- Implemented on `codex/repo-scoped-endpoints-prep` in commit `b638cdb`.
- Repo-scoped transport routes, pinned-root handler behavior, repo ID lookup, and search scoping for `ide_find_file` and `ide_search_text` are in place.
- Client config examples and the upstream-vs-fork publication decision are still open.

## Goal

Expose separate MCP server identities for individual Git repos inside one IntelliJ workspace, while preventing search and navigation tools from leaking sibling-repo results.

## Why This Is Needed

Current behavior already resolves module or content-root paths for `project_path`, but that only picks the containing IntelliJ `Project`. It does not narrow the search scope to one repo root. In local verification against an IntelliJ workspace containing multiple ePC-SAFT package roots:

- `ide_index_status` resolved the requested package roots correctly.
- `ide_find_file` returned sibling package results.
- `ide_search_text` returned sibling package results.

That means root resolution works, but repo isolation does not.

## Implemented Slice

Implement additive repo-scoped Streamable HTTP and SSE endpoints without changing existing endpoint behavior:

```text
/index-mcp/repos/<repo-id>/streamable-http
/index-mcp/repos/<repo-id>/sse
/index-mcp/repos/<repo-id>
```

Each endpoint should bind one Git root before JSON-RPC tool execution. Calls through that endpoint should behave as if:

- `project_path` defaults to the pinned Git root
- conflicting `project_path` values fail loudly
- search results outside the pinned Git root are blocked

That slice is now implemented locally for the current branch.

## Exact Hook Points

### Transport

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServer.kt`

This file already owns:

- `/index-mcp/streamable-http`
- `/index-mcp/sse`
- `/index-mcp`

Additive work belongs here:

- route parsing for `/index-mcp/repos/<repo-id>/...`
- endpoint-specific lookup of repo scope metadata
- forwarding repo scope into the request handler

### Request Context

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandler.kt`

This file currently:

- extracts `project_path` from tool arguments
- resolves an IntelliJ `Project`
- executes the selected tool

First implementation change should be a small request-scope object, for example:

```kotlin
data class RepoScopeContext(
    val repoId: String,
    val gitRootPath: String
)
```

Then add a handler entry path that accepts optional repo scope:

```kotlin
suspend fun handleRequest(
    jsonString: String,
    protocolVersion: String,
    repoScope: RepoScopeContext? = null
): String?
```

Tool dispatch should then:

- inject the pinned root when `project_path` is absent
- reject mismatched `project_path`
- pass the selected scope downstream for tool-level filtering

### Project Resolution

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/ProjectResolver.kt`

Keep existing project resolution. The repo-scoped endpoint still needs the containing IntelliJ `Project`. Do not try to turn `ProjectResolver` into a repo boundary object. Its job should remain:

- map Git root path to containing IntelliJ `Project`
- continue exposing `available_projects` for the broad endpoint

### Git Root Discovery

New file or service, likely under `server` or `util`:

- `RepoScopeRegistry.kt`
- or `GitRootRegistryService.kt`

Preferred source of truth:

- IntelliJ VCS roots via `ProjectLevelVcsManager`

Registry responsibilities:

- collect Git roots from open projects
- normalize paths
- derive stable repo IDs from directory names with collision suffixes
- return lookup entries for transport routing

### Search Scoping

Existing hook:

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/handlers/ExcludedPathScope.kt`

This already wraps a `GlobalSearchScope` and excludes path patterns. Extend this idea rather than inventing a separate filter path.

Add a repo-root-aware wrapper, for example:

```kotlin
class RepoRootScope(
    baseScope: GlobalSearchScope,
    private val repoRootPath: String
) : DelegatingGlobalSearchScope(baseScope)
```

Behavior:

- `contains(file)` returns true only when `file.path` is under `repoRootPath`

Then introduce helpers such as:

```kotlin
fun createRepoScopedProjectScope(project: Project, repoRootPath: String): GlobalSearchScope
fun createRepoScopedAllScope(project: Project, repoRootPath: String): GlobalSearchScope
```

### File Search

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/FindFileTool.kt`

Required changes:

- use repo-scoped `GlobalSearchScope` when repo scope is present
- keep output filtering as a safety check before serializing `FileMatch`

The likely minimum change is to centralize scope creation behind one helper that accepts optional repo scope.

### Text Search

File: `src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/SearchTextTool.kt`

Required changes:

- replace current project-wide `createFilteredScope(project)` usage with repo-scoped scope creation when repo scope is present
- apply the same scope for exact search and regex `FindModel.customScope`
- keep output filtering as a safety check

## Remaining Work

1. Add client config examples for multiple repo-specific MCP server entries.
2. Decide whether the work should be prepared as an upstream PR or maintained as a fork-first feature.
3. Review adjacent tools for repo-scope requirements beyond `ide_find_file` and `ide_search_text`.
4. Decide how stable repo IDs should remain when Git roots are renamed or relocated.

## Local Verification Coverage

### Transport

File target: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/transport/KtorMcpServerUnitTest.kt`

Add tests for:

- repo-scoped streamable HTTP route returns 200 for `initialize`
- unknown repo ID returns structured error or 404
- repo-scoped SSE route advertises the repo-scoped POST endpoint

### Handler

File target: `src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/server/JsonRpcHandlerUnitTest.kt`

Add tests for:

- missing `project_path` uses pinned repo root
- matching `project_path` succeeds
- conflicting `project_path` returns a structured error naming both roots

### Resolver / Registry

New test target likely needed:

- `RepoScopeRegistryUnitTest.kt`

Add tests for:

- Git-root discovery from VCS roots
- repo ID collision suffix behavior
- path normalization

### Search Tool Scoping

Implemented test targets:

- `FindFileToolScopeTest.kt`
- `SearchTextToolScopeTest.kt`

Need fixture layout with sibling repo roots under one workspace project and assertions that:

- repo-scoped `ide_find_file` does not return sibling repo files
- repo-scoped `ide_search_text` does not return sibling repo files

## Decision Point

If the additive endpoint path plus repo-root scope wrappers stay local to transport, handler, and the two search tools, this is a good upstream PR candidate.

If repo scope starts forcing broad changes across many tools or maintainers reject separate endpoint identities, keep this branch as fork prep and move the implementation into a maintained fork.
