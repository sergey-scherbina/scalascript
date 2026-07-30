# The board — what is in flight right now

**This is the only global work file, and it holds nothing but tasks actually being worked on.**
Planned and queued work lives in the per-module `SPRINT.md` / `BACKLOG.md`; see
[`specs/work-tracking-layout.md`](specs/work-tracking-layout.md) for the layout and
[`SPRINT-ARCHIVE.md`](SPRINT-ARCHIVE.md) for the 85 finished sections this file used to carry.

**The in-flight list is GENERATED, not maintained.** `.work/active/` is the source of truth — it is
already the mutex, already written by `scripts/coord-claim`, and every column of the old table came
from a field the claim file carries (`items` → task, `paths` → module, `slug` → claim,
`status` → state, `next` → notes).

```sh
scripts/board            # who is working on what, right now
scripts/board --check    # exit 1 if some copy of the table has drifted
```

Keeping a second copy cost, on 2026-07-30 alone: four live claims against one row (**75 % of the
work invisible**), four manual reconciliations in one hour that were red again within minutes
because claims land faster than hand edits, a gate whose only job was to watch the drift, and a
`coord-claim` broken on `main` by the attempt to automate the copy. A generated view has nowhere
for any of that to happen.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

## In flight

Run `scripts/board`. Nothing is listed here on purpose — a table in this file would be a second copy
of `.work/active/`, which is exactly what was removed.

## How a task gets onto this board

1. Pick an item from a module `BACKLOG.md` or a module `BUGS.md`.
2. `scripts/coord-claim <slug> --items ... --paths ...` — the claim is the mutex and it comes
   **first**, before planning, because planning takes minutes and that gap is the whole race window.
3. Mark the item `[~]` in that module's `SPRINT.md`. **There is no row to add here** — the claim
   already put you on the board.
4. Work in a worktree (`scripts/new-worktree <slug>`), never in the shared checkout.
5. Mark the module item `[x]`, then `scripts/coord-release <slug>`.

What now needs maintaining by hand is the claim itself, and the measurement says it currently is
not: on 2026-07-30 **all seven live claims still read `status: claimed-before-planning` with
`next: plan the task into SPRINT.md`**. Claiming before planning is deliberate and right, but nobody
updates it afterwards — so the `state` column carried no information at all. Update `status:` when
you start and keep `next:` current; the generated view can only be as good as the claims.
