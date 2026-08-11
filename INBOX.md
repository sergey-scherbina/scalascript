# INBOX — reports from people who USE ScalaScript, before anyone knows where the fix goes

**The rule is [`POLICY.md`](POLICY.md) P-3.10. The mechanics are
[`specs/work-tracking-layout.md`](specs/work-tracking-layout.md) §Inbound queue. This file is the
queue itself.**

Register with the tool, never by hand — it is what keeps the fields well-formed:

```sh
scripts/inbox-add <slug> --summary "…" --reported-by <who> --ssc-version <v> [--repro <path>]
scripts/inbox-add --list                 # what is waiting, oldest first
tests/e2e/inbox-gate.sh                  # what this file must satisfy
```

## Why this file exists at all

Every other record in this repo is born already routed: `lane:` and `area:` are judgements about
**where the fix goes**, and an agent makes them when filing. A user's report cannot carry them. The
reporter knows a symptom and a version; the module is a conclusion someone reaches later, by reading
code. Before this file there was nowhere to put a report that had not reached that conclusion yet, so
the choice was to guess a module or to lose the report.

**This is the one place an entry may exist without a module**, and it is a STATE, not a subtype —
`kind:` still classifies the report the same way it classifies everything else (P-3.8).

**Everything the reporter offers is kept, diagnosis included.** That is a correction, made
2026-07-31 after users said the form was too narrow, and they were right. The original wording told
reporters not to diagnose — which threw away real work (a bisect, a read of the source, a workaround
that failed) because of a rule that was never about their information at all. What P-3.3 forbids is
routing ON a guess; it says nothing against RECORDING one. So a reporter's reading of the cause goes
in `reporter-suspects:` and in the body, verbatim, and the triager still reaches their own conclusion
before `lane:`/`area:` are written. Information and authority are different things, and only the
second was ever meant to be constrained.

## What leaves this file, and how

- **Routed** — the entry MOVES into `<module>/BUGS.md` (or `BACKLOG.md`) of the module where the fix
  goes (P-3.2), gains `status`/`lane`/`area`/`gate`, and is **deleted from here**. Two copies of one
  record is the exact failure the whole policy exists to prevent.
  The reporter fields — `reported-by`, `reported-at`, `ssc-version` — **travel with it**. They are
  not decoration: `confirmed: no` in the header schema already means "fixed, but the reporter has not
  confirmed", and until now nothing recorded WHO to ask.
  There is deliberately no "routed" list here: it would be a second copy. The routed set is
  DERIVED — every entry anywhere carrying `reported-by` came from a user (P-3.5).
- **Closed without routing** — `duplicate` or `not-a-defect` produce no destination entry, so they
  are the one outcome nothing else can record. They get ONE line in the section below, with the
  reason. Not the report body; a line.

**Nothing is allowed to sit OUTSIDE the queue either.** `inbox-gate` also lists open `user-report`
issues and fails on any whose URL appears nowhere — not here, not as a `reported-by:` on a board.
Without that, the age limit would only govern reports that already got imported, and "lost politely"
would simply relocate to GitHub instead of being removed. Needs `gh`; without it the check says so
out loud rather than passing quietly, because a network check that silently becomes a no-op is worse
than one that is missing.

## Queue

<!-- inbox-entries:start — `scripts/inbox-add` appends here; the gate parses this region -->
## std-corpus-does-not-lower-to-rust — Surveyed all 131 std modules through build-rust: 31 compile, 75 are honestly refused, 25 emit Rust rustc rejects — and 8 of those 25 are one E0562 across std/ui
<!-- triage: new
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-11
     ssc-version: 1334bea8f
     repro: repro/std-rust-survey.tsv
     kind: feature -->

Not a defect report — a MEASUREMENT nobody had taken, offered because five of my six reports were
found the same way: by building a real program on this lane. Your own standard library is 131 real
programs, and none of them had been through `build-rust`.

Method: every `std/**/*.ssc` through `ssc-tools build-rust`, one at a time, on a toolchain built
from your current main in a detached worktree outside your checkout. Full table attached as
`repro/std-rust-survey.tsv`.

    REFUSED   75  (57%)  the backend says it cannot lower this — CORRECT behaviour, a coverage gap
    COMPILES  31  (24%)  lowers and compiles (no binary only because a lib has no `main`)
    BADRUST   25  (19%)  emits Rust that rustc rejects — the class I keep reporting

