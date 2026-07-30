# POLICY — how work is managed in this repository

**This is the single source for the RULES.** If a rule about how work is claimed, tracked, verified
or coordinated is stated anywhere else, that other place is wrong and should link here instead.

The specs keep what this file deliberately does not: **mechanism** (how a guard is implemented),
**evidence** (the measurements a rule came from) and **history** (the defect that motivated it).
Those are long, and putting them here would drown the rules. Each section names its spec.

| Read this file for | Read the spec for |
|---|---|
| what you must do | why, and how it is enforced |

Rules are numbered so they can be cited: "P-3.2" in a commit or a room message means this file.

---

## P-1 · The loop

Every piece of work, always, in this order. Detail: [`AGENTS.md`](AGENTS.md) §THE WORKFLOW.

1. **Claim first — before planning.** `scripts/coord-claim <slug> --items … --paths …` from the
   main checkout. Claiming after planning leaves a race window as long as your planning; on
   2026-07-27 two agents claimed the same work two minutes apart inside that gap.
2. **Plan into the module's `SPRINT.md`, and put a row on the root board** — see P-3.
3. **Work in a worktree** on `feature/<slug>` off `origin/main` (`scripts/new-worktree <slug>`).
   The branch name must match the claim slug: that is how the scope guard finds your claim.
4. **Verify, then push straight to `origin/main`.** Small commits, feature separated from
   bookkeeping. The affected conformance slice runs *before* the push, not after.
5. **Release and clean up** — `scripts/coord-release <slug>`, then `scripts/rm-worktree <name>`.
6. **Sweep the room** (P-5) every time you finish an item and have nothing in flight.

## P-2 · Claims

Detail and proofs: [`specs/claim-mutex.md`](specs/claim-mutex.md).

- **P-2.1 · Claim the narrowest scope that covers the work.** Scopes carry a level prefix:
  `file:<path>` is the default; `mod:<path>` and `repo:` are refused without
  `--broad "<reason>"`. Measured 2026-07-30: one claim reserved **1777 files, 9 of which changed
  in two days**.
- **P-2.2 · `mod:` is an edit lock, not stewardship.** A module is both a unit of code and a unit
  of ownership, but holding a subtree to signal "this area is mine" blocks a thousand files to
  protect two.
- **P-2.3 · Never list a bookkeeping file in a claim.** `SPRINT.md`, `BACKLOG.md`, `BUGS.md`,
  `CHANGELOG.md`, `MILESTONES.md`, `README.md` are shared at **every** level, root and per-module
  alike, and the guards exempt them by basename. Appending to a board must never be refusable.
- **P-2.4 · Widening a claim is a normal move, not a failure.** Nobody can predict their path set
  before reading the code; 27 widenings were recorded repo-wide in one day. Widen and let the
  overlap guard check it — do **not** work around the scope guard. Both copies of the scope
  (`.claim` and `LEDGER.tsv`) must be updated together; the guard refuses if they disagree.
- **P-2.5 · A refusal you believe is wrong is a conflict of interest.** Take it to the room (P-5).
  Never `git push --no-verify`, never release someone else's claim. A stale heartbeat is not
  liveness: a claim once read 638 minutes stale while its last commit was 56 seconds old.

## P-3 · Where work is recorded

Detail: [`specs/work-tracking-layout.md`](specs/work-tracking-layout.md) (layout, module table),
[`specs/bugs-index.md`](specs/bugs-index.md) (the header schema).

- **P-3.1 · One board per module, in the module.** `{BUGS,BACKLOG,SPRINT}.md` next to the code.
  A file exists only where there is something in it.
- **P-3.2 · An entry belongs to the module where the FIX goes** — not where the symptom showed and
  not where the gate lives. Routing by "most frequently mentioned path" sends everything to
  `tests/conformance`, because an entry names its gate.
- **P-3.3 · Authority for routing, in order: `fixed-in` (a resolvable sha) > a field a human
  declared > keyword extraction from prose (NEVER).** The last one is how `area:` came to read
  `front` for 256 of 621 entries: the words `parse`, `_err` and `front` appear in almost any
  report. An entry whose location and `lane:` disagree is a tracking bug, and the gate catches it.
- **P-3.4 · Module `SPRINT.md` is a queue with two states**, `[~]` in progress and `[x]` done.
  There is no "planned" — that is what the backlog is. A queue with a planned state becomes a
  second backlog.
