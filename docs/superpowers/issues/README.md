# Issue Mirrors

This directory holds the canonical local mirrors for GitHub issues related to Superpowers Project work in this repository.

Issue mirrors should live at:

- `docs/superpowers/issues/<issue-number>-<slug>.md`

Each mirror should track the GitHub issue title, state, milestone, labels, source plan or spec, acceptance criteria, and proof commands closely enough that an agent can audit drift before implementation.

## Closed Mirror Lifecycle

Closed issue mirrors may be removed after the GitHub issue is closed and the verification evidence is no longer needed.

Keep a closed mirror only when the historical context still matters. When a closed mirror should remain in the repo, mark it explicitly with:

- `**Mirror Retention:** Keep`

No issue mirrors exist in this clone yet because the repo-scoped endpoint work has stayed as direct local branch work so far.
