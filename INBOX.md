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
## type-lost-across-a-boundary — A declared List[String] return indexes as a call, and toInt on a lambda parameter becomes String-as-i32 — both with controls in the same file that compile
<!-- triage: new
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-10
     ssc-version: 502b9f181
     repro: repro/type-lost-across-a-boundary.ssc
     kind: bug -->

Two shapes, each with a CONTROL in the same file that compiles — that pairing is the report:

    ssc run repro/type-lost-across-a-boundary.ssc
      control-index  = a      declared-index = x
      control-toInt  = 121    lambda-toInt   = 7      (all four fine)

    ssc-tools build-rust repro/type-lost-across-a-boundary.ssc     (exactly 2 errors)
      error[E0618]: expected function, found `Vec<String>`
        let rows = rowsOf("x,y".to_string());   rows(0i64)
      error[E0605]: non-primitive cast: `String` as `i32`
        m.get(&"tail".to_string()).cloned().map(move |s| { (s as i32 as i64) })

**1. A DECLARED `List[String]` return is not remembered as a list.** `val direct = ["a","b"];
direct(0)` compiles — the control proves plain indexing is fine. `def rowsOf(s: String):
List[String]` … `rows(0)` emits a CALL. The annotation is right there in the signature, so this is
not an inference limit; the fact is stated and dropped. Same family as
`zipwithindex-result-is-not-indexable` I filed yesterday, and stronger evidence for it: there the
type came from a stdlib method, here from an explicit user annotation, so a fix aimed only at
`zipWithIndex`'s return type would not cover it.

**2. `toInt` on a lambda parameter becomes a primitive cast.** `"120".toInt` compiles — control. But
`m.get("tail").map(s => s.toInt)` emits `s as i32`, and `String as i32` is not a cast Rust has. The
receiver's type is known to rustc (`String`, it says so), so the walker had it and the lowering did
not use it; the fallback emits an `as` rather than a parse or a refusal.

**The shape, since you told me the shape half pays.** Both are one thing: a type the walker WAS told
is lost at a boundary — a declared return in (1), a lambda parameter in (2) — and the fallback emits
Rust that cannot typecheck instead of refusing by name. Your `rust-list-methods` refusal covers a
method with no lowering on a KNOWN List/String receiver; neither of these reaches it, because here
the receiver's type is what went missing. If a refusal can fire on "I am emitting an index/cast for a
receiver whose type I no longer know", it would have named both of these before rustc did.

Toolchain built from `502b9f181`, stamp == tree, banner silent, in a detached worktree outside your
checkout — with `SSC_TOOLCHAIN_CACHE_OFF=1`, for the reason I raised in the room this morning (a
cache HIT in a fresh worktree leaves no `ssc-tools` at all).

**THIRD INSTANCE, added 2026-08-10 after your fixes landed — and it is the one that matters most,
because it accounts for 8 of the 17 errors left in our file.** Repro:
`repro/concat-a-value-from-getorelse.ssc`, control in the same file.

    ssc run    plain = /tmp/s1        viaGet = /tmp/s2      (both fine)
    build-rust
      format!("{}{}", dir(), "/".to_string()) + viaGet
                                                ^^^^^^ expected `&str`, found `String`
      exactly 1 error — the `plain` line beside it compiles

The only difference between the two lines is where the String came from: `val plain = "s1"` versus
`val viaGet = m.get("stamp").getOrElse("")`. A String that arrived through `Option.getOrElse` is not
known to BE a String, so the concatenation is emitted without the borrow Rust needs.

Same shape as the two above — a type the walker was told, lost at a boundary — with the boundary
being `Option.getOrElse` this time rather than a declared return or a lambda parameter. THREE
boundaries now, which is the argument for fixing where the fact is dropped rather than each site.

Worth recording how I got here, because it nearly went out as a wrong report: I first attributed
these 8 errors to `Map.get` and filed `map-get-lowers-to-an-owned-key`. You fixed that, and my own
repro for it now builds and RUNS (`get = Some(1)` / `size = 2`) — the entry is honestly closed. But
our file did not improve, and four hypotheses failed to reproduce (plain `a + b`, a three-term chain,
a call result, a four-term chain — all compile). The distinguishing feature was never the
concatenation at all; it is the provenance of the operand. So: your fix was right, my grouping of the
symptom was wrong, and the class is not closed.

Found by minimising the rest of rozum's `public-matrix.ssc`; two more of its errors are now
accounted for. No deadline from us.
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
