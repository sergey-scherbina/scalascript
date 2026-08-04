# JVM backend (`jvm` lane) — bugs

Scope: defects whose FIX goes in `v1/runtime/backend/jvm/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module v1/runtime/backend/jvm`, never by
grepping for status.

Newest first.

## jvm-lane-cannot-compile-a-json-import — 14 type errors before a single line runs
<!-- status: open
     lane: jvm
     area: codegen
     kind: bug
     gate: tests/e2e/jvm-json-import-gate.sh -->

**Measured 2026-08-04.** Five lines, one import, four lanes:

```scalascript
[jsonParse](std/json.ssc)
val v = jsonParse("{\"a\":1}")
println("parsed")
```

| lane | result |
|---|---|
| int | `parsed` |
| native | `parsed` |
| js | `parsed` |
| **jvm** (`ssc-tools run-jvm`) | **14 × `[E007] Type Mismatch`** — the emitted Scala does not typecheck |

**Control, in the same gate:** the same program *without* the json import compiles and runs on the
jvm lane (`control ok`). So this is not "run-jvm is broken today"; it is specifically what the json
core lowers to.

**ROOT CAUSE CORRECTED 2026-08-04, and my first diagnosis was wrong.** I wrote that "the generator
emits the bindings without their element types". It does not: the emitted Scala is a faithful copy
of the source, and `case class JsonCoreOk(value: Any, next: Int)` is what `json-core.ssc:48` says.
`value` is genuinely polymorphic — a codepoint here, a `JsonCoreString`/`Number`/`Array`/`Object`
elsewhere — so `Any` is the correct declaration.

**The real cause is `Cons`.** `json-core.ssc` matches lists as `case Cons(head, tail)`, which is
ssc's spelling. Scala has no `Cons` extractor, so the pretty-printer emits the name verbatim and the
compiler answers `[E189] Not Found: Cons`. Every binder under an unresolved extractor then degrades
to `Any`, which fails again at each use — that is where `Found: (tail : Any)` ×7 comes from. **One
unlowered name produces fourteen errors.** The interpreter has always known it:
`PatternRuntime.scala` special-cases `Cons` by name. This lane never learned it.

Reduced to eight lines, no json:

```scalascript
def size(xs: List[Int], n: Int): Int =
  xs match {
    case Nil => n
    case Cons(_, tail) => size(tail, n + 1)
  }
```

`run-jvm` emits `case Cons(_, tail) =>` verbatim and fails; int, js and native all print `3`.

**PARTIALLY FIXED 2026-08-05, and the earlier note about the failed attempt was wrong on its
reason.** I had guessed the splice arm was "unreachable for pattern nodes, or the sibling `None`/
`Nil` arm is dead too". Instrumenting the pass — the step this entry told the next reader to take —
answered it in one build: **`rewriteActorAstCallsInSource` is never CALLED on this path.** It runs
inside `genUserOnlyWithLineMap`, the bytecode path. `emit-scala` and `run-jvm` go through
`generate` → `genModule`, which applies `fuseLazyListInSource` and nothing else. **The two emit
paths apply different fixups**, so anything repaired in one is unrepaired in the other — including
that `case None()` rewrite.

**What now works.** A new `lowerConsPatternsInSource` pass, wired into BOTH paths behind a
`contains("Cons(")` guard so the common emit does not pay for a parse:

```
def size(xs: List[Int], n: Int): Int =
  xs match { case Nil => n; case Cons(_, tail) => size(tail, n + 1) }

run-jvm  →  3        (was [E189] Not Found: Cons)
```

**What still does not, and why — measured, not guessed.** The same pattern in an IMPORTED module is
still emitted verbatim, so `std/json.ssc` still fails. The slice carrying an imported module does
not parse as Scala 3, and the pass therefore gives up. That failure used to be silent; it is not any
more:

```
ssc: jvm codegen could not parse a slice containing `Cons(` — the pattern is emitted verbatim
     and the Scala compiler will reject it
```

**The next step is to make that slice parseable, or to lower without a full parse** — and note that
the sibling pass has the identical `case None => src`, which is precisely why an arm added to it
looked unreachable for a day. A pass that gives up must say so.

**Also partial, landed earlier:** two sites in `json-core.ssc` now narrow in the pattern
(`case JsonCoreOk(low: Int, afterLow)`), which is correct on its own terms — the arm is only
meaningful for a codepoint, and anything else falls to the existing error arm. Verified on all four
lanes. It removes 2 of the 14 errors and does NOT unblock the lane by itself; `Cons` is the blocker.

**Shape of the original failure** — the cascade from the unresolved extractor:

```
7 ×  Found: (tail : Any)   Required: Int
5 ×  Found: (unit : Any)   Required: List[Any] / List[Int]
2 ×  Found: (low  : Any)   Required: Int
```

all downstream of a `case Cons(...)` the compiler could not resolve.

**The blast radius is not json.** `std/http.ssc` imports it, so the jvm lane cannot compile a
program that serves HTTP either. That is how this was found: `components-smoke` and
`middleware-smoke` both reported **`the server process EXITED before it listened`** — a serving
symptom for a defect that happens before a single line runs. Neither gate was reporting a server
problem, and neither had been run in months.

**Declared, not left red.** The gate counts the four cells that pass and prints
`KNOWN GAP jvm with json`. It **fails if that cell starts passing**, saying to delete the
declaration and close this entry.

## jvm-string-literal-s-concat-inserts-x — the string `"s"` on the left of a `+` emits an extra `x`
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: f2941736a
     gate: tests/conformance/jvm-string-s-literal.ssc -->

**Found 2026-07-31** by `v2-char-value` while establishing the golden for `char-as-value`. Found by
accident and worth saying how, because the accident is the reproducible part: the `v2-char-is-an-int`
entry records the repro line as `println("s" + 'b')`, so a case written from it straddles this defect
and reads as a Char problem.

**Reproduce** — the right operand does not matter, and neither does `Char`:

```scalascript
def main() =
  println("a" + 'k')      // ak    correct
  println("s" + 'k')      // sxk   WRONG
  println("f" + 'k')      // fk    correct
  println("raw" + 'k')    // rawk  correct
  println("as" + 'k')     // ask   correct
  println("s" + "k")      // sxk   WRONG — a String right operand too
```

**The trigger is a left operand that is EXACTLY the one-character string `"s"`.** `"f"`, `"raw"`,
`"md"` — the other interpolator prefixes — are all correct, and so is `"as"`, so it is not "any short
string" and not "any prefix name".

**Three lanes against one, and the odd one out runs real Scala**, so the semantics are not in doubt
and the defect is in the generator:

| lane | `"s" + "k"` |
|---|---|
| int | `sk` |
| js | `sk` |
| v2 | `sk` |
| **jvm** | **`sxk`** |

Measured on a cleared artifact cache (`rm -rf .ssc-artifacts`) — `run-jvm` memoizes on SOURCE, so a
stale artifact was the first thing ruled out.

**Not investigated further**, deliberately: it is a different module from the claim that found it.
The shape (`s` + an inserted `x`) reads like the `s"…"` interpolation path being taken for a bare
`"s"` literal, with `x` coming from a generated temp name — that is a hypothesis, not a measurement.


**FIXED 2026-08-04.** The hypothesis in this entry was right in spirit and wrong in mechanism. It
guessed "the `s"…"` interpolation path being taken for a bare `"s"` literal, with `x` from a
generated temp name". There is no temp name: the JVM backend post-processes its EMITTED SOURCE with

```
out.replaceAll("(?<![$\w])s(\"{1,3})", "sx$1")
```

`sx` is a real interpolator defined in the preamble — it routes each interpolated value through
`_show` so a whole-number Double prints `4`, not `4.0`. The rewrite turns `s"…"` into `sx"…"`. Its
lookbehind correctly excludes `bytes"…"` and `$s"…"`, and the comment above it says the pattern is
"conservative enough not to match inside identifiers" — which is true, and says nothing about string
LITERALS, where the hole actually was: in `println("s")` the `s` sits immediately before the closing
quote and matches `s"`.

**Two measurements the entry did not have**, both of which changed the fix:

- `val v = "s"; println(v + "k")` is wrong too, so it is not about literal syntax — it is any
  literal whose text ends with the standalone word `s`. `" s"` → `" sx"`; `"as"`, `"ss"`, `"s1"`
  are fine.
- The SAME function has a twin: `.replaceAll("\.mkString\(", …)` corrupts
  `println("a.mkString(b)")` into `a.map(_show).mkString(b)`. Fixed in the same commit — it is one
  regex-over-source mistake made twice, and fixing one would have left the other.

Widening the lookbehind was rejected: it fixes `"s"` but not `" s"`. The only reliable
discriminator is where a literal STARTS, so `rewriteCodeRegions` scans instead of matching, applying
both rewrites to code runs only. `${ … }` holes are code and are rewritten recursively; comments and
char literals are skipped whole so a `"` in either cannot desynchronise the scan.

**A trap worth recording**: the first version of the scanner used `out.append(src, i, stop)`. Scala 3
AUTO-TUPLED it to `append((src, i, stop))` against `append(x: Any)` — it compiled without error and
spliced tuple `toString`s like `,23,24)` into the generated program. The symptom was a Scala syntax
error thousands of lines from the edit. Explicit `src.substring(a, b)` everywhere.

**Gate:** `tests/conformance/jvm-string-s-literal.ssc`, 10 rows on all four lanes. Verified to fail
without the fix: **7 of 10 rows wrong**. The other 3 are guards — `s"d=$d"`, a nested interpolation
and a `Double` `.mkString` — and they are IDENTICAL before and after, which is the point: the
interesting way to break this fix is to delete the rewrites and bring `4.0` back.

## run-jvm-silent-success — `ssc-tools run-jvm` prints nothing and exits 0, including for a file that does not exist
<!-- status: fixed
     lane: jvm
     area: cli
     gate: none
     fixed-in: unrecorded -->

**Status:** OPEN, low priority — that lane is already documented as NON-CONFORMING and *"slated for
deletion with the v1 hybrid tier"* (`Main.scala:3088`). Filed because a silent `exit 0` is the one
failure shape this repo treats as always worth recording. Found 2026-07-30 by `json-number-policy`
while checking whether the jvm lane needed the same number-policy fix.

**Reproduce — two runs, both `exit 0`, both with no stdout at all:**

```
$ printf 'def main() = println(1+1)\n' > tests/conformance/_t.ssc
$ ssc-tools run-jvm tests/conformance/_t.ssc ; echo EXIT=$?
EXIT=0                                    # no "2"

$ ssc-tools run-jvm /does/not/exist.ssc ; echo EXIT=$?
Error: File not found: /does/not/exist.ssc
EXIT=0                                    # says Error, exits 0
```

**Not caused by a stale build and not local to one worktree:** identical on a freshly built worktree
and on the shared main checkout. Checked before filing.

**Why nothing caught it.** `contract.sc`'s default lanes are `int,js,v2`; `jvm` is opt-in
(`--lanes int,js,jvm`). If it were ever switched on, `laneCmd("jvm")` is exactly the command above, so
every case would compare EMPTY output against its golden — the whole lane would read as DIVERGE rather
than as "the lane is broken".

**Two separable defects, and the second is the one that matters regardless of deletion plans:**
1. no program output (the lane compiles via `scala-cli` and something in that path yields nothing);
2. **`exit 0` on `File not found`** — a fail-open in argument handling, independent of the backend.

Fixing (2) alone is cheap and stops the lane from lying about its own invocation.


**CLOSED 2026-08-04 — re-measured, and BOTH halves are correct now.** Nothing was fixed here; this
records the measurement so the entry stops sending people after a defect that is not there.

Run verbatim from this entry's own repro block, same file path and all:

```
$ printf 'def main() = println(1+1)\n' > tests/conformance/_t.ssc
$ ssc-tools run-jvm tests/conformance/_t.ssc ; echo EXIT=$?
2                                          # was: no output at all
EXIT=0

$ ssc-tools run-jvm /does/not/exist.ssc ; echo EXIT=$?
Error: File not found: /does/not/exist.ssc
EXIT=1                                     # was: EXIT=0
```

**`fixed-in: unrecorded`, deliberately** (the documented value for "fixed, commit not named" — specs/bugs-index.md). I could not identify a commit that changed either behaviour,
and would rather say so than name a plausible one:

- `RunJvmCmd` in `v1/tools/cli/src/main/scala/scalascript/cli/Main.scala` reads
  `if !os.exists(path) then System.err.println(s"Error: File not found: $file"); System.exit(1)` at
  HEAD, and `git log -S` on that string finds only refactors that MOVED the file
  (`b433a41e4`, `a9f7943f3`, `d0665660a`) — no commit that introduced the exit code.
- `bin/ssc-tools` ends in `exec java … "$@"`, which propagates the child's status, so the wrapper is
  not swallowing it either.

Which leaves two possibilities and no evidence to choose between them: the exit code was always 1
and the original observation came from something else in the reporter's shell, or a change between
2026-07-30 and 2026-08-02 fixed it without touching that string. The binary that measures correct
here was built at `ec70eb062` (2026-08-02), i.e. after this entry was filed.

**The paragraph worth keeping is the one about why nothing caught it**, and it is still true:
`contract.sc`'s default lanes are `int,js,v2`, so `jvm` is opt-in. If the lane ever regressed to
silence again, every case would compare EMPTY output against its golden and the whole lane would
read as DIVERGE rather than as "the lane is broken" — a shape that hides the cause behind 600
identical failures. That is a gap in the harness, not in this lane, and it outlives this entry.

## negtc-both-fail-derived-route-clients — the one case keeping the release gate red
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/corpus-baseline.tsv -->

**Status:** RESOLVED 2026-07-28 as a CLASSIFICATION, not a code fix — the case is genuinely
un-runnable and the two gates disagreed about that.

**Measured, in seconds** (the sweep now takes `--only`, so this cost minutes rather than the
56-minute gate):

```
derived-route-clients.ssc  both-fail  vm_rc=1  bytecode_rc=1
detail = ssc: unhandled runtime effect: Api.postApiTodos
```

`both-fail` means the VM and bytecode lanes AGREE — both fail. So it was never a parity divergence.

**The golden cannot run it either, which is the whole point:**

```
$ bin/ssc-tools run --v1 examples/derived-route-clients.ssc
[ERROR] [line 6, col 15] Undefined: awaitClient
```

The example demonstrates `apiClients:` derivation; its client block calls `awaitClient(Api.*)`
against a server nobody starts, and `awaitClient` does not exist on the INT lane at all. It is not
in `examples/run-all.sc`'s executable list and it is not a conformance case. And
`tests/conformance/corpus-baseline.tsv` **already marked it `SKIP`** — the corpus contract had
answered this question and frozen the answer; `bc-parity-sweep` simply did not read it.

**Fix.** The sweep now classifies a both-fail whose case the golden is declared unable to run as
`both-fail-no-golden`, reported in its own `NO-GOLDEN:` list and excluded from the `--strict`
verdict. Both lanes still RUN and their exit codes and detail are still recorded — the comparison is
performed and only the classification consults the declaration, which is the order AGENTS.md
requires.

Verified in BOTH directions, because a one-directional check would not distinguish this from a hole:

| baseline declaration | category | `--strict` |
|---|---|---|
| present (as frozen) | `both-fail-no-golden`, `no-golden: 1` | exit 0 |
| removed | `both-fail: 1` | **exit 1** |

`tests/e2e/negtc-shard-gate.sh` pins the invariants: the set is read from `corpus-baseline.tsv` and
never hand-maintained (the same reason `skipped-oversized-bytecode` avoids a per-example list),
`strict` still counts `both-fail` and never counts `no-golden`, and no-golden cases are NAMED rather
than only counted.

**Follow-up caught by the gate itself, worth keeping.** The first version named the bucket
`both-fail-no-golden`, which matched neither `$2 == "both-fail"` nor `$2 ~ /^skipped-/` in the
release gate's metric extraction. The freeze's ACCOUNTING CLOSURE — `identical + skipped + delegated
+ both-fail + mismatch + one-sided == frontend.total` — then failed with
`parity accounting 213 != frontend.total 214`. The check did exactly the job its comment claims
("catches any miscount/loss") and it caught mine. The bucket is now `skipped-no-golden` and
increments both `nogolden` and `skipped`, following this script's existing idiom for a sub-kind of
skip (`timedout++; skipped++`, reported as `skipped: N (timeout: M, no-golden: K)`).

Verified against the real `scripts/v21-negative-toolchain-freeze` on synthesized reports carrying
the CI numbers, all three states:

| state | freeze verdict |
|---|---|
| original (`both-fail: 1`) | `parity.both-fail expected 0, got 1` |
| first fix (`skipped: 133`) | `parity accounting 213 != frontend.total 214` |
| current (`skipped: 134`) | **`PASS`** |

Note what the first row says: the freeze had been demanding `parity.both-fail = 0` all along, with
the comment "a real deterministic both-lane gap must be classified away". The classification above is
what it was asking for.

**Left open deliberately:** `Api.postApiTodos` being an unhandled runtime effect IS a real native-lane
gap (derived API clients are not implemented there) — the same shape as the `System.nanoTime` gap.
It is not a *parity* finding and does not belong to this gate; if the example is ever meant to run,
that gap is the work.

## v2-distributed-failure-retry — retry path degrades to Stub
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: ea21eb8a5
     gate: tests/conformance/distributed-failure-retry.ssc -->

**Status:** FIXED 2026-07-28 in `ea21eb8a5` (regression `a373460c3`);
reported by the SSC v2 corpus audit and accepted by `codex`, SPRINT
`v2-distributed-failure-retry`.

**Reported real-harness reproduction.**

```bash
bin/ssc run --native tests/conformance/distributed-failure-retry.ssc
```

Fresh assembled baseline (2026-07-28): the declared JVM lane passes 1/1.
Default/legacy × `--native`/`--bytecode` is 0/4 with identical exit-0 output:
`failures: 0`, then `11`, `12`, `Stub`, `15`, `16`. The single breadcrumb
replaces the failed partition's two expected values, `13` and `14`; frontend
and VM/direct-ASM are therefore ruled out.

**Root cause and fix.** The actor coordinator retained retry payloads with
`List[(partitionId, partition)].toMap`. V2 has no dynamic `List.toMap`
dispatch, so the lookup itself became `Stub`; the coordinator then sent that
placeholder to a healthy worker and accepted its placeholder result as
success. Both ordinary and typed-wire retry lookups now build a real portable
Map through `foldLeft` + `updated`. State remains in the shared actor
coordinator; the deterministic local-loopback provider and its no-remote-
failure contract are unchanged.

**Verification.** The unchanged corpus case prints zero failures and ordered
values 11 through 16 on JVM and V2; default/legacy × VM/direct ASM is exact
4/4. The full `distributed-*` slice is 6/6, actors-plugin is 4/4, distributed-
plugin is 5/5, and the partial/map/heterogeneous/shuffle neighbors are exact
16/16 across the same frontend/engine matrix.

## v2-generator-provider — Dataset cannot consume the native Generator provider
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: 2f5bb135b
     gate: tests/conformance/dataset-from-generator.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`2f5bb135b`; regression
`e583c4203`; reported by the SSC v2 corpus audit, SPRINT
`v2-generator-provider`).

