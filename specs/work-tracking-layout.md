# Work tracking: one file per module, one board on top

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
