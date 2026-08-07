# Work tracking: one file per module, one board on top

> **The RULES about where work is recorded are [`POLICY.md`](../POLICY.md) §P-3.** This spec is
> the LAYOUT, the module table and the measurements behind them.

Bugs, backlog and sprint live **in the module they belong to**. There is exactly one file that is
global, and it is the active sprint board.

## Why

Measured 2026-07-30, before the split: `BUGS.md` held **630 entries** in one file, `BACKLOG.md` and
`SPRINT.md` likewise. Three consequences, all of them observed rather than predicted:

- **Every agent edits the same three files.** The claim protocol has to declare `BUGS.md`,
  `SPRINT.md`, `BACKLOG.md` and `CHANGELOG.md` as SHARED — "never an overlap" — precisely because a
  per-file mutex would serialise the whole project. Shared means the guard cannot help: two agents
  appending to the same region conflict, and the protocol's answer is "rebase and hope".
- **A module's own state is not readable from the module.** Someone working in
  `v1/runtime/backend/js/` had no way to see that lane's 151 open and closed defects without
  querying a 630-entry file at the root.
- **Nobody can tell what is in flight.** A single flat `SPRINT.md` mixed "planned", "being worked on
  right now by someone else" and "done", so the answer to *what is happening this hour* required
  reading the whole file and cross-checking `.work/active/`.

## Layout

```
<module>/BUGS.md        defects whose FIX goes in that module
<module>/SPRINT.md      that module's queue — [~] in progress, [x] done
<module>/BACKLOG.md     that module's can-wait work
BUGS.md · BACKLOG.md    cross-module only
SPRINT.md               THE BOARD — only what is selected for work
```

**The module list itself is [`tests/fixtures/modules.tsv`](../tests/fixtures/modules.tsv), not this
file.** It is the single machine-readable source: `scripts/bugs-split` and `scripts/work-split` read
it, `bugs-split --dry-run` refuses when it disagrees with the tree (a module listed but absent, or a
module with a `BUGS.md` the table has never heard of), and `coord-claim` can validate `mod:` scopes
against it. It used to live here in prose — twice, in two different shapes, with nothing checking
they agreed, which is the duplicated-state failure this repo has paid for repeatedly.

**A file exists only where there is something in it.** Creating empty files per module would
manufacture the impression of coverage; `scripts/bugs-report` walks whatever exists.

### Which module owns an entry

The module is **where the fix goes**, not where the symptom shows and not where the gate lives. That
distinction is the whole difficulty: measured on the 630 entries, routing by "most frequently
mentioned path" sent everything to `tests/conformance` because an entry names its *gate*, and 109
entries mention a conformance case while only a handful are defects *in* the corpus.

So routing uses the `lane:` header — the field that says which implementation misbehaves — and the
lane-to-module mapping is a column in `modules.tsv`. `apparatus` is the one lane that needs `area:`
too (`conformance` → `tests/conformance`, `build` → `scripts`, everything else → `tests`); that
exception lives in `scripts/bugs-split` because a table of lanes cannot express it.

**Authority order when the signals disagree**, strongest first — proposed by `board-ownership-fixed-in`
and adopted:

1. **`fixed-in:`** resolving to a commit that touched sources — a fact. Measured 2026-07-30: 216 of
   391 entries with the field resolve that way.
2. **`lane:` as declared by a human** — a judgement, and good enough.
3. **Keyword extraction — never.** It is how today's values were produced, and it put **4 entries
   with the wrong owner**: two v2-front defects filed under the interpreter, a JIT defect under the
   JS backend, and a v2 lane defect under the JVM backend. Every one had been routed by where the
   symptom SURFACED. Fixed 2026-07-30.

**`multi` stays at the root, and that is not a dumping ground.** An entry is `multi` when the same
defect is present in more than one implementation, so no single module can own it; the root is the
nearest common ancestor. If a `multi` entry turns out to be one module's after all, move it and
change the lane — that is a one-line edit, and the gate will keep the slug unique.

## Inbound queue — reports from people who USE ScalaScript

**The rule is [`POLICY.md`](../POLICY.md) P-3.10.** The mechanics:

```
INBOX.md                   the queue — untriaged reports only, never a routed copy
scripts/inbox-add          registers one; `--list` shows what is waiting, oldest first
scripts/inbox-route        moves one OUT to the board that owns the fix, or closes it; --self-test
tests/e2e/inbox-gate.sh    the invariants, with --self-test
```

**Route with the tool, not by hand.** `inbox-route <slug> --to <board> --lane <l> --area <a>` carries
the reporter's fields onto the destination entry, writes `confirmed: no` for a fix, and deletes the
entry from the queue. Hand-routing lost exactly that on 2026-08-07: three reports had been fixed and
on `main` for three days while their records still read `triage: new`, and the board entry standing
in for them pointed AT `INBOX.md` rather than carrying `reported-by` — which is the field the routed
set is derived from, so they were invisible from both ends. The tool refuses `--status fixed`
without `--fixed-in`, and refuses a slug already on a board.