**Reported real-harness reproduction.**

```bash
bin/ssc run --native tests/conformance/dataset-from-generator.ssc
# Dataset.fromGenerator requires the standard generator provider
```

Fresh assembled baseline (2026-07-28): the declared INT/JS/JVM lanes pass
3/3. V2 fails 0/4 in the exact default/legacy × `--native`/`--bytecode`
matrix; every mode exits 1 with the same message above before any output.
This isolates the defect to the missing provider bridge rather than frontend
or VM/direct-ASM behavior.

**Fix acceptance.** `Dataset.fromGenerator` must lazily consume the existing
native Generator value and `Dataset.toGenerator` must return one owned by the
same provider. Pin both directions at the real plugin boundary, preserve
ordered output, and avoid a duplicate generator implementation in
dataset-plugin.

**Root cause and verification.** The Dataset provider still contained explicit
placeholder throws even though the Generator provider already exposed the
portable `next`, `generator`, and `suspend` surface. Dataset now drains `next`
only when a terminal evaluates and resolves `generator`/`suspend` lazily
through the existing plugin context; Generator remains the sole owner of
queue, backpressure, cancellation, and suspend state. The Dataset suite moved
from the expected fail-first 4/6 to 6/6, Generator remains 10/10, focused
conformance is 2/2, and both the bridge case and neighboring provider fixture
compare exactly across default/legacy × VM/direct ASM (8/8 total routes).

## v1-jvm-coroutine-generic-surface — generated runtime rejects the public typed API
<!-- status: open
     lane: jvm
     area: codegen
     gate: tests/conformance/coroutine-basic.ssc -->

**Status:** OPEN (found 2026-07-21 by codex-q4 while enabling additive native-bytecode
conformance; deferred outside native-provider Q4 to `BACKLOG.md`).

**Reproduce:** `bin/ssc-tools run-jvm tests/conformance/coroutine-basic.ssc` fails compilation:
`coroutineCreate` does not take type parameters, and the value returned by `suspend` is `Any`, so
the two-way `b + "-pong"` expression has no `+`. The JVM runtime fragment currently exposes
`coroutineCreate(body: () => Any)` and `suspend(v: Any): Any`, while the normative/public API is
`coroutineCreate[Y, R, T](body: () => T)` and `suspend[Y, R](out: Y): R`.

**Notes / fix gate:** this is the pre-existing v1 Scala-codegen lane, not the new v2 native VM/ASM
provider. A faithful fix must preserve the independent yield type `Y` and resume type `R`; merely
adding unused type parameters to `coroutineCreate` does not repair inference for `suspend`. Until
that compatibility lowering is fixed, coroutine conformance must still run and diff the JVM lane as
an expiring `known-red` while the additive native VM and direct-ASM lanes compare normally.

## jvmgen-forked-e2e-wire-core-unpublished-snapshot — clean CI cannot resolve generated scripts
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: 2e097848b -->

**Status:** FIXED 2026-07-20 by `frontend-tui-fetch-refresh` (`2e097848b`). Found only after the
extended exact-SHA run reached the complete `sbt test` tail.

**Symptom/reproduce:** exact-SHA run `29719191208` completed lint, validation, conformance, every
release gate, and most of the full test sweep, then failed two typed cases in
`JvmGenSqlRuntimeTest` and the front-matter schema case in `JvmGenEffectsRuntimeTest`. All three
generated scala-cli scripts contained this unresolved directive:

```scala
//> using dep "io.scalascript::scalascript-wire-core:0.1.0-SNAPSHOT"
```

The snapshot was absent from both the clean runner's Ivy local repository and Maven Central. A
developer machine with a previous `wireCore/publishLocal` silently hid the failure.

**Root cause:** the scala-cli e2e suites run in forked JVMs whose working directories are their SBT
subprojects. `JvmGen.sscJarDirective` therefore cannot discover the sibling `backend/wire/target`
JAR and correctly falls back to the publishable dependency form. The existing test-resource
contract already packages SQL, typed-data, and graph runtimes and rewrites their unpublished
directives to absolute `using jar` paths, but `wireCore` was omitted when typed-data began depending
on it.

**Fix/verification:** `backendInterpreter / Test / resourceGenerators` now packages `wireCore` and
exports its exact path. The SQL and OpenAPI e2e harnesses replace only the generated unpublished
wire directive with that fresh local JAR; production emission and Maven fallback semantics are
unchanged. With `COURSIER_REPOSITORIES=central` (so the local Ivy snapshot cannot rescue the test),
the three exact CI failures pass 3/3; the complete affected suites pass 4/4 and 35/35, and
`frontendTui/test` remains 36/36.

## jvm-swiftui-row-path-invalid-escape — generated Scala emits `"\."`
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: 236a02de2
     confirmed: no -->

**Status:** fixed (2026-07-12, `236a02de2`), awaiting Sergiy confirmation;
found by codex in the combined Swift CLI release gate and corrected in the
`scalascript` Rozum room after inspecting source bytes.

- **Real-harness repro:** run the four Swift CLI suites including
  `SwiftUiRealFixtureBuildTest`. The first three complete 53/53, then the real
  `.ssc` → generated Scala → Swift package lane aborts at generated lines
  10500/10512 with `invalid escape character` in `name.split("\.", -1)`.
- **Root cause:** the table payload helpers added in `1ecbc80ca` appear in an
  `s"""..."""` runtime template in `JvmGenPreamble`; its `\\.` source spelling
  loses one escaping layer. The parallel non-interpolated triple string in
  `JvmRuntimeUiPrimitives` already retains two slashes and must not change.
- **Fix/done-when:** use four source slashes for the two interpolated preamble
  sites so emitted Scala contains two, pin the generated runtime text, and pass
  the real fixture plus the combined 53-test Swift CLI set.
- **Fix/result:** only the two interpolated preamble sites were corrected; the
  generated-source assertion and fresh combined 54-test Swift CLI set pass.

## v21-native-graph-provider-missing — standard Graph facade has no core-free owner
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: eb69124e2
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `eb69124e2`, taxonomy `ff42d5d57`),
awaiting Sergiy confirmation; found by codex while continuing the TI-8.2d3
blocking taxonomy after distributed local-loopback landed.

- **Installed baseline:** VM and direct ASM both exit 1 with empty stdout.
  `graph-storage-interpreter.ssc` stops at `unhandled runtime effect:
  Graph.putVertex`; `graph-rdf4j-http-storage.ssc` stops at `Graph.putRdf`.
  Explicit compatibility proves the semantic split: the local example prints
  `imports:b.ssc` and exits zero, while the HTTP example prints
  `Stored two books.` then fails at `Sparql.select is not available in
  interpreter mode; use ssc run-jvm with backend: rdf4j-memory`. Its front
  matter requires an external URL and credentials, so remote query execution
  is not a standard local blocker.
