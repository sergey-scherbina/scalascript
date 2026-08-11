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
   **The shared `main` checkout is for exactly three things** — reading state, fast-forwarding your
   own branch into it, and coordination commits that must be visible on `origin/main`. Nothing
   else, including a one-line doc fix: siblings switch its HEAD and `git clean` your untracked
   files, and they have.
4. **Verify, then push straight to `origin/main`.** Small commits, feature separated from
   bookkeeping. **Run `scripts/smoke-ci` before the push** — it is the same suite GitHub runs on the
   push, so a red there is a red you were going to get anyway, five minutes earlier and with the
   failing check named. The affected conformance slice runs *before* the push too, not after.
   **A user-facing feature ships with its doc update in the SAME push.** A feature with no doc
   update is incomplete — treat it exactly as a failing test. Docs go in their own commit, not
   mixed into the feature one.
5. **Release and clean up** — `scripts/coord-release <slug>`, then `scripts/rm-worktree <name>`.
6. **Sweep the room** (P-5) every time you finish an item and have nothing in flight.

## P-2 · Claims

Detail and proofs: [`specs/claim-mutex.md`](specs/claim-mutex.md).

- **P-2.1 · Claim the narrowest scope that covers the work.** Scopes carry a level prefix:
  `file:<path>` is the default; `mod:<path>` and `repo:` are refused without
  `--broad "<reason>"`. Measured 2026-07-30: one claim reserved **1777 files, 9 of which changed
  in two days**.
- **P-2.1b · A `file:` scope is a CO-TENANCY, not an exclusive lock — advisory since 2026-08-11.**
  Two claims naming the same `file:` path no longer refuse each other; the push is admitted and the
  hook names the co-tenant. Measured over 30 days: of 143 commit pairs from DIFFERENT claims
  touching the same file within six hours, **43 — 30 % — touched overlapping line ranges**, so
  refusing all of them prevented a resolvable conflict in three cases of ten and bought nothing in
  the other seven. A textual conflict is also the *visible* failure; the silent one is a clean merge
  that drops the other's work, and that happens on the boards, which never had a lock.
  What co-tenancy asks of you: **say where you are in the file** (P-5.1), rebase on `origin/main`
  before pushing, and **re-run your measurements after the rebase** — a verdict taken before it
  describes a tree that no longer exists.
  Still refused, and these are the point: an **`items:` overlap** (two agents on the same WORK,
  which no merge can fix), a `mod:`/`repo:` overlap, and a `file:` inside a `mod:` its owner has
  declared or touched.

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
- **P-2.4b · A claim exists when it is visible on `origin/main`, not when you write the file.**
  Hand-writing `.work/active/<slug>.claim` skips the `LEDGER.tsv` generation bump, and that bump is
  the entire mutex: without it two concurrent claims write disjoint files and auto-merge, so
  neither agent learns the other exists. Use `scripts/coord-claim`.
- **P-2.4c · Deliberately re-checking another agent's landed result is legitimate.** Claim it as
  `verify-<slug>` with the same `items` — the overlap guard admits that on purpose. Accidental
  duplication is what the mutex targets; an intentional cross-check found a live b-tree corruption
  on 2026-07-27.
- **P-2.5 · A refusal you believe is wrong is a conflict of interest.** Take it to the room (P-5).
  Never `git push --no-verify`, never release someone else's claim. A stale heartbeat is not
  liveness: a claim once read 638 minutes stale while its last commit was 56 seconds old.
- **P-2.6 · One claim is one TASK, released when that task is done.** P-2.1 bounds a claim's
  paths; this bounds its lifetime and its scope of work. A claim covering several tasks — "make
  module X ready", a list of criteria, a milestone — holds its paths for as long as the slowest of
  them, and every hour of that is an hour nobody else can touch the module. Measured 2026-08-05/06:
  one claim held all of `uniml/` for two days across six acceptance criteria; it was reaped twice
  for a stale heartbeat while its author was committing continuously, and a sibling who wanted the
  same module had to wait for a claim whose remaining work was in one corner of it.
  Prefer: claim, do the one thing, land it, RELEASE, claim the next. The cost of a second
  `coord-claim` is seconds; the cost of a two-day claim is everyone else's queue.
  A claim that has been open long enough to be reaped is a claim that should have been several.
