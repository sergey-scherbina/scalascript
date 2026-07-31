# Why 89 open bugs have no gate — measured, 2026-07-31

`scripts/bugs-report --no-gate --status open` returns **89 of 108**. Five in six known defects have
nothing that would catch them coming back. That number has been quoted for two days as "the real
backlog"; this is what is actually behind it.

## The finding

Every open, gateless entry, classified by what it carries:

| | | |
|---|---|---|
| **64** | **71 %** | **no code at all** |
| 11 | 12 % | a runnable ```scalascript``` repro |
| 10 | 11 % | a code block that is not runnable (IR dumps, emitted JS, tables) |
| 4 | 4 % | a shell repro (`bin/ssc …`) |

**A gate cannot be written for a defect that cannot be run.** The missing gates are not missing
effort or missing ownership — they are downstream of missing REPRODUCTIONS. Writing 89 conformance
cases was never the available task.

## What running the 11 showed

All 11 runnable repros were executed on the `int` lane:

- **2 still error** — `int-imported-module-mutable-registry-not-shared`,
  `typer-defines-sys-but-no-runtime-provides-it`. Certainly live, and gateable today.
- **9 run clean**, which proves nothing on its own: most describe a WRONG ANSWER or another lane, and
  `rc=0` cannot see either. They need the lane named in the entry and an oracle to compare against.

So of 89, the number that is gateable-as-written is at most 15 (11 + 4 shell), and 2 are gateable
immediately.

## The rule this earns

**An entry without a runnable reproduction is not actionable**, and should be treated the way a
missing `status:` is — a defect in the tracking, not a stylistic lapse. Making it a required field is
the natural move, and `tests/e2e/bugs-index-gate.sh` already has the machinery.

It cannot be turned on today: 64 entries would fail at once, and a gate that is red on `main` is a
broken shared suite, not discipline (the same mistake was made this week with the NUL gate, which
landed while four files still carried one). So:

1. **Require it for NEW entries.** The gate can scope by a date in the header or by "entries added
   after this commit", so it binds going forward without a flag day.
2. **The 64 are a backlog, not a bug.** Each needs somebody to reproduce it — which for many will end
   in "no longer reproduces", and closing those is worth more than any gate: one entry on this board
   was found fixed three days earlier and still open, and discovering that cost a claim, a worktree
   and a full build.

**I am part of the 64.** `js-char-is-a-plain-string`, filed an hour before this measurement, carries a
measured table and no runnable fence. The habit is easy to keep and easy to skip, which is exactly
why it belongs in a gate rather than in a rule nobody re-reads.

## What NOT to do

Do not write gates for the entries that merely look gateable. Nine of the eleven runnable repros
"pass" on the reference lane, and a case built from one of those would be **green whether or not the
defect exists** — the shape this repository keeps paying for. A gate needs the failing lane and the
expected answer, and an entry that names neither cannot produce one.