- **Expected/done-when:** a required core-free provider owns the portable
  process-local property/RDF graph operations actually promised by the standard
  library, with deterministic values/order/errors and no v1 graph plugin,
  Scalameta, compiler, remote-service emulation, or transparent fallback.
  Exact standard examples pass VM/ASM/build-jvm and only genuinely resolved
  taxonomy rows are retired; explicitly remote backends receive an honest
  optional/external classification or bounded diagnostic.
- **Fix/result:** `v2/runtime/std/graph-plugin` owns deterministic process-local
  property/RDF storage and exact remote-query diagnostics through the native
  SPI. The local public example is exact on VM/ASM/build-jvm; the external
  RDF4J HTTP row is non-blocking and names the required explicit backend.
  Provider tests are 4/4 and the exhaustive release gate is green at 47
  identical / 19 both-fail / 6 blockers with zero mismatch/one-sided rows.

## v2-swift-distribution-authority-gaps — signed routes can rebuild or select an unverified product
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: c75f49fe2 -->

**Status:** done (2026-07-11, through `c75f49fe2`); reported by `nativeui-reviewer` in the
`scalascript` Rozum room during the `v2-swiftui-apple-distribution-adapters`
pre-code audit at 22:07–22:08, after unsigned Xcode closure `1ff9b2e76`.

- **Real routing repro:** non-v1 `run --target ios --device` currently drops
  `--device`; `package` parses the source with v1 `Parser` before target routing
  and hard-stops; legacy device/archive helpers call `JvmGen` and infer
  `Debug-iphoneos`/`<App>.app`; generated fastlane lanes call `gym`, allowing a
  second build and product selection outside the checked-v2 artifact.
- **Expected:** one checked `SwiftV2Cli.emit` creates a
  `V2AppleDistributionContext`. Device, archive, export, notarization, DMG, and
  publish consume only its `XcodeAppArtifact`, explicit project/scheme, shared
  build-settings query, archive-relative app path, and common
  APPL/bundle/non-CLI verifier. No v1 parser/generator or inferred product path.
- **Credential/safety gap:** team id must be CLI then `SSC_TEAM_ID`; API key
  JSON must be flag then env; notarization must use an explicit keychain
  profile and bounded timeout. No prompt, secret logging, stale/duplicate
  export, archive traversal, or missing-tool stack trace is accepted.
- **Spec review residual (22:11 BLOCKED):** freeze Mac App Store as a checked
  Release archive plus canonical `app-store-connect` automatic export yielding
  exactly one PKG; make DMG input the codesign-verified app and require
  staple/validate only when notarization ran; transport release notes only via
  `SSC_RELEASE_NOTES` and pin custom lane names plus hostile-text gates.
- **Implementation review round 1 (22:33 BLOCKED):** route every explicit v2
  Apple package before `Parser` and reject signed `--v2 package` without a
  target before parse. Preflight flag-dependent codesign/ditto/xcrun/hdiutil,
  parse timeout with an exact bounded diagnostic, and reject incomplete API-key
  JSON before archive. Generated pilot/deliver must consume `SSC_BUNDLE_ID`.
  Extend secret-free fakes across device, Developer-ID/notary toggles, Mac PKG,
  generated/custom fastlane, tool absence, archive path/plist/app identity, and
  zero/duplicate app/PKG outputs; assembled negatives must prove no v1/stack.
- **Implementation review round 2 (22:53 BLOCKED):** generated Mac publication
  must invoke the platform-scoped lane as `fastlane mac mac_appstore`; the
  current bare `mac_appstore` resolves under `default_platform(:ios)` and the
  fake runner hides the real Fastlane failure. App verification must choose
  iOS versus macOS bundle layout from `SwiftPlatform`, never from which plist
  happens to exist; current device/archive fakes encode a mac-shaped iOS app.
  Fastlane API-key validation requires non-empty `key_id` and `key`, with
  optional `issuer_id` for supported individual keys. Add independent
  notarize/DMG toggle combinations and an assembled plain non-v1
  `package --target macos` Parser-bypass gate before round 3.
- **Plan/done-when:** commit the exact distribution authority spec delta and
  obtain Rozum APPROVE before code. Then implement secret-free command plans,
  verified archive/export handoffs, device deploy, Developer-ID/notary/DMG, and
  explicit IPA/PKG fastlane upload; gate with pure argv/env, fake-runner,
  synthetic archive/export, syntax, and assembled bounded-negative tests.
- **Root cause/fix:** signed routes still delegated product selection to legacy
  parser/generator/path conventions and allowed upload tooling to rebuild.
  They now share one checked-v2 context, platform-strict app/archive verifier,
  canonical fresh exports, bounded preflight, and explicit artifact-only
  fastlane lanes. `nativeui-reviewer` confirmed round 3; Swift is 43/43, the
  combined CLI matrix 53/53, assembled e2e passes, and `tkv2-*` is 12/12.

## v2-swift-xcode-contract-gaps — application metadata and product authority are ambiguous
<!-- status: unknown
     lane: jvm
     area: front -->

**Status:** CORE FIXED by swift-sibling work since the report; only the signed
distribution-adapter slice remains (feature-scale). Verified end-to-end (opus, 07-13,
real Xcode 26.5): `ssc-tools build --v2 --target macos examples/frontend/ios-hello/ios-hello.ssc`
writes a real Swift package + `ios_hello.xcodeproj` + scheme; `xcodebuild -showBuildSettings`
shows `PRODUCT_BUNDLE_PACKAGE_TYPE=APPL`, `PRODUCT_BUNDLE_IDENTIFIER=com.example.ios-hello`,
`FULL_PRODUCT_NAME=ios_hello.app`; `xcodebuild build` → BUILD SUCCEEDED, producing
`ios_hello.app` with a non-CLI `Contents/MacOS/ios_hello` and `Info.plist`
`CFBundlePackageType=APPL` / correct id / executable. All three "can't prove it's a real
.app" claims are satisfied. REMAINING: the named codesign/notarize/TestFlight distribution
adapter (feature-scale, swift specialists). _Original report:_ reported by `nativeui-reviewer` in the
`scalascript` Rozum room during the pre-code Xcode-project audit at seq/time
20:55, after Trusted HTML closure `2c1db9f1d`.

- **Design repro:** the v2 generator returns one ambiguous executable string and
  writes SwiftPM/Apple sources but no PBX application artifact. Apple CLI lanes
  either run the debug CLI or stop, while legacy device/archive/publish helpers
  call v1 `Parser`/`JvmGen`, hard-code `Debug-*` product paths, and do not prove
  that the selected bundle is an application rather than `<AppName>Cli`.
- **Expected:** metadata is extracted with the checked v2 source pass; UI mode
  requires validated reverse-DNS bundle identity and validated Apple versions.
  One deterministic multi-platform application target/scheme owns all AppCore,
  AppleApp, and resource inputs. Apple commands consume an explicit
  `XcodeAppArtifact`, discover the product through build settings, then verify
  `.app`, `CFBundlePackageType=APPL`, exact bundle id, and non-CLI executable.
- **Spec review residual (BLOCKED):** target-scoped cleanup is not precise
  enough. Current emit removes `Sources`/`Package.swift` but can retain an old
  product's xcodeproj/`@main` files; broader deletion could destroy user-owned
  resources. Freeze a sorted `.ssc-swift-generated.json` of exact owned paths,
  validate every entry as relative with no `..`, delete only the previous list
  and empty owned directories, preserve unlisted files, and atomically replace
  the manifest last. Gate product rename, UI→domain→UI, and preserved resources.
- **Implementation review round 1 (BLOCKED):** the metadata helper treats
  indented nested YAML keys as top-level and drops empty values into defaults;
  explicit `frontend: swiftui` does not force UI mode. Preserved user Resources
  are absent from the PBX phase, and ownership falls back to a non-atomic move.
  `XcodeAppArtifact` is not yet consumed: build/macOS run still use SwiftPM CLI,
  iOS still rejects, and no shared showBuildSettings/APPL/bundle/non-CLI verifier
  exists. The real gate hand-builds Program, hard-codes Debug paths, weakly
  matches `-list`, uses a generic simulator, and omits plist/product discovery
  and bounded launch. Record/fix all before round 2.
- **Implementation review round 2 (BLOCKED on evidence only):** production
  residuals above are closed, but the checked gate does not byte-compare two
  complete written UI trees including ownership manifest or assert the frozen
  destination-specific Xcode settings (target/product, bundle/display/version,
  deployments/platforms, Catalyst off, no team). macOS smoke calls unbounded
  `waitFor()` after `destroy`; use timed wait and forced kill in `finally` so
  both process and temporary-tree cleanup are bounded.
- **Unsigned application closure (2026-07-11):** generator `d1b4350b7`, common
  unsigned adapters `abf9943c8`, and acceptance evidence `3942297ca` are on
  `origin/main`. Checked metadata, deterministic PBX/scheme/resources,
  manifest-scoped atomic ownership, build-setting discovery, and plist
  verification drive real macOS and concrete iOS Simulator apps. Reviewer
  round 3 APPROVE; Swift 43/43, CLI 8/8, assembled e2e, and `tkv2-*` 12/12.
  Status remains open only for the named signed distribution-adapter slice.
