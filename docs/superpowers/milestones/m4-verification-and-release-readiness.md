# M4 - Verification And Release Readiness

## Purpose

Prove backward compatibility, add focused tests, and prepare either an upstream PR or a maintained fork path.

## GitHub Milestone

`M4 - Verification And Release Readiness`

## Related Specs

- [2026-06-04 Repo-Scoped Endpoints Spec](../specs/2026-06-04-repo-scoped-endpoints.md)

## Related Plans

- [2026-06-04 Repo-Scoped Endpoints First Slice Plan](../plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md)

## Related Issues

- None yet.

## Current Status

- Targeted unit, scope, and regression tests for the implemented repo-scoped work passed locally before the code commit.
- The remaining readiness work is publication strategy, any broader regression coverage, and upstream review.

## Success Criteria

- Transport, handler, and scope behavior are covered by unit or platform tests.
- Existing broad endpoints continue to work unchanged.
- The repo has a clear publish decision: upstream PR first or maintained fork.
