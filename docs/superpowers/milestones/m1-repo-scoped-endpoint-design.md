# M1 - Repo-Scoped Endpoint Design

## Purpose

Define how one IntelliJ workspace can expose multiple repo-specific MCP identities without breaking existing endpoint behavior.

## GitHub Milestone

`M1 - Repo-Scoped Endpoint Design`

## Related Specs

- [2026-06-04 Repo-Scoped Endpoints Spec](../specs/2026-06-04-repo-scoped-endpoints.md)

## Related Plans

- [2026-06-04 Repo-Scoped Endpoints First Slice Plan](../plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md)

## Related Issues

- None yet.

## Current Status

- Implemented locally on `codex/repo-scoped-endpoints-prep` in commit `b638cdb`.
- No GitHub issue mirrors or upstream PR exist yet.

## Success Criteria

- Transport, handler, and Git-root ownership are mapped to exact upstream files.
- Repo-scoped endpoint paths are specified clearly enough to implement.
- The repo boundary is defined as a Git root, not only an IntelliJ content root.
