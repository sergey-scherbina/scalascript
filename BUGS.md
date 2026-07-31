# Cross-module bugs

Per-module bug files live in the modules — see `specs/work-tracking-layout.md` for the
layout and `specs/bugs-index.md` for the entry format. **This file holds only entries that
no single module owns**: the same defect present in more than one implementation, where the
root is the nearest common ancestor. It is not a leftovers bin — if an entry here turns out
to be one module's, move it and change its `lane:`.

Query across every file at once:

```sh
scripts/bugs-report                        # counts by status, per module
scripts/bugs-report --module v2 --status open
scripts/bugs-report --no-gate              # open entries with no regression gate named
```

Newest first.

## typer-defines-sys-but-no-runtime-provides-it — `sys.env` type-checks, then dies at runtime on every lane
<!-- status: open
     lane: multi
     area: runtime
     gate: none -->

**Found 2026-07-31** by `skip-reprobe-after-fixes`, while re-probing the corpus SKIP list.

`v1/lang/core/.../Typer.scala:249` defines the symbol:

```scala
s.define(Symbol("sys", SType.Named("sys", Nil), SymbolKind.Object))
```

No runtime provides it. One line, both lanes:

```scalascript
def main() = println(sys.env.getOrElse("HOME", "none").length > 0)
```

```
int  [ERROR] [line 1, col 22] Undefined: sys
v2   ssc: unbound global: sys
```

**The shape is what makes it worth filing:** the type system PROMISES a symbol that nothing delivers, so a
program using standard Scala `sys.env` passes type-checking and fails only when it runs. That is worse than
an honest "unknown name" at compile time, and it is not specific to `sys.env` — anything reached through
that symbol behaves the same way.

**Four corpus cases use it** (`x402-cardano`, `x402-cardano-scalus`, `x402-client`, `x402-server`) and it is
their FIRST blocker. ⚠️ Fixing it would not un-skip any of them: all four also need network, so they belong
to the "cannot run headless" bucket either way. Recording that here so nobody spends the work expecting a
coverage win.

**Two honest resolutions, and the choice is a decision:** provide `sys.env` (ssc's own environment surface
today is the `Env` effect, so this would be a second way to do one thing), or remove the typer symbol so the
failure moves to compile time where it belongs.

## std-has-no-stdin-primitive — nothing in std reads a line from stdin, so an interactive `.ssc` program cannot exist
<!-- status: open
     lane: multi
     area: runtime
     kind: feature
     gate: none -->

**Status:** OPEN. Filed 2026-07-31 by `nadia-dev` (rozum side), blocking an external consumer.

