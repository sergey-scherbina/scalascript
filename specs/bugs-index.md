# The bug index — one source, one machine-readable header

The `BUGS.md` files are the single source of truth for what is broken. **There is one per module,
in the module** — where the files live and how an entry is routed to one is
[`work-tracking-layout.md`](work-tracking-layout.md). THIS spec defines the one part of an entry that
is **parsed** rather than read, so that every agent asking "what is still open" gets the same
answer.

## Why this exists

Measured on 2026-07-29, before the migration:

| | |
|---|---|
| entries in `BUGS.md` | 614 |
| **with no `Status:` line at all** | **108 (18 %)** |
| words meaning "closed": `FIXED` / `DONE` / `RESOLVED` | 332 / 67 / 3 |
| one-off freeform statuses (`STALE`, `LARGELY`, `MECHANISM`, `ENGINE`, `NO`, …) | 10 |

The status lived in prose, so every agent wrote its own `awk` over it, and those disagreed. On
2026-07-28 that produced a wrong answer to a direct question: a query for "remaining v2 work"
silently omitted the 108 entries that had no status line, and classified `DONE` entries as open
because the pattern only knew `FIXED`.

Prose is for humans and stays exactly as it is. The header is for queries.

## The header

Immediately after the `## <slug> — <one line>` heading, an HTML comment. It renders as nothing on
GitHub and parses with one regex.

```markdown
## v2-front-curried-def-second-clause — F drops the second clause of a curried def
<!-- status: open
     lane: native
     area: front
     gate: tests/e2e/v2-front-coverage.sh -->
```

```markdown
## v2-list-apply-method-stub — `xs.apply(i)` is a Stub
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: e34938737
     gate: tests/conformance/list-apply-method.ssc -->
```

### Fields

| field | required | values |
|---|---|---|
| `status` | always | `open` · `fixed` · `wontfix` · `duplicate` · `unknown` |
| `lane` | always | `native` · `int` · `js` · `jvm` · `v2-jvm` · `v2-rust` · `apparatus` · `multi` · `n/a` |
| `area` | always | `front` · `runtime` · `codegen` · `cli` · `conformance` · `build` · `docs` · `plugin` · `other` |
| `fixed-in` | when `status: fixed` | a commit sha that resolves in this repository, or `unrecorded` |
| `gate` | recommended | the path that would catch a regression; `none` if there is not one yet |
| `kind` | optional, defaults to `bug` | `bug` · `perf` · `feature` · `regression` · `apparatus` · `programme` |
| `duplicate-of` | when `status: duplicate` | the slug this duplicates |
| `confirmed` | optional | `no` — fixed but the reporter has not confirmed. **Not a separate status.** |

### `kind` — the subtype axis, as a field and not as a directory

Sergiy asked (2026-07-30) for work to be granulated by TYPE as well as by module, naming
`TASK/v2-performance.md`. The field is that request; `scripts/task-views` is the file.

**Why a field rather than a second directory tree.** The module axis is clean *because it is single
and derivable from where the fix goes*. A second axis in the filesystem gives
"a v2 performance defect in v2-core" two plausible homes — at which point "no slug appears on two
boards" stops holding, and "on neither board" becomes possible. That is not hypothetical: the first
hand-written `TASK/v2-perfomance.md` already contained one entry (six v1 methods over
`HugeMethodLimit`) that is a **v1** defect in a file named v2, with no correct home in that scheme.

`kind` is queryable and combinable, which a filename is not:

```sh
scripts/bugs-report --kind perf --module v2 --status open
scripts/task-views --check                      # the TASK/ documents, regenerated and diffed
```

**Why it is optional and defaults to `bug`.** Back-filling 635 entries automatically would repeat
the mistake `area:` already made: its values were extracted from prose word frequency, and 256 of
621 came out `front` because `parse`, `_err` and `front` appear in almost any report. Ordering of
authority, agreed in the room 2026-07-30: `fixed-in` (a resolvable sha) > a human-declared field >
keyword extraction (**never**). Nothing derives `kind` reliably, so it is declared when an entry is
written or next touched, and the default is stated rather than guessed.