- **Plan/done-when:** freeze the exact metadata/default/validation, PBX id and
  target settings, source/resource membership, artifact split, discovery, and
  distribution sub-slice in `specs/v2-swift-swiftui-native.md`; obtain Rozum
  design APPROVE before implementation. Close only after real unsigned macOS
  build/inspection/bounded launch, installed iOS 26.5 Simulator build, signed
  adapter routing tests, full affected suites, and reviewer confirmation.

## v21-native-zero-arg-println-arity — blank println fails before later statements
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: e74241f5e
     confirmed: no -->

**Status:** fixed (2026-07-11, `e74241f5e`), awaiting Sergiy confirmation;
found by codex while running the new core-free YAML provider against
`yaml-parse.ssc`.

- **Real-harness repro:** after staging the YAML provider, run
  `bin/ssc run --native examples/yaml-parse.ssc` or its `--bytecode` lane. Both
  print the first five YAML-derived lines and then fail at the source
  `println()` with `arity: 1 expected, 0 given`.
- **Expected:** the established zero-argument `println()` emits one empty line
  and document execution continues identically on VM/direct ASM/build-jvm.
- **Root cause:** the self-hosted lowerer exposes `println` only as an arity-one
  lambda and lowers an empty source application without the compatibility
  frontend's empty-string adaptation.
- **Fix/evidence:** lower only zero-argument global `println()` to the portable
  print primitive with an empty string. The focused real-harness fixture is
  exact on VM/direct ASM, the complete native-entry smoke passes, the YAML
  example advances to its independent fenced-section boundary, and fresh
  affected conformance passes 11/11.

## v2-bytecode-x402-unhandled-op-success — bytecode lane exits 0 with unresolved `Wallets.metaMask` op
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: 7192cd6e4
     confirmed: no -->

**Status:** fixed (2026-07-10, `7192cd6e4`); waiting for human confirmation
before `done`.

- **Found by:** codex during the TI-2 VM/ASM corpus baseline at `7ba4d413b`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, compare
  `bin/ssc run examples/x402-metamask.ssc` with
  `bin/ssc run --bytecode examples/x402-metamask.ssc` (the focused portable
  repro is `scripts/bc-parity-sweep --only x402-metamask.ssc --strict`).
- **Observed:** the VM exits 1 with source-located `Undefined: Wallets` at
  `Wallets.metaMask(Network.Base)`. The bytecode lane exits 0 and prints
  `Op("Wallets.metaMask", Base, <closure>)` as if the unresolved effect were a
  successful program result.
- **Expected:** VM and ASM agree. Until the wallet plugin/global is available,
  both lanes must fail with a stable unresolved-symbol/effect diagnostic; once
  it is available, both must execute it and produce the same observable output.
- **Root cause direction:** the v2 bytecode route preserves an unhandled plugin
  method fallback as a top-level `Op` value and the CLI treats that value as a
  successful result, while the VM/default route rejects the undefined
  `Wallets` global earlier. Inspect FrontendBridge method-object registration,
  `PluginBridge` fallback dispatch, and bytecode top-level result handling.
- **Owner/slice:** `v21-ti-native-front-parity` plus VM/ASM parity; add a
  focused conformance row before closing.
- **Root cause:** all four public v2 execution routes independently treated any
  non-Unit result as printable success. The bridge ASM path correctly preserved
  the unresolved dotted plugin fallback as `DataV("Op", ...)`, but its CLI
  result branch did not distinguish that diagnostic sentinel from ordinary
  user values.
- **Fix:** route bridge/native and VM/ASM final values through one result
  validator. A dotted auto-thread `Op` now raises `unhandled runtime effect`;
  the related missing-method `Stub` sentinel raises `unresolved runtime
  dispatch`; undotted user free-monad `Op` data remains printable.
- **Verified:** assembled `tests/e2e/v21-unhandled-effect-smoke.sh` rejects a
  native missing dispatch on VM and ASM and the exact bridge-ASM
  `Wallets.metaMask` repro; native-entry smoke PASS; CLI argv tests 2/2;
  affected `v2-*` conformance 8/8.

## v2-indent-conformance-demos-skipped — indent demo cases are skipped by conformance but fail directly
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: 886502d64
     confirmed: no
     gate: tests/conformance/indent-config-format.ssc -->

**Status:** fixed (2026-07-10, demo fix `886502d64`, conformance lane
`bcffa0019`); waiting for human confirmation before `done`.

- **Found by:** codex while fixing `v2-dsl-yaml-tuple-accessor`.
- **Repro before fix:** after `scripts/sbtc "installBin"`, direct v2 runs
  failed: `bin/ssc run tests/conformance/indent-config-format.ssc` crashed with
  `__method__: no dispatch for ._1 on "host"`, and
  `bin/ssc run tests/conformance/indent-block-statements.ssc` crashed with
  `__method__: no dispatch for ._1 on "x"`. The conformance harness also
  reported both cases as `SKIP (no expected/*.txt)`, so it did not catch the
  direct production v2 failures.
- **Root cause:** the demo parsers relied on infix precedence in expressions
  like `identifier <~ ... ~ value`; v2 grouped the `~` under the `<~`, so the
  mapper received the left scalar (`"host"`/`"x"`) instead of a Tuple2. The
  config demo also used a nullable blank-line regex that could match the empty
  string before consuming separators, leaving the second section in `rest`.
  Finally, `tests/conformance/run.sc` had only INT/JS/JVM lanes, so there was no
  honest way to activate v2-only expected-output cases.
- **Fix:** parenthesized the parser sequences so maps receive the intended
  Tuple2 shapes, changed config blank-line skipping to a non-nullable
  `blankLine.many()`, added a `while`/`for` sample to cover nested tuple shape,
  added expected outputs, and taught the conformance runner an opt-in `V2` lane
  for files declaring `backends: [v2]`.
- **Verified:** direct `bin/ssc run --v2` for both files; `bash -n
  tests/e2e/indent-layout-v2-smoke.sh &&
  tests/e2e/indent-layout-v2-smoke.sh`; `tests/conformance/run.sh --only
  'indent-config-format,indent-block-statements' --no-memo` now reports 2/2
  with `PASS [V2 ]`; `tests/conformance/run.sh --only 'parsing-*' --no-memo`
  passes 3/3; `git diff --check` passes.

## v2-bytecode-param-long-nontail-self-loop — `fixed` (2026-07-09)
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex while rerunning final gates for
  `v2-source-jvm-recursion-fib-perf` after rebasing onto `origin/main`.
- **Repro:** on fresh `origin/main` `8ec03cfbf`, run
  `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z recursive"`.
- **Observed failure:** `v2 bytecode self-recursive int arithmetic keeps VM result`
  returns `IntV(1)` instead of `IntV(832040)` for:
  `def fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2); fib(30)`.
- **Expected:** the bytecode lane returns the same `IntV(832040)` as the v2 VM.
- **Root-cause hypothesis:** `JvmByteGen.emitParamLong` emits every self-call in a
  Long-specialized helper as a parameter-rebinding loop jump. That is valid only
  for tail self-calls. Non-tail recursive calls inside expressions such as
  `fib(n - 1) + fib(n - 2)` must call the Long helper recursively and leave a
  value on the operand stack.
- **Impact:** the current v2 bytecode recursive-Int fast path is semantically
  wrong for non-tail recursion; this blocks using the focused recursive bridge
  gate as a production signal.
- **Fix:** `41e2fe1ed` threads tail-position information through
  `JvmByteGen.emitParamLong`: tail self-calls keep the parameter-rebinding loop,
  while non-tail self-calls emit recursive `invokestatic <helper>(J...)J`.
- **Verified:** focused recursive bridge test 3/3, self-tail bridge test 1/1,
  affected recursion conformance 3/3, and `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## jvm-artifact-cache-codegen-invalidation — `fixed` (2026-07-09)
<!-- status: fixed
     lane: jvm
     area: codegen
     gate: tests/conformance/litdoc.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while fixing `v2-litdoc-js-jvm-backend-lanes`.
- **Repro:** generate a `.scjvm` artifact for a fixture, change JVM codegen
  without changing the `.ssc` source bytes, run
  `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
- **Observed failure:** `run-jvm` reused
  `tests/conformance/.ssc-artifacts/litdoc.scjvm` because the artifact cache is
  invalidated only by the source SHA. `bin/ssc emit-scala
  tests/conformance/litdoc.ssc` showed the fixed generated Scala, but
  `bin/ssc run-jvm tests/conformance/litdoc.ssc` still compiled the stale
  `.scjvm` until that one generated file was removed.