> **Arrived by the wrong door — the report is [#76](https://github.com/sergey-scherbina/scalascript/issues/76).**
> This entry was hand-written straight onto a module board, which P-3.10 reserves for
> conclusions a reporter has not reached; a report from outside enters through the issue
> form and `INBOX.md`. Kept only because it carries the proposed surface and implementation
> order below, which the issue deliberately omits (the form asks reporters not to diagnose).
> **Triage owns the merge**: route #76 and drop or absorb this. Do not work both.

`std.os` exports `env` · `envOrElse` · `args` · `exit` · `cwd` · … — the whole environment
surface **except input**. There is no `readLine`, no `stdin`, no console module: the only
occurrence of the word in the tree is a comment in `runtime/std/free.ssc:97` (“production:
println / readLine”). Output has `println`; input has nothing.

The consequence is categorical rather than cosmetic: **no `.ssc` program can prompt a user
and read the answer**, on any lane. A REPL, an interactive CLI, a confirmation prompt, a
wizard — none of them are expressible today. Everything else the shape needs is already
here (`std.process.exec`, `std.fs`, `std.actors`, `std.http`), which is what makes this the
single missing piece rather than one of several.

**Concrete blocked work.** `nadia` (see rozum's `REPOS.md`) is an agent whose interactive
mode is a line-based REPL over `std.agent`. Its batch half is written and works; the
interactive half cannot be written at all. The Rust twin (`rozum:crates/nadia`) ships the
same REPL in ~40 lines because `std::io::stdin().read_line` exists.

**No workaround on the default lane.** A ` ```scala ` passthrough block does not help:
the standard tier is a self-hosted VM, not Scala-on-JVM, so `java.io` is unbound there
(`ssc: unbound global: java`). Reading stdin through `exec` (`exec("head", ["-n1"], …)`)
inherits no terminal and returns immediately.

### Shape of the fix

Exactly the one landed a day earlier for the sibling gap — `f101312ed` *"implement `exec`
on the native tier — std.process was missing from the DEFAULT lane"* — and that commit is
the template, down to the ordering:

1. Declare the primitive in `runtime/std/os.ssc` (or a new `std/console.ssc` if input
   deserves its own module) and add it to `exports:`.
2. Implement it per lane: `int`, `js`, `jvm`, `native`. On the native tier it is the same
   `QualifiedName(...) -> native { … }` registration `exec` now uses.
3. A conformance case that reads a line and echoes it, driven with a here-string so it is
   deterministic — that is the `gate:` this entry currently lacks.

Suggested surface, with the EOF case explicit so callers are forced to handle it rather
than discovering it as an empty string:

```scala
extern def readLine(): Option[String]   // None at EOF
```

`readLine(prompt: String)` is deliberately **not** proposed: prompting is `print` +
`readLine`, and folding them together makes the primitive untestable without a terminal.

### Not to be confused with

`std.process.exec` being unbound on the default lane, which **is already fixed**
(`f101312ed`). It was re-reported from the rozum side on 2026-07-30 against a toolchain
built at `ff493301c` — i.e. before the fix, with `SSC_NO_BUILD_CHECK=1` silencing the
launcher's own staleness warning. That report was wrong; this entry is not the same gap.

## std-ui-fetchUrlSignalTo-declared-never-implemented — a std primitive that exists only as documentation
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** OPEN. Found 2026-07-30 by `skip-triage-golden-lane`.

`std/ui/primitives.ssc:185` declares `extern def fetchUrlSignalTo(...)` with a nine-line doc comment
explaining how it differs from `fetchUrlSignal`, and lists it in `exports:` (line 31). It has **zero**
registrations in the entire tree:

* absent from the interpreter's `FetchIntrinsics` (`v1/runtime/std/fetch-plugin/...`)
* absent from the JS allow-list at `v1/runtime/backend/js/.../JsGen.scala:1406`
* absent from `RustTuiIntrinsics`

Its sibling `fetchUrlSignal`, declared nine lines above with the SAME `extern def` shape and the same
`headers: Signal[String] = emptyHeaders` default, has three registrations and imports fine. So this
is not about `extern` and not about default arguments — the symbol does not exist on any backend,
including the JS one its own comment describes.

**Blocks** `control-center-live` on every lane (hence a corpus SKIP, which hides v2 too).

**FIX DIRECTION SETTLED 2026-07-30 — implement it; do NOT delete the declaration.** Its WRITE-side
twin is fully shipped: `fetchActionTo(method, urlSig, body, onSuccessTick[, headers])` has an
interpreter intrinsic (`FetchIntrinsics.scala:147`) and JS runtime support (`signals.mjs:558`, `:1282`
— it resolves its URL from a signal at click time). The doc comment here calls `fetchUrlSignalTo`
"the read-side counterpart of fetchActionTo", and that counterpart exists. So this is an UNFINISHED
FEATURE, not dead weight, and deleting the declaration would discard a deliberate half of a pair.

**Three sites, mirroring the two neighbours it sits between:**
1. `FetchIntrinsics.scala` — an entry next to `fetchUrlSignal` (:47) that reads the URL from a signal
   the way `fetchActionTo` (:147) does.
2. `JsGen.scala:1406` — add the name to the emit allow-list.
3. `signals.mjs` — re-fetch when the URL signal changes, the read-side mirror of the `:1282` block.

**No corpus gain, and that is not a reason to skip it.** `control-center-live` also binds a port, so it
stays a SKIP either way — the value here is that `std/ui/primitives.ssc` stops advertising a primitive
that does not exist on any backend. Wants its own slice; sized, not started.
## v2-front-curried-def-second-clause — F drops the second parameter clause of a curried `def`
<!-- status: open
     lane: multi
     area: front -->

**Status:** OPEN (found 2026-07-28 by `v2-front-colon-trailing-lambda` while verifying that fix;
**A/B'd against `origin/main` — byte-identical behaviour with that change reverted**, so it is
pre-existing and independent).

**Reproduce** — two lines:

```scalascript
def ap(n: Int)(f: Int => Int): Int = f(n)
println(ap(3)((i) => i * 2))
```

F emits `(def ap (lam 1 (app (global f) (local 0))))` — a ONE-parameter lambda whose body applies
`f` as a **free global**. The second clause `(f: Int => Int)` is gone, so F declines the file with
`unbound global: (global f) is neither a top-level def nor an @-cell` and the F4a fallback
recompiles it with the legacy front. Output is correct (`6`), at the cost of a diagnostic on every
run and of F not being the front that compiled it.

**Severity: moderate, and it hides.** Fails into the fallback rather than into an error, so it is
invisible unless you read stderr — the same shape as the `val`-position half of
`v2-front-for-yield-parse-gap`. It also means any corpus case using a curried `def` is measuring
the legacy front while appearing to measure F.

**Not a duplicate of `v2-native-front-multiline-curried-def`** (FIXED 2026-07-18, `d0722478e`),
which was about the second clause starting on a NEW LINE. This one is a single-line curried def.

**FIX DIRECTION, now measured rather than guessed** (attempted 2026-07-28 by `v2-front-curried-def`,
**reverted** — the half-fix is worse than the bug, see below).

It is **two halves, and the def half alone is harmful**:

1. **The def.** `emitDefU` handles a second clause only when it is `(using …)`; a plain `(f: …)`
   clause falls through to `skipToEq` and is dropped. Generalising it to consume any number of
   `(params)` clauses is ~2 lines and makes F emit the oracle's shape,
   `(def ap (lam 2 (app (local 0) (local 1))))`.
2. **The call site, which is the part that is missing.** The oracle also FLATTENS the application:
   it emits `(app (global ap) 3 (lam …))`, while F's `postfix` loop nests them as
   `(app (app (global ap) 3) (lam …))`. This runtime has **no partial application** — with only
   half 1 applied, `ap(3)(f)` dies at run time with `arity: 2 expected, 1 given`.

**Measured, and this is why it was reverted:** with only half 1, the file goes from *"F declines,
fallback compiles it, prints `6`"* to *"F accepts, program fails at run time"*. `front-report` flips
`GAP` → `F`, which looks like progress and is a regression. Baseline restored and re-verified:
`GAP` + `6`.

**What half 2 needs.** Flattening `f(a)(b)` requires knowing the callee's TOTAL arity across
clauses, and F's `cx` has no arity table — `collectRetTab` carries return types only. So the real
work is a new pre-pass collecting per-def total arity (alongside `collectPlessDefs`), threading it
through `mkCxE`, and having `postfix`'s `(`-branch append to the existing argument list instead of
nesting when the callee is a known multi-clause def. Non-curried calls and genuine
returns-a-function calls must stay nested, so the arity lookup is the discriminator, not a syntactic
guess.

**Gate note:** `tests/e2e/v2-front-coverage.sh` uses this bug as its `--self-test` anchor (a known
GAP must still report GAP). Whoever fixes it must swap that anchor for another known gap, or the
self-test starts failing for the right reason.

## v2-serve-banner-missing — three corpus DIVERGEs, one cause: the native lane prints no server banner
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: eb82bd18e -->


**Status:** **FIXED 2026-07-28** by `v2-stub-apply-and-serve-banner` (`eb82bd18e`), option (1):
the native serving host prints the same three-line banner. Contract-preserving — the three cases
became PASS and no golden anywhere changed. `diff` of `examples/rozum-agent.ssc` native vs INT is
now byte-identical, and the scoped contract run is 6/6 PASS cells with all three recorded as
IMPROVEMENT over the frozen baseline.

**A first attempt printed only on the BLOCKING path** and left all three DIVERGE. The reasoning
was that "Ctrl+C to stop." cannot be true of an async serve — plausible, and wrong: those examples
call `serveAsync(port)`, and the INT reference prints all three lines and then carries on, because
v1's `serveAsync` runs `WebServer.start` on a background thread which prints before blocking.
Corrected by diffing against INT instead of reasoning about what the banner ought to say.

Option (2) — move the banner to stderr in both lanes — is filed separately as
`v2-serve-banner-belongs-on-stderr`, with the measurement it must make first.

baseline; these three were `FAIL` before `d11fd7a92` and are now `DIVERGE`, i.e. they RUN and only
the output differs).

**Measured** — `rozum-agent`, `rozum-agent-pool`, `rozum-agent-streaming`, INT vs the default lane:

```
INT                                  native
ScalaScript web · http://localhost:19694/  (root: .)     (absent)
  (backend=fast)                                         (absent)
Ctrl+C to stop.                                          (absent)
Done                                 Done
Posted the transaction.              Posted the transaction.
1                                    1
```

The PROGRAM output is byte-identical. The entire divergence is a three-line banner that
`WebServer.start` prints to the interpreter's stdout
(`v1/runtime/backend/interpreter-server/.../WebServer.scala:134-138`); the native lane serves
through a different implementation that prints nothing.

**Two fixes, and they are not equivalent — pick deliberately.**

1. *Make the native lane print the same banner.* Contract-preserving: three DIVERGEs become PASS,
   no golden anywhere changes, no other lane is touched. Low risk, but it propagates developer
   chatter into program stdout on a second lane.
2. *Move the banner to stderr in BOTH lanes.* Arguably the right answer — a server banner is not
   program output, and putting it on stdout is what dragged it into the golden in the first place.
   But it changes v1's observable contract and rewrites the golden of every serving example, so it
   is not a drive-by: it needs its own claim and a full-corpus re-freeze.

Recommendation: (1) now to clear the three cases, (2) filed as the real cleanup. **Do not do (2)
opportunistically** — check first whether the harness compares stderr as well
(`run.sc` builds its comparison with `outputWithFailureContext(out, err, exitCode)`), because if it
does, moving the banner to stderr changes nothing and only churns the goldens.

## v2-native-program-tail-quotes-strings — a program's tail value printed as a debug dump, not as output
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by `v2-program-tail-string-render`. Found 2026-07-28 by
`v2-multiblock-auto-output` while designing the per-block auto-output fix.

**The divergence was WIDER than this entry first said.** It originally recorded "a String prints
with quotes" and proposed fixing the top level only, "since v1 quotes a nested `StrV` and so should
v2". **Measured, that guess was wrong** — v1 leaves nested strings bare here too:

| program tail | native (before) | v1 reference | native (after) |
|---|---|---|---|
| `"HELLO!"` | `"HELLO!"` | `HELLO!` | `HELLO!` |
| `List("a", "b")` | `List("a", "b")` | `List(a, b)` | `List(a, b)` |
| `Some("x")` | `Some("x")` | `Some(x)` | `Some(x)` |
| `Map("k" -> "v")` | `Map("k" -> "v")` | `Map(k -> v)` | `Map(k -> v)` |
| `42` | `42` | `42` | `42` |

**Root cause.** `V2Result.report` ended in `println(ssc.Show.show(other))`. `Show.show` is the
*debug* rendering — correct for a nested value in a diagnostic, wrong for the program's user-facing
output, and it quotes every string at every depth. v1's auto-output path does not.

**Fix.** One line: render through `ssc.Prims.display`, the renderer the kernel's own `println` uses
(`anyStr` — "containers with UNQUOTED nested strings"), so the program tail and an explicit
`println` of the same value now agree, and both agree with v1. An explicit `println` was already
correct on both lanes; only the tail path was wrong.

**Gate.** Four cases in `tests/e2e/v2-error-diagnostic.sh`, each comparing the native tail against
**`ssc-tools run --v1` on the same file** rather than against a hardcoded string, so the
expectation cannot drift away from the reference. A/B'd: against the previous binary all four fail
with expected/actual printed (`expected (v1): HELLO!` / `got (native): "HELLO!"`); against the fix
all four pass.

**Note for the auto-output work.** `v2-native-multiblock-auto-output-missing` routes *fenced*
documents through `println` rather than through `report`, so it hid this for those files; a
fenceless `.ssc` (whose whole body is one block) always went through `report`, which is why this
needed its own fix.

## v2-native-case-unit-pattern-matches-where-int-does-not — `case _: Unit` was true on native, false on INT and JS
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: c3490e939
     gate: tests/conformance/type-ascription-unit.ssc -->

**Fixed 2026-07-29.** `Unit` is an ordinary type and `case _: Unit` holds for the unit value. Both
the interpreter's and the JS backend's type-test tables listed every scalar type *except* this one
and ended in a catch-all `false`, so the pattern fell through to the wildcard arm.

```scalascript
def f(x: Any): Unit =
  x match
    case _: Unit => ()
    case _ => println(x)
f(println("side"))
```

| lane | before | after |
|---|---|---|
| `bin/ssc run` (native, default) | `side` | `side` |
| `ssc-tools run-jvm` | `side` | `side` |
| `ssc-tools run --v1` (INT) | `side` then **`()`** | `side` |
| JS via `emit-js` | `side` then **`()`** | `side` |

**Scope was wider than this entry first said.** It was filed as "an INT gap"; measuring all four
lanes showed **JS had the same hole**. That is the part that mattered: INT and JS are the two lanes
the conformance suite *grades* with — INT is its reference — while native and JVM are the two that
were right. So any case written around `case _: Unit` was being checked against the lanes that got
it wrong, and a native-vs-INT diff would have been read as a native defect.

One arm each: `UnitV => typeName == "Unit"` in `PatternRuntime.scala`, `Unit => v === undefined` in
`JsGenCpsCodegen.scala` (unit is `undefined` in the JS lane and `null` stays distinct, so the test
does not conflate the two).

**A/B.** Against `tests/conformance/type-ascription-unit.ssc`: before, INT and JS differ from
`expected/` on 3 of 6 lines; after, all four lanes print it byte-for-byte. Native and JVM printed it
byte-for-byte both times — which is what establishes `expected/` as the right answer rather than my
reading of it.

## type-ascription-tuple-and-set-arms-missing — `case _: Set` cannot be answered on two lanes: Set is not a type there
<!-- status: open
     lane: multi
     area: runtime
     gate: tests/conformance/type-ascription-set.ssc -->

**Tuple half FIXED 2026-07-29 in 7939f4c9e** (gate `tests/conformance/type-ascription-tuple.ssc`).
**Set half FIXED on `int` 2026-07-30 in 84c3b2cc7** — and NOT fixable the same way on two lanes,
because this entry's premise was wrong.

Measured across all four lanes and all three containers, rather than on the Set-only probe this
entry was filed from:

| container | `jvm` (oracle) | `int` | native / `v2` | `js` |
|---|---|---|---|---|
| `Set(1,2)` | `set` | `other` → **`set`** | `other` | `other` |
| `Map("a"->1)` | `map` | `map` | `map` | **`other`** |
| `List(1,2)` | `list` | `list` | **`other`** | **`other`** |

It called this a *uniform* gap where the self-hosted lanes agree. **They do not**, and the reason two
of them answer `other` is not a missing arm:

- on the native lane **`Set(1, 2)` prints `List(1, 2)`** — a Set *is* a List there;
- on the JS lane `List(...)` is `[...args]`, and `_setOf(...)` returns a plain array too.

So on two of four lanes `Set` is not a distinct runtime value and there is nothing to test for.
An arm there would assert that a List is a Set — a wrong answer replacing an honest one. This is a
**representation gap, not a table gap**: different size of work, different owner.

Three separable pieces remain, so they can be picked up separately:

1. **`js` answers `other` for `Map`** although a Map IS distinguishable there (`_hamtOf`). A genuine
   table gap and the cheapest thing left in this entry.
2. **native answers `other` for `List`**, which is representable (`DataV("Cons"/"Nil")`) — also a
   table gap, but in the DataV branch rather than the primitive one.
3. **`Set` on native and `js` needs a distinct representation** before the question means anything.

**Original report (superseded 2026-07-30), Set half:** Found by the probe written for
[[v2-native-case-unit-pattern-matches-where-int-does-not]]; same two type-test tables, same missing
arm.

```scalascript
def kind(x: Any): String = x match
  case _: Tuple2[?, ?] => "pair"
  case _: Set[?] => "set"
  case _ => "other"
```

| | native | JVM (real Scala) | INT | JS |
|---|---|---|---|---|
| `case _: Tuple2[?, ?]` on `(1, 2)` | yes | yes | **no → yes** | **no → yes** |
| `case _: Tuple2[?, ?]` on `(1, 2, 3)` | no | no | no | no |
| `case _: Set[?]` on `Set(1, 2)` | **no** | yes | **no** | **no** |

**Correction to this entry's first version.** It said "Scala says yes" for bare `case _: Tuple2`.
It does not: that spelling is a *compile error* in Scala 3 — `Missing type parameter for
[T1, T2] =>> (T1, T2)` — and the JVM lane refused to build the first draft of the gate. The claim
holds for `Tuple2[?, ?]`, which is what the corrected table and the gate now use. Worth keeping the
mistake visible: the JVM lane is the only lane that runs real Scala, so it is the oracle for "what
should this do", and consulting it is cheap.

**Why the Set half is still open, rather than being one more line in the same commit.** Native gets
`case _: Set` wrong too. Fixing only INT and JS would *create* a lane divergence where all three
currently at least agree with each other — trading a uniform gap for a differential one, which is
strictly worse for a suite that grades by comparing lanes. The native table lives in `v2/src`
(`Runtime.scala`'s `__isTag__` primitive arm), held by another claim at the time. The fix is one arm
per lane: `Value.SetV => typeName == "Set" || typeName == "Iterable"`, the JS `_type`/marker
equivalent, and the `SetV` case in the native primitive table — all three together or none.

**The Set half is invisible to every differential gate we own**, which is the part worth
remembering: a cross-lane sweep compares lanes to *each other*, so a gap all three share reads as
green. It surfaced only because the probe asked what the JVM lane does instead of what another
self-hosted lane does.

## lint-markdown-unreachable-from-markdown-commits — the only job that lints `.md` cannot be triggered by a `.md` change
<!-- status: fixed
     lane: multi
     area: build
     fixed-in: 680181feb
     gate: tests/e2e/ci-status-guard.sh -->

**Status:** FIXED 2026-07-28 in `680181feb` — a second workflow,
`.github/workflows/lint-markdown.yml` (job `Lint Markdown (docs-only)`), triggered on
`paths: ['**.md']`. `ci.yml` and its `paths-ignore` are untouched.

**Why a second workflow and not a move.** Moving the job out of `ci.yml` would give exactly one
lint run per push instead of two on a mixed commit — but `scripts/ci-status` hard-codes a
`required_jobs` list containing `"Lint Markdown"` and reports a run that omits a required job as
RED, and `tests/e2e/ci-status-guard.sh` pins that. The move would therefore have turned every
future green `ci.yml` run into `missing required job: Lint Markdown`. Accepted consequence: a
commit touching both code and `.md` lints twice — same command, same whole-repo scope, so the two
cannot return contradictory verdicts.

**The defect.** `ci.yml` now carries, for both `push` and `pull_request`:

```yaml
paths-ignore:
  - '**.md'
  - '.work/**'
```

GitHub skips the run when EVERY changed path matches. A **markdown-only commit therefore produces no
run at all** — including no `Lint Markdown`. The job that exists to check `.md` is the one job a
`.md` change can never reach.

**Consequence, and it is not theoretical — it happened today.** `64a8a3339` was a docs-only commit
(`docs: NIG-0/NIG-1/NIG-3 done in SPRINT + CHANGELOG`). It introduced two literal hard tabs into
`SPRINT.md:137` and got no run. The breakage first surfaced hours later on `9f136e21f` — an
unrelated conformance commit — because `markdownlint '**/*.md'` lints the whole repository at
whatever SHA happens to trigger it:

```text
SPRINT.md:137:45 error MD010/no-hard-tabs Hard tabs [Column: 45]
SPRINT.md:137:48 error MD010/no-hard-tabs Hard tabs [Column: 48]
```

So the failure is attributed to a commit that did not cause it and does not contain the offending
path, and the commit that did cause it is reported green (in fact, reported nothing). The fix commit
has the mirror-image problem: `41541482d` touches only `SPRINT.md`, so **the repair is equally
unverifiable by CI** — it had to be checked by hand with the CI command
(`markdownlint '**/*.md' --ignore node_modules`, exit 0) and will only be confirmed by the next
unrelated code push.

**Why the paths-ignore itself is still right.** Its measurement stands: 43 of 58 non-`[skip ci]`
commits in one hour changed only `.md`/`.work/`, against an `sbt` job of 3h16m. The problem is not
the filter, it is that one job's *input* is exactly the filter's exclusion set.

**Fix direction.** Give `Lint Markdown` its own trigger rather than exempting it from the filter —
a second workflow (or a `push` entry with `paths: ['**.md']`) that runs *only* markdownlint. It is
seconds of runner time, so it does not reintroduce the queue pressure the filter was added to
relieve, and it restores the property that broke here: **the commit that breaks a check is the
commit the check reports on.**

## uniml-yaml-corpus-6ck3-percent-oracle-conflict — pinned event contradicts YAML 1.2.2 tag preservation
<!-- status: open
     lane: multi
     area: front -->

**Status:** OPEN (found 2026-07-28 during independent UPR-2a.1 review; upstream
`yaml/yaml-test-suite#9` remains open).

**Reproduction.** YAML 1.2.2 section 5.6 requires percent-escaped tag characters to
be preserved and compared exactly as presented. The same specification's Example
6.26, copied into pinned case `6CK3`, expects `!e!tag%21` as parser event
`<tag:example.com,2000:app/tag!>`. The public representation and the official event
oracle therefore cannot share one tag string without violating one of the two
contracts.

**Measurement defect found after landing.** `3341a35a9` preserved the public
representation but called `parserEventTag` on the actual event before comparison.
That makes `%21` equal to `!`, collapses two normatively distinct tags, and falsely
counts 6CK3 as a semantic/strict pass. This violates the project's compare-first
measurement rule even though the vendored expectation itself was not changed.

**Fix acceptance.** Keep exact shorthand in CST and `%HH`-preserving handle-expanded
tags in `YamlValue`. Compare that normative actual event to the unchanged vendored
event first. Only afterward classify the exact 6CK3 mismatch as
`oracle-discrepancy yaml/yaml-test-suite#9`. Any decoded test-suite compatibility
projection must be test-only, separately named, and unable to contribute to the
existing semantics/strict counts. Fail-first coverage must distinguish `%21` from
`!`; a full 402-case candidate diff must prove 6CK3 is the only changed row.
Expected corrected census is semantics `137`, strict `125`, failures `277`, with
validity `214`, actual errors `216`, source/chunks `402/402`, and zero crashes.
The tracker remains OPEN after the local measurement repair because the normative
text/example conflict and upstream issue remain unresolved.

**Local measurement repair landed.** `024d80524` removes pre-comparison decoding
from production, freezes the literal actual event, and adds a display-only
post-compare classifier guarded by the exact 6CK3 mismatch. The 402-row diff
changes only 6CK3; the corrected baseline/category SHA-256 values are
`563ec95401acfb8fab062b11408b5be8e5397a61c5d54676fff04865170fff95` and
`cc0d52c8f34207900a95afe6725d5f9a9265c1cb6ce10f6d82feb9bf21288c78`.

## uniml-yaml-projection-reorders-invalid-cst — semantic projection sorts tokens instead of validating source order
<!-- status: open
     lane: multi
     area: front -->

**Status:** OPEN (found 2026-07-28 during UPR-2 architecture audit).

**Reproduction.** `YamlProjection.project` flattens every CST root and calls
`sortBy(_.id)` before rebuilding the source string. A caller-provided or composed `ParseResult`
whose traversal order and token ids disagree is therefore silently reordered and reparsed instead
of returning `uniml.yaml.projection-invalid-cst`.

**Impact.** Projection can manufacture a semantic success from an invalid CST. The result hides the
original tree/order defect, and anchor/directive meaning can change because YAML is source ordered.

**Fix acceptance.** Add a fail-first projection test with disagreeing traversal/id order. Validate
one source identity, unique ids, monotone spans, source slices, and traversal/source order; reject
the invalid tree without sorting or reparsing it.

## scljet-sql-double-equals-parser-gap — WHERE rejects SQLite's `==` equality alias
<!-- status: unknown
     lane: multi
     area: front -->

**Status: FIXED 2026-07-28** in `94873f54d` (`scljet-sql-double-equals`). Normalized in the TOKENIZER.

**It was a LEXER gap, not a parser one — and that changes where the fix belongs.** `scljet/sql.ssc`
emitted one token per `=` CHARACTER (`c == 61`), so `==` became TWO `=` tokens and the parser saw
`v = = 2` -> `expected an expression operand`. The two places that already knew the alias —
`isCompareOp` (:869) accepting the string `"=="`, and `compareValue` (:1376) normalizing it — were
therefore UNREACHABLE. That is exactly the "internally inconsistent accepted operator set" the
original report named: the handling existed, the token never did.

**Fix.** `=` immediately followed by `=` consumes both characters and emits a single `=` token.
Normalizing at the tokenizer is what gets the alias into every downstream path at once — scalar,
WHERE/HAVING/ON, correlated scalar and index range. Emitting a `"=="` token instead would have
fixed the scan path and silently left the INDEXED one behind, because `isSargOp` (:2401) and the
index-range mapper (:4134) accept only `"="`. Only ADJACENT characters merge, so the fail-closed
cases hold: `a = = b` with a space is still an error, and so are `!==` and `<==`.

**Fail-first, measured** by reverting only `scljet/sql.ssc` and rebuilding: exactly the six `==`
probes fail with the reported error, and every `=` / `<>` / `!=` / spaced line is byte-identical
before and after — the case isolates the alias and nothing else.

**Regression** `tests/conformance/scljet-sql-double-equals.ssc` runs each query TWICE, once per
spelling, on a scanned table AND on an indexed one, and pins the spaced form as an error. INT + JS
green; rostered with the baseline digest reproduced before the new `roster-sha256` was written.

### Original report (kept for context)

**Status:** OPEN (found 2026-07-28 by `scljet-production-completion`;
reproduced on assembled `bin/lib/ssc.jar` from `b63206552` through
`bin/ssc-tools run --v1`).

**Real-harness reproduction.** `SELECT id FROM t WHERE v == 2 ORDER BY id`
returns `QUERY-ERROR:expected an expression operand`; reference SQLite 3.51.0
returns id `2`. Scalar-expression comparison already normalizes `==` to `=`,
so the accepted operator set is internally inconsistent.

**Root cause.** WHERE condition tokenization/parsing does not consume `==`,
while `compareValue` contains a local alias normalization. SC-8 must accept and
normalize the alias consistently in scalar, WHERE/HAVING/ON, correlated scalar,
and index-range paths, with complete-token fail-closed coverage.

## corpus-contract-delta-false-improvements — unobserved lanes and status changes look improved
<!-- status: fixed
     lane: multi
     area: conformance
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex review on `e124cc20f`.

**Reproduce.** In the pure classifier, freeze `x<TAB>js<TAB>FAIL` and observe
`x<TAB>js<TAB>DIVERGE`: row-level set subtraction reports both CHANGE and
IMPROVEMENT, although the cell never passed. A
`KNOWN-RED → FAIL` transition additionally prints that the declaration
"expired" because the lane now passes. Separately, if `backends:` stops
selecting a frozen red lane, requested-lane scoping treats the unobserved row
as an improvement.

**Root cause.** The delta compares whole `(case,lane,status)` strings instead
of mapping statuses by `(case,lane)`, and scopes the baseline by requested
lanes rather than the cells that actually ran.

**Fix / done-when.** Compare by cell key: a changed status is only CHANGE, and
IMPROVEMENT requires an observed frozen-red cell whose current row is absent
(therefore PASS/runnable). Backend-excluded cells stay out of scope. Synthetic
tests cover `FAIL → DIVERGE`, `KNOWN-RED → FAIL`, an unobserved lane, and a
real improvement.

## ci-status-blind-to-non-ci-workflows — the tool agents trust for a verdict could not see 4 of 5 workflows
<!-- status: fixed
     lane: multi
     area: front
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`ci-status-all-workflows`). Found while looking for work
after the bug list ran dry — this was not on the board, and that is the point: the check that would
have surfaced it did not exist.

**Symptom.** `scripts/ci-status` hardcoded `--workflow ci.yml --branch main --event push`. The repo
has five workflows. Four of them — `corpus-contract.yml`, `f4-front-swap.yml`, `native-release.yml`,
`pages.yml` — could not be queried by it at all, and the two that matter most run on a **schedule**,
which the `--event push` filter excludes by construction. So no automated check anyone ran ever
looked at them.

**Measured, which is what makes it a bug rather than a tidiness complaint:**

| workflow | last 12 runs |
|---|---|
| `corpus-contract.yml` | **0 success** — 4 `failure`, 7 `cancelled`, 1 running; every one `schedule` or `workflow_dispatch` |
| `f4-front-swap.yml` | 7 success, 1 failure |
| `native-release.yml` | **never run** (tag-triggered, `v*.*.*`) |
| `pages.yml` | 12 success |

A nightly that has never once been green, and a release workflow that has never executed, were both
invisible to every tool in the repo. This is the apparatus-lies-green shape again: not a wrong
answer, an unaskable question.

**Fix.** `--workflow`, `--event` (`any` disables the filter), `--branch`, `--latest` (a scheduled run
fires against whatever `main` was at the time, so an exact-SHA query almost never matches one), and
`--all-workflows` — one line per workflow file from its most recent run of any trigger, which is the
blind-spot sweep. Verdicts for a non-`ci.yml` workflow come from **its own jobs**: `ci.yml`'s four
required job names exist nowhere else, and inventing a required list for a workflow the script does
not know would be a guess.

**No-argument behaviour is byte-identical** — AGENTS.md §4c and the agents depend on it, and
`tests/e2e/ci-status-guard.sh` asserts the exact filter set it sends. That existing suite passes
unchanged.

**A/B'd, not just run.** Against the PREVIOUS `ci-status` the new cases fail
(`ci-status: unknown argument: --workflow`, `wf-green: expected exit=0 got=2`); against the fix the
whole suite passes. The new cases assert GREEN **and** RED for the same non-ci workflow, because a
one-sided check could not distinguish "judged its own jobs" from "recognised no job and defaulted to
pass", and the `--all-workflows` case asserts that a cancelled nightly makes the sweep exit 1 while a
never-run workflow is reported without being counted as a failure.

**Live output after the fix** (`scripts/ci-status --all-workflows`) shows `native-release.yml
NEVER-RUN` and each workflow's real verdict on one line.

**Not fixed here:** `corpus-contract.yml` being red is a real defect owned by the live
`corpus-contract-*` claims. This entry only makes it impossible to keep missing.

## scljet-ipk-update-numeric-affinity — INSERT auto-assigns invalid IPKs and UPDATE refuses valid affinity
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (opened 2026-07-27 by
`scljet-ipk-rowid`, expanded the same day by
`scljet-production-completion` after assembled INSERT/UPDATE differential).
The fail-first affinity matrix is green on INT+JS; the independently reviewed
live sqlite-jdbc matrix, reference row reopen, and integrity check closed SC-1a
in `b39127f61`.

**Symptom** (reference sqlite3 3.51.0 vs scljet, same statements on `emp(id INTEGER PRIMARY KEY, …)`):

```
UPDATE emp SET id = 5     → both move the row                                    ✓
UPDATE emp SET id = 7.5   → both: datatype mismatch                              ✓
UPDATE emp SET id = NULL  → both: datatype mismatch                              ✓
UPDATE emp SET id = 'x'   → both: datatype mismatch                              ✓
UPDATE emp SET id = '5'   → sqlite: row moves to 5   scljet: datatype mismatch   ✗
UPDATE emp SET id = 7.0   → sqlite: row moves to 7   scljet: datatype mismatch   ✗
INSERT INTO emp VALUES ('5', ...)  → sqlite: rowid 5  scljet: next auto rowid    ✗
INSERT INTO emp VALUES (7.0, ...)  → sqlite: rowid 7  scljet: next auto rowid    ✗
INSERT INTO emp VALUES ('x', ...)  → sqlite: mismatch scljet: next auto rowid    ✗
INSERT INTO emp VALUES (7.5, ...)  → sqlite: mismatch scljet: next auto rowid    ✗
```

Measured directly: `sqlite3 c.db "UPDATE emp SET id='5' WHERE id=1; SELECT rowid,id,name FROM emp"`
→ `5|5|ann`, and the same with `7.0` → `7|7|ann`.

**Root cause.** `targetRowidOf` accepts only `SqlInteger`, while
`valueIntOrNone` feeds `assignInsertRowids` and maps every non-integer value to
the same `None` as SQL NULL. SQLite applies its rowid numeric conversion first:
valid numeric TEXT/REAL becomes an explicit integer, only actual NULL requests
automatic allocation, and every other value is `datatype mismatch`.

**Why it was left.** A faithful fix needs SQLite's exact text→integer affinity rules (leading and
trailing space, sign, overflow, prefix forms like `'5abc'`), and `sql.ssc` has no such helper — the
nearest one, `parseLongStr`, lives in `jdbc.ssc`, the wrong direction for the engine to depend on.
The integral-REAL half is a two-line fix and could land on its own. A loud rejection is strictly
better than the silent no-op that preceded it, so this is a completeness gap, not a correctness
risk.

**Fix sketch.** Add one target-neutral coercion returning
`Either[String, Option[Long]]`, use it from both INSERT and UPDATE, and make
`assignInsertRowids` return `Either` so the whole row batch is validated before
pager mutation. Extend `scljet-update-ipk-moves-rowid` and add bound-value
INSERT/UPDATE plus live sqlite-jdbc differential vectors for exact integer
TEXT, decimal/exponent TEXT, integral REAL, rounding boundaries, invalid values,
collisions, and indexed paths.

## scljet-ipk-move-indexed-corrupts-btree — an IPK move on an INDEXED table wrote an out-of-order b-tree
<!-- status: fixed
     lane: multi
     area: build
     fixed-in: 9cb9865e1 -->

**Status:** FIXED (2026-07-27, `scljet-ipk-rowid`, commit `9cb9865e1`). Found the same day by
`scljet-ipk-rowid` while cross-checking the freshly landed IPK-move fix (`4a20a50b7`) against the
reference engine — i.e. it was live on `origin/main` for a few hours, not a long-standing defect.

**Symptom** (measured on `origin/main` `c85e484d7`; table with any index, `UPDATE emp SET id = 5
WHERE id = 1`):

```
scljet reads:  table rowids are not strictly increasing     <- our own reader refuses the table
sqlite3:       *** in database main ***
               Tree 2 page 2 cell 0: Rowid 5 out of order   <- reference: the FILE is corrupt
```

A rowid collision on the same path was additionally accepted in silence.

**Root cause.** `executeUpdate` has two branches. Unindexed deletes and reinserts through the
b-tree, so `leafInsertCell` places each cell by key and rejects duplicates. Indexed calls
`reindexTable`, which rebuilds table + indexes by writing the row list **in list order** and never
reaches `leafInsertCell`. The move fix gave the row a new KEY without changing its POSITION, and
`ipkMoveConflict` was wired only into the unindexed branch — so that path lost both guarantees.

**Fix.** The indexed branch now runs the same collision policy (`buildUpdateEdits` +
`ipkMoveConflict`, called to check only, so both paths refuse with identical wording) and then
`sortRowsByRowid` before the rebuild.

**Why it escaped a genuinely thorough verification** — worth keeping, this is the reusable part:

1. *The conformance case is a self-consistent oracle by design*, and its own header says so. But
   the failure here is not a wrong VALUE, it is a malformed b-tree — the file stays partly
   readable, so a self-consistent check can pass while the artifact is corrupt.
2. *The cross-engine differential existed and was thorough — but every case used an UNINDEXED
   table.* The bug lives on the other branch. Coverage of the right KIND (through a file, judged
   by the reference) is not coverage of the right PATH.
3. *The first gate written for it was itself fake.* It moved rowid 1 → 5 with rows at 1 and 7:
   `[5, 7]` is still ascending, so it passed with the ordering fix reverted. **An ordering bug is
   only observable when the fixture forces a reorder** — the test now moves 1 → 9, past the
   surviving row at 7. Both new gates were then confirmed to FAIL against `origin/main`'s exact
   code (2 failed / 7 passed) and pass with the fix.

**Gates:** `ScljetIpkRowidDifferentialTest` (indexed move + indexed collision, `PRAGMA
integrity_check` through a real file — it validates cell ordering AND cross-checks the index
against the table) and `tests/conformance/scljet-update-ipk-moves-rowid.ssc` (same two cases, int
+ JS).

## ci-vthread-carrier-starvation-hang — `Test via sbt` hangs to its 200-min timeout under CI load
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-22 by opus (build.sbt `-Djdk.virtualThreadScheduler.parallelism=16`). A major
contributor to the long-running ci-red-main saga: the `sbt — compile and test` job intermittently HANGS
(no output for hours → GitHub's 200-min action timeout), most often observed right after
`GeneratorNativePluginTest`.

**Root cause (measured, not guessed).** The generator/coroutine native plugin
(`v2/runtime/std/generator-plugin/.../GeneratorNativePlugin.scala`) hands values between a `Thread.ofVirtual()`
producer and the consumer via **UNBOUNDED `SynchronousQueue.put/take`** (no timeout). On **JDK 21** a
virtual thread that blocks inside a `synchronized` block **PINS its carrier** (fixed only in JDK 24,
JEP 491). Forked test JVMs run up to 4 suites in parallel (`Tags.limit(Tags.Test, 4)`) and a 2-core CI
runner has only **2 default virtual-thread carriers** (= availableProcessors). A few pinned virtual
threads exhaust the carriers, the generator/coroutine producer can never be scheduled, and the consumer's
`take()` blocks **forever** → the whole job hangs. The suite passes in **749 ms** standalone — it is
purely a carrier-starvation-under-contention hang.

**Deterministic repro (`/tmp/VThreadRepro.scala`, scala-cli, JDK 21):** pin 4 carriers with
`synchronized`+sleep virtual threads, then a `SynchronousQueue` handshake — `parallelism=2` →
`STARVED-HUNG` (poll times out); `parallelism=16` → `OK-42`.

**Fix.** Give the forked test JVM ample carriers so a handful of pinned VTs cannot exhaust them:
`ThisBuild / Test / javaOptions += "-Djdk.virtualThreadScheduler.parallelism=16"`. Test-only (no
production-runtime change), systemic (helps every virtual-thread-heavy suite: generators, coroutines, WS),
low-risk. Verified: option reaches the forked JVM (`show Test/javaOptions`), generator suite 9/9 green.
Follow-ups (BACKLOG-worthy): the plugin's unbounded `SynchronousQueue` handshakes are still a latent
liveness hazard if carriers are ever exhausted — a bounded `poll`/`offer` with a loud diagnostic would
convert a future hang into a re-runnable failure; and JDK 24 removes `synchronized` pinning entirely.

## frontend-tui-fetch-refresh-static-after-bootstrap — refresh ticks redraw stale fetched content
<!-- status: fixed
     lane: multi
     area: front
     fixed-in: 6c6fcf21b -->

**Status:** FIXED 2026-07-20 by `frontend-tui-fetch-refresh` (`6c6fcf21b`). Found while
implementing rozum `ucc-poc-msglist`.

**Symptom/reproduce:** emit a terminal app containing a `FetchUrlSignal("messages", url,
tick.id)`, a `DataTable.Remote` bound to it, and a button whose handler is
`IncrementSignal(tick)`. The emitted crate performs one GET before its first frame. Activating
the button increments `tick` and redraws, but the table still contains the bootstrap response;
the HTTP server receives no second request. The React backend already treats the same tick as a
managed-fetch dependency.

**Root cause:** `frontend/tui/TuiEmitter.collectFetches` reduces every fetch binding to
`id -> url`, discarding `FetchUrlSignal.tickId`. Generated Rust has only `bootstrap(signals)`,
called once before the event loop; `handle_key` mutates signals but no runtime code compares the
refresh tick or re-runs the GET.

**Fix / done-when:** retain `(id, url, tickId)` in emitter metadata, snapshot observed ticks after
bootstrap, and re-fetch a binding before the next frame when (and only when) its tick changes.
Transport failure must preserve the last-good body. A local-HTTP cargo regression must prove an
initial JSON table row changes after the refresh action, while a non-fetch app still emits neither
`ureq` nor fetch state. Contract: `specs/frontend-tui-fetch-refresh.md`.

**Resolution:** `TuiEmitter` now retains `FetchInfo(url, tickId)`, captures each binding's
post-bootstrap tick, and runs `refresh_fetches` before every interactive frame. Only changed ticks
issue a GET; failed GET/body reads leave the destination signal's last-good value intact. The
generated-runtime regression performs bootstrap, a successful refresh, unchanged-tick no-ops, and
a failed refresh against a local HTTP server; it observes exactly three requests and preserves the
successful second body. `frontendTui/test` passes 36/36, including all emitted-Cargo smokes.

## scljet-update-ipk-column-silently-ignored — `UPDATE t SET <ipk> = …` does nothing, and reports success
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`v2-f5b-typed-locals` batch C), together with its sibling
`scljet-update-ipk-does-not-move-rowid` — they are one defect seen from two angles, so they were
fixed as one change. See that entry for the implementation and the measured A/B.

The part specific to THIS report was the second half of the root cause: `WHERE id = 1` matched
nothing, because `executeUpdate` filtered the RAW records while the read path materialises the rowid
into the IPK column. `executeUpdate` now applies `ipkNormalizeRows` **before** `whereHolds` runs —
the same rule `finishRows` already follows on the read side, and the reason the reporter's exact
statement looked like a silent no-op rather than a failed match.

**Historical report:** OPEN (found 2026-07-17 by `scljet-address-write` while probing the write path before
building on it). **Engine — the `scljet-m3-writes` lane.** Silent wrong behaviour: no error, no
change, `Right(image)`.

**Symptom/reproduce** — an `INTEGER PRIMARY KEY` column IS the rowid, so assigning it must RELOCATE
the row. The reference does; we ignore the assignment and say nothing:

```sql
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann');
UPDATE emp SET id = 5 WHERE id = 1;
SELECT id, name, rowid FROM emp;
--   real sqlite3 3.51.0 → 5|ann|5     (the row moved: rowid is now 5)
--   scljet             → 1|ann|1     (assignment dropped, executeUpdate returned Right)
```

**Root cause (hypothesis).** `executeUpdate` builds the new record from the assignments and rewrites
the row **at its existing rowid**; the rowid is never recomputed from an assignment to the IPK
column. Since `finishRows`/`ipkNormalizeRows` now materialise the rowid INTO the IPK column on read
(`14f4da4ac`), an assignment to that column is written into a field the reader then overwrites — so
even if the record were updated, the read would still show the old rowid. The fix has to move the
row (delete + reinsert at the new rowid, or the equivalent), not just edit the field.

**Adjacent, same probe (correct, recorded so it is not "fixed" by mistake):** `UPDATE … WHERE
rowid = 999` on a missing row returns `Right` with no change. That IS standard SQL — `changes() = 0`,
the reference agrees. It is only wrong for an *address* write, where the address names one specific
cell; `scljet/address.ssc` therefore resolves the address before writing and refuses when it does
not exist, rather than changing the engine's SQL semantics.

## swift-renderer-inventory-missing-shipped-tag — backend inventory omits a lowerer tag
<!-- status: fixed
     lane: multi
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-18, `swift-renderer-port`). The web lowerer had gained
`element("select")`/`element("option")` (2026-07-13) and CSS `flex-wrap` (2026-07-14) with no Swift
renderer equivalent. Ported them for real (chose port over declare-gap): `<select>` → menu-style
`Picker` (`NativeUiSelectControl`) two-way bound to its value `Signal`, `<option>` children decoded
into `(value,label)` entries, `<option>` alone → strict sourced `Unsupported`; `flex-wrap:wrap` on a
`flex-direction:row` `div` → real wrapping `NativeUiFlowLayout` (custom SwiftUI `Layout`). Also fixed
a co-latent bug the port surfaced: `width:100%`/`height:100%` (emitted by the shipped textField/table
styles too, not just select) hit `invalidDeclaration` → rendered a red `Unsupported` at runtime; now
mapped to `frame(maxWidth/maxHeight: .infinity)` (`width:90vw` and other non-px lengths stay rejected,
still pinned by the diagnostic test). Inventory + renderer + styles updated together in
`SwiftNativeUiApple.scala`. Added a runtime probe test (`select renders a real menu Picker and decodes
its options rather than a stub`) that compiles the generated renderer under `xcrun swiftc -swift-version
6 -strict-concurrency=complete -warnings-as-errors` and asserts `decodeSelectOptions` + Picker `.body`
construction — so the inventory entries are proven real, not stub-satisfied. `v2SwiftBackend/test` 59/0.