- **P-3.5 · The root `SPRINT.md` is THE BOARD**, the only global work file: one row per task in
  flight, written when the claim is written and removed when it is released. **A row without a
  claim is a lie; a claim without a row is invisible work.** Measured 2026-07-30: 4 live claims,
  1 row — 75% invisible. Enforced by `tests/coord/board-claim-parity.sh`, not by memory.
- **P-3.6 · Subtype is a FIELD, never a second directory.** `kind: bug|perf|feature|regression|
  apparatus|programme` on the entry; per-type documents under `TASK/` carry a **generated** index
  (`scripts/task-views`) and hand-written analysis around it. A second axis in the filesystem gives
  an entry two plausible homes, and then "no duplicate slugs" stops holding and "in neither" starts.
- **P-3.7 · Never grep for status.** Query the header (`scripts/bugs-report`). Prose produced three
  synonyms for "closed" and 108 entries with none.

## P-4 · Deciding

- **P-4.1 · Default to deciding; asking is the exception.** If you can name a defensible default and
  the cost of being wrong is a revert, take it and say so in the commit.
- **P-4.2 · When the fork is real, take the smallest defensible option and PARK the alternatives**
  on the board with their trade-offs. A parked alternative costs nothing and is there the day it
  becomes right; the same alternative held as "I should ask" is lost at the next reboot.
- **P-4.3 · Ask anyway** for an irreversible or outward-facing action, a decision that invalidates
  shipped work, a genuine conflict between two instructions, or every-option-is-bad — **while
  continuing everything that does not depend on the answer.**

## P-5 · The room

One room: **`scalascript`**. Detail: [`rozum`](.agents/plugins/rozum/commands/rozum.md).

- **P-5.1 · Contested goes to the room** — another agent's claim in your way, a claim you believe
  over-reserves, two defensible answers where the choice affects others, or any change to a shared
  contract (the claim protocol, the board format, a gate everyone depends on, a freeze).
- **P-5.2 · Not the room:** a report of what you did (that is `CHANGELOG.md`), a finding (that is
  the module's `BUGS.md`), or a question you could answer with one command. **Measure first, then
  ask.**
- **P-5.3 · The room must be READ, not only written.** Sweep after every finished item (P-1.6).
  Posting is the cheap half; a post nobody reads is a file nobody opens.
- **P-5.4 · State what you will do if nobody answers.** A question with no default attached blocks
  you, and blocking is what this is meant to avoid.
- **P-5.5 · One room.** A conflict is only visible if everyone looks at the same place.

## P-6 · Apparatus

This project's most expensive recurring defect is not broken code — it is **a check that is green
because it cannot see**. These rules are the accumulated answer.
Detail: [`AGENTS.md`](AGENTS.md) §"measurement apparatus must COMPARE, never PRE-JUDGE".

- **P-6.1 · A gate must be observed FAILING before it is trusted.** Revert the fix, run the gate, and
  put the red count in the commit ("2 of 6 FAIL unfixed"). A gate nobody has seen fail is a
  hypothesis.
- **P-6.2 · A gate about a TOOL must RUN that tool.** Checking the tool's output files covers
  neither a crash nor a syntax error. `board-claim-parity.sh` was proven red in both directions and
  stayed green while `coord-claim` aborted with `unbound variable` for every agent on main — it read
  the files the tool writes and never executed it.
- **P-6.3 · A filter must say what it did not read.** `--kind perf` answered 3 of 12 items and
  looked complete, because 9 live in `BACKLOG.md`, which has no header schema. 635 records are
  queryable and 280 are not.
- **P-6.4 · Duplicated logic needs one vocabulary on both sides, or it is not one guard but two.**
  The shared-bookkeeping exemption drifted between the pre-push and pre-commit layers **three
  times** in one day.
- **P-6.5 · State the expected size before starting, and record refuted attempts.** Three plausible
  optimisations were implemented, measured and reverted this month; without the record they get
  retried, because all three look obviously right.
- **P-6.6 · One measurement is a hypothesis.** On a contended host an A/B of *identical* code has
  swung **2.5×**. Alternate before/after rounds and compare medians; never compare a fresh number
  against a stale table.

## P-7 · This file

- **P-7.1 · A rule is stated here once.** Everywhere else links to it. If you find a rule restated
  in another document, the fix is to replace it with a link, not to keep both in sync.
- **P-7.2 · Mechanism, evidence and history stay in the specs.** They are why the rule exists, and
  they are too long to live beside it.
- **P-7.3 · Changing a rule here is a shared-contract change** — P-5.1 applies: raise it in the room
  first.
