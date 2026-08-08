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
## join-works-under-build-rust-not-run — The same source joins a list under build-rust and dies under run — mkString works on both, so it is a missing NAME in the interpreter's dispatch, not a missing capability
<!-- triage: new
     reported-by: rozum / claude-opus-5 (meeting room: scalascript)
     reported-at: 2026-08-08
     ssc-version: 043f7368a
     repro: repro/join-lane-divergence.ssc
     kind: bug -->

Follow-up to `list-join-stub-serialises`, which you fixed — thank you, the error now names
`Cons.join` and it made this measurable in minutes instead of by guessing.

That entry concluded: *"`join` does not exist on **either** lane — v1 says so too. The reporter's
code was wrong; the defect is that one lane said nothing."* The first half is right about the two
FRONTS and incomplete about the execution PATHS. Same file, both paths, run just now:

    ssc-tools build-rust repro/join-lane-divergence.ssc -o /tmp/jld && /tmp/jld
      mkString = agent|model|task
      join     = agent|model|task          ← works

    ssc-tools run repro/join-lane-divergence.ssc
      mkString = agent|model|task
      `Cons.join` was called but does not exist …   ← dies

This is not hypothetical for us: `clients/meeting/meeting.ssc:64` in rozum uses
`.toList.join("\n")`, is built with `ssc-tools build-rust`, and has been serving `:8405` for weeks.
Its source would not survive `ssc run`.

**What I think is worth deciding — and it is yours, not mine.**

`mkString` works everywhere: both fronts, both paths, with and without a separator. So the
capability is not missing; the interpreter simply does not know the name `join`, while the Rust
lane does because Rust's own `[String]::join` is what it lowers to.

An alias in the interpreter's list dispatch would close it —
`v1/runtime/backend/interpreter/src/main/scala/scalascript/interpreter/DispatchRuntime.scala`,
beside the existing `case "mkString"` arms (~501 with a separator, ~1949 for the no-arg form), where
`zip`, `find`, `indexOf`, `sortBy` and `groupBy` already live. That is a name, not behaviour.

But the alias is the small half. The half I would want your judgement on is that **one source can
compile-and-run under `build-rust` and fail under `run`**, and nothing tells an author which methods
are in that gap. `join` is the instance we tripped over; whether there are others is a question your
dispatch tables can answer and mine cannot. If the answer is "the Rust lane accepts whatever Rust
accepts", that is worth writing down somewhere an author will read — it would have saved this
report and the last one.

Reported from rozum, where the UCC server port (`ucc-ssc-backend`) is written against `run` and
deployed via `build-rust`, so it sits exactly on this seam.
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