**Real-harness repro (was).** Run `v2SwiftBackend/testOnly ssc.swift.SwiftBackendTest -- -z "renderer
inventory"`. The assertion compares shipped lowerer tags with `SwiftNativeUiApple` inventory; do
not remove a tag or weaken subset comparison merely because Xcode execution tests are unavailable
on Linux.

## coreir-abi-int-width-declared-i32-actually-i64 — the v3 descriptor tells every foreign host that `Int` is 32-bit, when it is 64-bit
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: 9c49438d4 -->

**Status:** FIXED — `9c49438d4` (spec `4bdd5e986`, docs `ccc47efe1`), on `origin/main` 2026-07-17 (agent `int64-abi`). Sergiy decided **option (A)**
on 2026-07-16: `Int` → `I64`, make the descriptor truthful. Raised 2026-07-16 by `coreir-contract`,
who correctly escalated rather than fixing unilaterally, because the fix is a contract change.
Tracked in `SPRINT.md` §`control-interoperability`; spec `specs/numeric-width-reconciliation.md`.

**Root cause (the actual one, not the symptom).** Two distinct facts were being carried by one
field. `AbiType.Primitive` held only `value: AbiPrimitive` — the **wire width** — so the *source
spelling* (`Int` vs `Long`) had nowhere to live, and the only way the descriptor could tell two
same-name overloads apart was by giving them different widths. `Int → I32` was therefore doing two
jobs at once: declaring a width (wrongly — ssc `Int` is 64-bit) and carrying identity (accidentally).
That is why the bug could not be fixed by correcting the mapping alone: the bare one-line flip was
measured to produce `DUPLICATE_SYMBOL_ID at $.symbols: ssc:symbol:v1:5ddf0353…` for
`def widen(value: Int)` / `def widen(value: Long)` — the two overloads collapse onto one identity and
the module becomes unexportable. (It fails **closed**, which is why no silent corruption resulted from
the collision itself; the *silent* failure was always the declared width reaching foreign hosts.)

