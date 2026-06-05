# M2 - Repo-Scoped Search Enforcement

## Purpose

Constrain search and navigation tools so repo-scoped MCP identities cannot leak sibling repo results.

## GitHub Milestone

`M2 - Repo-Scoped Search Enforcement`

## Related Specs

- [2026-06-04 Repo-Scoped Endpoints Spec](../specs/2026-06-04-repo-scoped-endpoints.md)

## Related Plans

- [2026-06-04 Repo-Scoped Endpoints First Slice Plan](../plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md)

## Related Issues

- None yet.

## Current Status

- Implemented locally for `ide_find_file` and `ide_search_text` in commit `b638cdb`.
- Repo-root-aware search scope wrappers are in place, but adjacent tool families still need follow-up review.

## Success Criteria

- `ide_find_file` and `ide_search_text` use repo-root-aware search scopes.
- Output filtering exists as a safety check for out-of-root paths.
- Conflicting `project_path` values fail loudly on repo-scoped endpoints.
