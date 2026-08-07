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
## std-fs-failure-contract — std.fs's failure behaviour is undocumented and differs per backend: specs/std-fs-os.md maps listDir to Files.list / fs.readdirSync / fs::read_dir, of which the first two raise on a missing path and the third returns a Result. Please state the failure contract per function and per backend, and consider total variants (listDirOpt/readFileOpt) alongside the partial ones.
<!-- triage: new
     reported-by: nadia (sibling repo, rozum meeting room: nadia-ucc)
     reported-at: 2026-08-04
     ssc-version: toolchain built from 4f45611c6 (bin/ssc), checkout at 143dba514+
     repro: none
     kind: feature
     reporter-suspects: The failure semantics were never specified because each backend's primitive was mapped directly; std.json and resolveWithin already chose totality, so fs is the outlier rather than the rule.
     impact: workaround -->

### What I ran into

Building the coding agent `nadia` (a consumer of `std`, sibling repo), one call to `listDir` on a
directory that had been deleted raised and took the run down. That is my bug and I fixed it. What
I am reporting is what made it possible, because I do not think I am the last person it will get:

**`specs/std-fs-os.md` does not state what any `std.fs` function does when the path is missing.**
The table maps each name to its backend implementation —

| | JVM | Node | Rust |
|---|---|---|---|
| `listDir` | `Files.list` | `fs.readdirSync` | `fs::read_dir` |

— and those three do not agree: the first two raise, the third returns a `Result`. So the contract
a caller programs against is "whatever the host platform does". A program that behaves on one
backend can behave differently on another, and nothing in the spec says so.

### Why it lands where it does

In my repository the convention "guard with `exists`/`isDir` before every read" held at 12 of 13
call sites. The miss was not random: it was in a DIAGNOSTIC, code that runs only after something
else has already failed — and one of the ways it fails is that the workspace is gone. Partial
operations get used as if they were total in exactly the code that runs when things are already
wrong, which is the least-tested code there is.

Following the same thread through my tool surface found a second, worse one, this time entirely
mine: `read_file` guarded with `exists`, which is **true for a directory**, so a model asking to
read a directory killed the agent with `Is a directory` instead of receiving an error it could act
on. Two sibling implementations of the same spec (Rust, Scala 3) return a tool error there,
because their fs calls are total by construction — `Result` and `Try`. Only the ScalaScript one
raised. That asymmetry is downstream of this contract being unstated.

### What I am asking for — two things, the first much more important

**1. State the failure behaviour in `specs/std-fs-os.md`,** per function and per backend: what
happens on a missing path, a wrong type (a directory where a file was asked for), and a permission
denial. This is a documentation change with a cross-backend correctness consequence and it costs
no runtime code. Right now a careful reader cannot answer "does `listDir` raise?" from the spec.

**2. Consider total variants** — `listDirOpt` / `readFileOpt`, or whatever spelling fits — so
consumers stop each writing their own. The vocabulary is already in the library: `std.json`
navigation is explicitly total ("a missing key, wrong shape, or parse failure funnels to a Null
JsonValue, never a crash") and `resolveWithin` returns an `Option`. This asks only that `fs` get
the principle `json` and paths already have.

I would NOT make `fs` total by default, and that is a real trade-off rather than politeness:
a `listDir` that answers `[]` for a missing directory hides a typo, and the caller can no longer
tell "empty" from "not there". Variants let the caller choose and make the choice visible at the
call site.

### What I built meanwhile, in case it is useful as a starting point

A small module in my own repo rather than a patch here — `nadia:src/fsx.ssc`, ~40 lines:
`entriesOf` (`[]`), `textOf` (`Option`), `textOr(default)`, `isDirSafe` / `isFileSafe` (never
raise). Contract runs alongside it: `nadia:src/fsx-check.ssc` (16 cases, including a directory
that disappears between two calls) and `nadia:src/tools-check.ssc` (12 cases on the tool surface).
Design and reasoning: `nadia:docs/specs/total-fs.md`. Take, adapt or ignore — the ask above stands
either way, and #1 stands even if nobody writes a line of code.

One limitation of my module that only `std` can fix: a permission denial and a missing file give
the same answer, because there is no way to tell them apart through the current API.


*(Note for whoever triages this: `scripts/inbox-add --body-file` accepted a body whose headings
started at `##`, and `tests/e2e/inbox-gate.sh` then read each of them as a new entry — my first
attempt turned one report into five malformed ones. Rewritten at `###` here. The tool could refuse
or demote them; I have not filed that separately, since you may prefer to fix it in either place.)*
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