⚠ **`inbox-route --self-test` is NOT in the smoke suite yet** — `scripts/smoke-ci.ssc` is held by
the live claim `smoke-guard-sized-by-ci`, so the one line that registers it is left for whoever
holds that file. It costs 352 ms against a 420 s budget. Until then the self-test exists and passes
but nothing runs it, which is the state `inbox-gate` was in before it was registered.

**Why a separate place at all, when P-3.8 says subtype is a field.** Inbound is not a subtype, it is
a STATE BEFORE ROUTING. Every other record in this repo is born routed: `lane:` and `area:` are
judgements about where the fix goes, and the agent filing it makes them. A user cannot — they know a
symptom and a version. The alternatives were to guess a module (P-3.3 calls that extraction from
prose, and it put four entries under the wrong owner when it was tried) or to lose the report.

**Fields.** `triage: new | needs-info`, plus `reported-by`, `reported-at`, `ssc-version`, `repro`,
and optionally `kind` / `waiting-on` / `reporter-suspects` / `impact`. Full table in
[`../INBOX.md`](../INBOX.md).

**`lane:` and `area:` are *refused* here by the gate; `reporter-suspects:` is accepted.** That pair
of behaviours IS rule P-3.11, made mechanical. The refused fields carry the routing decision, whose
authority order P-3.3 fixes; the accepted one carries the reporter's own reading of the cause, which
is evidence and is kept in full. Users read the original wording as "do not diagnose" and said so on
2026-07-31 — they were right, and the fix was to separate information from authority rather than to
relax either. Only a slug and a way to reply are mandatory now; a version the reporter could not
obtain is recorded as `unknown`, because a required field they cannot supply is a report that never
arrives.

**Triage moves the entry.** Into `<module>/BUGS.md` (or `BACKLOG.md`) of the module that owns the fix,
gaining `status`/`lane`/`area`/`gate`, and out of `INBOX.md`. The reporter fields travel with it:
`confirmed: no` in the header schema already means "fixed, but the reporter has not confirmed", and
before this queue existed nothing recorded who to ask.

**There is deliberately no routed list.** It would be a second copy of a record that now lives in a
board. The routed set is derived — every entry anywhere carrying `reported-by` came from a user:

```sh
git grep -l 'reported-by:' -- '*BUGS.md' '*BACKLOG.md'
```

The one outcome that is NOT derivable is a report closed *without* routing (`duplicate`,
`not-a-defect`): there is no destination entry to derive it from, so those get one line each under
`## Closed without routing` — a line and a reason, never the report body.

**Age is an invariant, not a nicety.** `SSC_INBOX_MAX_AGE_DAYS` (default 14) is the point at which
the gate fails. A report nobody rejects and nobody routes has been lost politely, and the only
mechanical difference between a queue and a graveyard is whether anything notices.

## The header is unchanged

`specs/bugs-index.md` still defines it, and the gate still enforces it — the only change is that the
gate now walks every `BUGS.md` and requires slugs to be unique **across all of them**. Keep `lane:`
even though the file location now implies it: the lane is what makes the entry routable, and an
entry whose lane and location disagree is a bug in the tracking, which the gate can then catch.

## Backlog

Same split, same reason, no header schema: a backlog item is prose plus a title. Cross-module or
not-yet-scoped items stay in the root `BACKLOG.md`. When an item is picked up it moves to that
module's `SPRINT.md`, not to the root one.

## Sprint: two levels, and they mean different things

**A module `SPRINT.md` is a queue.** Items carry one of two states, and there is no third:

```markdown
- [~] SLUG — one line          in progress
- [x] SLUG — one line          done
```

An item that is neither is simply not in the sprint yet — it is in the backlog. This is deliberate:
a queue with a "planned" state becomes a second backlog, which is what the flat `SPRINT.md` had
become.

**The root `SPRINT.md` is the board, and it is the only global file.** A task is copied here
**before work starts on it** — the same moment the claim is written, so the board and
`.work/active/` agree — and its status is tracked here until it is released:

```markdown
| task | module | claim | state | notes |
|---|---|---|---|---|
| SLUG | v2/ | v2-front-arity | in progress | one line on where it stands |
```

The board answers *what is happening right now*; the module sprint answers *what is queued for this
module*. The same task therefore appears in two places on purpose, and the two are written at the
same moment, so they cannot drift by more than one commit.

**Order of operations**, because getting it wrong is what produced duplicated work on 2026-07-27:

1. pick an item from a module `BACKLOG.md` or a `BUGS.md`;
2. `scripts/coord-claim` — the claim is the mutex, and it comes first;
3. add the row to the root `SPRINT.md` **and** mark the module `SPRINT.md` item `[~]`;
4. work;
5. mark the module item `[x]`, remove the board row, release the claim.

Step 3 is one commit, not two: a board row without a claim is a lie, and a claim without a board row
is invisible.

## Querying

`scripts/bugs-report` aggregates across every `BUGS.md` and takes `--module`:

```sh
scripts/bugs-report                          # counts by status, per module
scripts/bugs-report --module v2 --status open
scripts/bugs-report --no-gate                # open entries with no regression gate named
```

Never grep for status — that is what produced three synonyms for "closed" and 108 entries with no
status at all before `specs/bugs-index.md` existed.