- **Impact:** after upgrading compiler/codegen/runtime bits, a user can keep
  running stale JVM generated source for unchanged `.ssc` files. This can hide
  fixes or preserve old failures in production verification.
- **Root cause:** `.scjvm` freshness only compared the `.ssc` source SHA, so
  source-fresh artifacts survived JVM backend/runtime codegen changes.
- **Fix:** `322ee868f` added `codegenVersion` to `ModuleJvmArtifact`, writes
  the current JVM codegen cache key, and makes `ModuleGraph.isJvmStale`
  invalidate legacy/old-key artifacts. `14aa2819d` adds a CLI regression that
  proves `run-jvm` regenerates a source-fresh `.scjvm` with an old key.
- **Gates:** `core/testOnly scalascript.artifact.ModuleGraphTest`;
  `cli/assembly; cli/testOnly scalascript.cli.JvmIncrementalCliTest`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'litdoc' --no-memo`.
- **Status:** fixed; waiting for reporter/human confirmation before `done`.

## v1-jvm-state-threaded-handler-codegen — `fixed` (2026-07-12, opus)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Root cause (refined 2026-07-12, opus) — THREE layers, all Any-typing of the
  deep-handler in JVM codegen:**
  1. `emitCaseBody` rendered a handler-arm lambda `(s: Int) => …` as bare `s => …`
     (dropped the type) → "could not infer parameter s". **FIXED** (571921446):
     annotate each param with its decltpe or `: Any`, mirroring JvmGenCpsTransform.
  2. `resume(())(x)` — `resume` is `Any => Any`, so `resume(())` is `Any`; applying
     `(x)` to it → E050 "Function1 does not take more parameters". Needs the inner
     `resume(())` cast to `Any => Any` before the outer application.
  3. `threaded(0)` — the `_handleWithReturn(...)` result is bound to an `Any` val;
     `threaded(0)` → E050 "threaded does not take parameters". Needs the general
     application codegen to cast an Any-typed callee to `Any => Any`.
  Layers 2–3 FIXED (571921446 + this commit): cast the Any-typed intermediate/callee to
  `Any => Any` at both application sites. run-jvm now matches INT/JS. `effects`/`head-field-effect-shadow`
  conformance stay green on all lanes after layer 1.

- **Found by:** claude-fable-5, while shaping the effect-multiarg-op regression.
- **Symptom:** `bin/ssc run-jvm` fails to COMPILE any handler whose arms return
  lambdas (the state-threading deep-handler idiom, busi's `runJournal`):
  "I could not infer the type of the parameter s / Expected type for the whole
  anonymous function: Any". Arity-independent (1-arg op repros identically);
  INT and JS lanes run the same code fine.
- **Repro:** `effect Cnt: def tick(n: Int): Unit` + handler arms
  `case Cnt.tick(n, resume) => (s: Int) => resume(())(s + n)` /
  `case Return(x) => (s: Int) => x`, applied as `threaded(0)` → `run-jvm` fails,
  `run --v1` / `emit-js` pass.
- **Impact:** low today — busi runs the interpreter lane; no corpus case uses the
  idiom on the JVM lane (that's why it was never seen).

## jvm-scjvm-cache-codegen-version — `fixed` (2026-07-08)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: 322ee868f
     gate: tests/conformance/.ssc-artifacts/std-ui-extended.scjvm -->

- **Found by:** codex, while fixing `conformance-jvm-std-ui-generated-braces`.
- **Repro:** keep a stale generated artifact such as
  `tests/conformance/.ssc-artifacts/std-ui-extended.scjvm` from before a JVM
  backend codegen fix, rebuild/install the CLI, then run
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** `run-jvm` reused the source-fresh `.scjvm` artifact and still
  failed with the old generated Scala `'}' expected, but eof found`. Removing only
  `tests/conformance/.ssc-artifacts/std-ui*.scjvm` forced regeneration and the same
  assembled command passed. This means the `.scjvm` freshness key does not account
  for backend codegen/runtime changes.
- **Status:** fixed in `322ee868f`. Root cause was that `.scjvm` artifacts used
  only the `.ssc` `sourceHash` as their freshness key, so generated Scala from an
  older JVM backend survived source-fresh after codegen/runtime fixes.
- **Fix:** `.scjvm` artifacts now carry `codegenVersion =
  "jvm-codegen-2026-07-08-1"` when emitted by the normal JVM artifact writer.
  `ModuleGraph.isJvmStale` treats missing/old codegen versions as stale while
  preserving ABI compatibility: legacy artifacts remain readable, then regenerate.
- **Verified:** `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
  (**15/15 green**, including legacy/old-version source-fresh `.scjvm`
  invalidation), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
  (**7/7 green**), `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-int-variables-while-update — `fixed` (2026-07-08)
<!-- status: fixed
     lane: jvm
     area: cli
     fixed-in: 4e67a2f41
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'variables' --no-memo` or
  `bin/ssc run --v1 tests/conformance/variables.ssc`.
- **Observed:** INT prints `5`, `10`, `720`, `55`; expected line 2 is `15`.
  JS/JVM pass. The first `while x < 5` loop increments `x` but does not accumulate
  the updated `x` into `sum`.
- **Status:** fixed in `4e67a2f41`. Root cause was the interpreter closed-form
  while optimizer, not the generic assignment path: it folded a body shaped like
  `x = x + 1; sum = sum + x` as if `sum` read the pre-update counter, producing
  `0+1+2+3+4 = 10`. ScalaScript assignment order requires `sum` to read the
  post-update `x`, producing `1+2+3+4+5 = 15`.
- **Verified:** `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
  (**6/6 green**); `scripts/sbtc "installBin"`; direct
  `bin/ssc run --v1 tests/conformance/variables.ssc`; and
  `tests/conformance/run.sh --only 'variables' --no-memo` (**1/1 green**).

## conformance-std-typeclass-int-jvm-gaps — `fixed` (2026-07-08)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: f92d147b0
     gate: tests/conformance/std-index.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`, run either
  `bin/ssc run --v1 tests/conformance/std-index.ssc` or
  `bin/ssc run-jvm tests/conformance/std-index.ssc`.
- **Observed:** INT prints the first two lines of `std-index` and then hits
  `StackOverflowError` in `EvalRuntime.evalApplyGeneral`/`DispatchRuntime.dispatch1`.
  JVM generated Scala rejects imports from `std/index.ssc` and sibling typeclass
  modules: `Left`/`Right` are imported from module objects that do not define them,
  and aggregate exports such as `std.intSum` are missing. Related full-gate misses:
  `std-foldable-traversable`, `std-functor-applicative-monad`, `std-index`,
  `std-bifunctor`, `std-monaderror`, and `std-selective`.
- **Status:** fixed in `f92d147b0` and `7328e35db`. Root causes were split
  across both lanes: INT extension dispatch preferred an imported same-named
  extension over built-in members, so `Option.map` in `map2Option` recursed;
  JVM import generation lost std typeclass re-export provenance, omitted
  standalone top-level extension imports, and emitted explicit context-bound /
  `using` instance calls as flat Scala calls. The std typeclass manifests also
  needed explicit type exports/imports so the strict import gate can resolve
  aggregator exports deterministically.
- **Verified:** `scripts/sbtc "backendJvm/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`;
  `scripts/sbtc "installBin"`; direct INT/JVM repros for `std-index`,
  `std-selective`, `std-monaderror`, and `std-foldable-traversable`; and
  `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
  (**6/6 green**).

## conformance-dsl-multi-pass-js — `fixed` (2026-07-08)
<!-- status: fixed
     lane: jvm
     area: front
     gate: tests/conformance/run.sc
     fixed-in: unrecorded -->

- **Found by:** codex, while verifying the docs-only `v2-prod-corpus-scope` slice.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`
- **Observed:** `dsl-multi-pass` passes the INT and JVM lanes but fails JS:
  expected line 2 `[name-resolve] undefined: z` and line 3 `ok: 8`, got
  `[parse] unrecognised token: x` for both. The failing source parses
  `"x + z"` / `"x + y"`; `parseExpr("x")` should produce `Var("x")`.
- **Scope:** JS backend/conformance lane. Not caused by the corpus-scope docs
  change and not a default output-parity blocker, but it is a production hygiene
  gate before claiming broad green status.
- **Hypothesis:** JS lowering/runtime mishandles the string-character predicate
  shape used by `t.forall(c => (c >= 'a' && c <= 'z') || c == '_')`, so alphabetic
  identifiers are rejected as parse errors.
- **Root cause:** JS `String.forall` passes boxed `_Char` values to the predicate, but
  `_arith` ordered `_Char` against a one-character JS string literal with native JS
  object-vs-string comparison. Equality already normalized `_Char`; ordering did not,
  so `c >= 'a' && c <= 'z'` was false for alphabetic characters.