**The fix.** Split the two facts. `AbiType.Primitive(value, declaredWidth: Option[NumericWidthEvidence])`:
`value` is the wire width and is now truthful (`Int` and `Long` both → `I64`); `declaredWidth`
(`DeclaredInt`/`DeclaredLong`) retains the source spelling, carries identity, and never changes
marshalling. Both directions fail closed — an integer width without evidence is rejected as an
ambiguous legacy export (`AMBIGUOUS_NUMERIC_WIDTH`), evidence on a non-integer primitive is rejected
(`INVALID_NUMERIC_WIDTH_EVIDENCE`), and a legacy `{"tag":"Primitive","value":…}` node fails to decode
(`SCHEMA_MISMATCH … missing=[declaredWidth]`) instead of being guessed as `Long`. `AbiPrimitive` keeps
all nine cases; `I32` is now unreachable from ssc source and is reserved for option (C)'s explicit
narrowing ABI, so (A) is (C)'s first slice rather than a dead end.

**Verified.** Producer suite 83/83 (7 expectations flipped — the truth changed; the brief predicted 6),
descriptor suites 32/32 (2 normative vectors deliberately re-frozen: the symbol id moved
`453bfef3…` → `c6231fac…`, and the frozen wire fragment gained `"declaredWidth":[{"tag":"DeclaredInt"}]`),
`core/test` 1138/1138, interop 36/36, plugin-profile 23/23. P6.5 literal fixed point unchanged at
**89 ok / 0 FAIL**, `stage1 == stage2` byte-identical at **79,667 B** (output diff to baseline: empty).
New vectors `NumericWidthAbiVectorTest` were **proven non-vacuous**: reintroducing `Int → I32` makes
all 5 fail loudly with `vector overflow32: a host marshalling an ssc Int per the descriptor changed
the value from 2147483648 to -2147483648`; a sabotage probe on the validator likewise reddens the
3 rejection controls.

**Symptom.** `v1/lang/core/src/main/scala/scalascript/artifact/PreBodyApiDescriptorProducer.scala:2066`
maps source `Int` -> `AbiPrimitive.I32` (`:2067` maps `Long` -> `I64`). But ScalaScript's `Int` is
**64-bit**. So the `ssc-api-descriptor-v3` interop surface — the thing whose whole job is to tell
JS/TS, Rust, Swift and WASM-WASI hosts how to marshal our values — declares a 32-bit width for a
64-bit value. A host that believes the descriptor **silently truncates any value > 2^31-1 at the ABI
boundary**. It fails open, it is cross-language, and it is on the interop surface.

**Reproduce** (measured in the real runtime, not read off the source):

```bash
scala-cli --power package v2/src --assembly -o /tmp/ssc.jar
cat > /tmp/w.ssc0 <<'EOF'
def p = (label, s) => #io.print(#sconcat(label, s))
def main = () =>
  let a = p("2147483647 + 1        = ", #i->str(#i.add(2147483647, 1))) in
          p("9223372036854775807+1 = ", #i->str(#i.add(9223372036854775807, 1)))
EOF
java -jar /tmp/ssc.jar run /tmp/w.ssc0
# 2147483647 + 1        = 2147483648            <- did NOT wrap at 32 bits => Int is not I32
# 9223372036854775807+1 = -9223372036854775808   <- DID wrap at 64 bits     => Int is I64
```

