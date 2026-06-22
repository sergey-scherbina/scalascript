# Worktree guardrail — keep feature commits out of the shared `main` checkout

> Status: implemented (2026-06-22).

## Problem

AGENTS.md §1 mandates "one agent = one worktree = one branch" and forbids editing,
committing, or testing directly in the shared `main` checkout. This is documented but
**not enforced**, and documentation alone has already failed once: a session created
`feature/rust-web-toolkit` *in the shared checkout* (`git checkout -b` + commits/rebases
directly on the shared HEAD, 2026-06-19 → 06-21) instead of a worktree. The shared
checkout stayed parked on that feature branch across a reboot, which is the misleading
state that made two agents independently re-run the same recovery.

Root trigger: the harness `EnterWorktree` tool false-positives ("Must not already be in a
worktree") when the working directory drifts after context compaction
([claude-code #27881](https://github.com/anthropics/claude-code/issues/27881)); an agent
that doesn't fall back to manual `git worktree add` can end up committing in shared `main`.

We cannot fix the upstream tool. We **can** make the *damage* (commits landing in shared
`main`) structurally hard.

## Goals / non-goals

- **Goal:** a commit of feature work in the shared `main` checkout (or on the `main`
  branch) **fails loudly**, with a message pointing to the worktree recipe.
- **Goal:** the legitimate main-checkout coordination flow (claims/releases/pause-resume
  under `.work/`) keeps working.
- **Goal:** commits in a feature worktree are **never** affected (zero blast radius for
  the normal flow).
- **Non-goal:** an unbypassable wall. `--no-verify` remains an escape hatch — this is a
  guardrail against *accidental drift*, not a security control.
- **Non-goal:** fixing `EnterWorktree` (upstream).

## Design

### `.githooks/pre-commit`

Block the commit when **either**:
- we are in the **main checkout** — detected by `git-dir == git-common-dir` (a linked
  worktree has `git-dir = …/.git/worktrees/NAME` ≠ `git-common-dir = …/.git`), **or**
- the current branch is **`main`** (covers a linked coordination worktree like
  `coord-main` that sits on `main`),

**unless** every staged path is under `.work/` (coordination commits stay allowed).

Paths are compared as resolved absolute paths so the check is correct from any subdirectory.
Detached HEAD in the main checkout is still blocked (the `git-dir == git-common-dir` arm),
which is exactly the parked-feature-branch case.

Escape hatch: `git commit --no-verify`.

### `core.hooksPath`

The hook is activated by `git config core.hooksPath .githooks`. This config lives in the
repo's common config, so a single setting covers the main checkout **and every linked
worktree**. `.githooks/` is version-controlled, so the hook travels with the repo. The
relative path resolves against each working tree's root, all of which contain `.githooks/`.

`scripts/setup-hooks` sets the config idempotently; `scripts/new-worktree` also ensures it.
Until `core.hooksPath` is set, the committed `.githooks/` files are inert — landing them is
safe.

### `scripts/new-worktree <name>`

Packages the reliable manual recipe so agents don't improvise (and don't fight a
false-positive `EnterWorktree`):
- resolve the main checkout (`git worktree list | head -1`),
- `git fetch origin`,
- `git worktree add` at an **external** path (sibling of the repo, e.g.
  `<repo>-wt-<name>`) — deliberately **not** under `.worktrees/`, which sibling agents
  prune (`git worktree prune` / `rm -rf .worktrees`) and which has killed in-flight
  worktrees,
- branch `feature/<name>` off `origin/main`,
- ensure `core.hooksPath`.

## Behaviour matrix

| Where | Staged files | Result |
|---|---|---|
| feature worktree (`feature/*`) | anything | ✅ allowed |
| shared main checkout | any non-`.work/` file | ❌ blocked |
| shared main checkout | only `.work/…` | ✅ allowed (coordination) |
| `coord-main` (linked wt on `main`) | any non-`.work/` file | ❌ blocked |
| any | anything, `--no-verify` | ✅ allowed (escape hatch) |

## Verification

`scripts/test-worktree-guardrail` (or manual) exercises every matrix row against a throwaway
repo. The hook must:
- exit 0 for a feature-worktree commit,
- exit 1 for a non-`.work` commit in the main checkout / on `main`,
- exit 0 for a `.work/`-only commit in the main checkout,
- be bypassed by `--no-verify`.

## Rollout

1. Land `.githooks/`, `scripts/`, this spec, AGENTS.md note (hook inert — `core.hooksPath`
   not yet set).
2. Coordinate in the rozum room (changes everyone's commit flow).
3. Set `core.hooksPath=.githooks` on the shared repo (covers all worktrees); re-sync the
   shared checkout to a commit that contains `.githooks/` so the hook is present there.
