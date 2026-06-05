# M3 - Transport And Config Integration

## Purpose

Expose additive repo-scoped MCP endpoint identities and make them practical for clients to register and use.

## GitHub Milestone

`M3 - Transport And Config Integration`

## Related Specs

- [2026-06-04 Repo-Scoped Endpoints Spec](../specs/2026-06-04-repo-scoped-endpoints.md)

## Related Plans

- [2026-06-04 Repo-Scoped Endpoints First Slice Plan](../plans/2026-06-04-m1-repo-scoped-endpoints-first-slice-plan.md)
- [2026-06-04 Codex Repo-Scoped Multi-Repo Config Plan](../plans/2026-06-04-m3-codex-repo-scoped-config-plan.md)

## Related Issues

- None yet.

## Current Status

- Repo-scoped transport endpoints are implemented locally in commit `b638cdb`.
- Client config examples and install guidance are now tracked by the Codex multi-repo config plan.

## Success Criteria

- Repo-scoped Streamable HTTP and SSE endpoint shapes are implemented or ready for implementation.
- Repo IDs are stable enough for MCP server naming.
- Client config examples exist for multiple repo-specific server entries.