Corroborated by `v2/specs/10-core-ir.md` §2 ("`Int` is 64-bit two's-complement, wrapping (matches
`ssc 1.0`'s `Int = Long`)") and by the durable memory note `project_interp_int64_and_entrypoint.md`
("ssc Int is 64-bit").

**Why it was not just fixed** (historical — resolved by Sergiy's 2026-07-16 decision, kept because it
explains the shape of the fix). `Int -> I32` was **not dead code**: it was asserted by live tests
(`PreBodyApiDescriptorProducerTest.scala:100,130,132,136,267,1212`), and `AbiPrimitive` is part of the
**frozen Slice A schema** that feeds `apiHash`. Changing the mapping changes the meaning *and the
hash* of every descriptor ever emitted. Three options were written up in full in `SPRINT.md`
(A: `Int`->`I64`; B: make surface `Int` genuinely 32-bit — a Core IR version bump; C: `I64` public
plus an explicit implemented narrowing ABI). **Sergiy chose (A)**, with (C) explicitly left reachable;
the contract change was announced in the rozum `scalascript` room before landing.

## coreir-compiler-unbounded-depth — a deep-but-well-formed capsule overflows the COMPILER at ~depth 500 on a 1m stack
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-20 by `coreir-compiler-depth` (reprioritized by Sergiy the same day, after
being deferred earlier that day). Found 2026-07-16 by `coreir-contract` while bounding the *reader*.
Two independent overflows hid behind one title; both are addressed, with one part deliberately left
open and re-scoped (see "What is NOT fixed" below).

**What the diagnosis actually separated.** The title says "the COMPILER", but reproducing both halves
showed two unrelated recursions with different cures:

| | Where | Frames/level | Driven by | Fix |
|---|---|---|---|---|
| **(A)** compile time | `tryFC` / `mayProduceAutoThreadOp` / `collectRegfields` | ~5 | Core IR nesting | depth bound → diagnostic |
| **(B)** run time | `evaluateRemainingAsStep` → `Runtime.value` (Runtime.scala:1512) | ~3 | the *interpreted program's* own non-tail recursion | explicitly sized VM thread |

(B) is not a bounded-input problem at all: `evaluateRemainingAsStep` recurses per *argument*
(bounded by arity), but evaluating an argument that is itself a call re-enters `Runtime.run`. That is
ordinary user recursion landing on the JVM stack, which no capsule bound can help.

**(A) — fixed by bounding.** `Compiler.MaxDepth` (default **250**, `-Dssc.compiler.maxDepth=N`) is
checked once at the top of `compileWithGlobals`, by an *iterative* worklist probe — a recursive probe
would overflow on the very input it exists to reject. `Compiler.subterms` is exhaustive with no
catch-all, so adding a `Term` case is a compile error rather than a silently under-reported depth
(which would make the guard fail OPEN). Measured to pick the number: the `tryFC` cycle costs ~5 frames
per level and a `(seq …)` chain compiles at depth 300 but overflows at 400 on `-Xss1m`, while
compiling all 85 `v2/examples` yields a maximum `Term` depth of **72** on artifacts up to 165 KB —
~3.5x headroom, below the cliff. The ladder is now: real programs run, 250–1000 hits the compiler
bound, deeper hits the existing `Reader.MaxDepth`. No `StackOverflowError` at any depth.

**(B) — fixed operationally, not architecturally.** The VM now runs on an explicitly sized thread
(`Main.onSizedStack`, default 64 MB, `-Dssc.stackSize=<bytes>`, `0` keeps the caller's thread). This
removes the dependence on an OS default that differs by platform — 1 MB Linux/CI vs 2 MB macOS, the
asymmetry that kept CI red for 192 runs while everything passed locally. Measured: `ssc0c` compiling
`examples/uselib.ssc0` overflows at 1m and 2m and needs **4m**, so the `-Xss512m` in the launchers was
a 128x overshoot. After the change that command succeeds with a bare `java -jar` and even under a
forced `-Xss1m`; user-level non-tail recursion at `-Xss1m` went from overflowing at 2 000 to running
20 000.

**What is NOT fixed — and why the playground still waits.** (B)'s cure is a bigger stack, and a
browser grants ~1 MB with no way to ask for more. A client-side VM therefore still needs an explicit
continuation stack (CEK-style) so non-tail calls live on the heap. That work is tracked in `BACKLOG`
under `site-playground`, now with the real number attached: the gap is 1 MB available vs 4 MB needed
for self-compilation — 4x, not the 512x the old launcher flag implied.

**Verification.** `v2/conformance/check.sh` 644 ok / 0 FAIL, exit 0, with each fix and with both. All
85 examples compile byte-identically before and after. Note this is a no-regression result, **not**
evidence that these fixes turned the 5 previously-documented FAILs green: `chk_compiler_diff` had
already been switched to the `sscx` 512m launcher before this work started, so those steps were
passing via that workaround. The sized thread is what makes the workaround unnecessary.

**Historical detail from the original report follows.**

**Symptom.** `Compiler.valuePositionsNeedEffectThreading` / `FastCode.tryFC` recurse without a bound.
A perfectly well-formed (nothing malformed — merely deeply nested) Core IR program overflows the JVM
stack at roughly **depth 500** on `-Xss1m`, which is the **Linux/CI default** main-thread stack;
macOS defaults to 2m, so this hides locally — the same asymmetry that kept CI red for 192 runs.

`StackOverflowError` is an `Error`, not a catchable failure: on an untrusted persisted capsule this is
a denial of service, not a diagnostic.

**Fresh gate baseline (2026-07-19, `5f39336a8`).** `v2/conformance/check.sh` naturally exits 1 with
637 ok / 5 FAIL. All five labels (`ssc0c uselib`; JS and Rust `quicksort-lib`/`zipwith`) have the same
default-stack compiler overflow, both initial run and retry, in
`Compiler.compileEffectAwareApplication` / `evaluateRemainingAsStep`; complete diagnostics are in
`$TMPDIR/ssc-conformance-logs-25835/failures.log`. The valid programs are structurally shallow (max
canonical S-expression depth: self compiler 28, JS/Rust generators 51), proving that reader depth alone
does not explain or guard this compiler recursion. The same `uselib` command succeeds at `-Xss512m`.

**Reproduce:**

```bash
python3 -c "n=500; print('(program (defs) (entry ' + '(seq '*n + '(lit unit)' + ')'*n + '))', end='')" > /tmp/d500.ir
java -Xss1m -jar /tmp/ssc.jar run-ir /tmp/d500.ir
# Exception in thread "main" java.lang.StackOverflowError
#   at ssc.Compiler$.valuePositionsNeedEffectThreading(Runtime.scala:654)
#   at ssc.FastCode$.tryFC(Runtime.scala:1886)
```

**Context / what is already done.** `Reader.MaxDepth` (default 1000, `-Dssc.coreir.maxDepth=N`) now
bounds the *decoder*, so the reader itself yields a diagnostic instead of crashing — see
`specs/coreir-codec-vectors.sh` §"bounded decoding", which tests at `-Xss1m` on purpose. But the
capsule path is only fully DoS-safe once the compiler is bounded too. Real Core IR is shallow
(measured: the 79,667 B X1 fixpoint IR is depth **25**; the `.coreir` fixtures are 6-12), so a
compiler-side bound has enormous headroom available.

**Current operational decision.** The normal-program F7 failures are handled by using the already
documented `sscx` 512m-stack launcher for stack-heavy tower generators. That restores shipped workloads
but intentionally does not change this bug's status or claim to protect adversarial capsules.

## irbin-v2bin-codec-fails-open — the deferred binary codec narrows BigInt, loses -0.0, and turns unknown tags into strings
<!-- status: unknown
     lane: multi
     area: front -->

**Status:** **3 of 4 FIXED 2026-07-27** by opus (`irbin-fail-open`, `cb8ad2863`); two sub-defects
remain and are marked below.

- **(1) BigInt narrowing — FIXED.** `IrBig` now travels as its decimal string (`big->str`/`str->big`),
  arbitrary precision like the canonical codec. A/B measured: `2^64+1` round-trips **PRESERVED** with
  the fix and **CORRUPTED** without it.
- **(3) Unknown tag / unparseable float — FIXED.** Both now decode to a named unbound global
  (`_err_irbin_unknown_tag`, `_err_irbin_bad_float`) — the tower's loud-failure idiom, same shape as
  `ssc1-lower`'s `_err_int_range`. The old codec crashed with `IndexOutOfBoundsException` on the same
  input, so the new regression case discriminates rather than agreeing with prior behaviour.
- **(2) `-0.0` still collapses — STILL OPEN.** The encoder uses `#f->str`, the USER-visible renderer.
  The canonical form is `Writer.floatLit`, which is **not exposed as a kernel prim**, so irbin cannot
  reach it from ssc0. Fixing this needs either a `floatLit` prim or an exact-bits float prim — a
  shared-kernel change, deliberately not bundled into a codec fix.
- **`IrBytes` still has no representation — STILL OPEN.** Same shape: it needs a bytes↔hex prim to
  encode what the canonical codec writes as `(bytes HEX)`.

Frozen size note: the demo moved 108 → **110 bytes**, deliberately — two bytes to stop returning
wrong values for BigInts the old encoding could not represent. Recorded in `check.sh` next to the
expectation so the change reads as intentional. New regression case
`v2/examples/irbin-failclosed.ssc0` pins all three fixed properties; `v2/conformance/check.sh`
409 ok / 0 FAIL.

**Historical report:** OPEN (found 2026-07-16 by `coreir-contract`). **Not** the canonical codec — `lib/irbin.ssc0`
is the deferred `v2-bin` experiment (`12-ir-format.md` §"Open / deferred"); the canonical text codec is
unaffected. Filed so it is fixed *before* `v2-bin` is ever promoted to a real format.

Three independent fail-open defects in `v2/lib/irbin.ssc0`:

1. **BigInt is silently narrowed to 64 bits.** `:53` encodes `IrBig(n)` as `encSVar(a1, #big->i(n))`.
   `big->i` "may overflow" per `10-core-ir.md` §5, so any BigInt outside Int64 is **corrupted**, not
   rejected. The canonical text codec is correct here (vector: "const CBig -> arbitrary precision").
2. **`-0.0` is lost.** `:54` encodes `IrFloat(d)` as `encStr(a1, #f->str(d))` — the *user-visible*
   renderer, which collapses whole doubles, so `-0.0` becomes `"0"`. This is the same root cause as the
   canonical-codec bug fixed on 2026-07-16; `irbin` should use the new `Writer.floatLit` semantics.
3. **Unknown tags become `IrStr`, and unparseable floats become `0.0`.** `:88` maps a failed `#str->f`
   to `IrFloat(0.0)`; `:89` is a bare `else` that turns **any** unrecognised tag into `IrStr`. Corrupt
   input decodes to a plausible-looking wrong program instead of an error.

Also: `IrBytes` has no representation at all in `irbin` (`grep -c IrBytes lib/irbin.ssc0` = 0), so the
binary codec cannot round-trip a bytes literal that the canonical codec now encodes fine.

## descriptor-v3-nested-owner-identity-leak — nested private identities under non-object owners fall back external
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `f4d4c01ec`, landing SHA
pending independent approval.

**Symptom/reproduce:** declare `private class Hidden { type T }` and expose a public
signature containing `Hidden.T`. Strict managed production returns `Right` with an
external `AbiType.Named("Hidden.T")` instead of rejecting the effectively private
local identity. Equivalent nested identities under traits/enums and nested local
effects/aliases have the same un-audited owner-boundary risk.

**Root cause/plan:** `collectLocalTypes` descends namespace objects but not every
nominal owner, so the known-local identity inventory is incomplete before qualified
external fallback. Collect types, aliases, and effects recursively under class,
trait, enum, and object owners; carry inherited effective visibility and whether the
owner itself is representable. Resolve any known nested identity through that
inventory before external fallback and reject private/internal/nonrepresentable
owners with the stable local-visibility error. Audit `localEffects` and alias
expansion through the same owner traversal rather than adding a type-only exception.

**Done when:** faithful class/trait/enum/object nested-owner regressions fail on the
reviewed checkpoint, then pass with stable paths/codes; prior public nested-object
positives remain green; the full focused, descriptor/core/interop/IR/ABI, and
modules/import-dir plus forced effect conformance radius passes. Keep `open` until
fresh independent approval and landing on `origin/main`.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The nested-owner regression fails because the private-class case returns
`Right` with `Named("Hidden.T")`; all previous 63 producer tests remain green.

**Local verification:** `f4d4c01ec` replaces the object-only collectors with one
recursive owner-aware inventory covering class/trait/enum/object, abstract
`Decl.Type`, inherited visibility, and receiver representability. Audit-hardening
vectors cover public-class members, a nested object below a class, known-owner/
unknown-member fallback, and the positive public-object namespace. Focused producer
passes 82/82 and full core passes 1132/1132; keep `open` until fresh review and
landing.

## descriptor-v3-import-identity-laundering — selected/imported aliases bypass canonical identity resolution
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `f4d4c01ec`, landing SHA
pending independent approval.

**Symptom/reproduce:** `import java.{lang as jl}` followed by a public `jl.String`
type succeeds as external `Named("jl.String")`; a chained `import jl.{Integer as
Int}` succeeds as `Named("jl.Integer")`. A local callback alias declared under
`object Types`, imported with `import Types.Callback`, is projected as an ordinary
parameter and receives no conservative callback policy. Selected types, importer
qualifiers, chained aliases, private identities, and platform roots therefore take
different resolution paths.

**Root cause/plan:** `ImportScope` models only final bare-name bindings and
`projectNamedApplication`/selected-name/callback-alias code bypasses expansion of
importer qualifiers. Implement one source-ordered lexical identity resolver used by
all bare and selected type projection plus callback classification. It must expand
direct/renamed importer prefixes and chained aliases, detect cycles/conflicts/
wildcards, retain exclusions/given-only behavior, apply platform-root checks after
expansion, and consult effective local visibility before external fallback. Remove
ad hoc paths that can disagree about the same spelling.

**Done when:** all three faithful repros fail on the reviewed checkpoint, then
platform chains reject and imported local callback aliases receive `ForeignBarrier`
policy; direct/rename/wildcard/exclusion/source-order positives remain green; the
full affected gates pass. Keep `open` until fresh independent approval and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. Three resolver regressions fail: selected `jl.String` and chained
`jl.Integer` return `Right`, and an imported local function alias has no callback
policy. All previous 63 producer tests remain green.

**Local verification:** `f4d4c01ec` anchors exact targets under the preceding import
environment and shares one resolver across bare/selected type projection, effect
rows, callback classification, and later importer qualifiers. Transparent aliases
snapshot their declaration-time import scope. Platform chains, selected local
prefixes, imported callbacks/effects, wildcard prefixes, private identities, and
source-order controls are green; focused producer passes 82/82. Keep `open` until
fresh review and landing.

## descriptor-v3-dual-effect-evidence-mismatch — preprocessing hides effect/object carrier disagreement
<!-- status: open
     lane: multi
     area: front -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** canonical section preprocessing rewrites raw `effect` syntax
into an object before correspondence. A CodeBlock containing an effect and a
Document carrier containing an ordinary object can consequently produce the same
stored declaration witness. The producer then silently chooses document text as
`rawSource`, so empty effect/object and multi/ordinary disagreements can pass or
change multiplicity evidence without the two retained carriers agreeing.

**Root cause/plan:** declaration witnesses compare only post-preprocess trees and
lose the raw effect-header distinction. Extract a deterministic semantic effect
evidence witness from each raw executable carrier before choosing one: effect versus
ordinary object kind, lexical name/order, plain versus multi multiplicity, and
unsupported generic/parent header shape are significant; source line offsets are
not. Package wrapping has already replaced `CodeBlock.source`, so preserve erased
empty-effect origin with reserved parser-internal `private type` sentinels rather
than runtime `val` fields or a broader new serialized source carrier. Require
CodeBlock and optional Document evidence to agree after their ordinary declaration
witnesses agree; filter the sentinels from API/runtime values and fail closed on a
packaged ordinary-object collision. Regress empty effect/object, multi/ordinary,
stale carrier positives/negatives, parser/EffectAnalysis invariance, and preserve
documentless fail-closed safety.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
the full affected gates. Keep `open` until fresh independent approval and landing.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Two raw-evidence tests fail: empty effect/object dual carriers return `Right`,
and a documentless empty effect silently becomes an ordinary value. Plain/multi
negatives, line-offset invariance, unsupported-shape rejection, and all prior effect
vectors remain green.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, compare ordered raw
effect witnesses before carrier selection and retain erased origin only in filtered
private type sentinels. Focused producer/parser/effect tests pass 75/75 (producer
63/63), descriptor 27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI
73/73, and affected conformance passes 2/2 modules/import-dir plus 9/9 effect cases.
Status remains `open` until fresh independent approval and landing; the local
commit is not a fix SHA on `origin/main`.

## descriptor-v3-array-byte-component-shadow — bytes shortcut ignores the `Byte` identity
<!-- status: open
     lane: multi
     area: codegen -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** the producer recognizes the syntax `Array[Byte]` and emits
primitive `Bytes` after checking only whether `Array` is a local type. It therefore
also emits `Bytes` when `Byte` is a generic binder or a known `@internal`, private,
or public local type, even though none of those names denotes the built-in byte
element type.

**Root cause/plan:** the bytes fast path pattern-matches the leaf spelling before
normal lexical binder/local resolution of both components. Resolve/check both
`Array` and `Byte` first (including binders and effective local visibility), and use
the shortcut only when neither spelling is shadowed. A non-public component rejects
with the ordinary stable visibility error; a public/bound component follows ordinary
type projection and must never become primitive `Bytes`. Regress a `Byte` binder,
both private and `@internal` local `Byte`, and public local `Byte`, while preserving
the existing local-`Array` vectors.

**Baseline:** regression commit `387a10384`; the focused suite is 39/46 and all
four new shadowing tests fail because the producer returns a successful descriptor
containing primitive `Bytes`. The unshadowed built-in positive remains green.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` resolves both component identities before the shortcut.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing on `origin/main`.

## descriptor-v3-codeblock-source-bypass — documentless modules skip source/AST correspondence
<!-- status: open
     lane: multi
     area: front -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** parse a packaged module, remove `document`, then change a
retained `ast.Content.CodeBlock.source` declaration header while keeping its old
tree. `topLevelStats` assigns `rawSource = None` to section code blocks when no
document snapshot exists, so correspondence is skipped and the stale tree returns
`Right`. When both document and code-block sources exist, the document source is
paired by position and the code-block source is silently ignored, so the two retained
sources can disagree without a deterministic rule.

**Root cause/plan:** retained source evidence is collected only from `DocumentContent`.
Always retain and verify every parseable executable `CodeBlock.source`, including
legacy/documentless packaged modules. When both source carriers exist, neither may
override the other silently: canonically reparse each, unwrap the manifest package
chain where its parsed shape contains it, require equal body-erased declaration
witnesses against the stored AST (and therefore against each other), then use the
document source for effect-header evidence only after agreement; otherwise fall back
to the code-block source. Regress documentless stale source and dual-source header
disagreement while keeping body-only invariance.

**Baseline:** regression commit `387a10384`; both the faithful documentless stale
`CodeBlock.source` repro and the dual-source disagreement repro return `Right` from
the old tree. They account for two of seven failures in the exact 39/46 focused run.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` verifies mandatory section source plus optional document
source against the stored AST. Focused producer 46/46, descriptor 27/27, core
1092/1092, interop 36/36, IR, artifact ABI 73/73, and affected conformance 2/2
are green. Status remains `open` until fresh independent approval and landing.

## descriptor-v3-package-wrapper-header-forgery — wrapper names match while wrapper semantics differ
<!-- status: open
     lane: multi
     area: conformance -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** for manifest package `demo.api`, replace the stored synthetic
wrapper with `object demo extends Serializable: object api: ...` while retained source
still has the plain declaration. `unwrapPackage` requires only one object and the
expected name, discards the wrapper header, and compares identical inner declarations,
so strict production returns `Right`.

**Root cause/plan:** the wrapper chain is structurally unique but not header-exact.
At every manifest package segment require the exact plain synthetic wrapper shape:
no modifiers, parents/inits, derives, self type, or any other non-body template/header
state, plus exactly one expected child wrapper until the leaf. Reject a forged wrapper
at the block path before inner declaration correspondence. Add the exact
`extends Serializable` stored-AST regression.

**Baseline:** regression commit `387a10384`; the forged-wrapper repro returns
`Right` and projects the inner API. It is one of seven failures in the exact 39/46
focused run.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` requires the exact plain wrapper at every package segment.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing.

## descriptor-v3-nonpublic-local-type-leak — private local types fall back to external names
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** a public signature using `Hidden.T`, where `Hidden` is a
private/internal local owner, is accepted as an external `AbiType.Named` instead of
being rejected. Likewise an `@internal` local alias for a callback type can be
exported without expanding to the callback shape, bypassing the mandatory callback
policy. The same bypass survives through a non-exported public wrapper alias, and
the built-in `Array[Byte]` fast path can outrun a private local `Array` declaration.
A qualified private local effect can likewise fall back to an external effect-row id.

**Root cause/plan:** the lexical type index records only public declarations and
recurses only through public owners. A known local declaration hidden by visibility
therefore becomes indistinguishable from an actually external qualified type. Index
all local type identities and aliases with effective owner visibility; if resolution
finds a known non-public local identity, fail with stable `UNSUPPORTED_PUBLIC_TYPE`
before external-name projection or callback classification. Regress both the private
qualified-owner and internal callback-alias shapes, a public alias chain, absolute
fully-qualified selection, the shadowed `Array[Byte]` fast path, and a private local
effect row. The bytes shortcut is valid only when lexical lookup finds no local
`Array`; a public local `Array` must retain ordinary local-constructor projection,
while a non-public one rejects before the shortcut.

**Baseline:** focused producer test accepts `Hidden.T` and `Callbacks.Hidden` as
external `AbiType.Named` values; the latter has no callback policy. These are two
of four expected failures in the `25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` covers relative and absolute private owners, direct and
wrapped callback aliases, private/public local `Array` shadowing, and a private
local effect. Focused producer 46/46, descriptor 27/27, core 1092/1092, interop
36/36, IR, artifact ABI 73/73, and affected conformance 2/2 are green. Status stays
`open` until fresh independent approval and landing on `origin/main`.

## descriptor-v3-source-ast-correspondence-tamper — count-only retained-source check accepts stale declarations
<!-- status: open
     lane: multi
     area: front -->

**Status:** open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** parse an effect containing operation `read`, then copy the
module so its retained executable source is only `effect Real:\n` while preserving
the old section AST. The strict producer sees the same number of source and AST
containers and returns `Right`, projecting the stale `read` operation that no longer
exists in retained source.

**Root cause/plan:** `topLevelStats` checks only retained-block counts before pairing
source with AST by position. Reparse each retained executable block through the
canonical declaration preprocessor/parser and compare an exact declaration-header
shape with its paired section AST while deliberately ignoring executable bodies and
comments. Reject a mismatch with stable `UNSUPPORTED_PUBLIC_DECLARATION`; preserve
body-only descriptor/hash invariance and add the exact copy/tamper regression.
Require a unique synthetic package-wrapper chain, normalize placeholder type aliases
symmetrically on both trees, and prove that body/RHS/default-expression-only retained
source changes remain accepted against the same stored declaration AST. Witness all
current ScalaMeta definition headers explicitly, including template/alias givens,
extension groups, and macros; never use a product-prefix-only fallback that would
accept a changed unsupported declaration header.

**Baseline:** focused producer test returns `Right` and still exports
`demo.api.Real.read` after retained source removes that operation; this is one of
four expected failures in the `25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` reparses and compares exact body-erased headers, normalizes
placeholder aliases, requires exact/plain wrappers, verifies both retained source
carriers, and conservatively covers every current ScalaMeta definition form.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status stays `open`
until fresh independent approval and landing on `origin/main`.

## scala-direct-polymorphic-value-select — moved structural apply retains `<none>`
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; remediation is green in feature commit `b6d2cd262` on frozen
`origin/main` base `6603e6c29`, but fresh independent review and the landing SHA
are pending. Reported as P1 by the fresh independent review of frozen candidate
`f4e860ed7..408f23c11` (2026-07-15).

**Faithful packaged reproduce:** compile with Scala CLI 3.8.3 against only the
packaged `scalascript-control_3` JAR. Inside `direct.reset`, place the ordinary
strict value
`val identity: [A] => A => A = [A] => (a: A) => a` before a block-level
`direct.shift`, then evaluate `selected + identity[Int](2)` in the captured suffix.
The direct source fails at the suffix call with raw compiler output
`undefined: identity.<none> ... TermRef(... val <none>)` twice. The explicit
`reset`/`shift` equivalent compiles, runs, and prints `42`.

**Root cause:** reference replacement rebuilt every moved `Select` with the source
`selection.symbol`. The structural `PolyFunction.apply` selection has
`Symbol.noSymbol`; copying that placeholder onto the moved qualifier constructed
the invalid `identity.<none>` term. A self-contained `PolyType`/`ParamRef` binder
graph is already closed when retained atomically. A rejected broad-cloning attempt
instead split rank-2/owner-dependent graphs, so only graphs that actually depend on
replaced owners are rebound.

**Implementation/verification:** moved selections first transform their qualifier.
When the source symbol is absent, `Select.unique` resolves the member from that
current qualifier; resolution failure becomes stable `DIRECT_STYLE_UNSUPPORTED`
before generated code is emitted. Independent polymorphic values, prefix/suffix
calls, explicit `.apply[Int]`, ordinary monomorphic function application, and
result/bound-only `ParamRef` cases execute; an owner-dependent nested polyfunction
fails closed at its declaration. Clean focused tests pass 51/51 (24 semantics and
27 diagnostics), the full leaf/package/POM gate passes 113/113, and the packaged
Scala CLI 3.8.3 consumer prints fourteen differential `42` values. Its packaged
negative reports only the stable unsupported diagnostic. Catalog validation is
26 vectors/9 lanes, validator negatives are 9/9, `scala-direct` is 3/3, and
affected conformance is 5/5. Keep this entry open until rereview approves and the
fix lands.

## scala-direct-captured-type-owner — captured A keeps a stale prefix owner
<!-- status: open
     lane: multi
     area: codegen -->

**Status:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** declare a local owner before capture and use its singleton
type as the captured result, for example
`direct.shift[scope.Key, owner.type, Nothing, Int](...)`. The prefix owner is
freshened, but the captured `A` used to type the generated explicit continuation
still names the original owner. Scala emits raw E007/owner-versus-owner² output.
The same failure occurs for `A = Prompt[inner.Key, Int]`; the equivalent explicit
control program compiles and prints `42`.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails the singleton case at the enclosing reset expansion with E007: found
`(owner : Object)`, required `(owner² : Object)`. This confirms a generated-type
owner split rather than a test-classpath artifact.

**Root cause/plan:** `explicitShift` opens `found.typeArguments(1).tpe.asType`
without applying the capture split's prefix replacement map, then moves and casts
the rank-2 body against that stale type. Rebind the captured type through the same
supported dependent/singleton type substitution used by prefix declarations and
use the rebound type consistently for the explicit shift and moved continuation
body. Prefer supporting both common singleton and local-prompt shapes; any truly
unsupported type must reject at the source marker with stable
`DIRECT_STYLE_UNSUPPORTED`, never a generated compiler error. Add faithful
packaged-consumer semantics for both shapes and the explicit differential.

**Implementation/verification:** the captured type is rebound through the active
term/type replacement graph before `asType`, and the rebuilt type is used by the
rank-2 body, explicit shift, and generated bind continuation. Source regressions
cover both `owner.type` and `Prompt[inner.Key, Int]`; the packaged Scala CLI 3.8.3
consumer executes their direct and explicit forms and prints `42` for each. These
cases are part of the clean 21/21 semantics suite and the 109/109 full leaf gate.

## scala-direct-moved-term-type-owner — moved RHS and suffix symbols keep stale owners
<!-- status: open
     lane: multi
     area: cli -->

**Status:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** before a direct marker, declare
`val owner = new Object` and
`val f: () => owner.type = () => owner`, then use `f` after capture. Although the
outer `ValDef.tpt` is rebound, an inferred/nested symbol type inside the moved RHS
still points at the old owner and Scala reports raw E007/quote-owner output. A
declaration in the captured suffix can retain the same stale owner graph. The
equivalent explicit program compiles and prints `42`.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails at the lambda RHS with E007: found `() => (owner : Object)`, required
`() => (owner² : Object)`.

**Root cause/plan:** term-reference substitution and the cloned declaration's top
type do not cover every owner-bearing type attached to nested moved definitions or
suffix terms. Audit the complete moved term after owner change, rebind the common
function/lambda and suffix declaration symbol/type graphs, and verify no replaced
old symbol remains. If Quotes cannot rebuild a particular graph soundly in M1,
reject it before constructing generated code with stable source-located
`DIRECT_STYLE_UNSUPPORTED`. Add prefix-RHS and suffix packaged regressions plus an
explicit differential; no raw E007 path is acceptable. Correct the spec and
CHANGELOG wording that currently claims dependent-owner completion too broadly.

**Implementation/verification:** definition-type rebinding reconstructs supported
method/poly binders and closure methods/parameters under the generated owner, then
audits moved terms for every replaced term/type symbol. Prefix and suffix
`() => owner.type` direct/explicit regressions all print `42` in the packaged
consumer. Unrepresentable richer graphs reject before code construction with the
stable unsupported diagnostic. These cases are green in feature commit
`a8f321d5c`, the clean focused 47/47 gate, and the full 109/109 leaf gate.

## scala-direct-contextual-forward-reference — prefix cloning breaks lazy givens
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** put compiler-lazy parameterless givens before a later marker,
including a forward/mutual pair such as `given first: TC = second` and
`given second: TC = first`. The givens remain unused, so the equivalent explicit
program compiles and prints `42`, but direct lowering moves the first RHS before a
fresh symbol for `second` exists and leaks an old owner/reference.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails at `given first: TC = second` with raw macro output: `a reference to
given instance second was used outside the scope where it was defined`.

**Root cause/plan:** `preparePrefix` allocates and records each fresh symbol only
after moving that declaration's RHS, which is valid only for backward references.
Allocate the supported crossing value symbols in a first phase, preserving flags
and rebinding their types, then move every RHS with the complete replacement map.
Support ordinary compiler-lazy given forward/mutual term references as promised by
the current spec. Any dependent type cycle that cannot be allocated soundly must
fail closed at its declaration. Add a real packaged consumer for forward/mutual
givens and keep ordinary strict sequencing/shared mutable-cell regressions green.

**Implementation/verification:** prefix lowering now allocates every supported
fresh value symbol before moving any RHS, then moves initializers with the complete
replacement map while retaining compiler `Given`/`Lazy` flags. The unused
forward/mutual-given direct and explicit programs compile and each print `42` in
the packaged consumer. The regression is green in feature commit `a8f321d5c`, the
clean 21/21 semantics suite, and the full 109/109 leaf gate.

## scala-direct-nested-reset-prompt-marker — outer marker survives in eager nested-reset prompt
<!-- status: open
     lane: multi
     area: cli -->

**Status:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported by the root
agent's adversarial pre-review of the `scala3-control-macros` feature checkpoint
`9c6850904` (2026-07-15).

**Symptom/reproduce:** inside an accepted outer `direct.shift` rank-2 `ShiftBody`,
use a second exact `direct.shift` targeting the outer scope as the prompt argument
of a nested `direct.reset`, while keeping the nested reset body ordinary. The prompt
expression is evaluated before entering the nested delimiter, but the current
survival audit skips the whole nested-reset `Inlined`/`Apply` tree. The outer marker
therefore survives macro lowering and can fail through a raw compile-time-only or
quote-owner path instead of the stable M1 diagnostic.

**Observed baseline:** a Scala CLI 3.8.3 compile against only the packaged
`scalascript-control_3` JAR reproduces the prompt-argument shape and fails on the
nested call's closing `)` with the raw compiler message `While expanding a macro,
a reference to parameter contextual$2 was used outside the scope where it was
defined`. This is a real packaged-consumer failure, not a source-inspection-only
hypothesis.

**Root cause/plan:** the nested-managed-reset exception is too broad. Refine the
specification first: only the nested reset's managed body/inline expansion belongs to
that nested transform; its eager prompt (and any other eager call arguments) remain
inside the enclosing `ShiftBody` audit. Parse the exact nested-reset call shape,
inspect those eager arguments for exact direct markers, and skip only the managed
body/expansion. Add an exact negative regression, while retaining positive coverage
for an ordinary nested managed reset body and explicit `scalascript.control.shift`.
Run the clean focused/full/package/consumer/catalog/conformance gates and freeze a
new checkpoint for independent review before landing.

**Implementation/verification:** the survival audit now parses the exact curried
nested-reset call, traverses its eager prompt, and skips only the contextual body
owned by the nested transform; an unknown call shape fails closed. The faithful
negative reports the inner marker's stable `DIRECT_STYLE_UNSUPPORTED`, while the
ordinary nested managed body and explicit `scalascript.control.shift` positives
remain executable. Clean focused suites pass 39/39, the full leaf passes 101/101,
and the rebuilt packaged-JAR consumer no longer emits raw owner output. Package,
POM, catalog 26/9, validator negatives 9/9, direct lane 3/3, and affected
conformance 5/5 are green. Keep this entry open until rereview approves and the
fix lands.

## scala-direct-boundary-break-escape — boundary break can outlive its delimiter
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; behavior and the missing alias/provenance regressions are green at
feature checkpoint `a8f321d5c`, but fresh independent review and the landing SHA
are pending. Originally
reported as P1 by the rereview of frozen `scala3-control-macros` checkpoint
`ec4eb279e` (2026-07-15); regression gap reported as P2 on 2026-07-15.

**Symptom/reproduce:** place `scala.util.boundary.break(value)` in a
`direct.reset` body, including a pure prefix before a later `direct.shift` or a
captured suffix. The macro may move the call below `Eff.defer` or a generated
continuation. When the resulting computation runs, its source `boundary` delimiter
has already returned, so the break escapes through delayed code instead of
retaining Scala's lexical boundary semantics.

**Root cause/plan:** the current external-return audit recognizes `Return` trees
but not Scala's library-level boundary control marker. M1 cannot prove that any
`boundary.break` call remains dynamically enclosed after defer/CPS movement, so
reject every such call conservatively with a stable direct-style diagnostic at
the exact break invocation. Keep returns local to a nested method accepted, and
add pure-prefix/suffix negatives proving no raw boundary exception or quote error
leaks.

**Implementation/verification:** the pre-lowering control audit recognizes the
exact `scala.util.boundary.break` overload symbols both as ordinary calls and
through inline provenance, then rejects at the invocation before `Eff.defer` is
constructed. Pure-body, captured-suffix, imported method alias, explicit-label,
module-alias, and transparent-inline provenance regressions are committed and pass
in the clean 26/26 diagnostics suite; the full leaf is 109/109 and the complete
package/catalog/conformance gate is green. Keep this entry open until rereview
approves and the fix lands.

## scala-direct-transparent-inline-position — wrapper diagnostic points at reset
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** invoke a transparent-inline wrapper around
`direct.shift` inside `direct.reset`. The transform correctly rejects the inline
expansion, but its primary position is the enclosing `direct.reset` rather than
the wrapper invocation, so the diagnostic does not identify the unsupported
source construct.

**Root cause/plan:** inline-boundary rejection reports the moved/body tree span
after compiler wrapping has widened it instead of retaining the nearest
`Inlined.call` source position. Track the closest provenance-bearing inline call
while descending and report `DIRECT_STYLE_UNSUPPORTED` there. Preserve the
existing unexpanded-inline application path and freeze exact message, line
content, and zero-based column for both shapes.

**Implementation/verification:** inline traversal keeps only non-empty
`Inlined.call` positions on a nearest-first stack and falls back to the marker when
the compiler supplies no call span. The transparent-inline regression reports the
wrapper invocation exactly; the earlier unexpanded-inline path remains unchanged.
Both pass in the 21/21 clean diagnostic suite; keep this entry open until rereview
approves and the fix lands.

## scala-direct-nested-shift-body-marker — direct marker survives inside ShiftBody
<!-- status: open
     lane: multi
     area: codegen -->

**Status:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** write an accepted block-level `direct.shift`, then place a
second exact `direct.shift` inside the outer marker's rank-2 `ShiftBody`. The outer
body is currently treated as wholly opaque, so the nested marker survives macro
lowering and later fails through a raw compile-time-only/ownership path rather
than a stable M1 diagnostic.

**Root cause/plan:** the shift-body exemption distinguishes its rank-2 lambda from
an ordinary crossed callback, but it also skips the invariant that no direct
marker may remain in the emitted tree. Scan the opaque body specifically for exact
managed direct markers and reject them at their source call with a stable
`CAPTURE_BARRIER`/`DIRECT_STYLE_UNSUPPORTED` diagnostic. Do not reject ordinary
explicit `scalascript.control.shift`/`Eff` code or a separately managed nested
`direct.reset`; add all three regression shapes.

**Implementation/verification:** every accepted marker now receives a narrow
ShiftBody survival scan. It rejects only the exact nested `direct.shift`, skips a
separately managed nested `direct.reset`, and leaves ordinary explicit
`scalascript.control.shift`/`Eff` code untouched. Exact negative plus both positive
families pass across the 16/16 semantics and 21/21 diagnostics suites; keep this
entry open until rereview approves and the fix lands.

## scala-direct-dependent-prefix-type-owner — freshened values retain stale type refs
<!-- status: open
     lane: multi
     area: codegen -->

**Status:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** create a local prompt scope before capture, then declare a
dependent value such as `Prompt[innerScope.Key, Int]` (or a value typed with
`owner.type`) and use it after `direct.shift`. Term references in initializers are
freshened, but the declaration's `tpt.tpe` still refers to the old local symbol;
Scala emits a raw `E007`/owner error from generated code. The common nested local
prompt shape therefore fails despite ordinary strict locals being advertised as
supported.

**Root cause/plan:** prefix freshening substitutes term trees only and reuses the
original quoted type tree unchanged. Rebind dependent/singleton type references
to the fresh symbols before creating each new declaration, preserving declaration
order, mutable/given/pattern flags, and shared-cell behavior. Support the common
local nested-prompt case; if a type shape cannot be soundly rebound in M1, fail
closed at the declaration with stable `DIRECT_STYLE_UNSUPPORTED`, never a raw
quote/type error. Add semantic coverage for dependent prompt and owner-singleton
flow plus diagnostics for any deliberately unsupported shape.

**Implementation/verification:** prefix cloning now moves the initializer first,
rebuilds affected `Select` and type trees, recursively rebinds supported
dependent/singleton `TypeRepr` paths, and fails closed for a dependent lambda type
whose binder graph M1 does not clone. The semantic regression carries a local
prompt plus `owner.type`, dependent mutable/given/pattern values, and a suffix
ascription across capture; the diagnostic regression freezes the unsupported
polymorphic case. Focused suites pass 37/37 and the full leaf passes 99/99; keep
this entry open until rereview approves and the fix lands.

## scala-direct-lazy-marker-eager — lazy marker initializer is lowered eagerly
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; remediation and regressions are green on the feature branch
(2026-07-15), but fresh independent rereview and the landing SHA are pending.
Reported as P1 by the independent `scala3-control-macros` review of frozen
checkpoint `fa992fd92`.

**Symptom/reproduce:** an unused `lazy val selected = direct.shift(...)` inside
`direct.reset` increments a counter in the shift body even though ordinary Scala
would never force the initializer. The computation returns its unrelated tail
value, but the counter is already one.

**Root cause/plan:** the block-level marker search accepts every non-mutable
`ValDef`, including `Flags.Lazy`, before the nested-marker traversal can classify
the lazy initializer as a capture barrier. Exclude lazy bindings from the accepted
marker form so the existing barrier walk reports exact `CAPTURE_BARRIER`. A strict
lazy declaration that would remain live across a later capture is separately
outside M1 and must fail closed rather than be moved or forced.

**Implementation/verification:** lazy marker declarations are excluded from the
accepted marker bind, so traversal reports the frozen lazy-initializer
`CAPTURE_BARRIER`; an ordinary lazy prefix before a later capture is rejected
without forcing it. Both regressions pass in the 16/16 clean-compiled diagnostic
suite; keep this entry open until rereview approves and the fix lands.

## scala-direct-prefix-owner-split — local declarations lose ownership across capture
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; remediation and regressions are green on the feature branch
(2026-07-15), but fresh independent rereview and the landing SHA are pending.
Reported as P1 by the independent `scala3-control-macros` review of frozen
checkpoint `fa992fd92`.

**Symptom/reproduce:** declare a local `val`, `var`, `given`, destructuring bind,
method, class, or type before `direct.shift`, then reference it from the shift body
or captured suffix. The macro fails with a raw `reference ... was used outside the
scope where it was defined` compiler error. The same failure occurs for a value
between sequential shifts. Existing shared-heap coverage used an external variable
and did not exercise the local cell that actually crosses capture.

**Root cause/plan:** the lowering moves prefix statements separately from the
generated continuation and only substitutes the marker result; it neither clones
strict local value symbols into the generated owner nor rewrites later references.
Clone/rebind ordinary strict `val`/`var`/`given` symbols in declaration order,
including pattern-generated synthetic `ValDef`s, so Scala performs its normal
closure boxing for a shared mutable cell. Until M1 models richer definition
ownership, fail closed when a local method, class, type, or lazy cell crosses a
capture. Add semantic and exact-diagnostic regressions before rereview.

**Implementation/verification:** capture splits now clone strict local value
symbols in declaration order and carry their replacement map through prompt,
shift body, suffix, and sequential markers. Scala closure conversion therefore
shares one captured mutable cell. Local method/class/type/lazy crossings reject
with exact diagnostics. The expanded semantics suite passes 14/14 and diagnostics
16/16 after a clean test compilation; keep this entry open until rereview approves
and the fix lands.

## js-control-direct-shorthand-value-symbol-capture — property symbol hides suffix capture
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open; repair candidate `ec95c4c65` is locally verified and awaits fresh
independent review plus landing. Reported as P1 by independent rereview of exact
frozen HEAD `c4377fabb` on 2026-07-15 (current rebased equivalent `58de23cf1`).

**Symptom/reproduce:** inside a shift body, save or evaluate a closure/expression
reading `({ later }).later`, then declare suffix `const later = 42`. The current
lexical scan calls `checker.getSymbolAtLocation` on the identifier in the shorthand
property and receives the property symbol rather than the referenced value symbol.
The crossing is missed; transformed execution throws `ReferenceError` instead of
the original lexical behavior.

**Required fix/verification:** centralize runtime-value symbol lookup and use
`checker.getShorthandAssignmentValueSymbol` for shorthand property names, falling
back to ordinary checker identity everywhere else. The shift-body shorthand must
fail file-atomically with one `JS_DIRECT_CAPTURE_BARRIER` on the source identifier;
ordinary property names, genuine shadowing, and type-only references remain
accepted. Add assignment-initializer shorthand coverage where the compiler AST
permits that form.

**Root cause/fix candidate:** `getSymbolAtLocation` returns the synthesized property
symbol for `ShorthandPropertyAssignment`. The candidate uses
`getShorthandAssignmentValueSymbol` in the same runtime-value resolver used by
marker ownership and continuation checks. Real JavaScript property and assignment-
initializer regressions now select `later`, emit one capture diagnostic, and retain
the untouched file when diagnostics are ignored.

## js-control-direct-forward-lexical-capture — shift body escapes declarations moved into the suffix
<!-- status: open
     lane: multi
     area: codegen -->

**Status:** open; cumulative repair candidate `c19d42401` with marker-layer closure
`4c6b8e2a9` is locally verified and awaits fresh independent review plus landing.
Reported as P1 on 2026-07-15 by the independent pre-integration review of frozen
direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** inside `direct.reset`, let a shift body save a closure that
reads `const later = 42`, with `later` declared after the marker. TypeScript and the
transform report no diagnostics, but lowering leaves the shift body outside the
generated `.flatMap` callback while moving `later` inside it. Running the saved
closure throws `ReferenceError: later is not defined` instead of returning `42`.
References to the marker's own declaration region have the same scope hazard,
including references hidden in nested closures.

**Required fix/verification:** specify and implement a sound rule: either preserve
binding/evaluation semantics through exact dependency-aware rebinding, or fail closed
before emit. Cover forward and own-marker references, nested closures, shadowing,
and declaration-initializer evaluation order; the accepted cases must remain
prefix-once/suffix-per-resume.

**Root cause/fix candidate:** the closed grammar rejected structural frame barriers
but did not compare value references with bindings moved across generated
continuations. Checker-symbol scans now reject own/later references in each marker
layer, including nested syntax, while retaining type-only, preceding, and shadowed
cases.

## scljet-update-ipk-does-not-move-rowid — `UPDATE t SET <ipk>=N` rewrites the column but leaves the rowid
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`v2-f5b-typed-locals` batch C). An `INTEGER PRIMARY KEY`
assignment now MOVES the row, as the reporter's "fix sketch" prescribed. Implementation
(`scljet/sql.ssc`):

- `EditRow` carries `newRowid` alongside `rowid`; `targetRowidOf` reads the assigned IPK value.
- `executeUpdate` `ipkNormalizeRows` **before** the WHERE (that half is the sibling entry
  `scljet-update-ipk-column-silently-ignored`), and passes `tableIpkIndex` down.
- `applyUpdates` deletes **every** old rowid before inserting **any** new one. Per-row
  delete-then-insert corrupts a swap (`1→2` together with `2→1`): the second delete would remove the
  row the first insert just placed.
- Collisions are refused **before** anything is written, so a rejected UPDATE leaves the image
  untouched — two moved rows landing on one rowid, or a move onto a row that is not itself moving:
  `UNIQUE constraint failed: rowid N already exists`. A non-integer target is refused
  (`datatype mismatch: INTEGER PRIMARY KEY must be assigned an integer`) rather than coerced.
- The indexed path (`updatedSqlRows` → `reindexTable`) applies the same target rowid, so the
  statement cannot move the row on an unindexed table and silently not move it on an indexed one.

**Measured A/B** (the case would otherwise have proved nothing — see the note below): with the move
disabled in the staged tree, `UPDATE emp SET id = 5 WHERE id = 1` gives
`SELECT rowid, id, name` → `1|1|ann`; with the fix → `5|5|ann`, matching real SQLite's `5|ann|5`.
New conformance case `tests/conformance/scljet-update-ipk-moves-rowid.ssc` covers both statement
forms, the non-IPK update, the collision, and the non-integer target.

⚠️ The case originally projected `id` alone and I read an A/B as "the test is blind to the move" —
**wrong on two counts**: the patch had gone into the unused staged copy (`bin/lib/native-front/…`
instead of the `standard/…` one the launcher prefers), and `id` does follow the rowid on read. The
case now selects `rowid` explicitly so the two cannot be confused.

**Semantics NOT changed here (deliberate, and still divergent from SQLite):** scljet stores the IPK
value in the record; real SQLite stores NULL there and lets the rowid carry it. The whole corpus
(and `physicalBytes` in the address cases) is built on the current convention, so switching it is a
separate, file-format-level change.

**Historical report:** OPEN (found 2026-07-16 by the `scljet-ipk-rowid` lane, while verifying that the read
substitution `14f4da4ac` does not regress `UPDATE`). **Pre-existing write-path gap — NOT a
regression from that fix.** Low severity relative to the read bug: it needs an explicit `UPDATE` of
an IPK column, which is rare.

**Symptom** (measured, both engines, same statements):

```
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann')
UPDATE emp SET id=5 WHERE name='ann'
                      → (rowid|id|name)
real SQLite           → 5|5|ann     ✓ the row MOVES to rowid 5
scljet (post-14f4da4ac) → 1|1|ann   ✗ the rowid never moved
real SQLite reading scljet's file → 1|1|ann   (agrees with us about what the file says)
```

**Root cause.** In real SQLite an IPK column IS the rowid, so assigning to it moves the row to a new
rowid (and fails on a duplicate). scljet's `executeUpdate` treats the IPK as an ordinary column: it
rewrites the record field and leaves the rowid alone.

**Why the read fix improves this rather than breaking it.** Before `14f4da4ac` scljet reported the
stored column (`id=5`) while real SQLite reading the very same file reported the rowid (`id=1`) —
the two engines *disagreed about our own file*. Now both say `1`: we are honestly reporting what the
file contains. The remaining bug is that the file is not what SQLite would have written. Pinning
this in a test therefore requires deciding the intended semantics first (move the rowid), not just
asserting today's output.

**Fix sketch.** In `executeUpdate`, detect an assignment to the IPK column (`tableIpkIndex`) and
re-key the row: delete + reinsert under the new rowid, erroring on a duplicate exactly as a
duplicate `INTEGER PRIMARY KEY` does today via `leafInsertCell`. Also update any index entries,
which store the rowid as their tail.

## js-control-npm-license-omitted — package tarball lacks Apache license
<!-- status: fixed
     lane: multi
     area: build
     fixed-in: 0d0ffcfd3 -->

**Status:** done in `0d0ffcfd3`; confirmed closed by independent second
pre-integration review. Originally reported as a P2 packaging defect on
2026-07-15; affected pre-land package commit `2a34d7ed3` and verification
`c53294fa7`.

**Symptom/reproduce:** run `npm pack --dry-run --json` in
`v2/host/js/control`. The package contains only `README.md`, `index.d.ts`,
`index.js`, and `package.json`; consumers do not receive the repository's Apache
2.0 license text.

**Root cause:** the package's explicit `files` allow-list omitted a package-local
copy of the repository license, and the original four-file pack oracle encoded the
omission as success.

**Fix/verification:** the repository Apache 2.0 text is copied byte-for-byte into
the package and included in the exact allow-list. The package test compares both
files; `npm pack --dry-run --json` reports exactly five entries, including the
10,837-byte `LICENSE`, with no bundled dependency.

## js-control-effect-owner-type-collision — descriptor ID is mistaken for owner identity
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: cf8f96200 -->

**Status:** done in reachable `origin/main` landing `cf8f96200`; the independent
rereviewer confirmed both inferred and explicit union-owner rejection. Reopened as
P1 on 2026-07-15 after the second pre-integration review rejected the first repair.

**Symptom/reproduce:** two `defineEffect("same.id")` calls create distinct runtime
owners, but both declarations currently produce `Effect<"same.id">`. TypeScript
therefore accepts handling an operation from the first key with the second key as
`Eff<never, A>`; runtime correctly forwards the request as unhandled.

**Root cause:** `Effect` carried only its stable descriptor literal. TypeScript
cannot generate a fresh phantom type for each ordinary function call, so distinct
runtime keys collapsed to the same declaration type.

**First fix (insufficient):** `Effect<Id, Owner>` carries a named `unique symbol`
owner supplied to `defineEffect(id, owner)`; inline and widened symbols are
rejected. Runtime registration is idempotent for one owner+descriptor and rejects
descriptor conflicts, aligning the phantom with authority. Positive handler
inference, cross-owner negative/residual typing, and same-ID runtime forwarding all
pass.

**Second-review repro:** let `CollapsedOwner` be
`typeof FirstOwner | typeof SecondOwner`, and let ordinary cast-free functions
return that union. The current guard rejects only the broad `symbol`, so inference
or an explicit `CollapsedOwner` type argument accepts both calls and gives them the
same `Effect<Id, CollapsedOwner>`. A wrong-owner handler again typechecks as
`Eff<never, A>` while runtime owner matching forwards the request.

**Second fix/verification:** private `IsUnion` and `SingleUniqueSymbol` guards now
reject both inference-only and explicit-generic union owners, while stable named
owner reuse remains positive. `npm run typecheck` passes all original fixtures and
the exact cast-free second-review repros.

## descriptor-v3-effect-header-evidence-misbinding — comments and same-name objects corrupt effect evidence
<!-- status: open
     lane: multi
     area: runtime -->

**Status:** open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** inserting `/* effect Phantom: */` before a valid
`effect Real` changes a successful projection into
`UNSUPPORTED_PUBLIC_DECLARATION`, so comments/body text are not invariant. An
ordinary unexported `Left.Choice` object encountered before an exported
`Right.Choice` effect can also consume the latter's erased source header and make
the real effect fail with missing evidence.

**Root cause/plan:** effect headers are found by a raw line regex and then assigned
to transformed objects by bare-name preorder. Replace the scan with a
comment/string-aware lexical projection, bind evidence to the structurally marked
effect candidate and lexical owner/order, and fail closed when an empty same-name
effect cannot be bound unambiguously. Add both faithful regressions.

**Baseline:** focused producer suite reproduces both failures: the comment/string
fixture reports the phantom header, and the ordinary object leaves the real effect
without evidence (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` binds lexically scrubbed evidence to the exact effect owner.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing on `origin/main`.

## jvm-bytegen-letrec-env-clobber — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: multi
     area: codegen
     fixed-in: 956b42539
     confirmed: no -->

**Status:** fixed in `956b42539`; awaiting reporter confirmation. Found by the
stack-safety focused VM/direct-ASM vector while qualifying effectful `While`
lowering.

**Symptom:** a non-tail local `LetRec` can corrupt a surrounding expression's
lexical environment on direct ASM. The effectful-loop witness completes its
handler, then a sequence suffix reading the outer long cell fails with
`ClassCastException: Value$ClosV cannot be cast to Value$LongCellV` at generated
`Entry.lam$2`; the VM returns the expected cell value.

**Reproduce:** generate direct ASM for an outer `Let(lcell.new(0), ...)` whose
first non-tail expression contains a local `LetRec` and whose following suffix
evaluates `lcell.get(Local(0))`. The effectful-While lowering in
`PortableEffectsStackSafetyTest` is one faithful witness; add a smaller generic
non-tail-`LetRec` plus outer-local-read regression as the independent guard.

**Root cause:** `JvmByteGen.gen(Term.LetRec)` stores the tied
`captured ++ closures` frame into JVM local slot 0 while generating the LetRec
body. It restores the Scala emitter's slot/target metadata afterward, but never
restores the runtime env array. A later argument or sequence chain therefore
receives an extra closure at the end of its frame, changing every De Bruijn
lookup.

**Fix/verification:** direct ASM now saves the caller env in a private JVM local,
installs the tied frame only for the `LetRec` body, and restores slot 0 while
leaving the expression result on the operand stack. Both residual-forwarding
handler-root filters remain on the pending and body target maps. The generic
non-tail-`LetRec` outer-local regression and the 20,000-iteration effectful
`While` vector pass on VM/direct ASM; the installed axis-20 ASM lane also returns
exact `100000`, `100000`, `20007`, `20000`.

## scala3-control-effect-key-row-elimination — FIXED / awaiting confirmation (2026-07-14, Codex)
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: 528d73af3
     confirmed: no -->

**Status:** fixed in `528d73af3`; awaiting reporter confirmation. Found by the `api_type_design`
implementation audit against the uncommitted `scala3ControlApi` reference model;
reported by codex-interop in the `scalascript` rozum room.

**Symptom:** `EffectKey[+Fx]` and public `named[Fx](id, witness)` permit distinct
runtime keys for the same static `Fx`. `handle[Fx, Nothing, ...]` matches one key,
forwards an operation using the other, yet returns `Eff[Nothing, ...]`. Covariance
also permits one key to masquerade as a key for a union row and falsely remove every
member.

**Reproduce:** create `k1` and `k2` for one effect type, perform an operation whose
`effect = k2`, handle that effect type with `k1` and `Residual = Nothing`, then pass
the accepted result to `Eff.runPure`. The current implementation reaches the
forwarded request despite the empty static row.

**Fix/verification:** `EffectKey` and `Operation` are invariant, each public key is
owned by one exact singleton witness, and runtime matching uses that owner identity.
The same owner with a conflicting descriptor is rejected. Compile-time regressions
reject `Nothing`, generic-wrapper, and union-key narrowing; runtime regressions prove
same-owner equivalence and distinct-owner forwarding. The full 39-test suite is green.

## control-companion-relative-links — FIXED (2026-07-14, Codex)
<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: 96fc5adfb -->

**Status:** fixed in `96fc5adfb`; found by `final_control_spec_audit` while
checking the Scala 3 bidirectional-control companion documents.

**Symptom:** links in `specs/algebraic-effects.md` and `specs/coroutines.md` target
`../direct-syntax.md` and, in the former, `../error-handling.md`. Those files are
under `docs/`, so rendered navigation from both specs is broken.

**Reproduce:** resolve each local Markdown target relative to its containing file;
`test -e specs/../direct-syntax.md` and `test -e specs/../error-handling.md` fail,
while the corresponding `docs/` paths exist.

**Fix/verification:** the targets now use `../docs/`; both destination files
exist, the changed companion/control links resolve, and Markdown lint passes.

## interp-jit-nested-match-duplicate-var — a nested `match` binding the same value-type miscompiles on the JIT
<!-- status: fixed
     lane: multi
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15, `JavacJitBackend.scala`). Two independent codegen
defects, both surfaced by a `match` nested inside another match's arm:

1. **Duplicate helper locals.** A nested match compiles to an IIFE lambda whose body
   re-declared the same helper locals (`inst`, `__fa_<ctor>`, `<bind>_a`, `tn`) as the
   enclosing match; Java forbids a lambda-body local from shadowing an enclosing-method
   local, so `javac` failed `variable inst is already defined`. FIX: a per-nesting-depth
   uniquifier (`GenCtx.nameSuffix` / `deeperMatch`) suffixes every emitted match helper
   local (`inst_1`, `__fa_Bin_1`, `a_a_1`, …). Depth 0 → empty suffix, so non-nested
   codegen is byte-identical. Depth strictly increases from an enclosing match to a
   nested one, so no ancestor/descendant scopes ever share a name.
2. **Unused ref binding extracted as `IntV`.** A named-but-unused pattern binding
   (`case Bin(l, r) => …` where `l`/`r` are unused `E` values) was eagerly extracted as
   `long l_a = ((IntV) __fa_Bin[0]).v()` — a `ClassCastException` on a ref field. Pre-fix
   this was masked in production (the runtime caught the CCE and fell back to tree-walk)
   but wasted every JIT attempt. FIX: `bindingReferenced` treats an unreferenced binding
   as a wildcard, so no local is emitted for it.

Empirically the javac error did NOT "error out the call" (see original report below) —
the runtime swallowed it and ran the tree-walk tier, so results were always correct; the
bug was a silent loss of JIT for nested matches. Verified: `SscVmTest` two new cases
(single-nested + triply-nested on the same param) JIT-compile as `ObjToLong` and return
the right values; full `backendInterpreter/test` green, 0 regressions.

