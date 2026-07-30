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
#slug<TAB>agent<TAB>started<TAB>items<TAB>paths
claim-mutex<TAB>opus<TAB>2026-07-27T17:41:02Z<TAB>claim-mutex<TAB>specs/claim-mutex.md .githooks/ scripts/
```

Each `<TAB>` above denotes one literal tab byte in `LEDGER.tsv`.

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

#### 2a. The two copies of the scope must agree

Scope is written **twice** — the ledger row and the `.claim` file — and for a while nothing checked
that they said the same thing. The overlap guard read only the `.claim`, so a path that appeared
solely in the LEDGER row was invisible to it and a rival could claim that exact path
(`0fade8820`; BUGS.md `claim-ledger-claimfile-scope-drift`). Duplicated state without a consistency
check does not stay consistent: on 2026-07-27 a live claim's `.claim` said `v1/runtime/**` while its
row said `v1/runtime/backend/interpreter/**`.

`pre-push` now enforces two asymmetric rules, split by **who can actually fix the problem**:

1. A claim the push **adds or rewrites** must agree with its own ledger row, on both fields
   (compared as sets, so order does not matter). Disagreement is **refused** — the pusher owns both
   copies, and letting it through is what opens the hole.
2. A claim **already live on the remote** whose copies disagree is compared on the **union** of the
   two, and named in a warning. Union is the fail-closed reading — the widest declared scope wins, so
   neither copy can hide work. Refusing here instead would let one agent's stale row block everyone
   else, which turns the mutex into a deadlock.

Related, same failure direction: the hook runs with **globbing off** (`set -f`). `for p in $paths` is
an unquoted expansion, so a claim path written `v1/runtime/**` used to be expanded against the
working tree and shrink to the directories that happened to exist at that moment — it stopped
covering anything created later. Claim paths are literal prefixes and must never be globbed.

Both rules and the glob case are asserted by `tests/coord/claim-hooks.sh`, which is runnable against
an older hook via `HOOKS_SRC=<dir>` — that is how each new case was shown to fail before the fix.

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


## Hierarchy — scope levels (2026-07-30)

A scope in `paths:` may carry a **level prefix**. The levels exist so that ownership can be stated at
the granularity the work actually has, instead of forcing every claim up to whole-directory size.

| scope | meaning | guard |
|---|---|---|
| `repo:` | the whole repository | conflicts with everything |
| `mod:<path>` | a module subtree | conflicts with any overlapping subtree; needs `broad:` |
| `file:<path>` | exactly one file | conflicts only with the same file, or with an owner who **holds** it |
| `<path>` | legacy, unprefixed | identical to `mod:<path>` — nothing that exists today changes meaning |

Module paths are the ones in [`work-tracking-layout.md`](work-tracking-layout.md), deliberately: one
hierarchy for bookkeeping and claims, not two that can disagree.

### Why, measured

One live claim on 2026-07-30 reserved six directories:

```text
v2/src                   1109 files reserved,  3 changed in two days
v2/backend                387 reserved,        2 changed
bench                     113 reserved,        0 changed
v2/backend-jvm-bytecode    83 reserved,        1 changed
v2/jvm-runtime             56 reserved,        1 changed
v2/lib                     29 reserved,        2 changed
                         ----                ---
                         1777 reserved,        9 changed   → 99.4% dead weight
```

⚠ **The honest caveat, and it bounds what this change is allowed to do.** Both refusals that claim
produced were **correct** — the file wanted, `v2/lib/ssc1-front.ssc0`, genuinely is one of the two
that changed. So this is not about undoing false refusals that happened. It is about the 1768 files
that would refuse someone else for nothing. The mutex therefore does not weaken.

### `owner_holds_file` — the whole of the new permission, and it is fail-closed

A `file:` scope inside someone's `mod:` is admitted **only** when that owner has neither *declared*
nor *modified* the file:

- **declared** — the file appears verbatim among the owner's scopes;
- **modified** — dirty in the owner's worktree, or changed on their branch (or on `main`) since their
  claim's `started`.

Anything undecidable answers **held**, i.e. refuses: worktree absent, timestamp unreadable, git
error. If no commit precedes `started`, the base falls back to the root commit — "everything ever
counts as touched" — which can only add refusals.

The race is closed by the layers that already exist: the guard re-checks at push time, and the
`# generation:` line in `LEDGER.tsv` serialises two claims that are racing.

### Contentious cases go to the room, not to a unilateral decision

Whenever two claims genuinely want the same scope — or an agent believes a broad claim is
over-reserving — that is a **conflict of interest and belongs in the rozum room**, not in a
`--no-verify` push and not in a silent release of someone else's claim. On 2026-07-30 a claim's
heartbeat read 638 minutes stale while its last commit was 56 seconds old; releasing it on the
heartbeat alone would have destroyed live work. The room is how that gets asked instead of guessed.

### Verification

`tests/coord/claim-scope-hierarchy.sh` asserts every level in BOTH directions, because a gate that
only shows the new permission working proves nothing about the mutex still holding:

```text
declared file vs same file                          → refused
declared file vs OTHER file                         → admitted
repo-level owner blocks a file                      → refused
module owner vs module                              → refused
file inside a module the owner has NOT touched      → ADMITTED   ← the point of the change
file inside a module the owner HAS touched          → refused
owner worktree ABSENT (undecidable)                 → refused    ← fail-closed
```

That fifth line failed on the first implementation — the permission did not actually work, because
`--before "$started"` found no commit and the fail-closed branch fired. Caught by the gate, not by
reading the code.

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
