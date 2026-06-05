# JetBrains Index MCP Plugin Project Context

## Durable Intent

This repository is the upstream source for the JetBrains Index MCP plugin. Superpowers Project adds a durable planning and execution layer on top of the plugin's existing code, tests, and release flow so repo-backed design work, implementation plans, and issue decomposition stay coherent over time.

For the current extension work, the immediate purpose is narrower than the full plugin roadmap: enable repo-scoped MCP identities inside one IntelliJ master workspace so agents can target one repo without cross-repo search leakage.

## Artifact Model

Canonical Superpowers Project artifacts live under:

- `docs/superpowers/PROJECT_CONTEXT.md`
- `docs/superpowers/milestones/`
- `docs/superpowers/specs/`
- `docs/superpowers/plans/`
- `docs/superpowers/issues/`

The lifecycle is `spec -> plan -> issue`. Milestone pages are index views only. Specs, plans, and issue mirrors stay in the flat canonical roots.

## Roadmap And Milestones

Current roadmap buckets for this upstream clone:

- `M1 - Repo-Scoped Endpoint Design`: scope the transport, handler, Git-root, and endpoint shape for repo-specific MCP identities.
- `M2 - Repo-Scoped Search Enforcement`: constrain `ide_find_file`, `ide_search_text`, and adjacent tools to pinned repo roots.
- `M3 - Transport And Config Integration`: expose repo-scoped MCP endpoint identities and document/client-config generation.
- `M4 - Verification And Release Readiness`: add tests, validate backward compatibility, and prepare an upstream PR or maintained fork path.

Current local status on `codex/repo-scoped-endpoints-prep`:

- `M1` is implemented locally through additive repo-scoped routes, repo ID lookup, and handler-level pinned-root validation.
- `M2` is implemented locally for `ide_find_file` and `ide_search_text` through repo-root-aware search scopes.
- `M3` still needs client-facing config examples and a cleaner publication story.
- `M4` still needs the upstream-vs-fork decision and the final publication workflow.

## GitHub Tracker Config

- Repository: `hechtcarmel/jetbrains-index-mcp-plugin`
- GitHub issue labels:
  - `type:feature`
  - `type:task`
  - `type:bug`
  - `status:triage`
  - `status:ready`
  - `status:blocked`
  - optional area labels such as `area:transport`, `area:scope`, `area:tests`, and `area:release`
  - optional execution or publication labels such as `execution:afk`, `execution:hitl`, `target:upstream`, and `target:fork`
- GitHub milestone titles should follow the milestone bucket names above.
- Issue mirror path: `docs/superpowers/issues/<issue-number>-<slug>.md`
- Source spec path: `docs/superpowers/specs/<yyyy-mm-dd>-<slug>.md`
- Source plan path: `docs/superpowers/plans/<yyyy-mm-dd>-<slug>.md`

GitHub Project board setup is intentionally skipped for now. The canonical artifacts remain in this repository.

## Execution Model

Implementation work in this upstream clone should start from a repo-local approved spec or plan, then move through direct implementation or issue decomposition depending on whether the work is staying as fork prep or becoming an upstream PR effort.

AFK work fits local design docs, implementation plans, test scaffolding, and contained Kotlin changes with clear proof commands. HITL work is required for upstream contribution strategy, PR publication, forceful repo ownership decisions, and any change that depends on Tanner choosing between a maintained fork and an upstream PR.

## Extension Skills

Agents should discover and use:

- `$project:setup-project`
- `$project:brainstorm-spec`
- `$project:write-plan`
- `$project:implement-plan`
- `$project:create-issues`
- `$project:resolve-issue`
- `$project:merge-changes`
- `$project:audit-project`

Method work should still use the core Superpowers skills such as `superpowers:writing-plans`, `superpowers:executing-plans`, and `superpowers:verification-before-completion`.

## Current Open Questions

- Should repo-scoped identities land as an upstream additive feature or as a maintained fork?
- Should Git-root discovery stay on IntelliJ VCS roots only, or allow explicit user curation from the start?
- Which other tool families need query-time scoping versus output filtering as a safety check?
- How should repo IDs remain stable when directory names collide or roots move?
- What client config examples should ship first for multi-repo registration from one IntelliJ window?
