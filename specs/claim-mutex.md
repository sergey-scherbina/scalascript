# Claim mutex — making task claims actually exclude each other

> Status: implemented 2026-07-27. Companion to
> [`specs/worktree-guardrail.md`](worktree-guardrail.md), which guards *where* work happens; this
> one guards *who owns it*.

## The problem, measured

Two collisions on 2026-07-27, hours apart:

| # | What happened |
|---|---|
| **A** | `post-f4-board-reconcile` and `f4-arc-closure` claimed the **same work under different slugs**, ~2 min apart (`ec56aeb01`, `08a6cb9d2`). Both agents did it; both edited `JdkServerBackend.scala`. |
| **B** | `v2-f5b-typed-locals` did SPRINT batch C while `scljet-ipk-rowid` held it on `origin/main` (`dde95c476` released "batch C landed" against a live foreign claim). |

Neither was a lapse of attention. Both are holes in the mechanism:

1. **The check is nominal, not semantic.** `/multi-agent claim` tests
   `git ls-tree origin/main .work/active/ | grep -Fx ".work/active/<slug>.claim"` — it can only see a
   claim with the *identical slug*. A is invisible to it by construction: the slug namespace is not
   the work namespace.

2. **The intended mutex is inert.** The protocol says `git push origin main  # if rejected: another
   agent won the race`. The rejection does happen (a second push to `main` is non-fast-forward), but
   the loser then fetches and rebases, and since every claim writes its **own new file** the rebase is
   clean and silent. The loser pushes on top having never seen the winner. *A mutex whose contended
   path auto-resolves is not a mutex.*

3. **Nothing binds commits to a claim.** All of B: an agent holding claim X can commit work belonging
   to claim Y and no gate objects.

4. **The order maximises the window.** `pick → plan → claim` makes the race window as long as
   planning takes. In A that was the whole 2 minutes.

## Design

Four mechanisms, deliberately layered by how hard they are to bypass.

### 1. `.work/active/LEDGER.tsv` — the only non-bypassable layer

One line per **active** claim (added on claim, removed on release), plus a header:

```
# generation: 7
#slug	agent	started	items	paths
claim-mutex	opus	2026-07-27T17:41:02Z	claim-mutex	specs/claim-mutex.md .githooks/ scripts/
```

The **generation counter is the mechanism**, not decoration. Every claim and release must increment
it, so two concurrent claims both rewrite line 1 from the same base. The loser's rebase then hits a
**conflict** on that line instead of auto-merging — it is forced to look at the winner's claim before
it can proceed.

This is the only layer that does not depend on an agent running anything: it rides on the remote's
fast-forward rule plus git's merge behaviour. A hook can be skipped with `--no-verify`; this cannot.

> Appending a line at EOF *usually* conflicts too, but not dependably — two appends can auto-merge
> depending on surrounding context. The generation line makes the conflict **deterministic**, which is
> the difference between a mutex and a coin flip. This is verified by a real two-clone test, not
> assumed (`tests/coord/claim-mutex-conflict.sh`).

### 2. Claims declare `items:` and `paths:`; `pre-push` refuses an overlap

A claim file gains two fields:

```
items: A1 A2 A3 A4          # SPRINT item ids and/or BUGS slugs — the WORK, not the name
paths: scljet/ tests/conformance/scljet-   # path prefixes this claim may touch
```

`.githooks/pre-push` refuses a push that adds a claim whose `items` intersect, or whose `paths`
prefix-overlap, a different live claim in the ledger. This is what makes collision A visible: the two
slugs differed, but both would have declared the same items (`A1`-`A4`).

Path overlap is by **prefix containment**, not glob intersection — predictable and explainable in an
error message. Full glob algebra is not worth the complexity here.

### 3. `pre-commit` scope guard — commits stay inside the claim

In a worktree on `feature/<slug>`, staged paths must fall inside that claim's `paths:` (read from
`origin/main`). This is collision B's gate.

Two deliberate softenings, or it would be unusable:

- **Always-allowed shared set:** `SPRINT.md`, `BACKLOG.md`, `CHANGELOG.md`, `BUGS.md`, `.work/**`,
  `specs/` — nearly every task touches these by design, and requiring each claim to enumerate them
  would make `paths:` noise that everyone copies blindly.
- **Backward compatible:** no claim for the branch, or a claim with no `paths:` line → allow, with a
  one-line note. Enforcement applies only when a claim actually declares its paths. A guard that
  breaks every pre-existing branch gets disabled, and a disabled guard protects nothing.

Escape hatch `git commit --no-verify`, same as the worktree guardrail.

### The shared set applies to layer 2 as well (fixed 2026-07-27)

Layer 3 exempted the board files from the start; **layer 2 did not**, so a claim that merely *named*
`SPRINT.md` was refused as an overlap against any live claim that also listed it — and nearly every
claim lists it. The two layers disagreed about the same files, and the practical result was that a
finished task could not be ticked off: on 2026-07-27 four completed items (SPRINT F1/F2/F3 and the
`js-int-division-by-zero` fix) sat open on the board because nobody could commit the tick. That is
the exact drift this mutex exists to prevent, produced by the mutex itself.

The overlap guard is for **work**, not for the notebook the work is recorded in. Two agents editing
different lines of `SPRINT.md` rebase cleanly; two agents doing the same *task* is what must be
caught, and `items:` catches that independently of any path. So `is_shared_bookkeeping` in
`.githooks/pre-push` skips those names on both sides of the comparison, and the refusal message says
so — if a board file ever appears in an overlap report, the hook has a bug.

**Guarded against over-reach:** `tests/coord/claim-hooks.sh` asserts the pair, not just the
permission — a claim naming only board files is allowed, board files alongside a disjoint work path
are allowed, and a claim that names board files *and* a genuinely overlapping work path is still
REFUSED. Measured against the pre-fix hook, the first two fail and the third passes, so the cases
discriminate rather than merely agreeing with today's behaviour.

### A refusal must say WHICH refusal it is (fixed 2026-07-27)

`scripts/coord-claim` pushed with `2>/dev/null`, so the pre-push hook's diagnostic was discarded and
an **overlap refusal** printed the same "another agent moved first" text as a plain generation race.
The two need opposite responses — retry vs. stop and re-read the queue — and the agent hitting the
first was told to do the second, retrying about ten times before diagnosing it by reading the hook
source. `coord-claim` now keeps the push output and distinguishes the two, printing the hook's own
reason for an overlap and stating plainly that retrying will not help.

### 4. Claim **before** planning

`AGENTS.md` §workflow changes from `pick → plan → claim` to `pick → claim → plan`. A claim is one
cheap, revocable commit; a plan is minutes. Claiming first collapses the race window to the length of
a single push, and losing the race becomes a normal, automatic outcome (re-read, pick again) instead
of discovering a duplicate hours later.

## Explicit non-goal

**Overlap is not banned — accidental overlap is.** The 2026-07-27 duplicate is exactly what caught a
live file-corruption bug (`BUGS.md` → `scljet-ipk-move-indexed-corrupts-btree`): a second agent
cross-checked the first's landed fix against the reference engine and found the b-tree it corrupted.
An agent that *deliberately* re-verifies someone's result is doing something valuable; it should
declare that (claim `verify-<slug>` naming the same items) rather than be prevented.

## Verification

Every gate here is a gate about gates, so the project's own rule applies with full force: *if this
were broken right now, would the apparatus say so?* Each mechanism ships with a test that
**demonstrates the failure**, not just the success:

| Test | Proves |
|---|---|
| `tests/coord/claim-mutex-conflict.sh` | two concurrent claims from the same base → the second's rebase CONFLICTS on the generation line (and, as a control, that two claims *without* the ledger auto-merge silently — the bug this replaces) |
| `tests/coord/claim-overlap-prepush.sh` | pre-push refuses an overlapping-items claim and an overlapping-paths claim; allows a disjoint one |
| `tests/coord/claim-scope-precommit.sh` | pre-commit refuses a staged path outside `paths:`; allows the shared bookkeeping set; allows when no claim or no `paths:` |

A green run of these is meaningless unless the red case was observed first — each script asserts both
directions.