<details><summary>original report (2026-07-14)</summary>
open. Interpreter JIT (JavaC backend, the default tier) codegen bug; workaround = avoid
the nested match.

**Symptom:** a function whose body has a `match` nested inside another `match`'s
case, where BOTH scrutinees are cast to the same runtime `Value` subtype (e.g.
`InstanceV`), makes the JIT emit two `InstanceV inst = (InstanceV) …;` locals in one
Java method → `javac` fails with `variable inst is already defined in method …`.
(The whole call was believed to error out; in fact the runtime bails to tree-walk.)
Minimal repro (SclJet `SqliteValue` = a sealed trait of case classes):

```scalascript
def cmp(a: SqliteValue, b: SqliteValue): Int =
  a match
    case SqlInteger(x) => b match      // <-- inner match on `b`, same subtype family
      case SqlInteger(y) => 0
      case _ => 1
    case _ => 2
```
(Note: `cmp`'s two ref params make it bail on the "both params ref-typed" cliff before
codegen; the collision reproduces on a single-ref-param function whose arm re-matches
the same param, e.g. `x match { case Bin(l,r) => x match { … } }`.)

**Workaround:** split each match into its own single-level helper so no method has
two same-subtype casts — see `integerOf`/`textOf` used by `compareKeys` in
`scljet/write.ssc`. The tree-walk tier (`SSC_JIT_BYTECODE=off`) is unaffected.
</details>

## js-userspace-long-arith-native-operator-mixes-bigint — CONFIRMED / worked-around (2026-07-14, opus)
<!-- status: unknown
     lane: multi
     area: codegen -->