- **P-2.7 · Work you find but will not do goes on a board before you move on.** AGENTS.md already
  requires queueing what you are *about to do*; this is its other half — the follow-up you are
  NOT doing, because it belongs to another module, because a live claim holds it, or because you
  decided it is not worth it now. In this repository that means the module's `BACKLOG.md`: a
  module `SPRINT.md` has exactly two states, `[~]` in progress and `[x]` done, so a task nobody is
  working on cannot honestly go there. Write it with the measurement that found it and what would
  settle it, not just its name — an entry a reader cannot act on is a reminder, not a task.

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
- **P-3.5 · The in-flight board is GENERATED — `scripts/board`, never a hand-written table.**
  `.work/active/` is the source of truth; it is already the mutex and already written by
  `scripts/coord-claim`, and every column derives from a field the claim carries (`items` → task,
  `paths` → module, activity → state, `next` → notes). The root `SPRINT.md` holds a pointer.
  *Superseded rule, kept because the reason matters:* until 2026-07-30 this required a row written
  with the claim and removed with the release, enforced by a parity gate. The rule was right and
  **unfollowable — nothing wrote the row**. Measured that day: 4 live claims, 1 row (75 % invisible);
  four manual reconciliations in one hour, each red again within minutes; and the attempt to automate
  the copy broke `coord-claim` on `main`, aborting after it had staged and leaving half-written
  claims for two other agents. Two copies of one fact cannot be kept honest by a gate when one is
  derivable.
- **P-3.6 · Maintain the CLAIM, not the board.** The generated view is only as good as
  `.work/active/`, so `status:` and `next:` are what must be kept current — set `status: in-progress`
  when you start. Measured 2026-07-30: all seven live claims still read `claimed-before-planning`.
  Claiming before planning is deliberate (P-2) and right; never updating it afterwards is what made
  the old `state` column carry nothing.
- **P-3.7 · Liveness is OBSERVED, never self-reported.** `scripts/board` reads the newest commit on
  the claim's branch, uncommitted files in its worktree, and whether the worktree exists — not
  `status:`, and never `heartbeat:`. Twice on 2026-07-30 a claim read hundreds of minutes stale by
  heartbeat while its last commit was under a minute old. **Before touching a foreign claim, look at
  its branch and its worktree**, then take it to the room (P-5).
- **P-3.8 · Subtype is a FIELD, never a second directory.** `kind: bug|perf|feature|regression|
  apparatus|programme` on the entry; per-type documents under `TASK/` carry a **generated** index
  (`scripts/task-views`) and hand-written analysis around it. A second axis in the filesystem gives
  an entry two plausible homes, and then "no duplicate slugs" stops holding and "in neither" starts.
- **P-3.9 · Never grep for status.** Query the header (`scripts/bugs-report`). Prose produced three
  synonyms for "closed" and 108 entries with none.
- **P-3.10 · A report from a USER enters the inbound queue, and leaves it by MOVING.**
  [`INBOX.md`](INBOX.md) is the one place an entry may exist without a module. Register with
  `scripts/inbox-add` (never by hand — `reported-by` is what makes `confirmed: no` answerable).
  Triage routes it to the module that owns the fix (P-3.2), where the entry is **moved** — not
  copied — carrying its reporter fields; the routed set is then DERIVED, not listed (P-3.5).
  `tests/e2e/inbox-gate.sh` bounds how long anything may wait, because a queue with no age limit is
  a graveyard with good manners.
- **P-3.11 · Take everything the reporter offers; take none of their authority.** A reporter's
  diagnosis, failed workarounds, logs and suspicions are EVIDENCE and are recorded in full
  (`reporter-suspects:` and the body) — refusing them throws away work someone already did. What
  the queue does not take from them is the routing DECISION: `lane:`/`area:` stay empty until a
  triager reaches one, because P-3.3 fixes that authority order and guessing it once put four
  entries under the wrong owner. The gate accepts the first and refuses the second, so the
  distinction is mechanical rather than a matter of etiquette. **A required field that a reporter
  cannot supply is a report you do not receive** — only a slug and a way to reply are mandatory;
  everything else, version included, may be `unknown`.

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
  neither a crash nor a syntax error. `board-claim-parity.sh` (deleted with the table it watched) was proven red in both directions and
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
- **P-6.7 · `cancelled` is RED, never neutral**, and a release must NAME which level of evidence it
  actually has: (1) `scripts/ci-status` exit 0, (2) the specific job that would catch this change,
  green, (3) local gates by name. Never write "green" for a run that produced no verdict. The
  ladder exists because level 1 became unreachable under churn — 6 of 14 runs on main ended
  `cancelled` — and a rule nobody can satisfy does not gate, it relocates the lying.
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
