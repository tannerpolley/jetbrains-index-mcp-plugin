# Repo-Scoped MCP Endpoints And Search Boundaries

## Summary

One IntelliJ master workspace should be able to expose one additive MCP identity per Git repo so agents can target a single repo without opening multiple IDE windows or leaking search results across sibling repos.

## Problem

The existing broad `/index-mcp` endpoints can resolve a containing IntelliJ project, but they do not enforce a Git-root boundary. In a multi-repo workspace, `ide_find_file` and `ide_search_text` can return sibling-repo results even when the caller intends to work inside one repo.

## Decision

- Add repo-scoped MCP routes under `/index-mcp/repos/<repo-id>/...` while preserving the legacy broad endpoints.
- Resolve `<repo-id>` from discovered Git roots and pin each route to one repo root.
- Inject the pinned root when `project_path` is omitted and reject conflicting `project_path` values.
- Enforce repo-root-aware search scope boundaries in `ide_find_file` and `ide_search_text`.
- Keep client-facing multi-repo registration examples and publication strategy as follow-up work.

## Current Implementation Status

- Implemented locally on `codex/repo-scoped-endpoints-prep` in commit `b638cdb`.
- Added `RepoScopeContext`, `RepoScopeRegistry`, additive transport routes, handler-level pinned-root validation, and repo-root-aware search scopes for `ide_find_file` and `ide_search_text`.
- Added focused transport, handler, registry, and search-scope tests.

## Remaining Open Questions

- Should the feature be proposed upstream first or maintained as a fork-first enhancement?
- Which additional tool families need repo-scope enforcement before publication?
- What client config examples should ship first for one-master-window workflows?
- How stable should repo IDs remain when Git roots collide, move, or are renamed?

## Related Artifacts

- Design note: [REPO_SCOPED_ENDPOINTS_SPIKE.md](../../../REPO_SCOPED_ENDPOINTS_SPIKE.md)
- Plan: [2026-06-04 Repo-Scoped Endpoints First Slice Plan](../plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md)
- Milestones:
  - [M1 - Repo-Scoped Endpoint Design](../milestones/m1-repo-scoped-endpoint-design.md)
  - [M2 - Repo-Scoped Search Enforcement](../milestones/m2-repo-scoped-search-enforcement.md)
  - [M3 - Transport And Config Integration](../milestones/m3-transport-and-config-integration.md)
  - [M4 - Verification And Release Readiness](../milestones/m4-verification-and-release-readiness.md)
