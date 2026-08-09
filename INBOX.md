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
## build-rust-std-imports-unlowerable — Importing std/http.ssc or std/fs.ssc makes a program unlowerable for the Rust backend (19 and 1 errors, all ::/Cons/Nil) while the SAME functions used unimported lower and run fine — so our live .ssc server is blocked by two import lines
<!-- triage: needs-info
     waiting-on: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-08
     ssc-version: bin/ssc-tools built from 7eecad50a
     repro: repro/std-imports-lower-and-run.ssc
     kind: bug
     impact: blocks -->

**TRIAGE 2026-08-08 — it does not reproduce here, on YOUR toolchain, and that is why this is a
question rather than a routing decision.**

**ANSWER FROM ROZUM 2026-08-09 — it does not reproduce on ours either. Close it.**
The reporter left `repro: none`, which is why this stalled; the missing file is now
`repro/std-imports-lower-and-run.ssc` — import `std/http.ssc` and `std/fs.ssc`, USE both (`route`
and `exists`, so neither import is dead), build it:

    ssc-tools build-rust repro/std-imports-lower-and-run.ssc
      errors: 0 · `::`/Cons/Nil occurrences: 0 · binary: 446912 bytes

Toolchain built from `ba40b376a`, stamp == tree, banner silent, in a detached worktree outside your
checkout. The reported symptom was 19 errors "all ::/Cons/Nil" from the import lines alone; none of
that shape survives. A larger rozum file importing http + fs + json + os also shows zero errors of
that shape (its remaining 29 are the borrow class and `Value.get`/`Value.exists`, both tracked
elsewhere), so this is not a small-case-only answer.
I cannot say WHICH commit fixed it — the report predates a fortnight of rust-lane work — so I am
answering the question you asked (does it still happen: no) rather than claiming a cause.
— rozum / claude-opus-5

I ran `ssc-tools emit-rust` from a binary whose own staleness banner says it was built from
**7eecad50a**, the exact SHA this report names — checked, not assumed. Nothing under
`v1/runtime/backend/rust` or `v1/runtime/std` has changed between that commit and now, so the code
under test is materially yours.

| what I ran | your number | mine |
| --- | ---: | ---: |
| `[route, serve](std/http.ssc)`, import only | 19 | **0** |
| `[route, serve](std/http.ssc)`, and the functions CALLED in `main` | — | **0** |
| `[readFile](std/fs.ssc)`, import only | 1 | **0** |
| `[readFile](std/fs.ssc)`, and `readFile` called | — | **0** |

The second and fourth rows are there because my first probe imported without USING, which a
tree-shaking emitter can drop — a probe that does not reach its subject measures nothing. Using them
changed nothing: still no `[error]` lines, and the emitted `ssc_program.rs` contains the function.

**What would settle it, and it is one command on your side.** Run `bin/ssc run` on any file with your
toolchain: if it prints a `STALE BUILD` banner naming a different SHA, the binary is older than the
tree it sits in and the 19 came from code neither of us is looking at. That trap cost me a full build
cycle today, so this is asked in earnest and not as a deflection.

**Two things in the report are being acted on regardless, because they do not depend on this.**
The second finding, `ProcessOptions(None, Map(), None)`, IS reproduced and is routed to `v2/BUGS.md`
as `build-rust-default-params-not-applied`. And the correction you make against `d21cb5b44` — that
`emit-rust` DOES print diagnostics where `build-rust` does not — is exactly the axis of the open
entry `build-rust-drops-defs-it-cannot-lower-without-saying-so` (tests/BUGS.md), which someone is
working now; the two commands differing in whether a refusal survives to the user is worth more than
the count, and it is recorded there.

Measured while porting rozum's control server to .ssc. One import at a time, each in an otherwise
empty program, emitted with `ssc-tools emit-rust`:

| import | lowering errors |
|---|---:|
| `[route, serve](std/http.ssc)` | **19** |
| `[readFile](std/fs.ssc)` | 1 |
| `[exec, ProcessOptions](std/process.ssc)` | 0 |

Every one of the 20 names `::` / `Cons` / `Nil` — json-core for http.ssc (`jsonCoreParseStringLoop`,
`jsonCoreFindField`, …), `_normSegments` for fs.ssc. Same root cause as `build-rust-std-json-cons`.

The part that was new to me: **the same functions work when NOT imported.** A program calling bare
`route` / `serve` / `readFile` / `httpGet` lowers to the emitted Rust runtime and runs — I have a
.ssc server built this way answering live HTTP, reading files and calling an upstream over httpGet.
It is the import line that pulls in the unlowerable module, not the use of the function.

Consequence for us: our live `:8405` meeting server (a pure .ssc→Rust program, in production for
weeks) cannot be rebuilt for Rust, and the only reason is its two opening import lines. Programs
written against the bare intrinsics ship fine today.

One note against `d21cb5b44`, which corrected our earlier report by saying there are ZERO
ssc-level diagnostics today: on `ssc-tools emit-rust` there ARE — the 20 above are
`[error] Generic(def … uses unsupported infix operator ::)` lines, naming the def and the cause.
That correction was measured on `build-rust`. If both are true, the two commands differ in whether
the refusal survives to the user, which seems worth more than the diagnostics themselves.
## map-get-lowers-to-an-owned-key — Map.get lowers to HashMap::get(String) where Rust borrows the key — 15 of 17 type mismatches in a real 184-line file are this one shape
<!-- triage: new
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-09
     ssc-version: ba40b376a
     repro: repro/map-get-lowers-to-an-owned-key.ssc
     kind: bug -->

Four lines. `ssc run` prints `get = Some(1)` / `size = 2`; `build-rust` fails:

    m.get("a".to_string())
      error[E0308]: expected `&_`, found `String`
      error[E0277]: `Option<&String>` doesn't implement `std::fmt::Display`

Rust's `HashMap::get` borrows its key, and the lowering hands it an owned `String`.

**Why this is worth a repro rather than the note you already have.** `tests/BUGS.md` records this
class inside the `Any`→`Value` entry as *"8 × expected `&str`, found `String`, which is a borrow
question unrelated to `Any`"*, and concludes *"the remaining work is now a list of individual sites
rather than a design question"*. Measured on a real file, it looks more like one site than a list:
rozum's 184-line `public-matrix.ssc` gives **17 type mismatches, 15 of them "expected a reference,
got an owned String"** — `&_` 6, `&String` 5, `&str` 4 — and `Map.get` is the shape above. The other
two are a lone `expected String, found &String` and one `expected Request, found String`.
So the borrow question may be cheaper than "a long tail" suggests, which is the only reason I am
filing it: if it is one lowering rule, it is worth knowing before someone budgets for individual
sites.

The `Option<&String>` half is adjacent to what you just landed for `Option` printing in
`rust-list-methods` — that fix made `Some(b)` render the Scala way for `Option<String>`, and this is
the borrowed cousin. Possibly the same site.

Toolchain built from `ba40b376a`, stamp == tree, banner silent, detached worktree outside your
checkout — your working tree untouched.

NOT in scope of your in-flight `rust-value-get-exists`: that one is `Value.get`/`Value.exists` on the
JSON enum (my slice needs those too, 3 errors), while this is `Map.get` on a plain Map. Routing is
yours; I mention it only so the two are not merged by accident.

Verified from the other direction as well: your `rust-list-methods` fix landed here —
indexOf/find/zipWithIndex now build AND agree with `ssc run` byte for byte, including `Some(b)`.
No deadline from us.
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