**The deficit this field does not fix, and which the tool now prints.** `--kind perf` over `BUGS.md`
alone answered 3 of 12 known performance items — the other 9 are prose in a `BACKLOG.md`, which has
no header schema at all. Measured 2026-07-30: **635 BUGS entries are queryable and 280 work records
are not (31%)** — 56 unheaded backlog entries and 224 sprint items. A filter that reads one of three
board types looks complete and is not, so `bugs-report` prints that coverage line in its default
summary, and warns on `--kind` specifically. Giving backlog entries the same header is the fix; it
is queued, not done.

### One word for closed

`FIXED`, `DONE` and `RESOLVED` all meant the same thing and split every query three ways. There is
now one: **`fixed`**. The "fixed but awaiting confirmation" nuance is `confirmed: no`, which is a
different question from whether the defect is still present.

`unknown` is deliberate and honest: it marks an entry nobody has classified yet. It is queryable,
so it can be worked off; silence was not.

`fixed-in: unrecorded` is the same idea for the many older entries whose prose says a defect was
fixed but never names the commit. Calling those `unknown` would be a lie in the other direction —
somebody did fix them. The sentinel keeps them queryable as "fixed, provenance missing".

## The prose rule

An entry keeps its original report — that history is worth having. But **a superseded report must
be under `**Original report (superseded YYYY-MM-DD):**` and written in the past tense.**

This is not style. On 2026-07-28 it caused two real incidents: an entry whose original report was
preserved verbatim in the present tense was cited as current truth and a regression was waved
through on it; and an entry led with `**Status:** OPEN, still reproducing` for a defect that had
been fixed hours earlier, because the fix was appended below the stale line. A grep lands on the
first matching sentence, so the first matching sentence has to be true today.

## The gate

`tests/e2e/bugs-index-gate.sh` fails when any of these is false:

- every `## ` entry has a header comment;
- `status` / `lane` / `area` are present and in their enums;
- `status: fixed` carries a `fixed-in` that LOOKS like a sha (7-40 lowercase hex, and not all
  digits — an 11-digit CI run id matches the hex class perfectly, and three were in this file), or
  the literal `unrecorded`;
- and, **only in a full clone**, that the sha actually resolves. CI checks out with `fetch-depth: 1`,
  where `git cat-file` sees no commit but the tip: wired naively, the gate reported 319 of 320 valid
  shas as unresolvable and turned `main` red on its first CI run (30484689408). It now detects a
  shallow clone, checks shape only, and SAYS SO in its output — a gate whose verdict depends on
  clone depth is worse than no gate, but one that quietly checks less is worse still;
- `status: duplicate` carries a `duplicate-of` naming an entry that exists;
- slugs are unique.

Without it this decays again — the state above is what "just keep it tidy by hand" produced over
the life of the file.

**Wired 2026-07-29**, in the same commit that migrated the entries — deliberately not before.
CI lists its e2e scripts explicitly (no directory auto-discovery), so the gate could land first and
simply not run; putting it in `ci.yml` while 618 entries still lacked headers would have left the
shared suite red, which is a broken suite and not discipline. It runs with `--self-test`, so each
CI run also proves the gate can still fail.

## Querying

`scripts/bugs-report` reads the headers and answers directly, so nobody writes another `awk`:

```sh
scripts/bugs-report                  # counts by status
scripts/bugs-report --status open    # every open entry
scripts/bugs-report --lane native --status open
scripts/bugs-report --v2             # the v2 lane view
scripts/bugs-report --module v2      # everything in v2/BUGS.md
```

It walks every `BUGS.md`, which is why `--module` exists. `v2/bugs-analysis.md` (was `bugs-v2.md` at
the root) remains as human analysis, but its **numbers decay** — it still reports a count taken
before several of those entries were closed. Treat `scripts/bugs-report` as the live answer and that
file as commentary; it now carries a banner saying so.
