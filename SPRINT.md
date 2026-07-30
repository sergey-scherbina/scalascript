# The board — what is in flight right now

**This is the only global work file, and it holds nothing but tasks actually being worked on.**
Planned and queued work lives in the per-module `SPRINT.md` / `BACKLOG.md`; see
[`specs/work-tracking-layout.md`](specs/work-tracking-layout.md) for the layout and
[`SPRINT-ARCHIVE.md`](SPRINT-ARCHIVE.md) for the 85 finished sections this file used to carry.

A row appears here **in the same commit as the claim**, and disappears when the claim is released. A
board row without a live `.work/active/<slug>.claim` is a lie; a claim without a board row is
invisible work. Both directions have cost real duplicated effort on this project, which is why they
are written together rather than "kept in sync".

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

## In flight

| task | module | claim | state | notes |
|---|---|---|---|---|
| `board-row-not-written-by-tool` | *(unrecorded)* | `board-claim-parity` | in progress | row added by board-claim-parity; claim predates the tool |
| `jsgen-char-literal-escape` | *(unrecorded)* | `jsgen-char-escape` | in progress | row added by board-claim-parity; claim predates the tool |
| `rozum-room-policy-in-skills` | *(unrecorded)* | `room-policy-and-module-table` | in progress | row added by board-claim-parity; claim predates the tool |
| `claim-granularity-policy` + `shared-bookkeeping-per-module` | *(repo-wide)* | `coord-policy-and-shared-boards` | in progress | room policy + scope levels into AGENTS.md; module boards shared again |

## How a task gets onto this board

1. Pick an item from a module `BACKLOG.md` or a module `BUGS.md`.
2. `scripts/coord-claim <slug> --items ... --paths ...` — the claim is the mutex and it comes
   **first**, before planning, because planning takes minutes and that gap is the whole race window.
3. In ONE commit: add the row here, and mark the item `[~]` in that module's `SPRINT.md`.
4. Work in a worktree (`scripts/new-worktree <slug>`), never in the shared checkout.
5. Mark the module item `[x]`, delete the row here, release the claim.

Step 3 is deliberately one commit. Two commits is how the board and `.work/active/` drift, and a
board nobody trusts is worse than no board — the state it is meant to make cheap to read (who is
touching what, right now) is exactly the state agents get wrong when they guess.