- **Fix:** `39ebb6fda` adds a shared `_charCodeOrNull` helper and normalizes `<`, `>`,
  `<=`, and `>=` when either operand is `_Char`, while preserving normal string
  concatenation and ordinary string-vs-string comparison.
- **Verification:** after `scripts/sbtc "installBin"`,
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` passes
  `dsl-multi-pass` in INT/JS/JVM. Neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  confirms `collections` and `dsl-multi-pass` still pass; the remaining `parsing*`
  INT-only failures are tracked separately as `conformance-parsing-int-empty-output`.

## bytecode-shared-runtime-routes-unbound — `fixed` (2026-07-07)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** claude (green-main takeover), unmasked by fixing EmitScalaFacadeCliTest's missing
  `-Dssc.lib.path` (the CompilerLoader env error hid it).
- **Symptom:** `ssc compile-jvm --bytecode` fails: "shared runtime compile failed …
  `_ssc_runtime.scala:4931: Not found: _routes` / `Not found: route`" — `JvmGen.genRuntime`'s
  capability gating emits runtime code that references the route registry without emitting its
  definitions (route infra lives in the http/serve runtime piece; the gate combination for the
  bytecode shared runtime includes the referencing piece but not the defining one).
- **Repro:** `sbt 'cli/testOnly *EmitScalaFacadeCliTest'` — 5 of 7 fail on this (2 pass after the
  lib-path harness fix). Or any `compile-jvm --bytecode` invocation.
- **Impact:** the whole `--bytecode` separate-compilation happy path (compile-jvm/link facade family).
- **Root cause:** self-contained `JvmGen.genModule` always emitted either `serveRuntime` or
  `stubServeRuntime`, but split `JvmGen.genRuntime` omitted both when the unioned capability set did
  not contain `Serve`. The always-included common/effects runtime still references route/http/ws
  dispatch symbols, so the shared `_ssc_runtime.scala` could not compile for no-server bytecode
  artifacts.
- **Fix:** `83fc339e2` emits `stubServeRuntime` in split runtime when `Serve` is absent and adds a
  `JvmGen.generateRuntime(Set.empty)` regression test for `_routes`, `route`, `onWebSocket`, and
  `_httpDoRequest`.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest"`;
  `scripts/sbtc "installBin"`; `scripts/sbtc "cli/assembly"`; `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.

## scjvm-artifact-cache-ignores-compiler-version — `fixed` (2026-07-07)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: 322ee868f -->

- **Found by:** claude (green-main takeover), while fixing jvmgen-block-call-empty-parens: after
  rebuilding `bin/ssc` with a codegen fix, `ssc run-jvm` kept producing byte-identical BROKEN output.
- **Symptom:** `run-jvm` reuses `<file-dir>/.ssc-artifacts/<name>.scjvm` whenever the SOURCE sha
  matches (`ModuleGraph.isJvmStale`) — the cache key ignores the compiler/binary version, so codegen
  fixes are invisible until the artifact is deleted by hand. Cost ~4 rebuild-and-scratch-head cycles.
- **Repro:** run any `.ssc` via `run-jvm` (caches the emission), change JvmGen, `installBin`, run
  again — the old emission runs.
- **Fix:** duplicate of `jvm-artifact-cache-codegen-invalidation`, fixed by
  `322ee868f` (`codegenVersion` on `.scjvm` artifacts +
  `ModuleGraph.isJvmStale` key comparison) and guarded by `14aa2819d`
  (`run-jvm` regenerates a source-fresh `.scjvm` whose codegen key is old).
- **Gates:** see `jvm-artifact-cache-codegen-invalidation` above:
  `ModuleGraphTest`, `JvmIncrementalCliTest`, `installBin`, and conformance
  `litdoc` on INT/JS/JVM.
- **Workaround:** `rm -rf <dir>/.ssc-artifacts` after rebuilding the binary.
- **Status:** fixed; retained as a historical duplicate of the 2026-07-09
  cache-invalidation fix.

## jvmgen-block-call-empty-parens — `fixed` (2026-07-07)
<!-- status: fixed
     lane: jvm
     area: front
     fixed-in: unrecorded -->

- **Fixed by:** claude (green-main takeover), SHA: see `fix(jvm): conformance JVM-lane...` on main.
  Peeling the symptom exposed THREE stacked root causes; all four tests now PASS
  (`bin/ssc run-jvm` on signals / effects / rest-validate / distributed-map), with regression
  guards frontendSwiftUI 118/118 (widget `f() { block }` form preserved for curried callees) and
  backendInterpreter `*JvmGen* *Effect*` 193/193.
  1. **empty-parens** (signals, rest-validate): `JvmGen.emitExprDeep` emitted `f() { block }` for
     EVERY bare-name single-block call (fa5d3c821, Jun 2 — swiftui widgets). Broke single-thunk
     callees (`computed`/`effect`/`validate`/`runActors`). Now curried form only when
     depDefs/localDefSigs shows ≥2 param clauses. Exposed 2026-07-06 when bp2-2 enabled the
     warm JVM batch lane — the bug is older than the "suspect window" guessed below.
  2. **Console duplicate** (effects): the preamble's `object Console` println-shadow collided with
     a user `effect Console:` lowering. Preamble shadow is now omitted when the module defines its
     own top-level `Console` (JvmRuntimePreamble.sourceFor + collectUserTopNames bare-Stat arm);
     the effect-object lowering carries the println/print bridges instead.
  3. **premature declared-type cast** (effects, distributed-map): CPS-emitted defs whose ops are
     handled at the CALL SITE (`handle(greet())`) were cast to their declared type
     (`.asInstanceOf[String]`) while still holding an unresolved Free → ClassCastException
     `_FlatMap → String`/`DistributedResult`. Declared type is now preserved only for
     SELF-handling bodies (contains a `handle` form), extending codex's
     `preserveTotalEffectfulReturnTypes`.
  Diagnosis gotchas that cost cycles, recorded for the next agent: `.ssc-artifacts` scjvm cache
  (see the new open bug above), a stale sbt/bloop daemon from a deleted worktree answering builds
  (`scripts/kill-stale-builders --kill`), and macOS Xcode `strings` silently failing on JVM
  `.class` files (use `grep -ac` / `unzip -p … | grep -ac` instead).

### Original report (superseded 2026-07-07)

- **Found by:** claude (tkv2-components slice), via the full-corpus A/B: 4 tests
  (signals, effects, rest-validate, distributed-map) fail the JVM lane in any FRESH
  build of origin/main, while the shared main checkout "passes" only because its
  `bin/ssc` is STALE (pre-2026-07-03 source — its generated preamble lacks the
  webauthn `configureStore` block and `Bench.opaque`). Reproduced on a pristine
  origin/main worktree + fresh `installBin`.
- **Symptom:** `ssc run-jvm tests/conformance/signals.ssc` — user code
  `val doubled = computed { … }` is emitted as `computed() { … }` (empty first arg
  list + trailing block) → Scala compile error "missing argument for parameter
  thunk". Same for `effect { … }`.
- **Repro:** `scripts/new-worktree probe && cd ../scalascript-wt-probe &&
  scripts/sbtc installBin && bin/ssc run-jvm tests/conformance/signals.ssc`.
- **Suspect window:** whatever changed JvmGen's call-with-block emission between the
  main checkout's stale binary (~2026-07-03) and current origin/main. Not caused by
  the tkv2 slice (repro has none of its commits). NOTE: rebuild the shared main
  checkout's bin/ssc after fixing — its staleness masks this class of regression
  in any corpus run executed from the main checkout.

## jvmgen-handle-result-mainpath — `fixed` (2026-06-15, all contexts incl. Any-taint propagation)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (effect-result × main-path composition probes).
- **Symptom:** a `val r = handle(...)` (Any-typed `_handle` result) used in a NON-arithmetic main-path
  context fails JVM scala-cli; interp + JS run it fine. A cluster of related JVM-only divergences:
  - `r match { case _ => r * 2 }` → `value * is not a member of Any` (`emitExprDeep` had no `Term.Match` case → arm fell to `.syntax`).
  - `if r > 5 then r * 10 else 0` → `Found Any / Required Boolean` (the `_binOp(">", r, 5)` cond wasn't cast to Boolean).
  - `dbl(r)` (user fn) → `Found Any / Required Int` (main-path call didn't cast the arg to the callee param type; only the CPS path did).
- **FIXED (2026-06-15):** in `emitExprDeep` — added a `Term.If` Boolean cast when the cond is an Any-typed handle-result comparison, a `Term.Match` case that recurses scrutinee + arm bodies + guards, and a `Term.Tuple` case; cast main-path call args that reference a handle-result val to the callee's `calleeParamType` (reusing the CPS `localDefSigs`/`depDefs` index). Routed any term that references a handle-result val through `emitExprDeep` via a new `termRefsHandleResultVal` in `termNeedsCustomEmit`. Guard: `CrossBackendPropertyTest` "effect-result main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith / nested-handles — interp == JS == JVM).
- **ALSO FIXED (2026-06-15, Any-taint propagation):** the two formerly-deferred contexts:
  - `List(r, r).sum` → `No given Numeric[Any]` — broadened the `emitExprDeep` `_anyCall0` Select routing from "qual IS a handle-result-val Name" to "qual REFERENCES one" (`termRefsHandleResultVal(qual)`), so `List(r, r).sum` → `_anyCall0(List(r, r), "sum")`.
  - tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` — added `anyTypedVals`, a superset of `handleResultVals` populated by Any-taint PROPAGATION: an untyped val whose rhs references an Any-typed val (`val t = (r, r+1)`) is itself Any-typed. The routing predicates now key off `anyTypedVals`, and the arith-operand check also recognizes `Select(anyTypedVal, _)` (so `t._1 + t._2` lowers to `_binOp`). Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected. Guard: `result-in-list-sum` + `result-in-tuple` added to the composition test (interp == JS == JVM).

