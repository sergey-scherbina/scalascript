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
## no-paren-list-method-becomes-a-field — headOption on a list is emitted as a Rust field access, so it lands as 'no field headOption on Vec' — the by-name refusal covers calls but not no-paren member access
<!-- triage: diagnosed
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-10
     ssc-version: 7fea7a711
     repro: repro/no-paren-list-method-becomes-a-field.ssc
     kind: bug -->

Four lines, control on the line above the defect:

    ssc run    control = 2      defect = bb          (both fine)

    ssc-tools build-rust
      error[E0609]: no field `headOption` on type `Vec<String>`
        kept.headOption.unwrap_or("-".to_string())

`kept.length` on the line above compiles, so list methods as such are fine. `headOption` is
written WITHOUT parentheses, so it arrives as a select rather than an apply, and this lane emits
it as a Rust FIELD access on the `Vec` — a field no `Vec` has.

**This is the shape you already fixed, reaching one form further.** `rust-list-methods` made an
unlowered method on a known List/String receiver refuse BY NAME instead of passing through, and
that is what makes the next instance cheap to find. It fires on a method CALL; a no-paren
member access slips past it and becomes a field. Two consequences worth separating:

  * `headOption` has no lowering (`lastOption`, `head`, `tail`, `isEmpty` are worth checking for
    the same reason — I did not, so treat that as a guess, not a report).
  * Whatever the answer for `headOption`, the REFUSAL should cover the no-paren form too, or the
    next one lands on a user as `no field X on type Vec<...>` again.

Rozum's `public-matrix.ssc` reaches this through `hit.map(...).getOrElse("null")` where `hit`
came from `.drop(1).filter(...)` — the same family, arriving as `unwrap_or` on a `Vec<String>`.

Worth recording how I got here, because I nearly filed it as my own mistake: I read
`hit.map(...).getOrElse(...)` on a List, concluded `getOrElse` belongs to Option, and "fixed" my
source with `.headOption`. That made it WORSE (4 errors, and rustc then called the receiver
`Option<String>`), which is what sent me to a minimal repro instead of a second guess.

Toolchain built from `7fea7a711` — your main with both merged rozum fixes — stamp == tree, banner
silent, detached worktree outside your checkout.

Measured effect of those two on our file, since it is the honest way to say thank you:
**33 errors at the start, 16 yesterday, 2 now.** One of the two is the `Request` handler branch;
this is the other. No deadline from us.
## option-bound-to-a-val-is-not-tracked — An Option from find() lowers correctly inline but not through a val — the chain over the bound name becomes a list map, so getOrElse lands as unwrap_or on a Vec
<!-- triage: new
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-11
     ssc-version: 62bf49839
     repro: repro/option-bound-to-a-val-is-not-tracked.ssc
     kind: bug -->

Five lines, control on the line above the defect, and the two differ only by a `val`:

    ssc run    inline = bb!    bound = bb!          (both fine)

    ssc-tools build-rust
      error[E0599]: no method named `unwrap_or` found for struct `Vec<String>`

`xs.find(p).map(f).getOrElse(d)` written inline COMPILES. Bind the same `find` to a name first
and the chain over that name lowers as a LIST map — so `getOrElse` becomes `unwrap_or` on a
`Vec<String>`, which has no such method.

So the Option-shape is known while the expression is whole and lost the moment it passes through
a binding. The walker has `localSeqs` and `localStrings` — sets of local names whose type it
remembers — and there is no `localOptions` beside them. That is the shape; whether the fix is a
fourth set or something narrower is yours to judge.

**Related to work already in flight, which is why I am flagging rather than guessing:** the branch
`feature/rust-no-paren-member` carries `f8e7f6ba8` — "getOrElse is the UNWRAP, so it does not keep
the Option" — which splits `getOrElse` out of the `isOptionExpr` case it shared with `map`/`flatMap`.
That is the same function this report lands in. Its claim `rust-no-paren-member` has not
heartbeat since 2026-08-10T20:43 (~8h) though the commit on it is timestamped 22:59, and its
worktree is still there. I have not touched any of it: a stale claim with work behind it is the
case your own triage table says to ask about rather than take.

The rest of that entry IS fixed and verified here — `repro/no-paren-list-method-becomes-a-field.ssc`
now builds AND runs (`control = 2`, `defect = bb`), matching `ssc run`. Thank you.

Toolchain built from your current main, banner silent, digest matching, detached worktree outside
your checkout.

This is the last error in rozum's `public-matrix.ssc` — 33 at the start of yesterday, 1 now, and
it is this one. No deadline from us; the slice has waited a week and can wait longer.
<!-- inbox-entries:end -->

**TRIAGED 2026-08-10 — reproduced, and the emitting line found.**

`bin/ssc-tools build-rust repro/no-paren-list-method-becomes-a-field.ssc` fails exactly as
reported: `error[E0609]: no field 'headOption' on type 'Vec<String>'`.

**The line is `RustCodeWalk.scala:1994`**, the catch-all of `renderTerm`:

    case m.Term.Select(qual, m.Term.Name(field)) =>
      renderTerm(qual, ctx).map(q => s"$q.$field")

Any no-paren select becomes a Rust FIELD access. That is right for a case-class field and wrong for
everything else, and nothing between it and the emitted text asks which the receiver is — so the
report's second point is the load-bearing one: whatever `headOption` gets, the REFUSAL has to reach
this line, or the next unlowered no-paren member arrives at a user as `no field X on type Vec<…>`.

**`headOption` itself is small**: on a `Vec<T>` it is `.first().cloned()`, which is an `Option<T>`,
and the `.getOrElse("-")` beside it already lowers to `unwrap_or`. The reporter's guess that
`lastOption`, `head`, `tail`, `isEmpty` want checking is worth taking seriously — they reach the
same line — but each needs its own check, since `isEmpty` is a method on `Vec` and would compile
today while `head` would not.

**NOT FIXED HERE: `RustCodeWalk.scala` is held by the live claim `rust-topval-intrinsic`**, taken
six minutes before I looked. Diagnosing without editing is what could be done honestly; whoever is
in that file next has the line number and the shape.

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