**Status:** WORKED AROUND in userspace (`scljet/sql.ssc` `arithValue`). When a plain (non-effectful)
`def` does Long arithmetic like `val x = f(a); val y = f(b); x * y` and the compiler cannot *prove*
both operands are `Long`, the ssc→JS backend emits a **native** `(x * y)` / `Math.trunc(x / y)`
rather than the BigInt-safe `_arith('*', x, y)`. That is fine when both are BigInt, but scljet decodes
small table integers to JS **Numbers** (`record.ssc` `signedByte`'s `.toLong` compiles to identity),
while integer literals are **BigInt** — so `Number * BigInt` throws `TypeError: Cannot mix BigInt and
other types`. What defeats the compiler's Long proof here is a helper with a mixed body: an
`asLong`-style function whose `SqlReal` branch emits `Math.trunc(x)` (Number) and `SqlInteger`/`_`
branches emit BigInt makes the *result* look non-Long, so downstream `x op y` uses native operators.
**Workaround (reliable):** accumulate through a `var` seeded from a `0L`/`1L` **literal** — exactly what
`sumValues` does (`var s = 0L; s = s + a; s = s + b`) — which forces the `_arith` path (`_arith('+', s,
a)`), and `_arith` coerces Number↔BigInt. `longAdd`/`longSub`/`longMul`/`longDiv`/`longIsZero` in
`sql.ssc` do this; `_arith('/', …)` truncates toward zero (matches sqlite integer division). Also
`.toLong`/`.toDouble` on a value can compile to a no-op, so convert via the same `var d = 0.0; d = d +
x.toDouble` accumulation (as AVG does), not a bare `x.toDouble`. Verified: conformance
`scljet-sql-expr` [int, js] green (incl. `250/100 = 2`). Proper fix belongs in JsGen (prove Long across
helper returns, or emit `_arith` for any not-provably-Int operand as the non-CPS path already does).

## v2-native-table-model-contract-gaps — first Apple model draft diverges at four strict seams
<!-- status: fixed
     lane: multi
     area: front
     fixed-in: d54d02126 -->

**Status:** done (2026-07-11, `d54d02126`); found by
`apple_table_impl_map` during the read-only implementation audit and confirmed
fixed by `nativeui-reviewer` in the `scalascript` Rozum room after three rounds.

- **Draft repro:** evaluate a fetch table in `loading` with last-good rows and
  an empty/stale body; the draft reparses and can replace the retained rows.
  It also admits Float through ordinary display/payload scalar conversion,
  validates table colors separately from shipped CSS colors, accepts a generic
  signal as a fetch source, and accepts a read-only refresh signal.
- **Action/model audit extension:** refresh must also be an Int/non-overflowing
  capability before transport. When a committed source update removes a row,
  its in-flight task plus action/edit slots must be cancelled/pruned immediately
  rather than waiting for SwiftUI `onDisappear`.
- **Decoder audit extension:** Foundation numeric `NSNumber` must be classified
  before Bool bridging so JSON `0`/`1` stays numeric; exact `yyyy-MM-dd` parsing
  must reject any trailing/normalized input by round-trip, not trust
  `DateFormatter.isLenient = false` alone.
- **Expected:** loading immediately retains the last-good set; ordinary display
  excludes Float and Field payloads allow only String/Int/BigInt/Bool; one
  bounded native color grammar serves CSS and table status; fetch metadata and
  writable refresh capabilities reject at descriptor decode.
- **Reconciliation invariant:** old/new typed row identities reconcile
  transactionally; deleted identities synchronously release table task/state.
- **Rozum implementation review round 1 (BLOCKED):** the green 40/40 suite and
  real iOS 16 typecheck do not close seven residual groups:
  1. row transport needs canonical descriptor signatures/current-capability
     authentication; a same-site replacement must update/cancel rather than be
     frozen by `StateObject`;
  2. payload kinds are action-specific (link/delete/edit cannot accept arbitrary
     modes), top-level edit is not a button, and edit requires its field/value;
  3. link targets are current writable String cells and refresh targets are
     current writable non-overflowing Int cells at preflight;
  4. finite Float must reach money formatting without passing the stricter
     ordinary-display scalar conversion first;
  5. initial fetch error without rows must render visible `Error:` status;
  6. every row map key must be String and init failures are bounded and sourced;
  7. the six probes must add Fields, token/base/header/content-type/scalar/
     overflow negatives, edit dedupe, descriptor replacement, row/unmount/
     deinit cancellation, and stale-completion rejection.
- **Rozum review round 2 preliminary residual:** owner/site plus descriptor
  signature alone does not authenticate the supplied row/action/slot. The
  runner must match a canonical action signature at the current slot and the
  typed identity in the current committed row set; arbitrary actions under a
  live table signature and old rows after replacement/removal must reject.
  Replacement itself commits only after both descriptor decode and candidate
  snapshot succeed, preserving the previous capability/model on failure.
- **Rozum review round 2 final additions:** row transport must call the same
  extracted URLSession/generation runner as ordinary fetch actions rather than
  duplicate its Task loop. `rowsPath` is exactly empty for static/signal and a
  valid non-empty dotted path for fetch when supplied (`a..b` never aliases to
  `a.b`). Model appearance is tracked even when initial decode fails so a later
  valid descriptor mounts; replacement must never pair retained old row cells
  with a changed column layout or remove the old capability before a coherent
  candidate commits.
- **Plan/done-when:** close all four seams before the first Apple table code
  commit, add them to the six named executable gates, and obtain Rozum reviewer
  confirmation with the complete slice.
- **Fix/verification:** one strict decoder/model/Grid implementation now owns
  transactional source snapshots, exact row/action capabilities, shared
  URLSession generation, deterministic columns, and synchronous stale-row/task
  disposal. Round-3 review found no remaining blocker or lifecycle leak.
  Independent and local runs both passed the named 6/6 and full Swift backend
  40/40, including generated macOS execution and iOS 16 strict typecheck.

## v2-swiftui-fake-native-fallbacks — deferred semantics render misleading content
<!-- status: unknown
     lane: multi
     area: runtime -->

**Reactive-attribute slice landed (opus, 2026-07-13).** `renderElement` bound only 3
reactive (signal-bound) attributes — `style`/`value`/`checked` — so ANY other
`NativeUiSignal`-bound attribute fell into the `"reactive attribute X is not mapped"`
Unsupported stub. Extended the reactive allowlist to the accessibility/state attributes
with a faithful SwiftUI mapping: `disabled`→`.disabled()`, `aria-disabled`, `title`→`.help()`,
`aria-label`→`.accessibilityLabel`, `required`, `aria-modal` (via a new
`reactiveAttributes` set + `reactiveAttributeDiagnostic`; each is read as the signal's LIVE
value and re-applied on change, reusing the existing style/value/checked plumbing).
STRICT preserved: an attribute with no faithful native mapping, or a malformed signal
(e.g. an Int bound to `disabled`), still yields a sourced `NativeUiUnsupported`. Renderer-only
(`SwiftNativeUiApple.scala` + test) — did NOT touch `SwiftRuntime`/`SwiftNativeUiHost`/
content-toolkit/WebKit (the sibling's active files). Verified under
`swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors`: `<div disabled={flag}>`
resolves the live `true` and applies `.disabled()`; a still-unmapped / Int-valued reactive
attribute stays Unsupported. `v2SwiftBackend/test` 58/58, 0 regressions.


**Semantic-table slice landed (opus, 2026-07-13).** The last inventoried element still
rendering as an Unsupported stub was the semantic HTML `<table>` family
(`table/thead/tbody/tr/th/td` — returned `unsupported("semantic table adapter pending")`).
Every other tag already rendered a real SwiftUI control (Text/heading, VStack/HStack,
Button, TextField/Toggle, Link, img, List, reactive DataTable→Grid). Now `<table>` renders
a real `ScrollView { Grid { GridRow … } }` (header row bold + `Divider`, cell CSS via the
existing `NativeUiStyles.apply`, `<th>` click events via `runEvents`), with strict decode:
malformed structure / a non-`th|td` cell / a bare `thead|tbody|tr|th|td` outside a table
still yields a sourced `NativeUiUnsupported`, never fake success. Renderer-only
(`SwiftNativeUiApple.scala`) — renders the `element("table", …, [thead,tbody])` shape both
`std/ui/lower.ssc` and content-toolkit already emit, touching neither. Verified end-to-end
under `swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors`: the table
DataV decodes to real header/body Grid rows; malformed variants throw the strict diagnostic.
`v2SwiftBackend/test` 55/55 (was 54), 0 regressions.


**Status:** LARGELY FIXED by swift-sibling work since the report; one residual slice
closed by opus (2026-07-13). Audit (opus, 07-13, real Swift 6.3/Xcode 26.5): the fake
"Native data table" string is gone; the `render`/`renderElement` dispatch now routes every
unknown tag / malformed node / malformed list-map / unmapped reactive attr / unsupported
role / unsupported semantic attribute to a sourced `NativeUiUnsupported` (red Text +
accessibilityLabel), and `NativeUiStyles` validates unknown CSS keys AND invalid values.
Residual silent-ignore closed: 4 cosmetic props the std/ui toolkit emits — `box-sizing`,
`border-collapse`, `cursor`, `user-select` — were in `supportedProperties` but accepted ANY
value with no diagnostic (`cursor: banana` → silently swallowed). Now value-validated against
the standard keyword sets (else sourced Unsupported), mirroring the existing `overflow`
pattern (`SwiftNativeUiApple.scala`). Verified end-to-end: `emit-swift` → the generated
`NativeUiStyles.swift` carries `cosmeticNoOpValues`; compiled+run under
`swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors` — bogus values now
diagnose, the 5 real toolkit values stay accepted; `v2SwiftBackend/test` 54/54, 0 regressions.
REMAINING (feature-scale, swift specialists): real actions/tables/WKWebView semantics + fetch
async lifecycle (the content-toolkit path deliberately `fatalError`s today — mid-refactor).
_Original report:_ found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** generate a root containing trusted HTML, a data table,
  an unknown semantic tag/style, or an unimplemented event. The draft renderer
  shows raw markup as `Text`, shows a fake “Native data table”, ignores the
  action/style, or converts a malformed list/map to empty output.
- **Expected:** implemented inventory entries have their exact native semantics;
  every deferred, malformed, or unknown semantic value becomes a deterministic
  sourced `NativeUiUnsupported` presentation. Silent ignore/fake success is
  forbidden.
- **Plan/done-when:** make the core renderer strict and use explicit Unsupported
  stubs for the separate actions/tables/WKWebView slice; add generated inventory
  and malformed-value gates before replacing each stub with real semantics.
  Inventory acceptance is behavioral, not string presence: CSS values,
  align/justify/position/inset/borders/white-space, semantic role/
  aria-disabled/required attributes, and malformed declarations must either
  map exactly or render sourced Unsupported. Fetch signals/actions also remain
  sourced Unsupported until the complete async lifecycle slice.

## v2-nativeui-root-transaction — failed Apple extraction leaks root/runtime state
<!-- status: fixed
     lane: multi
     area: conformance
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** missing-root `__nativeUiTakeRoot` throws before clearing
  `appleContext`; duplicate registration retains the first root and signals.
  The current test reinstalls the plugin and masks leakage.
- **Expected/fix:** explicit begin/commit/abort transaction with cleanup or
  restoration for zero roots, duplicate roots, and evaluation failure.
- **Fresh review delta (Rozum 2026-07-11):** `emptyHeaders` is registered once
  at plugin install, but begin clears its `SignalKey` while the global retains
  the old cell. Make it root-local/lazy and test an omitted-header Apple root.
- **Fix/verified:** begin/take/abort and duplicate failure reset the same plugin
  instance; each Apple begin re-registers the constant header cell under its
  root key, including the omitted-header action path.
- **Done-when:** one plugin instance can fail then begin a clean extraction;
  zero/duplicate/evaluation-error tests prove rollback.

## v2-nativeui-portable-graph — canonicalization/equality can leak host values or miscompare cycles
<!-- status: fixed
     lane: multi
     area: front
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** DataV→MapV cycles point to an unconverted DataV; ClosV traversal
  mutates caller-owned environments; equality marks failed map-key candidates
  as visited/equal. Descriptor helpers retain raw values, and static table rows
  are not validated as String-keyed maps.
- **Expected/fix:** graph-safe non-mutating validation/canonicalization,
  tri-state or candidate-isolated cyclic equality, deep stable paths at every
  ABI constructor, and exact row/map validation.
- **Fresh review delta (Rozum 2026-07-11):** conversion still breaks a benign
  alias when an outer DataV and a closure env share the same portable MapV and
  an unrelated host map forces copying. Preserve that alias without changing
  ClosV identity or its environment.
- **Fix/verified:** validation is closure-context aware; conversion pins the
  closure-reachable portable subgraph, copies transitional host maps without
  alias loss, and equality backtracks cyclic unordered candidates soundly.
- **Done-when:** adversarial cycle/reorder negatives, nested ForeignV paths,
  closure non-mutation, and every descriptor family are green.

## parser-trysplitparse-quadratic-hang — `fixed` (2026-06-28)
<!-- status: unknown
     lane: multi
     area: front -->

- **Found by:** busi (phone-demo hub). A `/api/issue` route used `given` as a local val name: `val given = req.form.getOrElse("number", ""); val number = if given.length > 0 then given else …`. Loading the ~3500-line `demo_server.ssc` pegged one core at ~100% CPU and never bound (>90s); the *same* code in a tiny file instead fast-failed with `illegal start of definition`. (busi originally mis-attributed this to the `if <param> then <param> else …` shape and to a `View[Int]` — both red herrings; the trigger is purely the identifier name.)
- **Root cause:** `given` is a Scala-3 soft keyword, so scalameta rejects it as an identifier → in `Parser.parseScalaWithDiagnostic` BOTH the Source-mode parse and the `{…}` Term-mode parse fail → the `trySplitParse` fallback runs. That fallback tried EVERY split point (`lines.length - 1 to 1 by -1`), each re-parsing an O(N)-line `prefix` as `Source` plus a `suffix` as `Term`. For a large block that is O(N) parses over O(N)-line prefixes = **O(N²)** total. Confirmed size-driven, not single-parse-exponential: a 1010-line block ≈ 6s, a ~3500-line block ≈ 90s; a `jstack` mid-hang showed `main` in `Parser$.trySplitParse$…` → `…prefix.parse[Source]` → scalameta `argumentExprsInParens` recursion.
- **Minimal repro:** `val given = "x"; val number = if given.length > 0 then given else "z"` in a code block — a fast `illegal start` in a small file, a ~quadratic hang in a multi-thousand-line one. Renaming `given` → `gv` parses and runs fine.
- **FIXED (2026-06-28):** bounded `trySplitParse` to small trailing suffixes — `private val MaxSplitSuffixLines = 48`, range `lines.length - 1 to math.max(1, lines.length - MaxSplitSuffixLines) by -1`. The handler-file pattern this fallback targets (class defs + a trailing lambda) always has a short trailing term, so only the last few split points are useful; small blocks (≤48 lines) keep the original full-range behaviour. Turns the 90s hang into a fast diagnostic (busi hub: 90s → ~3s `illegal start`).
- **Guard:** `ParseErrorPositionTest` — "large block with `given` as an identifier yields a fast diagnostic, not a quadratic hang" (2500-line block; asserts a populated `parseError` in <15s). All 146 `scalascript.parser.*` tests pass; the handler-file trailing-lambda split still parses; busi `make v2-test` + `make v2-test-js` are 47/47 on both backends with the rebuilt jar.
- **Note:** verified against this branch's base (`origin/main` @ ce0554245) — `trySplitParse` is byte-identical to the commit busi pins (72d0196f3), where it was first reproduced + the fix built/tested. busi keeps its own workaround (no `given` val, `getOrElse` auto-number) so it is unaffected; this lands the parser-robustness fix for everyone.

## rust-index-read-moves-noncopy — `fixed` (2026-06-22)
<!-- status: unknown
     lane: multi
     area: codegen -->

- **Found by:** mellow-shrew (self), via an end-to-end `cargo run` smoke against the just-landed rust-web-toolkit follow-ons (`origin/main` @ d0141a1d4). The `backendRust` unit suite is string-match only (no `cargo` compile), so it missed a generated-Rust move error.
- **Symptom:** an index *read* on a non-Copy element sequence panicked the Rust compiler, not the program — `error[E0507]: cannot move out of index of Vec<String>`. Minimal repro:
  ```scalascript
  @main def run(): Unit =
    val parts: List[String] = "a,b,c".split(",").toList
    println(parts(1))      // → parts[(1i64) as usize]  — moves the String out of the Vec
  ```
  `Vec<i64>` indexing was fine (i64 is `Copy`), so the bug only surfaced once `f2afd3378` made `.split`/`.toList` results indexable (`Vec<String>`, non-Copy).
- **Root cause:** the `seq(i)` index-read lowering (`RustCodeWalk.scala`) emitted a bare `seq[(i) as usize]`. Using a `Vec`'s `Index` output by value moves it; legal only for `Copy` elements.
- **FIXED (2026-06-22):** index *reads* now emit `seq[(i) as usize].clone()` — required for `Vec<String>`/structs, elided by rustc for `Copy` elements (i64/char/bool), so zero cost. The `seq(i) = v` *store* path is now handled explicitly in `Term.Assign` (new `asSeqIndexTarget` helper) so the assignment **target** stays bare — you can't assign to a clone.
- **Guard:** `RustGenCollectionTest` — "index read on a String seq clones the element" + "index store on a mutable array stays bare". Verified end-to-end with a throwaway `cargo run` smoke (all new collection/string ops compile + run): output `30 70 70 30 100 6 1 a-b-c true true true b 3`. `backendRust` 235/0.
- **Follow-up (filed in BACKLOG):** the rust backend has no `cargo`-compile coverage in its unit suite — this whole bug class (move/borrow errors in valid-looking generated Rust) is invisible to string-match tests.

## interp-js-string-map-nonchar — `fixed (interp + js)`
<!-- status: unknown
     lane: multi
     area: runtime -->

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** `"abc".map(c => c.toInt).sum` threw (`No method 'sum'`) on interp + JS — mapping a String's chars to a NON-Char value should yield a `Seq[Int]` (then `.sum`), but interp/JS `String.map` rebuild a String. JVM correct (294).
- **FIXED (interp, 2026-06-15):** `String.map` returns a `String` only when EVERY mapped element is a `Char` (interp has a real `CharV`); otherwise a `List` (`strMapResult`). `"abc".map(_.toInt)` → `List(97,98,99)`; char-to-char maps stay Strings.
- **FIXED (JS, 2026-06-21):** added a JS Char wrapper. A char produced by iterating a String (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) is now boxed as a `_Char(code)` (`JsRuntimePart2a`): `valueOf` returns the code point and `toString` the 1-char string, so concatenation/arithmetic/`_show` coerce naturally. `_dispatch` gains a `_Char` branch mirroring the interp's `dispatchChar` (`toInt`→code point, `isDigit`/`isLetter`/`toUpper`/`asDigit`/…), and `String.map` now returns a String only when every result is a `_Char` (else a Seq) — mirroring `strMapResult`. `_eq` bridges `_Char` to a 1-char String literal and to an Int (the interp allows `CharV == IntV`), so `c == 'a'` and predicates work even though char *literals* stay JS strings. Verified: interp == JS == JVM on `"abc".map(_.toInt).sum` (294) and char-method map/filter; `CrossBackendPropertyTest` "String.map char vs non-char cross-backend" now asserts all three agree.
- **Residual (minor, by design):** a char *literal*'s `.toInt` (`'5'.toInt` → 5 on JS vs 53 on interp/JVM) still diverges — char literals stay JS strings to avoid touching literal-pattern codegen (which compares with `===`, not `_eq`). The actionable bug (`String.map(nonChar)` + iterated-`Char` methods) is closed; literal coercion is left as a separate, lower-value follow-up.

## interp-cons-in-effect-handler — `fixed` (example) (2026-06-13, `721ee62b9`)
<!-- status: unknown
     lane: multi
     area: front -->

- **FINAL diagnosis (two earlier mis-diagnoses corrected):** NOT a `::` bug and NOT a
  "resume result not forced to ListV" bug. `resume(())` **correctly** returns the
  continuation's pure result `()` (Unit); `println(rest)` after `val rest = resume(())`
  prints `()`. The `algebraic-effects.ssc` Logger handler did `msg :: resume(())`, i.e.
  `msg :: ()` → "No method '::' on StringV" — it assumed `resume(())` of the final
  continuation would be `Nil`. That is the **deep-handler list-accumulation** pattern
  (Koka/Eff `return x => []`), which needs a handler **return clause**. ScalaScript's
  `handle` has **no return clause** (the spec's own Logger example just does `resume(())`,
  returning Unit), so the pattern is unsupported. **Example bug, not an interp bug.**
- **Fixed:** rewrote the Logger section to a working accumulator (append each msg + resume)
  producing the same `List(Hello, World!)`, with a comment on the return-clause gap.
  Also corrected the State section (stdlib `State` + `set`, dropped a broken parameterized
  redecl — see `interp-parameterized-effect-decl`).
- **Underlying language gap (future feature, not filed as a bug):** a handler **return
  clause** would make `msg :: resume(())` work (the spec types `resume` as returning the
  *handler body's* type, which requires bridging the pure/base case). Large feature
  (parser + typer + interp + 4 backends) — out of scope; noted in BACKLOG.