## agent-streaming-test-port-collision — `fixed` (2026-06-15, 26dae7699)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex during `rozum-agent-endpoint-pool` regression check.
- **SHA at filing:** `2334d0be4` (feature worktree).
- **Symptom:** running the sync and streaming agent SDK suites in the order
  `AgentSdkInterpreterTest AgentSdkStreamingInterpreterTest` aborts the streaming
  suite with `java.net.BindException: Address already in use`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"`.
- **Root cause:** `examples/rozum-agent.ssc` binds `19694`, the same port as
  `AgentSdkStreamingInterpreterTest`; when the sync suite ran first, the
  streaming suite could immediately rebind the same port and abort.
- **Fix:** moved `AgentSdkStreamingInterpreterTest` to port `19698`.
- **Verify:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` — 14 tests passed in the formerly failing order.



---

## jvmgen-handle-result-arith — `fixed` (2026-06-15)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (new effect-composition shapes — handle result fed into main-path arithmetic).
- **Symptom:** using a `val` bound to `handle(...)` as an operand of an arithmetic/comparison infix fails JVM scala-cli with `value * is not a member of Any`. `handle(...)` lowers to `_handle(...)` (returns `Any`), so `val r = handle(...){…}; println(r * 2 + base)` emits `r * 2` raw on the Any-typed result, which Scala 3 rejects. interp + JS run it fine.
- **Repro:** a one-shot effect program ending `val r = handle(loop(n)){ case Counter.tick(resume) => resume(k) }; println(r * 2 + base)` (or two results: `println(r1 + r2)`).
- **Root cause:** `termNeedsCustomEmit` only routed a handle-result-val through `emitExprDeep` (where `ApplyInfix` lowers `+ - * / % < > <= >=` to `_binOp`) when the val appeared in a 0-arg method `Select` (`termContainsHandleResultCall`), NOT when it appeared as an arithmetic operand — so `r * 2` fell to `emitExpr`'s `.syntax` raw fallback.
- **FIXED:** added `termContainsHandleResultArith` (walks for a handle-result-val `Term.Name` used as an operand of an arithmetic/comparison `ApplyInfix`) and wired it into `termNeedsCustomEmit`; the existing `emitExprDeep` `ApplyInfix` → `_binOp` path then lowers it (nested arith re-fires the predicate via `emitCallArg`→`emitExpr`). Guard: `CrossBackendPropertyTest` effect sub-shapes 8 (`r*2+base`) and 9 (`r1+r2`), run through scala-cli on seeds 11/47 and 155/191. Property test green.

---

## jvmgen-multishot-handle-result-any — `fixed` (2026-06-15, 23a33c976)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (its multi-shot effect shape).
- **Symptom:** a method call on the result of `handle(...)` fails JVM scala-cli with e.g. `value sum is not a member of Any` — `handle(...)` lowers to `_handle(...)` which returns `Any`, so `val all = handle(prog()){…}; all.sum` (typical for a multi-shot handler whose result is a `List`) doesn't type-check. interp + JS (dynamically typed) run it fine.
- **Repro:** a `multi effect NonDet` program ending `val all = handle(prog()){ case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }; println(all.sum)`.
- **Severity / why deferred at filing:** harder than the emitCaseBody class — it is about the `_handle` RESULT type (Any), not the handler body. A real fix needed the codegen to know the handled-program's result type (here `List[Int]`) and cast, or `_handle` to be generically typed; `List[Any].sum` would still need `Numeric[Any]`.
- **FIXED (23a33c976):** runtime `_anyCall0(recv, m)` dynamically dispatches 0-arg collection methods on an Any Iterable (numeric folds via `_binOp`); codegen tracks vals bound to `handle(...)` (`handleResultVals`), routes a `x.method` on them through `emitExprDeep` (via `termContainsHandleResultCall` in `termNeedsCustomEmit`) → `_anyCall0`. Property test re-added the multi-shot `all.{sum,max,min,length}` shape as the guard; 96 tests green.
---

## jvmgen-effect-handler-arg-arith — `fixed` (2026-06-15, 7c843b121)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (its broadened multi-arg / arithmetic effect handlers).
- **Symptom:** a handler that does arithmetic on op-args, e.g. `case Combine.mix(a, b, resume) =>
  resume(a * b + 1)`, fails JVM scala-cli with `value * is not a member of Any`. The op-args are
  bound `val a = _args(0)` (type `Any`) and `emitCaseBody` had no arithmetic case → `a * b`
  emitted raw, which Scala 3 rejects on `Any`. interp + JS run it fine.
- **Repro:** `println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })`
  for `effect Combine: def mix(a: Int, b: Int): Int` → scala-cli "value * is not a member of Any".
- **FIXED (7c843b121):** `emitCaseBody` now lowers an arithmetic/comparison `ApplyInfix` to the
  `_binOp("op", l, r)` runtime helper (same as `emitExpr` for Any operands; mirrors the existing
  `::` Any-cast case). Guard: `CrossBackendPropertyTest` effect shapes (arg-carrying / two-op) run
  through scala-cli. 101 effect+jvmgen tests green.
- **ALSO FIXED (78d1ce178) — control-flow case:** an `if` in a handler body with a comparison on Any-typed op-args (`if k > 2 then resume(k) else resume(0)`) — `emitCaseBody` had no `Term.If` case so `k > 2` emitted raw. Added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) + casts the condition to `Boolean`. Property test gained a conditional-resume effect shape (run through scala-cli).

---

## jvmgen-handle-in-arg-position — `fixed` (2026-06-15, 91fc574f5)
<!-- status: fixed
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (xbackend-property-equivalence — the generated
  cross-backend differential, found this on its first effects run).
- **Symptom:** JVM codegen emits a `handle(...)` effect expression RAW (unqualified) when it
  appears in **call-argument position**, e.g. `println(handle(body){cases})`, so scala-cli fails
  with `Not found: handle - did you mean _handle?`. interp **and** JS run it correctly.
- **Works (idiomatic):** binding the result first — `val r = handle(body){cases}; println(r)` —
  lowers correctly to `_handle(() => body, Set(...), Map(...))`. Only the inline/nested form breaks.
- **Repro (minimal):**
  ```scalascript
  effect Counter:
    def tick(): Int
  def loop(n: Int): Int ! Counter =
    var acc = 0
    var i = 0
    while i < n do
      acc = acc + Counter.tick()
      i = i + 1
    acc
  println(handle(loop(3)) { case Counter.tick(resume) => resume(2) })
  ```
  `ssc emit-jvm` / scala-cli the output → "Not found: handle". Change last line to
  `val r = handle(loop(3)) { ... }
println(r)` → works.
- **Root cause:** `JvmGen` lowers `handle` via `emitExpr` (case `handle(body){cases}` →
  `emitHandleForm`) and special-cases the `val x = handle(...)` / statement forms, but an
  effectful term nested inside another `Term.Apply` arg falls to the `.syntax` raw fallback
  instead of recursing the arg through `emitExpr`/`emitHandleForm`. (Likely the same for other
  effectful forms — `runAsync`, etc. — as direct call args.)
- **Severity:** low — narrow corner case, trivial workaround (bind to a `val`). Fix touches the
  core CPS emission path (would need care vs the 33 JvmGenEffects tests), so deferred from the
  property-test slice that found it.
- **FIXED (91fc574f5):** `termContainsEffectExpr` (walks children for any effectful sub-expr) added to `termNeedsCustomEmit` so a `handle`/effect nested in a call arg routes through `emitExprDeep` and lowers to `_handle(...)`. Regression guard: `CrossBackendPropertyTest` effect kind uses the inline `println(handle(...))` form (interp==JS==JVM via scala-cli). 119 effect+jvmgen tests green, no regression.

---