**Read the first two columns before the third.** 57% is not "broken": it is the lane telling the
truth about what it does not implement yet — `extracts X with 1 args` (11), `unsupported infix
operator` (9), `Array[Byte]` (3), `Parser[T]` (3). Those are a roadmap, not bugs.

**The 25 that emit bad Rust are concentrated, which is the useful part.** Nine are ONE shape —
`error[E0562]`, `impl Trait` in a position Rust does not allow — and eight of those nine are
`std/ui/*`: containers, display, form, input, layout, nodes, state, typography. One fix, eight
modules. Two more are nastier than a type error: `std/dsl/pretty.ssc` emits Rust that does not
PARSE (`expected identifier, found +`), and `std/ui/theme.ssc` blows the macro expander
(`recursion limit reached while expanding format!`).

**And the pattern that explains all of it.** Inside `std/ui`, three modules compile —
`primitives.ssc`, `offline.ssc`, `webauthn.ssc` — and nine do not. `primitives.ssc` is the one
rozum's `clients/meeting/meeting.ssc` actually imports, and I verified a week ago that it builds a
1.7 MB binary that serves. So on this lane the modules someone has BUILT work, and the modules
nobody has built do not. Coverage has been following use, one program at a time, which is exactly
why an outsider with one real program has been able to find five defects in two days.

The cheap structural answer, if you want one: this survey is a gate. It needs no fixtures — the
corpus is your own std — and its useful assertion is not "everything compiles" but "the BADRUST
column does not grow". A module that moves from REFUSED to BADRUST is a regression; one that moves
from BADRUST to REFUSED is progress.

**Correction I owe you, because the first number I computed was wrong.** My first pass reported
131/131 failing. That was a methodology artefact: a library module has no `main`, so `build-rust`
exits non-zero with "expected binary not found" AFTER compiling it cleanly, and I counted the exit
code instead of reading the log. 31 modules were fine all along. I caught it because a 100% result
is likelier to mean a broken measurement than a broken world — the same shape as your toolchain
cache shipping green because the publishing checkout already had its launchers on disk.

Toolchain: your main, banner silent, digest matching. Nothing in your tree was touched.
<!-- inbox-entries:end -->

## Closed without routing

<!-- inbox-closed:start -->
<!-- inbox-closed:end -->

## Entry format

```markdown
## <slug> — <the summary in the REPORTER's words, not yours>
<!-- triage: new
     reported-by: <handle, address, or issue URL>
     reported-at: 2026-07-31
     ssc-version: 1.72.0
     repro: examples/reported/foo.ssc
     kind: bug -->

Everything they wrote, in their terms and at their length — what they ran, what happened, what they
already tried, logs, their theory, the workaround that did not help. Do not compress it into what you
think matters: you are reading it before you know which part turns out to be the clue. Add your own
findings under a separate heading rather than editing theirs, so the two never blur.
```

| field | required | values |
|---|---|---|
| `triage` | always | `new` · `needs-info` |
| `reported-by` | always | a handle, an address, or a URL — something that can be replied to |
| `reported-at` | always | `YYYY-MM-DD` |
| `ssc-version` | always | what they ran; `unknown` is allowed and is itself information |
| `repro` | always | a path to a minimal case, or `none` |
| `kind` | optional | as in `specs/bugs-index.md`; defaults to `bug` |
| `waiting-on` | when `triage: needs-info` | what was asked of the reporter, and when |
| `reporter-suspects` | optional | THEIR hypothesis about the cause, in one line. Explicitly not a routing decision — see below |
| `impact` | optional | `blocks` · `workaround` · `annoying` · `fyi`, as the reporter judged it |

**`lane:` and `area:` are absent on purpose, and that is NOT a limit on what a reporter may say.**
Those two fields carry the ROUTING DECISION, whose authority order is fixed (P-3.3: a resolvable
`fixed-in` > a human's declared judgement > never keyword extraction). An inbox entry has not reached
that decision yet, so the fields stay empty — writing them to look complete is how four entries
landed under the wrong owner.

A reporter's own diagnosis is a different thing entirely and is **welcome**: it goes in
`reporter-suspects:` and in the body. The gate refuses `lane:`/`area:` here and accepts
`reporter-suspects:` precisely so that the distinction is mechanical rather than a matter of
etiquette — you can say anything, and nothing you say can silently become the routing.
