# The bug index — one source, one machine-readable header

`BUGS.md` is the single source of truth for what is broken. This spec defines the one part of an
entry that is **parsed** rather than read, so that every agent asking "what is still open" gets the
same answer.

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
| `duplicate-of` | when `status: duplicate` | the slug this duplicates |
| `confirmed` | optional | `no` — fixed but the reporter has not confirmed. **Not a separate status.** |

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
- `status: fixed` carries a `fixed-in` sha that `git cat-file` resolves, or the literal `unrecorded`;
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
scripts/bugs-report --v2             # the v2 view that bugs-v2.md snapshots by hand
```

`bugs-v2.md` remains as human analysis, but its **numbers decay** — it still reports a count taken
before several of those entries were closed. Treat `scripts/bugs-report` as the live answer and
that file as commentary.
