# v2 — the self-hosted compiler — bugs

Scope: defects whose FIX goes in `v2/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module v2`, never by
grepping for status.

Newest first.

## v2-lanes-cannot-run-four-corpus-workloads — float globals unbound, indexed array store not a function
<!-- status: open
     lane: native
     area: codegen -->

**Status:** OPEN (found 2026-07-28 by `bench-tier-dead-lanes` while building the first full
cross-backend table since the v2 work; filed, not fixed).

BOTH v2 lanes — the VM and the DEFAULT bytecode lane — fail outright on **4 of 33** bench-corpus
workloads. They had been printing `n/a`, i.e. reading as "unsupported", because the v2 lanes
swallowed their exception unless `SSC_BENCH_DEBUG` was set. That is now unconditional, so:

| workload | failure (both `v2` and `v2-bytecode`) |
|---|---|
| `float-loop` | `RuntimeException: unbound global: d` |
| `float-fold` | `RuntimeException: unbound global: d` |
| `pattern-match-heavy` | `RuntimeException: unbound global: d` |
| `array-update` | `RuntimeException: app: not a function: 0` |

Two causes, not four: three float-heavy workloads share one unbound global `d`, and the fourth is
an indexed array store.

**Reproduce:**

```bash
bin/ssc-tools --backend v2-bytecode bench --machine --reps 3 bench/corpus/float-loop.ssc
bin/ssc-tools --backend v2-bytecode bench --machine --reps 3 bench/corpus/array-update.ssc
```

**Why it matters beyond the benchmark:** these are ordinary programs — a float accumulation loop and
an in-place `Array` update. `pattern-match-heavy` in particular is one of the four rows the v2
production-route policy in `BACKLOG.md` was decided on, and it cannot currently run on either v2
lane at all.

## v2-front-drops-float-literal-suffix — `0d` / `1.5f` lex the suffix as an identifier
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN — **diagnosed here, handed over deliberately**. The fix belongs in
`v2/lib/ssc1-front.ssc0`, which is held by the live `v2-front-for-yield` claim (its heartbeat reads
stale but triage found a worktree and running processes, and its own last claim-update widened it
to exactly that file). Found 2026-07-28 by `v2-backend-matrix-gaps`.

**Reproduce** — two lines:

```scalascript
var x: Double = 0d
println(x)
```

| lane | result |
|---|---|
| INT (`ssc-tools run --v1`) | `0` |
| JS (`ssc-tools run-js`) | `0` |
| **v2** (`ssc run --v2`) | **`ssc: unbound global: d`** |
| v2, `SSC_FRONT=legacy` | same — so this is BOTH self-hosted fronts |

`1.5f` fails the same way with `unbound global: f`. `10L` works.

**Root cause, located.** `v2/lib/ssc1-front.ssc0`, the numeric-literal branch of `lexFrom`. It
strips a trailing `L`/`l` on both the hex and decimal paths — with a comment that says exactly why:
*"strip a trailing Long suffix L/l … without this the `L` lexed as a separate id → `unbound global:
L`"*. `d`/`D`/`f`/`F` were never given the same treatment, so the digits lex as a number and the
suffix lexes as an identifier.

**Fix shape** (for whoever holds the file): mirror the `L` handling at the three emission points of
that branch — the dot-float, the exponent-float, and the integer path — consuming a trailing
`d`(100)/`D`(68)/`f`(102)/`F`(70) and, on the integer path, emitting a **`float`** token rather than
an `int` one, since `0d` is a Double. Guard it with "the character after the suffix is not
alphanumeric" so `0dx` does not silently lex as `0d` + `x`.

**Impact, measured.** It is not just a literal-syntax gap: it took **three of 33 bench-corpus
workloads out of the matrix as "v2 cannot run this"** — `float-loop`, `float-fold` and
`pattern-match-heavy` — because the generated bench wrapper declared its Double sink as `0d`. The
programs themselves run on v2 perfectly well. The wrapper has been changed to `0.0` so the matrix
measures the backend rather than the parser, but the language gap is real and still open.

## v2-array-indexed-store-silently-dropped — `a(i) = v` is parsed away, and the answer is wrong
<!-- status: open
     lane: native
     area: front -->

**HALF FIXED 2026-07-29 (`v2-backend-matrix-gaps`). The DEFAULT front still has it — do not close.**

| front | `Array(0,0); a(1)=7; println(a(1))` |
|---|---|
| `SSC_FRONT=legacy` | **7** — fixed |
| `SSC_FRONT=F` (the DEFAULT) | **0** — still silently dropped |

Fixed on the legacy path by adding the `app` arm to `finishAssignment` in
`v2/lib/ssc1-front.ssc0`, beside the existing `var` and `sel` arms — that is the one place every
route into assignment parsing passes through — plus the `ForeignV(ArrayBuffer)` `update` arm in
`Prims` that the lowering now reaches. Both halves were required; the runtime arm alone had been
written and reverted earlier precisely because it was unreachable.

**Why F is not fixed here, and exactly where it is.** F is a separate self-hosted parser
(`specs/v2.2-p6.5-fsub.ssc`, staged as `tower/bin/fsub.ssc`) with its own, narrower rule:

```
def isAssignHead(ts) = if fst(hd(ts)) == 1 then isAssignOp(hd(tl(ts))) else false   // :1156
```

It requires the FIRST token to be an identifier and the SECOND to be `=`. For `a(1) = 7` the second
token is `(`, so the statement is not an assignment head at all; it falls to `parseExpr`, which
stops at `=` and drops the rest. Same defect, different code, and it needs a postfix-then-`=` path
rather than a two-token peek. F emits S-expression strings (a method call is
`(app (global _sel_<name>) recv args…)`), so the arm must be built where the receiver and index
arguments are still separate values, not recovered from an already-emitted string.

**This is the "fix BOTH fronts or it is a half-fix" shape** that `project_two_front_bug_pairs_0728`
records — met live: the legacy fix looked complete and correct, and the default front was still
wrong.


**Status:** OPEN — **diagnosed here, handed over deliberately** (the fix is in
`v2/lib/ssc1-front.ssc0`, held by the live `v2-front-for-yield` claim). Found 2026-07-28 by
`v2-backend-matrix-gaps` while clearing category 1 of `specs/v2-vs-v1-backend-matrix.md`.

**This is a CORRECTNESS bug, not a performance one.** It produces a wrong answer with no
diagnostic on either v2 lane.

```scalascript
val a = Array(0, 0)
a(1) = 7
println(a(1))
```

| lane | result |
|---|---|
| INT (`ssc-tools run --v1`) | `7` |
| JS (`ssc-tools run-js`) | `7` |
| **v2** (`ssc run --v2`, and `--interpret`) | **`0`** — the store is discarded, exit 0, no message |

Control: a plain `var x = …` reassignment works correctly on v2, so this is specifically an
**indexed** store.

**Root cause, located.** `v2/lib/ssc1-front.ssc0`, `parseExprOrAssign`:

```
let re = parseExpr(toks) in
if kindIs("=", prT(re)) then
  match prV(re) {
    case Pair(ltag, ldata) =>
      if #seq(ltag, "var") then  ... emit assign ...
      else pr(mkSExpr(prV(re)), prT(re))     -- ← anything else: the `= rhs` is DROPPED
  }
```

Only a bare `var` is accepted as an assignment target. For `a(1) = 7` the left side parses as an
application, falls into the `else`, and the parser returns just that expression — the `= 7` is
never consumed and never lowered. Nothing reports it.

**Fix shape** (for whoever holds the file): accept an application on the left of `=` and lower it
the way Scala defines it — `a(i) = v` is `a.update(i, v)`. **Both halves are needed**: the v2
runtime dispatch has no `update` arm for `ForeignV(ArrayBuffer)` either (it has `length`,
`isEmpty`, `nonEmpty`, `toList`, `foldLeft`, `map`, `foreach` and no store), so the front change
alone will turn a silent wrong answer into a silent no-op. A runtime arm was written and reverted
here precisely because it is unreachable until the front stops discarding the statement — landing
it alone would have looked like a fix while changing nothing.

**Impact:** `bench/corpus/array-update` cannot be measured on either v2 lane, and any `.ssc`
program that writes into an array is silently wrong on the default lane.

## v2-char-is-an-int — a Char literal IS its code point on the v2 lane, in `println`, `toString` and concatenation
<!-- status: open
     lane: native
     area: runtime
     gate: none -->

**Found 2026-07-30** by `jsgen-char-escape`, by its own gate: the conformance case written to prove a JS
Char-escape fix was run on all three lanes and v2 failed one line of it. Longer write-up, including why
it went unnoticed, in `specs/v2-char-is-an-int.md`.

```scalascript
def main() =
  println('x')
  println('x'.toString)
  val c = 'a'
  println(c)
  println("s" + 'b')
  println('x' == 'x')
```

| line | int (golden) | v2 |
|---|---|---|
| `println('x')` | `x` | **`120`** |
| `println('x'.toString)` | `x` | **`120`** |
| `println(c)`, `val c = 'a'` | `a` | **`97`** |
| `println("s" + 'b')` | `sb` | **`s98`** |
| `println('x' == 'x')` | `true` | `true` — agrees |

**It is not a `Show` difference.** `.toString` and string concatenation are wrong too, so the value is
not being RENDERED as a number — it *is* a number on this lane. v2 has no distinct Char representation,
so `'x'` compiles to the `Int` 120 and everything downstream sees an Int.

**Why it hid.** Equality agrees, and Char COMPARISON is the common case in a lexer or parser — including
inside the corpus. What breaks is Char-in-text. Note that `"a\nb".lastIndexOf('\n')` and `'\n' == '\n'`
both give int's answer on v2, so the gap is confined to a Char used as a value in text, not to a Char
passed as an argument.

**Do not** fix it by special-casing `Show` for an Int in Char position: the `.toString` and
concatenation rows show the value has already lost its identity by then. It needs a Char-shaped value in
the v2 runtime, or a compile-time marker that survives into `Show`/`toString`/`+`.

**Not gated yet, on purpose.** `tests/conformance/char-literal-escapes.ssc` deliberately omits the bare
`println('x')` line — including it would leave that case red for a reason unrelated to the escaping it
exists to gate. A fix here should bring its own case.

## sbt-test-7-failures-first-visible-2026-07-29 — `sbt test` ran to completion for the first time in weeks
<!-- status: open
     lane: native
     area: front
     gate: tests/e2e/v21-negative-toolchain-release-gate.sh -->

**Status:** open, 3 clusters, filed for their owners. Found by `serve-banner-to-stderr`: fixing the
negative-toolchain gate let the `sbt` job reach `Test via sbt`, which had been unreachable behind it.
Run 30468925065 (`4b2f65a96`), 207 min, **7 failed of several thousand**, suite ran to completion —
this is not a timeout.

The per-push jobs were green in the same run (Lint, Validate, 4 conformance shards, Examples), so
none of this is on the per-push verdict path.

### Cluster 1 — Swift backend, 4 tests. LIKELY FALLOUT FROM `dd56c4b8d` (Mirror).

```
java.lang.IllegalArgumentException: swift backend: unsupported primitive '__regmethod__'
  at ssc.swift.SwiftBackend$.fail(SwiftBackend.scala:436)
```

Failing: `run package uses the real Swift toolchain`, `emit writes deterministic v2 AppCore packages
for macOS and iOS`, `build accepts v2 lane flags in positional-independent order`, `checked top-level
metadata forces SwiftUI mode and rejects nested or empty bundle ids`.

`git log -S'__regmethod__' -- v2/lib/ssc1-lower.ssc0 specs/v2.2-p6.5-fsub.ssc` points at **dd56c4b8d**
("`Mirror.fromProduct` on the native lane"), whose message states `fromProduct` is "one tag-level
`__regmethod__`". So a new primitive reached a backend that does not implement it. **Same family as
`sql-plugin-rowcodec-mirror-arity`**, which that commit also broke and which is fixed — a Mirror
change has now bitten two consumers, so the third question worth asking is which others exist.

Owner: `v2/backend` is held by the `v2-backend-matrix-gaps` claim.

### Cluster 2 — JS runtime helper missing at execution, 2 tests.

```
ReferenceError: _charCodeOrNull is not defined        (x3 in one assertion)
  "[ERR:…]" did not equal "[1:hello,2:world,3:done]"  (JsGenTypedRouteClientTest.scala:643)
```

Failing: `JS typed route clients encode and decode through generated codecs`, `JS SSE runtime
delivers events and close() stops the stream`.

**What is already ruled out**, so the next person does not repeat it:
- the helper IS defined — `v1/runtime/backend/js/src/main/resources/scalascript/js-runtime/core-dispatch.mjs:18`;
- `JsGen.scala:233` appends `JsRuntimeCoreDispatch` **unconditionally**, commented "CoreDispatch +
  CoreCollections are always included";
- it is NOT the TreeShaker pruning it: the shaker walks AST declarations of user code, not the
  runtime prelude text (checked — it has no handling for runtime functions).

So the open question is precise: **the failing path must assemble its JS without going through that
always-include site.** `JsGen` emits `_charCodeOrNull(...)` as synthesized text at three places
(lines 3045, 3441, 3450), so any assembly that omits core-dispatch produces exactly this
ReferenceError. Start at how `JsGenTypedRouteClientTest` builds the bundle it executes.

`JsGen.scala` and `TreeShaker.scala` are unclaimed as of filing.

### Cluster 3 — v2 companion dispatch on the JS lane, 1 test.

`run-js --v2 dispatches an imported explicit companion (__mk_method_obj__)`. Not investigated; the
name suggests the same emitter-synthesized-symbol family as cluster 2 and as `dd56c4b8d`'s own note
("this is the SECOND emitter-synthesized reference to bite the shaker today").

### CORRECTION 2026-07-30 — clusters 1 and 3 are ONE cause, and it is bigger than either

Cluster 3's test is *named* for `__mk_method_obj__`, which is what it was originally written to
regress. It no longer fails on that. Its actual error, from run 30468925065:

```
Error: unimplemented primitive: __regmethod__
    at $prim (/tmp/ssc-native-js-….cjs:644:32)
```

The same primitive as cluster 1's Swift failure (`unsupported primitive '__regmethod__'`). So this is
not "Swift, JS and companion-dispatch"; it is **one primitive with no implementation**, surfacing
wherever a backend meets it.

**Verified by grep, repo-wide, excluding `.md` and `target/`:** `__regmethod__` occurs ONLY in the two
lowerers — `v2/lib/ssc1-lower.ssc0` and `specs/v2.2-p6.5-fsub.ssc` — plus one salvage patch. No
backend directory contains it: not `v2/backend/js`, not `v2/backend/swift`, not `v2/backend/rust`, not
`v2/backend-jvm-bytecode`, and not `v2/src`.

So both fronts EMIT a primitive that nothing implements by that name. That predicts the rust and wasm
backends fail identically the moment a program reaches it, and it means the failure count understates
the blast radius — it is bounded by which backends currently get exercised, not by which are affected.

Introduced by `dd56c4b8d` ("`Mirror.fromProduct` on the native lane"), whose message states
`fromProduct` is "one tag-level `__regmethod__`". **That commit has now broken three consumers:**
`sql-plugin` (Mirror arity, fixed in `0ee779b8d`), the Swift backend, and the v2 JS backend. A
lowering change that adds a primitive has to add it to every backend that can see it, or declare the
backends out of scope — the same rule `dd56c4b8d`'s own message states about shaker roots
("the symbol must add its shaker root in the same change").

**Left to the owner (`v2/backend` and `v2/lib` are both in the `v2-backend-matrix-gaps` claim):** one
question decides the fix — does the native VM implement it under a different mechanism (a generic
prim table built by concatenation, say), or does the native lane simply never reach it? If the
latter, the primitive has no implementation at all and the Mirror work is unfinished rather than
merely unported.

## v2-optin-provider-cases — cases that need an OPT-IN provider are run on the standard launcher and counted as v2 failures
<!-- status: unknown
     lane: native
     area: front -->

**Status: FIXED 2026-07-29** for the three PDF cases, with **no code change** — they now declare
`backends: [int]`, the gate the contract already honours. Measured per lane first
(`int=ok, js=FAIL, jvm=FAIL, v2=FAIL` for all three), so the declaration states what is true rather
than what is convenient, and the reason is written into each file's front-matter next to it.
Verified: `--only 'invoice-pdf,invoice-email,pdf-extract-demo,hello'` yields **6 lane cells instead
of 12**, all PASS, contract GREEN — the three contribute `int` only.

Deliberately NOT invented: a new `provider:` front-matter key. `backends:` already expresses "this
case runs on these lanes", the contract already reads it, and a second key would be a second thing
to keep in step. The MCP/NFC candidates below are still open and may not need one either.

**Original status:** OPEN — **measured; this is a case-selection gap, not a v2 defect** (found
2026-07-29 by `v2-optin-provider-cases` while working the plugin-globals cluster of `bugs-v2.md`).

**The evidence is build.sbt's own words.** `v2/runtime/providers/pdf-plugin` is declared:

> *"Explicit, opt-in PDF provider. Its renderer/parser dependencies are staged under
> `bin/lib/providers/pdf` and never enter the standard launcher graph."*

There is a separate launcher for exactly this — `bin/ssc-provider <name> run <file>` — and
`bin/lib/providers/` currently holds **five**: `graph-rdf4j`, `mcp`, `nfc`, `pdf`, `swift`.

**Measured, both directions:**

```
bin/ssc run --v2      examples/invoice-pdf.ssc   -> ssc: unbound global: htmlToPdfBase64
bin/ssc-provider pdf run examples/invoice-pdf.ssc -> renders, rc=0
```

So the three PDF cases (`invoice-email`, `invoice-pdf`, `pdf-extract-demo`) do not fail because v2
is broken. They fail because the corpus contract runs every case with `bin/ssc`, which deliberately
excludes the provider's heavy third-party deps (PDFBox, openhtmltopdf, jsoup).

**Same shape as `backend:`, which was fixed in `baa55cdb9`** — the contract executed cases on lanes
they never claimed. Here it executes them with a launcher they cannot run under. Both make the v2
number describe something other than v2.

**Why this is not a one-line follow-up.** Unlike `backend:`, the affected cases carry NO marker to
honour: `examples/invoice-pdf.ssc` has no front-matter at all. A fix therefore needs BOTH
(a) a declaration on the cases — the corpus already has a `requires:` key that `run.sc` honours via
its `backendFeatures` table, so extending that rather than inventing a second key is the obvious
route — and (b) `contract.sc` reading it, exactly as it now reads `backend:`.

**Not all of cluster C is this.** Verified: `bin/ssc-provider mcp run examples/mcp-agent.ssc` STILL
fails with `unbound global: mcpServer`, so the two MCP cases are a different problem even though
`mcp` is also a provider. `nfc-ndef` (provider `nfc`) is untested. Do not assume the whole cluster
collapses into this one — it is 3 confirmed, up to 6 candidates.

**Effect on the number:** if these are excluded the way `backend:` cases now are, the honest v2
count drops by at least 3 more.

## v2-infix-extension-operator-stringifies — `a ++ b` on a user type silently becomes a STRING
<!-- status: open
     lane: native
     area: codegen -->

**Status:** **PARTS 1+2 LANDED 2026-07-30** (`v2-backend-matrix-gaps`) — correct on
`SSC_FRONT=legacy`. **Part 3 (F, the DEFAULT front) is still open — do not close.** Found
2026-07-29 by `v2-content-and-dsl-diverge` while reducing `dsl-ast-builder`'s DIVERGE.

| form | INT | v2 legacy | v2 **F (default)** |
|---|---|---|---|
| `text("AA") ++ text("BB")` | `DocBeside(…)` | **`DocBeside(…)`** ✓ | `DocText(AA)DocText(BB)` ✗ |
| `"[" + s + "]"` (the trap) | `[mid]` | **`[mid]`** ✓ | `[mid]` ✓ |

Parts 1+2 landed together, which is what makes them safe: `primitiveWins` in `__arithExt__` now
also keeps STRING pairs (and, for `++`, list pairs) primitive, so widening the lowerer to `++`/`/`
does NOT turn `"[" + render(a) + "]"` into a Doc — the exact regression that got the first attempt
reverted. Verified both directions on the same build. Conformance `dsl*,content*,std-ui*,string*,
list*,collection*,parser*` on int,v2: 72/76, contract GREEN.

**Part 3 — what F needs, now that it has been looked at.** F does know about extensions
(`extMethods` lives in its context `cx`, :445), but the `++` emitters do not receive it:

```
def emitPP(l, r, dq)      = if isStrCode(l, dq) then emitSconcat(l, r) else emitArithS("++", …)   // :367
def emitPPt(l, r, lt, dq) = if lt == "S"        then emitSconcat(l, r) else emitArithS("++", …)   // :404
```

So the work is threading `cx` (or just the extMethods list) through `emitBin`/`emitBinT` into
`emitPP`/`emitPPt`, then emitting `(prim __arithExt__ (lit (str "++")) L R (global ++))` when the
name is an extension method. ⚠️ F self-compiles byte-identically as a hard invariant, so this must
be verified against the C_min/X1 fixpoints, not only conformance.

**Historical note on the original diagnosis** (kept — it is what made parts 1+2 land correctly):

**Reproduction** (real harness; `std/dsl/pretty.ssc` declares `extension (l: Doc) def ++` / `def /`):

```scalascript
[DocText, DocBeside, text](std/dsl/pretty.ssc)
val x = text("AA") ++ text("BB")
println(x)
```

| form | INT | v2 |
|---|---|---|
| `a ++ b` (INFIX) | `DocBeside(DocText(AA), DocText(BB))` | **`DocText(AA)DocText(BB)`** — a String |
| `a.++(b)` (EXPLICIT) | `DocBeside(…)` | `DocBeside(…)` — correct |

The explicit method form already dispatches correctly, so this is purely the INFIX lowering. It is
the whole of `dsl-ast-builder`'s divergence: `render(doc)` loses `module example`, `val x = 42` and
`  val y = x + 1`, because the document was never built — it was stringified.

**Root cause, exact.** `ssc1-lower` lowers infix `++` to `IrPrim("__arith__", ["++", l, r])`, and the
runtime's `__arith__` has a generic arm `case "++" => StrV(x.toString + y.toString)`. The mechanism
to do better ALREADY EXISTS — `__arithExt__(op, l, r, ext)` keeps primitive operands primitive and
otherwise calls the extension closure — but the lowerer emits it for **`|` only**, and the note
there says the other operators "keep the established numeric-first behavior". For a non-numeric
receiver that is not numeric-first, it is simply wrong.

**⚠️ Why the obvious one-line fix is WRONG — measured, not predicted.** Extending the
`isExtensionMethod(op)` guard to `++` and `/` in `ssc1-lower` does fix the case… and BREAKS string
concatenation in any program that declares such an extension:

```
"[" + render(a) + "]"   ->   DocBeside(DocBeside([, AA), ])
```

because `+` is upgraded to `++` by the KC5-micro string rule, and `__arithExt__`'s `primitiveWins`
guard only keeps **numeric** operands primitive — strings fall through to the extension. Reverted
for that reason; a fix that trades one silent wrong answer for another is not a fix.

**What a complete fix needs — two parts that must land together:**
1. `v2/src/Runtime.scala`, `__arithExt__`: `primitiveWins` must also be true when the operands are
   STRINGS (and for `++`, lists), not only numbers. It is a few characters at the `if op == "|"`
   chain.
2. `v2/lib/ssc1-lower.ssc0`: extend the existing `isExtensionMethod(op)` branch from `|` to `++`
   and `/`.
3. And, as always for the differential gate, the same in `specs/v2.2-p6.5-fsub.ssc` — note F emits
   **no** `__arithExt__` at all today, so F needs the mechanism added, not just widened.

Part (1) is in `v2/src`, which was held by a live claim (`v2-backend-matrix-gaps`) when this was
found, which is why this is filed complete rather than half-landed.

## rozum-agent-family-v2-diverges-again — four cases that were PASS hours ago now DIVERGE
<!-- status: open
     lane: native
     area: front
     gate: tests/conformance/contract.sc -->

**Status:** OPEN, **not caused by the claim that found it** (`v2-backend-matrix-gaps`, 2026-07-29).
Proven by control build, not assumed: the same four cases diverge identically with that claim's
change reverted (staged jar + tower swapped back).

`rozum-agent`, `rozum-agent-pool`, `rozum-agent-schema-derived`, `rozum-agent-streaming` are all
**`v2 DIVERGE`** — they run to exit 0 and produce output that differs from the INT golden.

**They have flip-flopped three times today**, which is the reason this is filed rather than
shrugged at:

| when | state |
|---|---|
| earlier 2026-07-28 | `v2 FAIL` (crash — the `try` multi-statement parser gap) |
| after that parser fix | `v2 DIVERGE` (ran, wrong output) |
| ~2 h before this entry | **`v2 PASS`** — recorded as IMPROVEMENTs, and someone tightened the baseline accordingly (baseline 108 → 100) |
| now | **`v2 DIVERGE`** again, and therefore counted as REGRESSIONs against that tightened baseline |

**Reproduce:**

```bash
scala-cli --server=false tests/conformance/contract.sc -- --lanes int,v2 \
  --only 'rozum-agent,rozum-agent-pool,rozum-agent-schema-derived,rozum-agent-streaming'
```

**For whoever takes it.** All four import `std/agent.ssc`, so this is one cause and not four. The
window is narrow — between the two full-corpus runs of 2026-07-29 — so `git log origin/main` over
that span should name it quickly. Note that the baseline was tightened while they were passing, so
the gate is now correctly red rather than newly broken: a re-baseline would HIDE this, and is the
wrong response.

## ssc1-front-annotation-before-declaration — an annotation on its own line is `_err` on the legacy front
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 3afe7fe7b
     gate: tests/e2e/ssc1-front-annotation.sh -->


**Status:** **FIXED 2026-07-28** by `ssc1-front-annotation-case-class` (`3afe7fe7b`). The `@` branch
of `parseOneStmt` skipped `@Name(args)` but not the statement separator the newline produces, so it
then looked at a `;` instead of the annotated declaration. One extra `skipSemis`.

**Renamed** from `…-before-case-class`: measured, `@inline` before a `def` failed identically, so it
was never specific to `case class`.

**Gate: `tests/e2e/ssc1-front-annotation.sh`, deliberately NOT a conformance case.** A conformance
case was written first and thrown away after measuring it: it is green on the V2 lane **with and
without** the fix, because that lane runs F and F never had this gap. A gate that cannot
distinguish the two states is not a gate. The e2e asserts on the legacy front directly and greps
the lowered IR for `_err`; A/B'd at 4-of-5 FAIL against the unfixed front and 5-of-5 ok with it,
with the always-working same-line spelling green in both states so a fix cannot trade one for the
other.

**Result on the three cases:** no more `_err`. They now fail later at
`unbound global: JsonCodec_derived` / `ObjectCodec_derived` — cluster B (typeddata, `backend: jvm`
examples deriving from HOST Scala typeclasses), which is out of scope by design. The corpus
contract on all three is GREEN.

`graph-storage`, `graph-codecs`, `typed-object-codec`, all listed in `bugs-v2.md` cluster A as "a
top-level construct emitted directly AFTER the Mirror block — not yet reduced to a minimal repro".
It is not the Mirror block: it is the annotation.

**Minimal repro, three lines:**

```scalascript
@graphLabel("M")
case class M(id: String)
println("after")
```

| front | result |
|---|---|
| `v2/lib/ssc1-front.ssc0` (legacy/oracle) | **`_err`** |
| `specs/v2.2-p6.5-fsub.ssc` (F, the default) | clean |

**Narrowed by A/B, four shapes** — the annotation is the whole trigger, `derives` is irrelevant:

| shape | legacy | F |
|---|---|---|
| `@graphLabel("M")` + `case class` | `_err` | clean |
| `@key` + `case class` (annotation with NO arguments) | `_err` | clean |
| `@graphLabel("M")` + `case class … derives JsonCodec` | `_err` | clean |
| `case class … derives JsonCodec`, **no annotation** | clean | clean |

All three corpus cases carry annotations before a `case class` (`graph-codecs` has 3), all three are
`_err` on the legacy front only, and all three are clean under F.

**Why it still breaks the default lane even though F is clean.** `bin/ssc run` rejects these files,
so something on the default path still consults the legacy front — the checker pre-pass or the
structural route. Worth confirming which, because it means F being able to parse a construct is not
sufficient for the default lane to accept it.

**Fix direction.** `ssc1-front`'s top-level item dispatch: an `@annotation` line before `case class`
is not skipped/attached the way F does it. F's handling is the reference — it accepts all four
shapes above.

## v2-content-inlines-never-parsed — v2 stores each block's raw markdown as ONE `Text` inline, so `contentPlainText` leaks syntax
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN. Found 2026-07-30 by `skip-triage-golden-lane` while reducing `content-tables`' v2
DIVERGE. **Referenced by `d2349fe99`'s message, which is why this entry exists** — that commit fixed
the OTHER half (canonical attr order) and pointed here for the rest.

**Also corrects the record:** the earlier note that "v2 emits 1 line vs 23" for `content-tables` is
STALE. Measured from a fresh worktree build, v2 emits all 21 lines and 6 differed; `d2349fe99` closed
2, and the remaining 4 are all this bug.

**Reproduce** — a paragraph and a table cell, both with inline markup:

```
<!-- @meta id=para -->
A **bold** and a [buy](/buy) and `code` and *em*.
```

| lane | `contentPlainText` |
|---|---|
| int (golden) | `A bold and a buy (/buy) and \`code\` and em.` |
| v2 | `A **bold** and a [buy](/buy) and \`code\` and *em*.` |

Same for table cells: golden `b | buy (/buy)`, v2 `**b** | [buy](/buy)`.

**It is NOT a rendering bug in the plugin.** `ContentNativePlugin.inlineText` (`:118-123`) already
handles `Strong`/`Emphasis`/`Link` correctly. The tree it receives simply has no such nodes: each
block's whole source text arrives as a single `Text` inline. Proof by elimination — if the `Link` HAD
been parsed, v2's `inlineText` would print `buy` (it drops the href, unlike v1). It prints
`[buy](/buy)`, which is neither v1's answer nor v2's own parsed-Link answer, so nothing was parsed.

**Consequence beyond one case:** v2's `Strong`/`Emphasis`/`Link`/`Code` arms in BOTH `inlineText` and
`inlineMarkdown` are dead code in practice, and `contentToMarkdown` only looks right by accident —
echoing raw text happens to reproduce the source. Any consumer that walks the inline tree (a UI
renderer, a toolkit section) sees a flat string.

**Where it lives — CORRECTED 2026-07-30, the first answer was wrong twice over.**

The original text said the fix belongs in `v2/lib` / `specs/v2.2-p6.5-fsub.ssc`, held by
`v2-backend-matrix-gaps`. Neither is true. Traced properly:

`v2/bin/ssc1-run.ssc0:693` builds `NativeCompilation(ir, sscParseFrontmatters(paths),
sscParseContents(...), paths)`, and `sscParseContents` (`:652`) executes **`std/content-core.ssc`** —
the markdown-to-content conversion is written in ScalaScript itself, not in the front. `v2/lib` contains
no content emitter at all (`grep -a` for `Paragraph`/`Markdown` across `v2/lib/*.ssc0` finds only
unrelated comments), and `NativeV2Structural` only validates what arrives.

**The two exact lines, both in unclaimed `.ssc` files:**

1. `v1/runtime/std/markdown-core.ssc:308` — `markdownInlines` splits the text on `${…}` expressions
   ONLY (`markdownInlineExpr`); everything else becomes a single `MarkdownText`. It never looks for
   `**bold**`, `*em*`, `` `code` `` or `[label](href)`.
2. `v1/runtime/std/content-core.ssc:158` — `contentInlines` maps `MarkdownText` and `MarkdownExpr` and
   **silently drops every other node** (`case Cons(_, tail) => contentInlines(tail)`). That arm is dead
   today precisely because (1) never produces anything else.

So the v2 lane's inline markdown support is expression interpolation and nothing more. Both files are
plain `.ssc` and were unclaimed when this was corrected, so the work is available — it is a feature-sized
addition to a ScalaScript-level parser, not a front change.

**Reference implementation to mirror:** `MarkdownDocContent.scala:135-160` (the UniML bridge v1 uses)
maps table cells and paragraphs through `mapInlines`; and v1's plain-text rules are
`ContentIntrinsics.inlinePlainText:1500-1517` — note `Code` keeps its backticks and `Link` renders as
`label (href)`, which v2's `inlineText` does NOT do today. **So fixing the parse alone is not enough:**
v2's `Link` arm must also append ` (href)` or `content-tables` will trade one diff for another.

## corpus-contract-scljet-jdbc-v2-timeout — a correct case that does not fit its budget on a loaded runner
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN (found 2026-07-28 while making the nightly contract report a verdict again).

**What the gate says.** `Corpus Contract` run `30368982153`, shard 3/4:

```text
✗ 1 REGRESSION row(s) in rostered cases:
    scljet-jdbc  v2  TIMEOUT
```

`scljet-jdbc` is not in the baseline, so this reads as a fresh regression.

**It is not a code regression — measured.** The obvious suspect was SC-2 (`b9b060e6e`), which added
freelist validation on *every* delete. A/B on `examples/scljet-jdbc.ssc`, v2 lane, same machine,
same launcher, only the two `.ssc` files swapped:

```text
with SC-2:     26.03 s   exit 0, correct output
without SC-2:  26.07 s   exit 0, correct output
```

Identical. The case is simply slow and *correct*, and it is slow for a reason already filed:
`f-front-compile-cost-7x-on-scljet` — F is the default native front and costs ~7× on scljet cases.

**So why TIMEOUT at a 90 s budget?** The two budgets are already separated deliberately
(`--timeout` 30 s for the INT golden probe, `--lane-timeout` `max(90, timeout)` for lane
comparison), the call sites are correct (`contract.sc:719` passes `laneTimeoutS`), and `runLane`
even retries once on 124 so contention does not flap the gate. For this to time out **twice** at
90 s, the runner has to be ~3.5× slower than a local Mac — which four shards of parallel JVMs on a
shared 2-core runner can plausibly be.

**Do not "fix" it by baselining it.** A `TIMEOUT` row would freeze a *correct* case as permanently
non-PASS, and `contract.sc`'s own header says why that is corrosive: *"A correctness gate that
reports perf as TIMEOUT noise trains people to ignore it — which is how this one died."*

**Two honest options, in preference order.**

1. Make the case fit: `f-front-compile-cost-7x-on-scljet` is the real cost. 26 s local for one
   example is the defect; the budget is downstream of it.
2. If the budget must move first, move it with a measurement attached — record the observed CI
   wall-clock for this case (the run log does not print per-case timing today, which is its own
   gap) and set `--lane-timeout` from that number, not from a guess.

**Not attempted here** because it is neither the stale-baseline bookkeeping this claim took on nor
a change I can justify without CI-side timing data.

## backend-check-mutual-recursion-drops-output — the Core IR parity gate is red on 3 of 4 generators
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN, **pre-existing** (found 2026-07-28 by `v2-backend-matrix-gaps` while verifying an
unrelated change; **proven not caused by it** — the gate fails identically on a control build with
that change reverted).

`v2/backend/check.sh` runs every Core IR fixture through the v2 VM and each source generator and
requires byte-identical output. `mutual-recursion` fails on **jvm, rust and wasm** — the expected
line `1000` is simply absent from all three — while **js passes**:

```
FAIL mutual-recursion     jvm (expected vs got):
1d0
< 1000
ok   mutual-recursion     js
FAIL mutual-recursion     rust  … same
FAIL mutual-recursion     wasm  … same   (wasm reuses the Rust generator)
```

**Reproduce:** `cd v2/backend && ./check.sh`.

**Notes for whoever takes it.** The gate ends `FAILURES PRESENT` with a non-zero exit, so it is a
loud red rather than a silent one — but it means this gate cannot currently certify a change to the
JVM or Rust generators, which is how it was found. js passing while jvm+rust+wasm fail points at
the two Scala-hosted generators rather than at the fixture or the VM. `wasm` shares the Rust
generator, so it is likely one cause and not three.

## js-compound-assign-dispatches — `x += 1` compiles to a dynamic method call that always throws
<!-- status: open
     lane: v2-jvm
     area: codegen -->

**Status:** OPEN (found 2026-07-28 by `bench-tier-dead-lanes`; filed, not fixed — the JS codegen
paths belong to another lane).

**Reproduce** — three lines:

```scalascript
var i = 0
i += 1
println(i)
```

| lane | result |
|---|---|
| INT (`ssc-tools run --v1`) | `1` |
| JS (`ssc-tools run-js`) | `Error: Method not found: += on 0` |

**Root cause.** `emit-js` emits `_dispatch(i, '+=', [1])` — the dynamic
method-dispatch fallback — instead of `i = i + 1`. `_dispatch` has no `+=` entry, so it reaches
`throw new Error('Method not found: ...)`. The operator is not compiled at all, it is *looked up*.

**Impact beyond the repro:** any `.ssc` program using `+=`/`-=`/`*=` is silently JS-broken. It took
down the entire `js` benchmark column because the bench wrapper itself used `+=`.

## v2-source-backends-miss-autoOutput — `__autoOutput__` is unimplemented in both v2 source backends
<!-- status: open
     lane: v2-jvm
     area: codegen
     gate: tests/conformance/run.sc -->

**Status:** OPEN (found 2026-07-28 by `bench-tier-dead-lanes`; filed, not fixed).

Per-block auto-output is a prim each backend must implement (it cannot be a source-level pattern —
Unit-ness is a runtime property whose representation differs per backend). It was implemented for
the VM/bytecode lanes and **not** for the v2 source generators, so every program routed through
them dies:

| lane | failure |
|---|---|
| `v2-jvm` | `Exception in thread "main" java.lang.RuntimeException: unknown prim1: __autoOutput__` |
| `v2-rust` | `panicked at main.rs: unimplemented prim: __autoOutput__` |

**Reproduce:** `bin/ssc-tools --backend v2-jvm bench --machine bench/corpus/arith-loop.ssc`
(add `SSC_BENCH_DEBUG=1`; without the fix above the failure was invisible).

**Impact:** the `v2-jvm` and `v2-rust` benchmark columns are not "slow" or "unsupported" — they
cannot run any program at all. This is the "fix every backend or it is a half-fix" shape.

## sql-plugin-rowcodec-mirror-arity — a fourth Mirror field took the ASM release gate red
<!-- status: fixed
     lane: native
     area: plugin
     fixed-in: 0ee779b8d
     gate: v2/runtime/std/sql-plugin -->

**Status:** FIXED 2026-07-28. Found by `ci-dispatch-verdict-obtainable` while getting the first
on-demand CI verdict; root cause conclusive, not inferred.

**Symptom.** Every native/ASM build of a typed-SQL program died at runtime:

```
Exception in thread "main" java.lang.RuntimeException: RowCodec.derived expects Mirror metadata
    at ssc.plugin.sql.SqlNativePlugin.$anonfun$16(SqlNativePlugin.scala:168)
    at ssc.gen.Entry.entry(typed-sql-crud.ssc:25)
```

This took `ScalaScript 2.1 compiler-free ASM artifact release gate` red on `main` (run
30358000052). It was **green two hours earlier** on `e8c1d0c9f`, which bounds the regression window
precisely.

**Root cause.** `SqlNativePlugin.rowCodecDerived` destructured the Mirror with

```scala
case List(mirror @ Value.DataV("Mirror", Seq(Value.StrV(tag), fieldValues, typeValues))) =>
```

`Seq(a, b, c)` means **exactly three**. `dd56c4b8d` ("Mirror.fromProduct on the native lane") gave
the Mirror a **fourth** field — the constructor — in both fronts (`v2/lib/ssc1-lower.ssc0` and
`specs/v2.2-p6.5-fsub.ssc`). The pattern stopped matching, fell through to `case _`, and threw.

**Why no test caught it.** Both Mirror fixtures in `SqlNativePluginTest` construct the OLD
three-field shape, so the suite stayed green while every production build was broken. A fixture
frozen at the old shape tests the old world. This is the same class as the apparatus failures in
`specs/build-ram-budget.md` §1: the check could not fail when the thing it checks did.

**Fix.** The consumer needs tag/fields/types and nothing else, so it now matches the first three and
ignores the rest (`Value.StrV(tag) +: fieldValues +: typeValues +: _`) — forward-compatible with
whatever the Mirror grows next. One fixture updated to the current four-field shape.

**Verified compare-first:** with the old pattern the updated test fails with exactly the production
message (`RowCodec.derived expects Mirror metadata`, 3 succeeded / 1 failed); with the fix, 4/4 pass.

**For the Mirror lane:** `SqlNativePlugin` was the only production consumer that destructured a
Mirror by fixed arity (`grep -rn 'DataV("Mirror"' v1 v2` — one production site, two test sites).

## js-v2-unit-pattern-does-not-match-and-unit-literal-pattern-crashes — two Unit-pattern defects on the v2 JS codegen
<!-- status: open
     lane: native
     area: codegen -->

**Status:** OPEN (found 2026-07-28 by `v2-multiblock-auto-output`; the first one is what made a
per-block auto-output attempt emit a bare `()` and get caught by the corpus as
`deep-tail-recursion FAIL [JS/v2]`).

**Reproduce** — one file, `bin/ssc-tools run-js --v2`:

```scalascript
def viaAscription(x: Any): String = x match
  case _: Unit => "UNIT"
  case _ => "OTHER"
def viaLiteral(x: Any): String = x match
  case () => "UNIT"
  case _ => "OTHER"
println("ascription-unit: " + viaAscription(println("")))
println("literal-unit:    " + viaLiteral(println("")))
```

| pattern | native (VM/ASM) | JS/v2 |
|---|---|---|
| `case _: Unit =>` | `UNIT` | **`OTHER`** |
| `case () =>` | `OTHER` | **crash** |

**(a) `case _: Unit` does not match the unit value.** Native is the correct lane here — its
`__isTag__` maps `UnitV` to the `"Unit"` tag deliberately. JS/v2 answers `OTHER`, so any code that
discriminates on Unit silently takes the wrong branch instead of failing.

**(b) `case ()` throws instead of not matching.** The emitted JS is

```js
$d_viaLiteral = function($t215){ … var $t217=$t216; switch($t217.t){ case "Tuple0":{ return "UNIT";} default:{return "OTHER";} } … };
```

and the unit value is `null` on this lane, so `$t217.t` throws
`TypeError: Cannot read properties of null (reading 't')`. A pattern match must never crash on a
value it simply does not match — the `switch` needs a null/unit guard before the `.t` read.

**Related.** `v2-native-case-unit-pattern-matches-where-int-does-not` is the same question on the
INT lane (INT also fails to match). Native is correct on all three comparisons; the two codegens
disagree with it in different ways.

## v2-native-uncaught-error-diagnostic-empty — every failing native program reports a diagnostic with no content
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: c1c960209
     gate: tests/e2e/v2-error-diagnostic.sh -->

**Status:** **FIXED 2026-07-28** by `v2-native-error-diagnostic` (`c1c960209`). Found the same day
by `v2-native-import-graph` while probing the map-reduce fixture — the native lane answered
`ssc: SscThrow` where v1 named the exception and its message. This was not one program's problem:
it is what the **default** lane printed for **any** uncaught error, so it was the first thing a
user saw when anything went wrong.

**After, both execution lanes:**

```
ssc: RuntimeException("the real message")
ssc: index 9 out of bounds for list of length 3
```

**Fix.** (1) `SscThrow` overrides `getMessage` with `Show.show(value)` — an override rather than a
constructor argument, because `throw`/`catch` is also an ordinary control-flow path in this
runtime and a *caught* `SscThrow` must not pay to build a message nobody reads. (2)
`Prims.listIndex` checks the range itself and raises what `listAt` and v1 already say, instead of
handing the check to Scala's `List.apply`; the non-cons (`ArrayBuffer` tail) shape still goes
through the same slow path.

**Gate.** `tests/e2e/v2-error-diagnostic.sh` — every probe runs on BOTH execution lanes and prints
`expected=… got=…` on mismatch; `--self-test` first asserts an impossible expectation so the
comparison is proven capable of failing. Committed RED (4 of 5 cells) one commit before the fix.
It also carries a **control**: a *caught* `RuntimeException` must still bind its message
(`caught:inner`), which passed before and after — this changed the message, not the payload.
Conformance cannot see this class of defect by construction: a case is graded on stdout against
`expected/<name>.txt`, and these programs produce no stdout at all.

**Measured, two four-line programs, real staged `bin/`:**

```scalascript
def boom(): Int = throw new RuntimeException("the real message")
println(boom())
```

| lane | output |
|---|---|
| `bin/ssc run` (default, ASM) | **`ssc: SscThrow`** |
| `bin/ssc run --interpret` (VM) | **`ssc: SscThrow`** |
| `bin/ssc-tools run --v1` | `[ERROR] RuntimeException(the real message)` |

```scalascript
val xs = List(1,2,3)
println(xs(9))
```

| lane | output |
|---|---|
| `bin/ssc run` (default, ASM) | **`ssc: 9`** |
| `bin/ssc run --interpret` (VM) | **`ssc: 9`** |
| `bin/ssc-tools run --v1` | `[ERROR] [line 2, col 9] index 9 out of bounds for list of length 3` |

`ssc: 9` is the whole diagnostic. Not the operation, not the bound, not the line — the index.

**Root cause, two independent defects that meet at the same printer.**

1. `v2/src/Runtime.scala:21` — `final class SscThrow(val value: Value) extends RuntimeException`
   is constructed with **no message** (`case "__throw__" => a => throw new SscThrow(a(0))`), so
   `getMessage` is `null`. `StandardMain.scala:19` prints
   `Option(error.getMessage).getOrElse(error.getClass.getSimpleName)` → the literal string
   `SscThrow`. The user's thrown value — the only thing that carries meaning — is never rendered.
2. `Prims.listIndex` (`v2/src/Runtime.scala:3417`) falls through to `unlistPub(v)(i)`, i.e. Scala's
   `List.apply`, whose `IndexOutOfBoundsException` message is the bare index `"9"`. The sibling
   helper `listAt` right below it already raises the honest
   `sys.error(s"list index out of bounds: $i")` — that path is simply not the one taken.

**Fix direction.** `SscThrow` should render its `value` as its message (`Show.show`/the parity
display renderer, so the two lanes agree on how a thrown value prints), which fixes every
`throw` at once and needs no change in the printer. `listIndex` should raise with the same shape
`listAt` uses, including the length. Both belong in `v2/src/Runtime.scala`. A gate belongs with
them: a conformance case cannot assert on stderr, so this wants
`tests/e2e/` — compare-first, printing expected/actual on mismatch.

## conformance-known-red-silently-ignored-on-v2 — the one lane that needs a declared red cannot have one
<!-- status: fixed
     lane: native
     area: conformance
     fixed-in: 895e5ecff -->

**Status:** FIXED 2026-07-28 in `895e5ecff` — the V2 lane now calls
`checkLane("V2 ", "v2", v2Out, expected, knownRed)` like the other five.

Proved with three probes rather than one, because the positive alone is vacuous:

```text
A  declared + failing lane   -> suite GREEN, "KNOWN-RED [V2 ] <reason>"
B  same case, NO declaration -> suite RED,   "FAIL [V2 ]"
C  declared + PASSING lane   -> suite RED,   "STALE known-red: this lane now PASSES"
```

C is the load-bearing one: self-expiry is what stops a known-red outliving its bug. Probes used
`actors-bounded-mailbox` (v2 fails, `unbound global: Overflow`) and `case-class-body-methods`
(v2 passes); both were restored, the commit touches `run.sc` only. Regression check: three
v2-enabled cases still PASS on V2.

Original report follows.

**The defect.** `run.sc` checks six lanes. Five route through `checkLane`, which
consults the `known-red:` map. The V2 VM lane does not:

```scala
checkLane("INT",   "int",    intOut, expected, knownRed)   // line 445
checkLane("JS ",   "js",     jsOut,  expected, knownRed)   // line 456
checkLane("JS/v2", "js-v2",  out,    expected, knownRed)   // line 466
checkLane("JVM",   "jvm",    jvmOut, expected, knownRed)   // line 489
checkLane("JVM/v2","jvm-v2", out,    expected, knownRed)   // line 499
check    ("V2 ",             v2Out,  expected)             // line 511  ← no lane key, no knownRed
```

**Why it is worse than a missing feature: it is fail-open.** `parseKnownRed`
happily parses, validates and returns `known-red: v2 — <reason>`. It enforces
that a reason is present, exits 1 without one, and lowercases the lane. Nothing
then consults the entry for `v2`. So a correct declaration is *accepted* and
*ignored*, with no warning on any stream — the author's evidence that it worked
would have to be a careful re-read of the lane's bucket in the output.

**Real reproduction (measured, not reasoned).** Add `v2` to the backends of
`tests/conformance/actors-bounded-mailbox.ssc` and declare the gap:

```yaml
backends: [jvm, v2]
known-red: "v2 — native actors provider has no Overflow surface"
```

```text
PASS [JVM]
FAIL [V2 ]
  line 3: expected=drop-newest: x  got=ssc: Actors scope failed: unbound global: Overflow
Results: 0 passed, 1 failed out of 1 tests
```

Identical output with and without the declaration.

**Consequence — this is why v2 corpus coverage stays low.** `run.sc`'s own
documentation states the contract: *"a declared red is a visible red, a reroute
is an invisible green"*, and a known-red **expires by itself** — if the lane
starts passing, the suite fails and tells you to delete the declaration. For
every lane but V2 an agent can therefore enable coverage for a known gap
honestly. For V2 the only two options are an **undeclared red** (indistinguishable
from a regression, and it reddens the nightly gate) or **leaving `v2` out of
`backends:`** (invisible). The second is cheaper, so it is what happens — the
gap then has no expiry and nothing notices when it closes.

**Fix.** Route the V2 lane through `checkLane("V2 ", "v2", v2Out, expected,
knownRed)` like the other five. Prove it red-then-green with the actors case
above: declared → bucketed as known-red, suite green; then delete the
declaration → suite red again. Add the stale-declaration direction too (a
known-red `v2` on a case that passes must fail the suite), since that
self-expiry is the property that makes the mechanism safe to use at all.

## f-front-silent-delegation-hides-coverage-gaps — F is the default front, and 26 corpus cases were never compiled by it
<!-- status: fixed
     lane: native
     area: front
     fixed-in: d900d00cf -->

**Status:** **FIXED 2026-07-28** by opus (`f-front-silent-delegation`, `d900d00cf`) — the silence is
fixed and the gap is now measured. The 26 gaps themselves are F work, listed below rather than
guessed at.

**What was wrong.** F is the DEFAULT native front. When it cannot lower a file, `RunNativeV2`
transparently re-lowers through the legacy front and uses that result. That is correct — nothing has
executed yet, so there is no double side effect — but it was announced only under `SSC_FRONT_TRACE`,
an env var nobody sets. Fine while F was opt-IN staging; as the DEFAULT it means every F coverage
gap looks like plain success at the CLI, and **any corpus run driven through `bin/ssc` measures the
LEGACY front for those programs while reporting them as F.** Same shape as the `--bytecode` fallback:
a silent fallback turns a differential measurement into a self-comparison.

**MEASURED, which is the point of fixing the silence: 26 of 329** conformance cases, about 8 percent,
delegate. They were passing, and they were passing on the other compiler:

`dsl-multi-pass`, `effect-deep-handler-state`, `effect-imported-handler`, `effect-multiarg-op`, `effect-transitive-handler`, `effects-handler`, `effects`, `for-comprehensions`, `head-field-effect-shadow`, `indent-block-statements`, `indent-config-format`, `js-applyunary-effect-cps`, `js-effect-multishot-long-fold`, `js-parser-combinator-choice`, `markdown-html`, `parsing-error-node`, `parsing-parse-all`, `parsing-recover-until`, `standard-scala-multifence`, `std-foldable-traversable`, `std-index`, `std-semigroup-monoid`, `tagless-context-bounds`, `v2-self-hosted-markdown-core`, `v2-self-hosted-parser-fuzz`, `v2-self-hosted-yaml-core`

The clustering is itself the finding — **9 of the 26 are effects** (`effect-*`, `effects*`,
`js-*-effect-*`, `head-field-effect-shadow`), plus 3 parsing, 3 `v2-self-hosted-*` and 3 `std-*`.
Any statement of the form "F covers N of the corpus" made before today is overstated by these.

**The condition is the whole design, and getting it wrong is easy.** The fallback fires for two
unrelated reasons, because an F gap and a user error both surface as an unbound global:
`undefinedThing()` routes through exactly the path a construct F cannot lower does. Announcing both
would print a compiler-internals line under every typo — and a message that appears when nothing is
wrong is one people filter out within a day, taking the real signal with it. So it announces only
when the DEFAULT front SUCCEEDED where F failed, tested by running the default front's program
through the very check F failed.

**The first condition was wrong and the gate caught it before it landed.** Checking for `_err*`
sentinels was not enough: a plain `undefinedThing()` is an ordinary unbound global, not a sentinel,
so every typo still printed the coverage-gap line. A one-sided gate, "the marker appears", would
have passed and shipped it. Visible in the measurement too — `actors-bounded-mailbox` announced
under the wrong condition and correctly stopped once both fronts were compared.

**Gate.** `tests/e2e/f-front-delegation-visible.sh`, three sides: a file F compiles prints nothing,
a user typo delegates SILENTLY, a real gap announces. It also asserts the marker string still
matches the one the runner emits, so a rewording fails there instead of quietly switching the
measurement off. 3/3.

**Reproduce the census:**

```bash
for f in tests/conformance/*.ssc; do
  bin/ssc run "$f" 2>&1 >/dev/null | grep -qF 'F did not lower this file' && basename "$f"
done
```

## v2-callback-exc-parity-followups — distributed/generator callbacks rewrap user throws
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 218e7c527
     gate: tests/conformance/run.sh -->

**Status:** **DONE 2026-07-28** by `codex` (`218e7c527`; found by the SSC
v2 parity audit and re-confirmed in the exact assembled harness, SPRINT
`v2-callback-exc-parity-followups`).

**Real-harness reproduction.**

```bash
tests/conformance/run.sh \
  --only 'distributed-callback-user-throw,generator-callback-user-throw' \
  --no-memo
```

The assembled V2 lane is 0/2 before the fix and prints an exact observable
diff: expected user messages `distributed-user-boom` /
`generator-user-boom`, but got `Distributed.handler[fail] callback failed for
[7]` / `Generator.foreach callback failed for [8]`. Direct plugin tests fail
the same contract independently: distributed is 4/5 and generator is 9/10
because both throw `IllegalArgumentException` where the exact original
`ssc.SscThrow` instance is required.

**Root cause.** Both callback helpers catch all `Throwable` values and add
plugin diagnostics before checking for `ssc.SscThrow`. Dataset-plugin already
establishes the correct order: rethrow the user value first, contextualize
only genuine host failures.

**Resolution.** Both helpers now rethrow `ssc.SscThrow` before their general
diagnostic catch. The host-failure control remains wrapped with the exact
operation and rendered arguments, so the fix narrows only the user-exception
path.

**Verification.** Distributed/generator plugin suites moved from 4/5 and 9/10
to 5/5 and 10/10. The assembled pair moved from 0/2 to 2/2 on V1 JVM, V2
direct ASM, and V2 VM. Default/legacy × native VM/direct ASM also compare
exactly; the neighboring distributed/generator conformance slice is 8/8.

## v2-native-backticked-identifier — quoted keyword parameters corrupt native parsing
<!-- status: fixed
     lane: native
     area: front
     fixed-in: c4e4d3a33 -->

**Status:** **DONE 2026-07-28** by `codex` (`c4e4d3a33`; found and
re-confirmed in the same assembled exact-output harness while continuing
SPRINT `v2-native-backticked-identifier`).

**Real-harness reproduction.** With CSS scopes installed, all five aggregator
roots fail at `unbound global: label`. Running canonical component modules one
at a time gives an exact split: `spinner`, `badge`, `avatar`, `code`, `card`,
`switch`, `alert`, `tag`, `stats`, `data-list`, and `table` exit 0, while:

```bash
bin/ssc run --native examples/std-ui/input.ssc
# ssc: unbound global: label
```

`Input.render` is the only reduced module containing a quoted keyword
parameter and reference:

```scalascript
def render(name: String, label: String = "", `type`: String = "text"): String =
  html"""<input name="${name}" type="${`type`}">"""
```

`ssc1-front` has no lexer branch for backtick-delimited identifiers. It emits
three tokens (backtick operator, `type` keyword, backtick operator), breaks the
parameter list/body boundary, and later evaluates `label` as a global. This is
why the structural sentinel gate did not name the actual source construct.

**Resolution.** `ssc1-front` now scans a closed backtick-delimited name as one
ordinary `id` token without its delimiters; an unclosed name remains visible
to the parser instead of consuming the rest of the file. The multi-file
`std-ui-native-backticked-id` regression preserves the original HTML
interpolation plus default/explicit keyword-parameter calls.

**Verification.** The focused regression is exact on default/legacy ×
VM/direct ASM and PASS on INT/JS/JVM. All five original std-ui roots compare
exactly on the same four native routes and remain 5/5 on INT/JS/JVM.

## v2-native-std-ui-scope-global — V2 standard runtime omits the CSS scoping helper
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/std-ui-aggregator.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`757212871`; found and
re-confirmed in the same assembled exact-output harness while verifying the
full std-ui closure; SPRINT `v2-native-std-ui-scope-global`).

**Real-harness reproduction.** After the quote scanner and `html` parser
corrections, all five original roots advance to the same runtime failure:

```bash
bin/ssc run --native tests/conformance/std-ui-aggregator.ssc
# ssc: unbound global: scope
```

The direct-ASM route fails identically. The first imported component already
contains the canonical API shape:

```scalascript
private val sc = scope("Spinner")
private val rootCls = sc.cls("root")
val css = sc.css(""".root { display: inline-block }""")
```

V1 supplies `scope(name)`, `Scope.cls`, and `Scope.css` in its core runtime,
but `v2/runtime/std/ui-plugin` registers none of them. This is not a parser or
module-resolution error: execution reaches an honest missing-global lookup.

**Resolution.** The V2 UI plugin now registers the existing contract:
`scope(name)` returns portable `Scope` data, `.cls(name)` appends
`__<scope>`, and `.css(source)` rewrites selectors with the V1 regex
semantics. The implementation stays in `v2/runtime/std/ui-plugin`, not core.

**Verification.** `v2NativeUiPlugin/test` is 16/16. The multi-file
`std-ui-native-css-scope` regression and all five original std-ui roots are
exact on default/legacy × VM/direct ASM; the declared INT/JS/JVM lanes remain
green.

## v2-native-html-interpolator-parse — self-hosted frontend emits `_err` for built-in `html` strings
<!-- status: fixed
     lane: native
     area: front
     fixed-in: a04239fdc
     gate: tests/conformance/std-ui-diag-html-root.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`a04239fdc`; found and
re-confirmed in the same assembled exact-output harness while reducing
`v2-std-ui-closure-pair-match`; SPRINT
`v2-native-html-interpolator-parse`).

**Real-harness reproduction.** This minimal document fails before execution:

```scalascript
println(html"""<p>${"<x>"}</p>""")
println(html"""<p>${raw("<x>")}</p>""")
```

All four assembled routes fail identically:

```bash
bin/ssc run --native tests/conformance/std-ui-diag-html-root.ssc
bin/ssc run --bytecode tests/conformance/std-ui-diag-html-root.ssc
SSC_FRONT=legacy bin/ssc run --native tests/conformance/std-ui-diag-html-root.ssc
SSC_FRONT=legacy bin/ssc run --bytecode tests/conformance/std-ui-diag-html-root.ssc
# exit 1: structural CoreIR contains parser sentinel _err
```

Import-closure bisection first isolated the full failure to
`examples/std-ui/data-list.ssc`, then to `items.map(it =>
html"""<li>${it}</li>""")`; the top-level two-line reduction above proves the
lambda is not required. `v2/lib/ssc1-front.ssc0::parseAtom` recognizes only
`s`, `f`, `raw`, and `md` string prefixes, so `html` remains a variable and
the following string token survives as malformed syntax.

**Required semantics.** This cannot be fixed by treating `html` as ordinary
`s` interpolation: literal HTML is trusted, each interpolated value must
escape `&`, `<`, `>`, `"`, and `'`, and `raw(value)` must preserve trusted
markup. The existing V1 interpreter/JVM/JS contract implements exactly that.

**Resolution.** The native frontend now builds dedicated HTML interpolation
IR: ordinary holes call internal `__htmlEscape__`, while `raw(value)` lowers
to portable `_Raw` data and bypasses escaping. Treating `html` as ordinary
`s` interpolation was rejected because it would silently remove the existing
HTML-safety contract.

**Verification.** The multi-file `std-ui-native-html-lambda` regression checks
the original lambda/import boundary plus escaped and raw output. It is exact
on default/legacy × VM/direct ASM and PASS on INT/JS/JVM; the five original
std-ui roots also compare exactly.

## v2-std-ui-closure-pair-match — native self-hosted frontend crashes after resolving the aggregator
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 9509190dd
     gate: tests/conformance/std-ui-aggregator.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`9509190dd`, full closure
confirmed at `c4e4d3a33`; found and re-confirmed in the same assembled
exact-output harness while working SPRINT `v2-std-ui-missing-fixture`;
tracked as follow-up `v2-std-ui-native-pair-match`).

**Real-harness reproduction.** After correcting the five stale imports to the
canonical root module, each assembled native command reaches the source
closure and then fails:

```bash
bin/ssc run --bytecode tests/conformance/std-ui-aggregator.ssc
# ssc: match: no arm for Pair/2
```

std-ui-extended{,-b,-c,-d} fail identically. Running the staged
`tower/bin/ssc1-run.ssc0` directly through `v2/ssc` preserves the stack:
`Runtime.handlerMatchFailed` is reached while the self-hosted frontend tower
executes, with repeated `compileEffectAwareConstructor` /
`compileEffectAwareApplication` frames. This is before user code or either
native execution backend starts.

**First root cause isolated.** Import-closure bisection reduced the crash to
`examples/std-ui/progress-bar.ssc`, then to a two-file fixture containing
`"""aria-busy="true""""`. `scanTripleEnd` chose the first three quotes in the
four-quote run, although the literal quote is content and the final three are
the delimiter. The rest of the function was consequently parsed as string
fragments plus a malformed `idx_assign`; the lowerer's honest `Cons` match on
its argument surfaced that corruption as `Pair/2`. The focused regression
fails identically under default/legacy × VM/ASM before the scanner correction
and preserves both output quotes afterward.

**Resolution chain.** The quote-run correction removed `Pair/2` and exposed
three independent honest layers: missing built-in `html` parsing, missing V2
UI-plugin CSS scopes, and backticked keyword identifiers. Those layers were
tracked separately and closed by `a04239fdc`, `757212871`, and `c4e4d3a33`;
no catch-all match arm was added.

**Verification.** The multi-file `std-ui-native-pair-minimal` regression is
exact on default/legacy × VM/direct ASM and PASS on INT/JS/JVM. The complete
five-root closure now matches every checked-in expected output on both native
engines with both frontends and remains 5/5 on INT/JS/JVM.

## v2-std-ui-imports-stale-after-tests-move — five fixtures still target the old conformance location
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 29c6cc249
     gate: tests/conformance/std-ui-aggregator.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`29c6cc249`, full native closure
confirmed at `c4e4d3a33`; found and re-confirmed in the same assembled
exact-output harness while working SPRINT `v2-std-ui-missing-fixture`).

**Real-harness reproduction.** The declared INT/JS/JVM conformance slice is
5/5, because the compatibility resolver's fallback search masks the bad
relative path. Each assembled native command fails before execution:

```bash
bin/ssc run --bytecode tests/conformance/std-ui-aggregator.ssc
```

The same failure occurs for std-ui-extended{,-b,-c,-d}; all five diagnostics
resolve `../examples/std-ui` to the nonexistent
`tests/examples/std-ui/index.ssc`.

**Root cause.** Commit `d0665660a` moved `conformance/*.ssc` to
`tests/conformance/*.ssc` byte-for-byte and did not update their imports.
`../examples/std-ui` was correct before that move. Git history contains no
`tests/examples/std-ui` fixture, while the canonical module remains at
`examples/std-ui/index.ssc`; nearby `std-ui-i18n.ssc` already uses the correct
`../../examples/std-ui/...` route.

**Resolution.** The five imports and the aggregator's explanatory example now
point at the canonical `../../examples/std-ui` module; no duplicate test
fixture was introduced and expected output stayed unchanged.

**Verification.** The original five roots are 5/5 on INT/JS/JVM and exact on
default/legacy × native VM/direct ASM.

## semantic-gate-red-tkv2-typed-client-derived — a golden that encoded a DROPPED program
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 6b8965258 -->

**Status:** FIXED (2026-07-28, `semantic-gate-tkv2-red`). Found 2026-07-28 by `v2-board-and-f5b`
while gating an unrelated change; fixed under its own claim.

**Symptom.** `specs/v2.2-p6.5-semantic.sh` reported 247/248 with
`tkv2-typed-client-derived: exit expected=0 got=1`, and the program failed identically under
`SSC_FRONT=F` and `SSC_FRONT=legacy` with `ssc: unhandled runtime effect: Api.getApiItemsById`.

**Root cause — the red was the CONSEQUENCE OF A CORRECT FIX, and the stale artifact was the golden.**
The program declares `backends: [js]` and consists of ` ```scalascript @side=server ` /
` @side=client ` fences. Until `6b8965258` the native lane **discarded attributed fences entirely**,
so this program extracted to nothing, "ran cleanly, produced nothing", and was frozen with a
**0-byte golden and exit 0**. That fix made attributed fences code again — so the program finally
ran, on a lane it never claimed to support, and failed. Nothing regressed: the gate had been green
only because the program was being silently thrown away.

This is precisely the failure the gate's own header warns about — it promises that "an empty golden
always means *ran cleanly, produced nothing*, never *failed*" — while a third possibility,
*was discarded*, was indistinguishable from the first.

**Two hypotheses were tried and rejected before this one, both recorded so nobody repeats them:**
(1) the OpAnf purity-registry fix (`c3841d01e`) re-enabling Op lifting — refuted, the program fails
under `SSC_NO_OPANF=1` too, just with a different error; (2) an F-specific coverage gap — refuted,
legacy fails identically.

**Fix.** Removed the stale golden (of the 12 empty goldens in the 248-program set, this was the ONLY
one whose program declares a lane the gate does not run — the other 11 are legitimately empty), and
added an `EXCL_LANE_NOT_DECLARED` exclusion to the freeze worker: a program whose front-matter
`backends:` omits this lane can never be a golden for it. `EXCL_ORACLE_ERR` would catch it today,
but the new rule catches it even while some other defect is silently swallowing the program — which
is the case that actually occurred.

**Verified:** gate GREEN at **247/247, 0 mismatches**; the guard classifies
`tkv2-typed-client-derived` (`[js]`) as EXCLUDE and `scljet-mutate-update` / `markdown-html`
(`[int, js]`) as KEEP.

## scljet-auto-rowid-negative-and-max-boundaries — allocation starts at zero and wraps at Long.MaxValue
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: a00db1967
     gate: tests/conformance/json-read.ssc -->

**Status:** FIXED in `a00db1967` (found 2026-07-27 by
`scljet-production-completion`; static root cause confirmed against reference
SQLite 3.51.0). Portable regressions cover negative-only and `Long.MaxValue`
tables; the live sqlite-jdbc gate additionally covers an empty table and
occupied positive fallback candidates, with reference file reopen and
integrity green in `b39127f61`.

**Reproduction.** With only rowid `-5`, `INSERT ... VALUES (NULL, ...)` chooses
1 in SclJet but -4 in SQLite. With an existing `Long.MaxValue`, SclJet computes
`max + 1` and wraps to `Long.MinValue`; SQLite chooses an unused positive rowid
through its bounded fallback.

**Root cause.** `maxRowidOf` starts at zero instead of representing an empty
table separately, and `assignInsertRowids` increments unchecked. SC-1a must use
the true signed maximum, start an empty table at 1, and choose an unused
positive fallback when the maximum cannot be incremented.

## native-release-unqualified-and-unrelocatable — release workflow cannot prove a runnable v2 artifact
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN (found 2026-07-27 by `native-release-qualification` against
`origin/main@6919b46db`; no release run exists yet).

**Real release-path reproduction.** `gh run list --workflow native-release.yml`
returns no runs because the workflow is tag-only. Its x86_64 macOS leg still
uses the retired `macos-13` runner, and each archive contains only `ssc`,
`lib/ssc-plugin-host.jar`, and an optional README. It never executes the
compressed artifact.

The native v2 runtime requires
`bin/lib/standard/native-front/{tower,runtime}` plus an `ssc.lib.path`
installation root. The archive ships neither the data nor a relocatable
bootstrap, so successful `native-image` compilation would still not prove that
an extracted product can run `.ssc` without borrowing the checkout.

Effective-setting audit found three earlier build blockers before a hosted run:
the sbt-native-packager output is named `scalascript-cli` while the workflow
looked for `ssc`; both explicit native-image config paths resolve below the
non-existent `v1/native-image-configs`; and
`--features=org.graalvm.home.HomeFinder` names an abstract utility that does not
implement the required hosted `Feature` interface. The generic
`scripts/ci-status` non-CI contract also treats the intentionally skipped
manual publication job as red, so exact run-job evidence is required without
making that privileged job reachable from dispatch.
The tag path also accepted non-SemVer `v*.*.*` matches and used
`gh release upload --clobber` without per-ref concurrency, allowing concurrent
or failed reruns to mix or delete non-reproducible native assets.

**Fix acceptance.** A credential-safe `workflow_dispatch` run must build,
archive, isolate, and execute Linux x86_64, macOS arm64, and macOS x86_64
artifacts. The qualifier must verify the complete native-front manifest,
direct/archive byte identity, VM/direct-ASM output without fallback, plugin-host
startup, bounded timeouts, and exact checksums before upload. Shared local and
CI native-image defaults must use the existing config files and no invalid
feature. Publication remains tag-only, exact stable SemVer, serialized per ref,
and create-only for a previously absent release. Record the successful run id,
required job conclusions, and fix SHA here.

**First hosted diagnostic run — MEASURED, red (2026-07-28, recorded during claim
triage after the owning session went silent).** Run `30316338197` finished
`failure` at 01:24Z. `Prove compare-first release refusals` passed; **all three
qualify legs failed**, and `Publish qualified tag` was correctly skipped:

```text
success  Prove compare-first release refusals
failure  Qualify ssc-linux-x86_64  on ubuntu-latest
failure  Qualify ssc-macos-x86_64  on macos-15-intel
failure  Qualify ssc-macos-arm64   on macos-latest
skipped  Publish qualified tag
```

None of them reached `native-image`. All three died in the **sbt build step**,
compiling `testUtils`, with an empty dependency classpath:

```text
[E008] v1/runtime/backend/test-utils/.../testkit/TestInterpreter.scala:3:19
 3 |import scalascript.backend.spi.Backend
   |       value backend is not a member of scalascript
 4 |import scalascript.interpreter.{Interpreter, Value}
   |       value interpreter is not a member of scalascript
 5 |import scalascript.parser.Parser
   |       value parser is not a member of scalascript
```

`testUtils` declares `.dependsOn(backendSpi, backendInterpreter)` in `build.sbt`,
and `build.sbt` has **no commits in the preceding 24 h** — so the source is not
the defect and the module graph was not edited out from under it. The classpath
was empty at compile time anyway. **Undiagnosed**; the next step is to reproduce
`sbt "cli/installBin"` at that SHA with the workflow's `set
cli / graalVMNativeImageOptions := …` prefix present and then absent, because
that `set` is the one thing the release path does that no green CI job does.
Until that is explained, `native-release` cannot qualify anything, and the
earlier note "no release run exists yet" is superseded: one exists and it is red.

## f-block-comment-lexed-as-code — F has no block-comment support; doc comments become expressions
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 2adeef250 -->

**Status:** FIXED (2026-07-27, landed `2adeef250`; status line corrected 2026-07-28 — it was left
saying OPEN by the same agent that fixed it, which is the exact board-lying failure that agent spent
the day correcting elsewhere). F's lexer gained `scanBlockEnd` + a `lexSlash` arm; three X1 gate
cases (prose, BOUND-global words, inline) were proven to fail without it. Evidence level 3: full
local conformance 309/0, X1 fixpoint byte-identical.
Found 2026-07-27 by `v2-board-and-f5b`. **F-front only**
(`specs/v2.2-p6.5-fsub.ssc`), and F is the DEFAULT native front.

**Minimal reproduction — two lines** (a `/**` doc comment followed by any def):

```
/** The database is returned unchanged when the journal is not hot. */
def main(): Int = 42
```

F's `entry` for that program:

```
(entry (seq (prim i.mul (lit (int 0)) (lit (int 0)))
  (global The) (global database) (global is) (global returned) (global unchanged)
  (global when) (global the) (global journal) (global is) (global not)
  (prim __arith__ (lit (str "/")) (prim __method__ (lit (str "25")) (global hot)) (lit (int 0)))
  (app (global main)) ... (lit (int 42)) (app (global main))))
```

The opening marker lexes as division-then-star (hence `i.mul 0 0`), every prose word becomes an
unbound global, and the closing marker yields a nonsense `__method__ "25"`. The legacy front
(`ssc1-run.ssc0`) handles the same input correctly. Reproduces BOTH in a bare `.ssc` and inside a
fenced code block, so it is the LEXER — not the literate projection, and not the import path.

**Impact — MEASURED 2026-07-28 after the fix landed: NOT the dominant breadth blocker. My prediction
was wrong, and the correction matters more than the original claim.** Delegation was re-measured with
`SSC_FRONT_TRACE=1` over 7 doc-comment-heavy conformance programs: **7 of 7 still delegate**, this one
included. The real-corpus gate likewise did not move (MATCH 202 / DIFF 327 over 529 programs, versus a
205/315 baseline taken on a smaller corpus — so no improvement, and the two numbers are not directly
comparable anyway).

What the fix actually bought is **diagnosis, not coverage**: for `scljet-mutate-update` the unbound-
global set went from 304 prose words plus the real ones, down to **53 real identifiers** —
`error ×6`, `leftChild ×4`, `seen ×2`, `cellPtr ×2`, `refTrunk ×2`, and the `jvmVfs*` host-VFS
intrinsics. Those are the genuine blockers, and they were invisible under the prose. The corpus gate
also cannot answer the delegation question at all: it compares IR byte-for-byte against the UNTYPED
oracle, while F now emits typed IR by design, so its DIFF count mixes coverage gaps with
typed-by-design divergence. **Use `SSC_FRONT_TRACE` for the delegation question, not the corpus gate.**

Original claim, kept because it was wrong in an instructive way:
Block doc comments are idiomatic in this codebase (the whole `scljet/` engine uses them), so every
such module poisons F's lowering. Measured on `tests/conformance/scljet-mutate-update.ssc`, same
staged tower, only the runner differing:

| runner | IR | method sites | prose globals (the/is/and) |
|---|---|---|---|
| `ssc1-run.ssc0` (legacy) | 568,555 B | 860 | **0** |
| `ssc1-run-fsub.ssc0` (F) | 664,520 B | 1060 | **304** |

**Why nobody noticed.** The prose lowers to UNBOUND globals, so `RunNativeV2.validateNoReader`
rejects F's program and the F4a delegate-fallback silently re-lowers with legacy. Output is correct;
the cost is a full double lowering, F's work discarded, and the program counted as a corpus DIFF.
**Re-measure the 315 DIFFs (SPRINT `V-3`) after fixing this** — a large share may be this one defect.

**Fail-open risk, structural rather than observed:** the fallback only fires because the prose words
are unbound. The opening marker ALREADY lowers to a bound expression, and a comment containing words
like `map`, `filter` or `head` would lower to bound globals too — a silently-wrong program that
`validateNoReader` would pass. Check that direction first when fixing.

**Fix sketch:** F's lexer skips line comments (the X1 gate has a `comment` case) but has no
block-comment state. Add block-comment skipping with nesting, mirroring `ssc1-front`'s lexer, and add
BOTH a gate case whose comment holds prose AND one whose comment holds bound-global words
(`map`, `filter`), so the fail-open direction is pinned too.

## corpus-contract-doc-mislabels-v2-lane — operator spec sends triage to the wrong architecture
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 2a796b258 -->

**Status:** **FIXED 2026-07-27** in `2a796b258` (reporter confirmation
pending). Found by Codex while reconciling the E7 gate docs at `fc5f07f28`.

**Reproduce.** `specs/corpus-contract.md` calls the contract's `v2` lane a
v1-frontend → v2-VM bridge. The comment beside `laneCmd` improves the frontend
name but still calls it a VM lane and says `ssc-tools run --v2` is the retired
bridge. Current routing says otherwise:

- `bin/ssc run --v2` enters `StandardMain → RunNativeV2` with the native
  frontend/checker and native plugin host. In a default environment it passes
  `bytecode=true`, so direct ASM is the primary backend (with link-time
  side-effect-safe VM fallback); `--interpret` or `SSC_EXEC=vm` opts into the
  VM. `bin/ssc info --execution-plan --v2` reports
  `{"tier":"standard","frontend":"native","checker":"native","backend":"asm"}`.
- `bin/ssc-tools run --v2` also uses the native frontend and `RunNativeV2`, but
  selects `bytecode=false` and therefore the VM. `FrontendBridge`/`RunV2` has
  been retired.

**Impact / fix.** A failing production cell would be assigned to both the wrong
frontend and the wrong execution backend. Update the two Corpus Contract docs,
the stale `laneCmd` comment, and the remaining retired-bridge comments in
`Main.scala`; keep `bin/ssc info --execution-plan --v2` as the executable
architecture check.

## corpus-baseline-update-scoped-run-truncates — `--update-baseline` can erase out-of-scope rows
<!-- status: fixed
     lane: native
     area: front
     fixed-in: fc5f07f28
     gate: tests/conformance/contract.sc -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex on `540f0ba52`.

**Reproduce.** `tests/conformance/contract.sc` refuses
`--update-baseline --shard`, but accepts both:

```bash
scala-cli tests/conformance/contract.sc -- --update-baseline --only hello
scala-cli tests/conformance/contract.sc -- --update-baseline --lanes int
```

The update writes `current`, which contains only the cases and lanes selected
by those flags, over the whole `corpus-baseline.tsv`. The first command drops
every non-`hello` row; the second drops every JS/v2 row. Use a disposable copy
for the red reproduction — running either command in the main checkout is
destructive. Review also found that a trailing/missing `--only`, `--shard`, or
`--lanes`, an empty `--only ''`, and duplicate canonical lanes can evade
value-based guards unless raw option presence/arity is validated.

**Root cause.** The shard guard correctly states the invariant ("update rewrites
the WHOLE baseline") but checks only successfully parsed values.
`--only`, malformed/missing values, and a non-canonical lane list create the
same partial-observation shape or are silently replaced by defaults.

**Fix / done-when.** A baseline update is admitted only for the unsharded,
unfiltered canonical lane list, and every refused or malformed combination
exits 2 with the exact unsafe scope in the diagnostic. Pin the cheap negative
control with `--list` so no built toolchain or corpus execution is required.

## v2-native-front-drops-attributed-code-fence — the DEFAULT lane silently discards any ```scalascript fence carrying an attribute
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/fen -->

**Status:** **FIXED 2026-07-27** by `corpus-gate-remaining-reds`. Was high severity: silent code loss
on the default execution path. Found while scoping the doc-block decision — the decision question is
what surfaced it, the bug is independent of it.

**Fix.** `sscFenceSource` in both runners (`v2/bin/ssc1-run.ssc0` legacy, `ssc1-run-fsub.ssc0` F) and
`joinScalaScript` in `ssc1-check-run.ssc0` now dispatch on the info string's FIRST WORD
(`sscFenceToken` / the new `fenceLangTok` in `v2/lib/mira-md.ssc0`) instead of matching the whole
string. `yaml` and `yml` had the identical defect on the same lines — `` ```yaml @id=plans-data ``
(literally the syntax `ContentDocumentTest` covers) would have been dropped too; they are fixed in
the same pass, so the whole function now dispatches by one rule, the way `sql` already did.

**What was deliberately NOT changed.** The obvious one-line version — make `getFenceLang` return the
first word — was rejected after checking: `sscSqlFenceSource` reads `@db=` and `@side=` back out of
that same string via `sscFenceAttr`, so narrowing it at the source would have silently broken sql
fence attributes. `getFenceLang` keeps returning the full info string, with a comment saying why.

**Verification.** The 5-line repro now prints `42` on v2/F, v2/legacy and INT (was `unbound global:
helper` on both native fronts). New corpus case `tests/conformance/fence-attr-code.ssc` — the only
case that attributes a `scalascript` fence, which is exactly why the differential gate was blind to
this — passes on INT, JS, JVM and V2. `sql-basic`, `sql-transaction`, `v2-self-hosted-yaml-core`,
`content-binding`, `content-tables`, `content-introspection`, `content-to-markdown` 7/7. **X1 self-
compile fixpoint: stage1 == stage2 byte-identical (409,629 B)** — the runners are part of the tower,
so this was the load-bearing check.

**Note for `specs/ssc-doc-blocks.md`.** With this fixed, a `@doc` block now *executes* on v2, as it
should until the feature exists. The earlier appearance of "`@doc` already works on v2" was this bug,
confirmed by the fix flipping it.

**Symptom.** A fence written `` ```scalascript @id=defs `` is executed by INT and by JS, and is
**dropped entirely** by the native lane — the code inside it never reaches the program:

```markdown
```scalascript @id=defs
def helper(): Int = 42
```

```scalascript
println(helper())
```
```

| lane | result |
|---|---|
| `ssc-tools run --v1` (INT) | `42` |
| `ssc-tools run-js` | `42` |
| `bin/ssc run --v2` (**default**) | `ssc: unbound global: helper` |
| `SSC_FRONT=legacy` + `--v2` | `ssc: unbound global: helper` |

**Front-agnostic** — legacy fails identically, so this is the native lane's fence recognition, NOT
an F regression. It is the same family as `f-case-object-drops-program`: source the author wrote
does not reach the program. Here it happens to surface loudly as `unbound global` when something
references the dropped code; when the dropped block only had side effects (the literate `## Example`
case) it is **silent** — the program just prints less.

**Why it matters now.** The `@key=value` fence-attribute syntax is a shipped surface (`ContentDocumentTest`
covers `` ```yaml @id=plans-data ``, and `Content.CodeBlock.attrs` is read by the SQL/transaction
block runners). Any `.ssc` that attributes a scalascript fence is miscompiled on the lane that is now
the default.

**Fix direction.** The native front's fence scanner evidently matches the info-string exactly
(` ```scalascript `) instead of taking the FIRST word of it as the language and the rest as
attributes, which is what CommonMark specifies and what the v1 parser does. Reproduce with the
5-line case above; the gate would not have caught it because no corpus case attributes a scalascript
fence.

**Interaction with the doc-block decision (Sergiy, 2026-07-27: "маркер doc-only блока").** Note the
native lane's current behaviour makes `@doc` look like it already works there — it does not; it
drops the block for the wrong reason, and would drop `@id=…` just the same. `@doc` has to be
implemented explicitly in every lane AFTER this bug is fixed, or the doc-block feature would be
built on an accident. See `specs/ssc-doc-blocks.md`.

## v2-front-try-in-def-body-shapes-break — `try`-as-a-def-body shapes break; (a)+(b)+(c) FIXED
<!-- status: unknown
     lane: native
     area: front -->

**Status: (c) FIXED 2026-07-28** in `d11fd7a92` (`f-try-multistmt-def-body`) — together with the sibling
`unbound global: try` this entry recorded for the legacy front, which turned out to be the same
shape seen from the other side. Root cause, fix, and the before/after are below; the original
report is kept verbatim under it.

**ROOT CAUSE — `try` was not a LAYOUT OPENER, on BOTH self-hosted fronts.** `isLayoutOpener` (F:
`specs/v2.2-p6.5-fsub.ssc`; oracle: `v2/lib/ssc1-front.ssc0:2920`) listed `=` `=>` `then` `else`
`match` `do` `with`/`yield` and never `try`. So `try` ⏎ `<indented body>` emitted **neither a
virtual `{` nor a `;`**: the indented lines sit at `v > ind` of the *enclosing* block, where the
newline step returns the stack unchanged, so the body's statements were **glued onto the `try`
line**. `parseExpr` then read `val` as a *variable reference*:

```
(def f (lam 0 (let ((global val)) (seq (prim cell.set (global @x) …      ← ssc1-front (oracle)
(def f (lam 0 (let ((lit (int 0))) (seq (prim cell.set (global x__cell) … ← F
```

A single-expression body survived only because gluing ONE token changes nothing — which is exactly
why (c) outlived (a) and (b), and why the corpus (single-expression only) never saw it.

**Why the differential gate said MATCH while both sides were wrong.** `specs/v2.2-p6.5-fsub.sh`
compares the `(rc, stdout)` tuple. Both fronts were broken and broken *differently*, and both died
at rc=1 with empty stdout — a two-sided crash reads as agreement. This is the AGENTS.md
"apparatus fails GREEN" pattern once more: the comparison was real, the *observable* was too
coarse to separate "both correct" from "both dead". Fixing F alone would have converted it into a
real DIVERGE against an oracle that is itself wrong, so the claim was widened and **both fronts
were fixed**.

**Three defects, one shape** (all in the fix):

1. `isLayoutOpener` gains `try` **and `finally`**, in both fronts. Both lex as identifiers, not
   keywords, so ssc1-front needed its own `id` arm next to the `kw` one. It fires only when the
   keyword is immediately followed by NL, so `try {` and `try e catch …` on one line are untouched
   by construction.
2. ssc1-front `parseBlock`: `try` lexes as an `id`, so `try { … }` in **statement position**
   matched the `name { block }` call-with-thunk rule and lowered to `try(() => body)`. That is the
   `SSC_FRONT=legacy` → `ssc: unbound global: try` this entry already recorded for the braced def
   body — same family, opposite end. New `isBlockArgCallName` excludes it.
3. ssc1-front `parseArmBody`: `finally` is an arm boundary. With a *braceless* `catch`, the
   arm-body statement loop swallowed the finally clause into the last arm —
   `(let (-1) (app (global finally) …))`, i.e. `unbound global: finally` **and** a `__tryCatch__`
   with no finally at all.

**MEASURED before/after — real harness, `bin/ssc run` (default = F native front).** Fail-first was
taken with the pre-fix binary, green with a fresh `sbt installBin`:

| program | before | after |
|---|---|---|
| the 5-line repro below | `native frontend rejected incomplete parse …: _err`, rc=1 | `ok`, rc=0 |
| `examples/rozum-agent.ssc` | same `_err` | runs (`Done` / `Posted the transaction.`) |
| `examples/rozum-agent-pool.ssc` | same `_err` | runs |
| `examples/rozum-agent-streaming.ssc` | same `_err` | runs |
| `examples/rozum-agent-schema-derived.ssc` | same `_err` | **past the parse gap** → `if: condition not Bool: Stub("Mirror.isProduct")` (a different, already-filed gap — see `rozum-agent-schema-derived-js-and-v2-gaps`) |

`SSC_FRONT=legacy ssc run --native` also prints `ok` on the repro, i.e. defect 2 is fixed too.

**Gates.** `specs/v2.2-p6.5-fsub.sh --self`: 158 ok / 0 FAIL, X1 **FIXPOINT stage1 == stage2
byte-identical** (410953 B) — `fsub.ssc` itself contains no `try`, so the self-compile is
untouched by the change. Six new differential cases were added to that corpus and deliberately
include the shape that always passed (`try_1stmt`) next to the one that never did
(`try_multistmt`), because a corpus carrying only the former is what let this survive. New
cross-lane regression case `tests/conformance/try-multistmt-body.ssc`.

---

### ⛔ SUPERSEDED — original report, kept only as history. Do not cite it as current state.

**Everything below was written BEFORE the fix and is preserved verbatim, so it is written in the
present tense and reads as if it were still true. It is not.** Re-measured on `origin/main`
`e34916fcf` with a fresh `sbt installBin`, all three of its load-bearing claims are false:

| what the report below says | state on 2026-07-28 after `d11fd7a92` |
|---|---|
| the 5-line repro yields `structural CoreIR contains parser sentinel _err` | prints `ok`, exit 0 |
| importing `std/agent.ssc` is enough to reproduce it | prints `agent-import-ok`, exit 0 — the bisected trigger is clean |
| "this is the root cause of `rozum-agent-schema-derived v2 FAIL`" | **no longer the cause.** That example now stops at `ssc: if: condition not Bool: Stub("Mirror.isProduct")` — the derives/Mirror gap in `rozum-agent-schema-derived-js-and-v2-gaps`, a different subsystem |
| "Owner note … NOT taken by …" (implying a live claim) | the claim `f-try-multistmt-def-body` is **released**; the work landed in `d11fd7a92` |

**⚠️ This section has already been used to wave a regression through** as "known, already-tracked
front defect, held by another agent's active claim". It is neither known-as-this-cause nor held by
anyone. A `rozum-agent-schema-derived` v2 failure seen today is **not** explained by this entry —
attribute it to the Mirror/derives gap, or measure it, but do not close it against a fixed bug.
Note also that the current failure exits **0** while printing its message, so an exit-status check
reads it as success; that is `v2-native-error-diagnostic`'s lane, not this one.

### Original report (kept for context)

**⚠️ SHAPE (c) IS STILL OPEN — found 2026-07-27 by `corpus-gate-remaining-reds`, AFTER (a)+(b)
landed and verified against a build that contains them.** The differentiator is not where `catch`
sits — it is a **MULTI-STATEMENT `try` body**.

Minimal repro (5 lines), on a build with (a)+(b) in it:

```scalascript
def f(): String =
  try
    val x = "ok"
    x
  catch case e: Throwable => "caught"
println(f())
```

→ `ssc: native frontend rejected incomplete parse …: structural CoreIR contains parser sentinel _err`

A/B that isolates it exactly — only the `try` body changes:

| `try` body | `catch` layout | result |
|---|---|---|
| single expression `"ok"` | `catch case e => "caught"` (same line) | **ok** |
| single expression `"ok"` | `catch` / `case` on separate lines | **ok** |
| single expression `"ok"` | case body on the next line | **ok** |
| **`val x = "ok"` then `x`** | same line | **`_err`** |
| **`val x = "ok"` then `x`** | case body on next line | **`_err`** |

**Why it matters right now.** *(SUPERSEDED — see the table above: as of `d11fd7a92` this is no
longer the cause of that failure.)* This is the root cause of `rozum-agent-schema-derived v2 FAIL`, which
the corpus contract has been reporting: the failure is not in that example at all — importing
`std/agent.ssc` is enough, and `std/agent.ssc:385-391` (`postChatCompletionsOnce`) is exactly this
shape. Bisected at top-level def boundaries to that function; `std/json.ssc` and `std/http.ssc`
import clean. So one parser gap silently removes `std/agent.ssc` — and everything importing it —
from the native lane.

**Owner note.** *(SUPERSEDED — the fix landed in `d11fd7a92` and the claim is released; nothing
here is unowned or in flight.)* The fix belongs in `specs/v2.2-p6.5-fsub.ssc` (the
`parseHArms0`/`parseCatchPf`
region that (b) already touched). NOT taken by `corpus-gate-remaining-reds`: that file had three
fixes land in it today from another agent and `v2-try-misses-io-exceptions` is live in the same
area — handing over the pin rather than racing on the same parser.

**Status of (a)+(b):** **FIXED 2026-07-27** by opus — (a) `front-try-def-body-shapes`, (b)
`front-braceless-try-def-body`. Found the same day while pinning `v2-native-front-try-catch` layer 2
— the conformance case for that fix hit both on its first run.

**(b) — ROOT CAUSE, and it was one line.** `parseHArms0` called `hClose` **unconditionally** at the
end of the catch arms, while `hOpen` only consumes a `{` when one is there. So a BRACELESS
`catch case … => …` ate the next `}` in the stream — which is the **virtual brace layout inserted to
close the enclosing def body**. The def's block stayed open and swallowed everything after it:
`def w(..) = try ⏎ … ⏎ catch ⏎ case …` followed by `println(w(0))` lowered to a def *containing* the
println and `(entry (lit unit))` — no output, exit 0. Fix: consume the `}` in `parseCatchPf`, and
only when a `{` was actually opened (`closeIfBraced`).

**Measured before/after, F front, four shapes** — this is also the matrix that corrected the old
notes, since the two fronts turned out to have COMPLEMENTARY gaps:

| shape | legacy front | F before | F after |
|---|---|---|---|
| def body, braced | fails | `W` | `W` |
| def body, braceless multiline | `W` | **empty, exit 0** | **`W`** |
| `val` binding, braceless | `W` | fails loudly | **`W`** |
| braceless def then another def | `W` | `cf` `g1` | `cf` `g1` |

The loud failures were invisible in the CLI because the F4a fallback covers them; the **silent** one
was not, because F emitted a VALID-but-wrong program with nothing for the fallback to detect. **v2 FRONT (parse/layout), not the kernel.** The v1
reference handles both shapes.

**(a) — ROOT CAUSE, to the line.** `specs/v2.2-p6.5-fsub.ssc` `parseHArm` — the CATCH handler-arm
parser — assumed **every** arm was `pat : T =>` and read the type head as *the token two past the
pattern*:

```
parseHTyped(isWild(hd(ts)), snd(hd(ts)), snd(hd(tl(tl(ts)))), skipTypeToArrow(tl(tl(ts))), …)
```

For `case _ => "W"` the tokens are `_`, `=>`, `"W"`, so the "type" became **`"W"` — the arm's own
body** — and F emitted `__isTag__(v, "W", -1)`, a test that can never hold. The handler fell through
to `__handler_dispatch_miss__`, and on the default lane the whole program then vanished at exit 0.
An ordinary `match` was never affected: `parseGenArm` tests `isTypedArm` first; only this handler
path assumed. (Verified by lowering: plain `match { case _ => "W" }` → a correct
`(default (lit (str "W")))`; the same arm under `catch` → the bogus tag test.)

**Fix:** `isTypedHArm` + an untyped catch-all branch (`parseHAll` / `parseHWildAll` /
`parseHBindAll`) mirroring the typed one minus the tag test. The remaining arms are still parsed, so
token consumption cannot desync, but not emitted — a catch-all makes them unreachable.
**Measured after:** `case _ =>` → `W`, `case e =>` → `bound`, a typed arm followed by `case _` →
`fallback`, and a *non-throwing* body with a wildcard catch still returns the value (`2`), i.e. the
handler does not fire when nothing is thrown. The conformance case
`tests/conformance/try-catch-exception-delivery.ssc` now carries both untyped shapes as the
regression shield.

**(b) — STILL OPEN.** The braceless multiline `try` as a def body does not terminate cleanly (detail
below). Untouched by this fix.

**Measured, minimal repros (real CLI, built `bin/`):**

```
def w(x: Int): String =
  try { (10 / x).toString } catch { case _ => "W" }      -- WILDCARD catch, braced, def body
println(w(0))
```

| lane | result |
|---|---|
| `ssc run` (default) | **no output, exit 0** |
| `ssc run --native` (F) | **no output, exit 0** |
| `ssc run --bytecode` / `--interpret` | no output |
| `SSC_FRONT=legacy ssc run --native` | `ssc: unbound global: try` (loud) |
| `ssc-tools run --v1` (reference) | **`W`** ✓ |

The trigger is the **bare wildcard** `case _` specifically: the same def with
`catch { case e: Throwable => … }` works, and the same wildcard works when the `try` is an
*expression argument* (`println(try { … } catch { case _ => … })` → `wild`). Only `try` as a **def
body** with a wildcard catch breaks.

```
def f(a: Int, b: Int): String =
  try
    (a / b).toString
  catch
    case e: Throwable => "c"      -- BRACELESS multiline, def body
println(f(10, 0))
```

This one parses, but does not TERMINATE cleanly: a following top-level statement is silently
dropped (no output, exit 0), and a following `def` whose body starts with `try` becomes
`ssc: unbound global: try`. Inserting any other `def` between them hides it. v1 prints `c`.

**Why it matters more than it looks:** the default lane fails **silently at exit 0** — a whole
program disappears with no diagnostic. The F4a delegate-fallback does not save it either, because F
emits a *valid but wrong* program (no unbound global to trip the fallback) — the same class as the
F multi-file-order regression (`f-native-multi-file-positional-args-reversed`).

**Not fixed here** — this is front/layout work, and the claim that found it was scoped to the
exception-delivery kernel bug. The conformance case `tests/conformance/try-catch-exception-delivery.ssc`
documents both shapes as deliberately absent so it pins delivery rather than layout.

## literate-import-executes-example-blocks — importing a literate module RUNS its documentation examples on INT, not on v2
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: unrecorded -->

**Status:** **RESOLVED 2026-07-27** by the `@doc` feature (Sergiy's decision, `specs/ssc-doc-blocks.md`)
— the divergence is gone, and the mechanism that caused it is now expressible rather than implicit.
`v1/runtime/std/coroutine.ssc`'s three `## Example` blocks are marked `@doc`, so importing it prints
nothing: `coroutine-demo` is **8 lines on INT and 8 on v2** (was 17 vs 8). Original report below —
it remains the reason the feature exists.

**Measurement** (`examples/coroutine-demo.ssc`, which imports `std/coroutine.ssc`):

| lane | output |
|---|---|
| `int` | **17 lines** — the 9 lines printed by `std/coroutine.ssc`'s own `## Example` blocks, then the demo's 8 |
| `v2` | **8 lines** — the demo's own output only |
| `js` | `Error: not callable: ()` (a separate gap, see below) |

So INT executes an imported literate module's example blocks as side effects of the import, and the
native lane does not. Because the corpus contract takes the live INT output as the golden when a case
has no `expected/` file, INT's behaviour is the reference and `v2` reports `DIVERGE`.

**Why it matters beyond this case.** Every literate module under `v1/runtime/std/` with a runnable
`## Example` block imposes that block's output on every importer — invisible until someone diffs the
lanes. The two defensible answers point in opposite directions (v2 is arguably right: an import
should bind names, not print; INT is arguably right: a `.ssc` document IS its blocks), so this needs
an owner decision rather than a patch.

## rozum-agent-schema-derived-js-and-v2-gaps — a newly-runnable example fails on both non-INT lanes
<!-- status: open
     lane: native
     area: front -->

**Status:** OPEN — **but the v2 half is CLEARED, and what it was hiding is now visible** (updated
2026-07-28 by `f-try-multistmt-def-body`). The predicted outcome held: fixing
`v2-front-try-in-def-body-shapes-break` shape (c) removed the `_err` without touching this example.
Measured on a fresh `sbt installBin`, `bin/ssc run examples/rozum-agent-schema-derived.ssc` now
reaches **`ssc: if: condition not Bool: Stub("Mirror.isProduct")`** — i.e. the parse gap is gone and
what remains on the v2 lane is the **derives/Mirror** gap, the same class as
`js-lane-missing-derives-and-coroutinecancel`. Three sibling examples that the same parse gap had
removed from the native lane (`rozum-agent`, `rozum-agent-pool`, `rozum-agent-streaming`) now run
correctly there. The **JS half is untouched** and still the binding defect described below.

Remaining work for this entry: (1) the JS `route` extern-binding defect, (2) the v2 `Mirror.isProduct`
Stub. Neither is a parse problem any more.

**✅ v2 HALF NOW FIXED (2026-07-28, `4f5ecf261`, `v2-mirror-isproduct-stub`).** The `Stub("Mirror.isProduct")`
below was the native `Mirror` implementing only three of its members; both fronts now register
`isProduct` as a tagged method. Measured on a fresh `sbt installBin`: `bin/ssc run
examples/rozum-agent-schema-derived.ssc` prints `Done / Derived posted. / Explicit posted. / 2` —
**byte-identical to the v1 reference**. `fromProduct` remains `Stub` but is NOT on this example's
path (filed as `v2-mirror-fromproduct-stub`). **The JS half is still open** — the `route`
extern-binding defect described below.

**RE-CONFIRMED on `e34916fcf`** (2026-07-28, after `8f736ca8b` made the native scan follow in-fence
imports — which changes this example's module graph, so the earlier measurement could not simply be
assumed to still hold). Fresh `sbt installBin`, three probes: the 5-line `try` repro prints `ok`;
importing `std/agent.ssc` alone prints `agent-import-ok`; the example itself still stops at
`Stub("Mirror.isProduct")`. **It exits 0 while printing that** — so a gate keying on exit status
reads this failure as success, which is `v2-native-error-diagnostic`'s lane and worth knowing before
anyone reads a green exit code here as the example working.

⚠️ **Do not attribute a v2 failure of this example to
`v2-front-try-in-def-body-shapes-break`.** That bug is FIXED (`d11fd7a92`) and its claim released;
its preserved original report is written in the present tense and has already been mis-cited once as
a live, someone-else's-claim explanation for a regression here. That entry now carries a SUPERSEDED
banner for exactly this reason.

**Original status:** OPEN (found 2026-07-27 by `corpus-contract-shard-fix`; the same two entries the
07-17 run had already reported — the last time the gate managed to finish before it started timing
out).

**Symptom** (`examples/rozum-agent-schema-derived.ssc`):

| lane | result |
|---|---|
| `int` | runs (serves on `http://localhost:19702/`, prints `Done`) |
| `js` | `Error: not callable: …` from the generated `.cjs` |
| `v2` | `native frontend rejected incomplete parse …: structural CoreIR contains parser sentinel _err` |

**⚠️ v2 SIDE PINNED 2026-07-27.** It is not this example's code at all. Importing `std/agent.ssc`
ALONE reproduces the `_err`; `std/json.ssc` and `std/http.ssc` import clean. Bisected at top-level
def boundaries to `std/agent.ssc:385-391` (`postChatCompletionsOnce`), then reduced to a 5-line
repro: a `try` with a **multi-statement body** as a def body. Full A/B and owner note in
`v2-front-try-in-def-body-shapes-break` shape **(c)**, which is where the fix belongs — that entry's
(a)+(b) fixes are already in the build this was measured on. Fixing (c) should clear the v2 half of
this bug without touching this example.

**JS SIDE PINNED 2026-07-27 (opus).** `Error: not callable: ()` is `_show(undefined)`, and the call
site is `_call(_call(route, "POST", "/v1/chat/completions"), …)`. `route` IS implemented in the JS
runtime — `_ssc_http_route`, exported as `globalThis.route` — but the generated module binding
`const route = std.http.route` shadows it, and `std.http.route` comes from the extern probe
`(typeof _ssc_ui_route !== 'undefined') ? _ssc_ui_route : undefined`, which finds no `_ssc_ui_route`
and yields `undefined`. So this is a BINDING defect, not a missing implementation: the extern probe
looks only for the `_ssc_ui_*` name and never for the runtime function actually present.

This is the same CLASS as `coroutine-demo-js-not-callable` (an extern silently bound to `undefined`)
but NOT the same cause — that one has no JS implementation at all. Fixing either does not fix the
other; the entry there records the comparison. Common to both: the probe's `: undefined` tail is
fail-open and should be a loud link error naming the extern.

**Notes.** Two independent gaps behind one case: a v1-JS codegen defect and a native-front parse gap.
The case also binds a real socket on a fixed port, which makes it a candidate for
`tests/conformance/corpus-skip.txt` on the documented non-hermetic grounds — but **do not skip it to
make the gate green**: skipping must follow from it being non-hermetic, and the two lane gaps have to
be filed (this entry) either way. Pin the `_err` sentinel to a concrete construct before triaging the
v2 side.

## f-front-compile-cost-7x-on-scljet — F as the default front pushes correct cases past gate budgets
<!-- status: open
     lane: native
     area: front
     kind: perf -->

**Status:** OPEN, but **2.5× smaller than filed** — re-measured 2026-07-28 after
`v2-method-dispatch-never-jits` was fixed (`197ae13ab`). Filed as a PERF finding: output is correct
on both fronts, so nothing here is a correctness bug.

**Re-measurement 2026-07-28** (`bin/ssc run --v2`, slice-1 build, two runs per cell, outputs
byte-identical between fronts):

| program | front F | legacy | ratio |
|---|---|---|---|
| `examples/scljet-crud.ssc` | **11.40 s** (was 28.20) | **4.08 s** (was 4.16) | **2.8×** (was 6.8×) |
| `examples/scljet-jdbc.ssc` | **27.94 s** (was: exceeded a 90 s budget) | 9.35 s | 3.0× |

**The legacy column is the control and it is what makes this comparison honest across days:** it
reproduces its originally-recorded 4.16 s at 4.08 s, on a different day and a different build, so
the F column's 28.20 → 11.40 is a real change and not machine drift.

Cause of the improvement: F is an `.ssc` program *interpreted on the v2 VM* and its hot path is
lexing/parsing/string concatenation — exactly the `__method__`-dispatching shape that was never
being JIT-compiled. This was written down as a prediction in `specs/v2-runtime-perf-vs-v1.md`
**before** it was measured. `scljet-jdbc`, the one case that could not fit even the raised 90 s lane
budget, now completes in 27.9 s and PASSED the v2 lane in the 2026-07-28 corpus-contract run.

Still open: ~2.8-3.0× remains, and `specs/v2-f-compile-cost.md` Result 2 (the cost SCALES with
program size, so a super-linear step inside F is the likely remaining cause) has not been retested.

**Original measurement** (2026-07-27, same tree/build, `examples/scljet-crud.ssc`, `v2` lane) —
kept so the delta above is checkable:

| front | wall | output |
|---|---|---|
| F (default since `56d7d705f`) | **28.20 s** | correct |
| `SSC_FRONT=legacy` | **4.16 s** | correct, identical |

**Impact.** The corpus contract's per-lane budget was 30 s, so seven scljet cases
(`scljet-crud`, `-jdbc`, `-bytes`, `-full`, `-readonly-codecs`, `-text-projection`, `-write-table`)
reported `v2 TIMEOUT` as REGRESSIONS in run `30281019432` while being perfectly correct — a 28.2 s
case against a 30 s budget is a coin flip on a loaded CI runner. Mitigated on the gate side by
splitting the golden probe from the lane budget (`--lane-timeout 90`, `afa5981a2`), which stops the
correctness gate reporting perf as TIMEOUT noise. **That is mitigation, not a fix** — the ~7×
remains.

**LOCALISED 2026-07-28 (`f-compile-cost-profile`, `specs/v2-f-compile-cost.md`).** Isolating the
front phase (staged tower invoked directly, identical JVM flags for both runners) settles what the
7× actually is:

| | front F | front legacy |
|---|---|---|
| `examples/scljet-crud.ssc` | **25.72 s** | **1.28 s** |

- **NOT the F4a fallback.** `SSC_FRONT_TRACE` reports **0 delegations** on this program — F lowers it
  successfully and its output is accepted, so this is not double lowering. That explanation is dead.
- **It SCALES rather than being a fixed startup tax:** `hello` (414 B source) is **4.2×**,
  `scljet-crud` (2,115 B) is **13.5×**. A constant cost would show the ratio shrinking as work grows;
  it does the opposite — which is why warming or caching cannot absorb it, and why a correct case
  landed on the wrong side of a 30 s budget.
- **The "recovery path" line below is WRONG and should not be relied on:** typed arithmetic was
  measured at ~1% (`specs/v2-f5b-typed-ir-design.md` §"MEASURED PERF FINDING"). F5b's payoff is
  kernel size and directness, not compile speed.

Open next (V-6b): whether F itself belongs on the bytecode lane — but the `f5c` wins were NUMERIC
while F is string/list-heavy, so qualify before investing. Given the cost is proportional, a
super-linear step inside F may be the cheaper find.

**Context (historical).** The F4 flip was known to cost speed (the `f4-arc-closure` batch recorded "2-4× slower;
hello 0.8→1.5 s, scljet 8→32 s"), but nothing measured it against the corpus, so nobody knew it was
already breaching a gate budget. `v2-f5b-typed-locals` (SPRINT Batch B) is the recovery path.

## corpus-contract-never-green — the always-on differential gate produced ZERO verdicts in 13 runs
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 0c626d348 -->

**Status:** FIXED (2026-07-27, `corpus-contract-shard-fix`: `0c626d348` mechanism, `3b2b98f96`
matrix, `e8214a277` separator). Filed because the *detection* failure is the durable lesson, not the
timeout.

**Symptom.** `Corpus Contract` — added `48110001c` (2026-07-14) as "the always-on differential gate
for the v2 migration" / "strangler-fig safety net" — never once reported `success`: 3 `failure`
(07-15..07-17) then 10 `cancelled` (07-18..07-27).

**Root cause.** Not a hang: the `timeout-minutes: 60` wall. Run `30244286812` logged `… 350/485` at
`07:53:40` and was killed at `07:55:17`. The workflow header still promised "~415 cases … ~25 min";
by 07-27 the corpus was 485 cases and per-case cost had grown (F became the default native front and
is 2-4× slower), so a full pass needed ~95-100 min.

**Why nobody noticed for 12 days.** GitHub surfaces a job timeout as **`cancelled`, not `failure`**,
which reads as "someone cancelled it"; and `scripts/ci-status` only ever queries `ci.yml` with
`--event push`, so no automated check looks at the scheduled workflows at all. Consequence: the whole
F4 front-flip landed without its differential net, and
`bytecode-opanf-purity-registry-marks-every-def-pure` (above) sat undetected on `main` for 5 days —
the gate caught it within one run of being able to finish.

**Fix.** `--shard i/N` (round-robin) + `--list` in `contract.sc`; the baseline compare was already
subset-safe (`inScope`), and `--update-baseline` now refuses to run sharded (it would truncate the
baseline to 1/N). Workflow runs 4 shards, `fail-fast: false`. `MILESTONES.md` §Health now states that
a scheduled workflow reported `cancelled` is RED.

## f-case-object-drops-program — F silently lowers a top-level `case object` to `0`, dropping the rest of the program
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 35331f1c7
     gate: tests/e2e/v21-slim-distribution-gate.sh -->

**Status:** FIXED (2026-07-23, `35331f1c7`) by `opus` under the
`flip-sbt-suite-under-F` claim. Verified: X1 fixpoint stage1==stage2 byte-identical (406008 B), semantic
248/248, REAL v21-slim + v21-jre-module + v21-negative-toolchain (release.ready true, parity.mismatch 0) +
v21-direct-asm gates all PASS under F, F-sensitive cli suites 17/17 under F. The F4 default-front flip's last CI blocker on the `sbt — compile and test`
job (run `30011873355`/`7ce98b9fd` failed at the **physically slim standard distribution gate** step, NOT
`Test via sbt` — the whack-a-mole premise was slightly off: `Test via sbt` itself is clean under F).

**Symptom.** Under F (the default native front) `tests/e2e/v21-slim-distribution-gate.sh` fails at check
`fs/os provider vm`: `run tests/fixtures/v21-native/fs-os-provider.ssc` prints a single `0` (rc 0) instead
of its six lines. Legacy (`SSC_FRONT=legacy`) is correct — a genuine F-regression, not a flake.

**Root cause.** F's `compile` (`specs/v2.2-p6.5-fsub.ssc`) has no top-level `case object` rule. `parseTopItem`
routes `case class` (isCCHead = `case`+`class`) and bare `object` (isObjectHead) but a standalone
`case object N [extends P]` — head is `case`(2,6)+word `object` — misses BOTH and falls through to
`exprItem`, which lowers it to a bare `0` expression AND consumes the following statements. So any program
that reaches F fully (no other coverage gap) and contains a top-level `case object` — e.g. anything importing
`std/os.ssc`, which declares `case object Jvm extends Platform` — collapses to `(entry (lit (int 0)))`, the
empty-program default. This is a SILENT wrong answer: no unbound global, so `validateNoReader` passes and the
F4a delegate-fallback never fires. (Corpus/fixpoint/semantic missed it because `fsub.ssc` itself uses no
`case object`, and complex programs importing `std/os` delegate to legacy for OTHER coverage gaps first.)

**Fix.** Add a `case object` branch to `parseTopItem`: `isCaseObjectHead` + `caseObjectItem`, lowering
`case object N` to `(def N (ctor N))` — EXACTLY the nullary-enum-case emission (`eraseEnumNull`, ssc1-lower
:3826) — and consuming `case`/`object`/name + optional `extends`/body (`skipCCBody(skipExtends(...))`) so the
walk resumes at the next statement. `fsub.ssc` uses no `case object`, so the X1 fixpoint is byte-unchanged.

**Repro.** `scripts/sbtc installBin`; `SSC_NO_CDS=1 bin/ssc run tests/fixtures/v21-native/fs-os-provider.ssc`
(F prints `0`, legacy prints the six lines). Minimal: a `.ssc` whose sole block is
`case object Jvm` + `println("hi")` prints `0` under F, `hi` under legacy.

## f-native-multi-file-positional-args-reversed — `ssc run --native A.ssc B.ssc` runs files in REVERSE order under F
<!-- status: fixed
     lane: native
     area: front
     fixed-in: fa19761c2 -->

**Status:** FIXED 2026-07-23 by opus (`fa19761c2`). Root cause: F's native runner
(`v2/bin/ssc1-run-fsub.ssc0`) re-lowers the multi-file closure from a source string it rebuilds with
`sscConcatSources(seen)`. `seen` is the loaders' flat dedup list in **reverse-pre-order**, which reverses
SIBLINGS (multiple positional roots, and — latent — multiple sibling imports), so `A.ssc B.ssc` concatenated
as B-then-A. The legacy runner (`ssc1-run.ssc0`) instead does `lowerProg(allStmts)`, and `allStmts` is
accumulated in correct POST-ORDER via `sscApp` — hence the F-vs-legacy divergence the F4 default flip
exposed (cli/Test `V2RunArgvCliTest` case "run --v2 keeps multi-file positionals … as source files",
`List("second","first") != List("first","second")`). **Fix:** a dedicated post-order path traversal
(`sscOrderMod`/`sscOrderImps`/`sscOrderRoot`/`sscOrderRoots`) mirroring the `sscLoad*` loaders' `sscApp`
accumulation but yielding ordered SOURCE PATHS; `main` concats `sscConcatSources(orderedPaths)`. The shared
loaders can't return this list (the front-matter/markdown/content projectors read their def slot), hence the
duplication. `seen` threading is byte-identical, so the structural content projection's `reverse(seen)` is
unchanged. Verified: `V2RunArgvCliTest` 2/2 green; A/B byte-identical F-vs-legacy on 2-file, 3-file,
reversed-arg, and import-before-root (`70d5bbabf` guard) scenarios; L48 fixture `first\nsecond`; P6.6
self-compilation fixpoint stage1==stage2 byte-identical (runner-only change — the fixpoint/semantic gates
operate on `fsub.ssc`, untouched). Distinct from the tracked `dualrun.expected` IMPORT-value-lowering
residuals.

<details><summary>Original report (2026-07-23, opus during <code>v2-f4-reflip-D1</code>)</summary>

A new, minimal,
deterministic manifestation of the DOCUMENTED "multi-file source-concatenation" caveat class (spec
`specs/v2-language-surface.md` §7 F4a; `specs/v2.2-p6.5-dualrun.expected`). The F4 flip (F is the default
native front, `10bb15bfa`) is LIVE with that caveat accepted by Sergiy; this entry records the SMALLEST
repro and its CI / A-B blind spots so the fix loop can use it.

**Deterministic repro (F default vs `SSC_FRONT=legacy`, staged `bin/ssc` after `installBin`):**

```
# tests/fixtures/v21-native/multi-first.ssc   -> println("first")
# tests/fixtures/v21-native/multi-second.ssc  -> println("second")
bin/ssc run --native multi-first.ssc multi-second.ssc
  F (default): "second\nfirst"   # WRONG — reversed (3/3 deterministic)
  legacy:      "first\nsecond"   # correct
# and reversed args confirm it: F on (multi-second, multi-first) prints "first\nsecond".
```

F executes the positional input files in REVERSE argument order. It is asserted by
`tests/e2e/v21-native-entry-smoke.sh` check `L48` (`expect_out "L48" $'first\nsecond' …`).

**Scope / blast radius (SMALL):** ONLY multiple POSITIONAL file arguments. Single-file runs and
single-file-with-imports (`[lib](lib.ssc)` link imports) are byte-identical F-vs-legacy (verified). The
tracked `dualrun.expected` residuals are the IMPORT-based multi-file value-lowering class; this positional
sub-case is DISTINCT and was not in that list nor exercised by the corpus.

**Why CI + the "72-script A/B green" readiness claim missed it:** `v21-native-entry-smoke.sh` is NOT run
in CI. In a whole-script rc-level A/B it reads as `both_fail` — F exits rc=1 at `L48` (via `expect_out …
exit 1`) while legacy reaches rc=1 only LATER on unrelated env checks (http-server-fast / storage) via the
smoke's ERR trap — so an rc-only differential buckets it "not an F-only regression." The real signal is at
the CHECK level: legacy PASSES L48, F FAILS it. (AGENTS.md "compare first, classify after" — the
script-level both-fail masks the check-level F divergence.)

**Fix direction:** F's multi-file source-concatenation lowering must preserve positional file order. A
fallback cannot help (F resolves all globals and produces a WRONG value, so there is no error to trigger
the F4a delegate-fallback). Deep F-lane change; part of the F5b typed-IR arc. Interim workaround:
`SSC_FRONT=legacy bin/ssc run --native …` for multi-positional-file runs.
(Superseded: the fix was NOT a deep typed-IR change — the runner's source-concat order was wrong;
correcting it to post-order matched legacy. See Status above.)

</details>

## f-int-literal-overflow-fails-open — F wraps out-of-range 64-bit integer literals instead of rejecting
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 180f16fcb
     gate: tests/e2e/int-literal-failopen-smoke.sh -->

**Status:** FIXED `180f16fcb` (`v2-f4-reflip`, 2026-07-22). F's lexer now range-checks decimal literals and
fails CLOSED, mirroring `ssc1-lower`. Root cause: F's `scanNumV` accumulated `acc*10 + d`, which silently
WRAPS Long, so an out-of-range literal lexed to the wrapped value and emitted it. Fix (fsub.ssc, lexer/parser
only): `scanNumOv` flips an overflow flag the step BEFORE the multiply would overflow signed 64-bit; an
overflowing decimal becomes a kind-11 token (raw digits) that `parseAtom1` fails closed via
`(global _err_int_range)` and `parseNeg` folds ONLY the min64 half (exactly 2^63) — every in-range literal
stays kind 0 → byte-identical. Verified: `tests/e2e/int-literal-failopen-smoke.sh` GREEN under F (incl.
F-as-default post-reflip); X1 fixpoint byte-identical (388,384 B); semantic 248/248; dualrun 45/45 EQUAL.
Was the fail-OPEN safety regression that reverted the first F4 default-front flip (`3750df8c2` → `bf24267e9`).

**Symptom:** an integer literal that overflows signed 64-bit is silently WRAPPED by F (exit 0, wrong value) instead of failing closed. The old front (`ssc1-front`+`ssc1-lower`) and the v1 reference correctly REJECT it (exit 1). In-range literals (min64 `-9223372036854775808`, max64 `9223372036854775807`, 2^62, 3e9) are all correct on F — only the OVERFLOW path fails open.

**Repro:** `tests/e2e/int-literal-failopen-smoke.sh` — with F as the native default (`SSC_FRONT=F bin/ssc run` on the `vm`/`asm` tiers):
- `9223372036854775808` (2^63) → F prints `-9223372036854775808` exit 0 (expected: fail closed, exit≠0).
- `99999999999999999999999999` → F prints `-2537764290115403777` exit 0.
- `-99999999999999999999999999` → F prints `2537764290115403777` exit 0.
Old front / v1: all three `failed closed (exit 1)`. Smoke: 3 checks FAILED under F, all pass under the old front.

**Why the 528-program `SSC_DUALRUN_ALL` sweep missed it:** these targeted overflow literal VALUES are not present in any corpus program, so the output-equivalence sweep had nothing to compare — a genuine OUT-OF-CORPUS gap. The e2e smoke covers it; the corpus does not. (Prior sibling class: `v1-interp-int-literal-above-2^31-becomes-null`, FIXED `5b71ad2f6` — the v1 leg of the same fail-open family.)

**Fix (future F arc):** F must range-check integer literals during lowering and fail closed on 64-bit overflow, mirroring `ssc1-lower`. Do NOT edit `specs/v2.2-p6.5-fsub.ssc` here — sibling-owned; this is a separate future arc. Blocks re-attempting the F4 default-front flip.

## f-native-out-of-corpus-smoke-regressions — F fails on out-of-corpus native e2e smokes the corpus sweep can't see
<!-- status: fixed
     lane: native
     area: front
     fixed-in: f02100097
     gate: tests/e2e/v21-native-md-interpolator-smoke.sh -->

**Status:** FIXED (both regressions cleared; confirmed by the landed flip). Found 2026-07-22 by opus
during the `v2-f4-reflip` re-flip verification, which was HALTED as a result — ①② landed, F stayed
opt-in via `SSC_FRONT=F`. F-front (`specs/v2.2-p6.5-fsub.ssc`) native tier. **③.1 md-interpolator FIXED**
2026-07-22 (`f02100097`). **③.2 plugin-boundary FIXED** 2026-07-23 via D2 (`cca93b867`) — re-diagnosed as
an isolation/class-load issue rather than a fixture-output divergence: self-hosted `irTextToData` reader
+ Reader-free `validateNoReader`, measured zero `ssc.Reader` loads under F (was 24-25×), both isolation
smokes green A/B. **Closed 2026-07-27 on a re-run, not on the paperwork.** The flip landing
(`56d7d705f`, `RunNativeV2.frontIsF` now opt-OUT) reported the readiness bar this bug itself defined —
72/72 e2e smoke scripts green under F-as-default with zero F-only regressions, CI green on the flipped
tree (run `30020319173` on `18ee1c21a`, 4/4 jobs) — but a status flip justified by another commit's
claim is exactly the pre-judging this project keeps paying for. So the three owning smokes were re-run
from a fresh `installBin` stage at `492254d31`, F being the live default:

| smoke | default (= F) | `SSC_FRONT=legacy` |
|---|---|---|
| `tests/e2e/v21-native-md-interpolator-smoke.sh` | PASS | PASS |
| `tests/e2e/v21-native-plugin-boundary-smoke.sh` | PASS | PASS |
| `tests/e2e/v21-plugin-backend-isolation-smoke.sh` | PASS | PASS |

`bin/ssc run tests/fixtures/v21-native/md-interpolator.ssc` prints the interpolated content
(`[Hello, Ada!` …), not the fail-open `[<closure>]`. The gate is fail-loud for this exact defect — it
is what caught ③.1/③.2 on 2026-07-22 in the first place. See the per-item notes below for both root
causes.

**What happened:** after fixing blocker ① and bumping the CI budget (blocker ②), the re-flip passed every
corpus-level gate — X1 fixpoint byte-identical, semantic 248/248, dualrun 45/45 EQUAL, negtc gate PASS
(frontend.ok 208≥200, mismatch=0) — but the targeted native `bin/ssc` e2e SMOKE set (run under F-as-default)
caught **2 F-regressions** that PASS under `SSC_FRONT=legacy` and FAIL under F (A/B-verified; 10 other smoke
failures fail on BOTH fronts = pre-existing, NOT flip-related):

1. **[FIXED 2026-07-22 `f02100097`] `tests/e2e/v21-native-md-interpolator-smoke.sh`** — F FAIL-OPENed on
   markdown `md"…"` / `raw"…"` interpolation.
   Repro (was): `bin/ssc run tests/fixtures/v21-native/md-interpolator.ssc`. Legacy prints the interpolated
   content (`[Hello, Ada!\n  nested 3\n\nTail]\nsingle-Ada\nlocal:ok\n[]`); **F printed `[<closure>]\n<closure>\nlocal:ok\n[<closure>]`, exit 0** — the classic fail-open `<closure>`.
   **Actual root cause:** F's lexer only special-cased `s"…"` (a kind-6 interp token); ANY other interpolator
   prefix (`md`/`raw`/…) was lexed as a bare ident + a SEPARATE string token, so `md"…"` parsed as a reference
   to the `md` global (a closure) applied to nothing → `<closure>` at runtime. (Not "F emits a closure where
   the reference forces it" — F never recognized the prefix as an interpolator at all.) The F4a delegate-fallback
   could not catch it: `md` IS a bound global, so the IR was closed and ran (wrong, not unbound).
   **Fix:** parser-level, mirroring the reference (ssc1-front :1053-1067) — `parseAtom1`'s kind-1 ident branch
   now routes a bare `md`/`raw` immediately followed by a str (kind 4) or triple (kind 8) token to interpolation:
   `md"…"` = `(prim __mdStrip__ <s-interp>)` (de-indent/trim; triple content escTriple-escaped before the `$`
   split), `raw"…"` = plain s-interp; a prefix NOT followed by a string stays an ordinary ident (`md("ok")` is
   still the call). F's own source uses no interpolation so the fixpoint is untouched. **Verified (rebased on
   origin/main):** F output byte-identical to legacy on the fixture (zero `<closure>`); `v21-native-md-interpolator-smoke`
   PASS; X1 fixpoint byte-identical (405,396 B); semantic 248/248; int-literal smoke green. (`f"…"`/`html"…"`
   prefixes are separate follow-ups.)
2. **[FIXED 2026-07-23 via D2] `tests/e2e/v21-native-plugin-boundary-smoke.sh` +
   `v21-plugin-backend-isolation-smoke.sh`** — NOT a
   fixture-output divergence (as first suspected) but an **ISOLATION / class-load regression**. Re-diagnosed
   and PINNED 2026-07-22 (opus). Both smokes assert the isolated native run loads no compiler/host class; the
   boundary smoke greps the `-verbose:class` log for `ssc.Reader` (a "textual CoreIR reader"). Under F-default
   an `ssc run --native` **class-loads `ssc.Reader`** (measured: `ssc.Reader$`, `ssc.Reader$P` + 23 parse
   lambdas from the compiler jar); legacy loads **zero**. So under the re-flip, both smokes go RED.
   **Root cause (measured):** the F runner tower (`ssc1-run-fsub.ssc0`) produces its structural result by
   round-tripping F's emitted Core IR **text** through the `#coreir.decode` prim, which is
   `IrToData.program(ssc.Reader.parseProgram(s))` (`v2/src/Runtime.scala:2999-3001`). The F runner uses
   `coreir.decode`; the legacy runner does not → legacy loads no Reader. The load happens on **every** F run
   (gap or not — verified on `examples/extensions.ssc`, a fallback program: Reader still loaded 24× during the
   F attempt before the fallback fires).
   **CORRECTION (2026-07-23): `RunNativeV2:101` `ssc.Reader.validate` was NOT a red herring — it is the
   SECOND Reader source.** The 2026-07-22 note tested each source in ISOLATION and, finding each alone
   insufficient, wrongly concluded validate was a red herring. The correct reading: **BOTH** load `ssc.Reader`,
   so both must go. MEASURED 2026-07-23: `#coreir.decode`-only removal → still 12× (via validate);
   validate-only removal → still ~24× (via decode); **both removed → 0×.** (The 2026-07-22 test replaced only
   validate and still saw 24-25× because decode still ran.) The boundary smoke's *static* javap check inspects
   the `RunNativeV2` forwarder class and does not see `validate` (which lives in `RunNativeV2$`), so it was
   already passing; only the RUNTIME `-verbose:class` check needed both removals.
   **⇒ RESOLVED via D2 (Sergiy's choice), landed 2026-07-23.** (D1/D3 below not taken.) D2 = (a) the F runner
   emits the `IrProg` Data directly through a self-hosted S-expr reader `irTextToData` (in the ssc0 runner
   `ssc1-run-fsub.ssc0`, dropping `#coreir.decode`); (b) `RunNativeV2:101` `Reader.validate` → Reader-free
   `validateNoReader` (faithful port, fallback unchanged). MEASURED zero `ssc.Reader` under `SSC_FRONT=F`
   (direct + fallback programs) == legacy; both isolation smokes PASS under F AND legacy; X1 fixpoint
   byte-identical (405,396 B — `fsub.ssc` untouched); semantic 248/248. See `specs/f-runner-structural-data-d2.md`.
   Original options considered:
   - **(D1) Scope the isolation guard.** The smoke's `ssc.Reader` guard was written to catch a *retired host /
     scalameta parser*; the kernel `ssc.Reader` used by `#coreir.decode` is a *bounded, validated KERNEL codec*
     (Runtime.scala comment), not a compiler frontend. Narrow the guard to the genuinely-forbidden classes
     (`scala.meta`, `NativeFrontmatter`, `scalascript.parser.Parser`, host Markdown) and ACCEPT the kernel
     Reader under F. Least-risk; but weakens the "native runtime loads no textual CoreIR reader" invariant.
   - **(D2) Make the F runner emit structural Data directly** (no text→decode round-trip), like legacy's
     `ssc1-lower`. Purest isolation; but a deep F-lane change (the F runner's output IS canonical IR text by
     design) — owned by the F lane.
   - **(D3) Make `#coreir.decode` not use `ssc.Reader`** (inline the decode in the kernel). Deep, risky —
     `ssc.Reader` is the "BOUNDED + VALIDATED" fail-closed codec; reimplementing risks that guarantee.
   Recommendation: **(D1)** as the proportionate near-term unblocker (the kernel Reader is a legitimate runtime
   component, not a frontend leak), with **(D2)** as the ideal if the F lane wants literal zero-Reader isolation.
   Independently, `RunNativeV2:101`'s `validate` can be swapped to the in-place `assertNoUnboundGlobal` scan as
   isolation hygiene whenever the tower path is addressed — behavior-verified, but pointless on its own.

**Why the corpus sweeps missed both:** the failing programs are e2e **fixtures** (`tests/fixtures/v21-native/*`),
not corpus `examples/*.ssc`, so the 528-program dual-run and the 248-golden semantic gate had nothing to
compare — a genuine OUT-OF-CORPUS gap, the SAME class as `f-int-literal-overflow-fails-open`. This is the
mission lesson made concrete: **a clean corpus dual-run is necessary but NOT sufficient for a default-front
cutover — the targeted e2e smokes must also be green under F-as-default.**

**Fix gate (F arc — do NOT edit fsub.ssc outside the owning F lane):** make `bin/ssc run` under F byte-identical
to `SSC_FRONT=legacy` on `tests/fixtures/v21-native/md-interpolator.ssc` and every `v21-native-*` /
`v21-self-hosted-*` smoke fixture; then re-run the full `tests/e2e/*smoke*` set under F-as-default (all green)
before re-attempting the flip. Start with md-interpolator (`parseInterp`/`interpChain` in fsub.ssc + how the
markdown-content block renders the interpolated pieces). Coordinate on `fsub.ssc` (shared with the F5b /
f-stmt-partial-function-block lanes). See `specs/v2-language-surface.md` §7.

## f-string-literal-pattern-not-data — F match/case-lambda rejects a string-literal pattern
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-22 by opus. F-front (`specs/v2.2-p6.5-fsub.ssc`) only. Affected BOTH a regular
`x match { case "s" => .. }` and a `f { case "s" => .. }` case-lambda.

**Fix:** the ordered resolver now handles STRING / FLOAT / BOOL literal arms alongside INT. New
`isScalarLitHead` + `litArmIR` + `parseGenLit`/`parseGenLitRest` (mirror `parseGenInt` — an ordered
`(if (__eq__ scrut <lit>) body rest)` using the same literal emitters as `litPatF`); `parseGenArm` routes
a scalar-literal arm to `parseGenLit`; and the two match-dispatch predicates (`parseMatchArms`,
`caseLamIsGen`) route a scalar-literal FIRST arm to `parseGenMatch` (previously only `fst==0` INT did).
Verified byte-equal to default: string/float/bool patterns in both regular match and case-lambda, a
realistic multi-string HTTP-method dispatch (`case "GET" => ..`), and int/ctor arms unchanged. Gates:
fsub `--self` byte-identical (403836 B; F's source uses no non-int literal arms → fixpoint neutral);
semantic 248/248; dualrun slice EQUAL.

**Repro:** `val v = "Foo"; v match { case "Foo" => println("lit"); case other => println(other) }` →
default prints `lit`; F errors `ssc: match: scrutinee not Data: "Foo"`. Int-literal patterns
(`case 1 =>`) work on both — only STRING (and likely other non-int scalar) literal patterns fail.

**Root cause (located):** `parseMatchArms` (`specs/v2.2-p6.5-fsub.ssc` :1006) routes an INT-literal first
arm to the ordered resolver via `fst(hd(tl(ts))) == 0`, but a STRING literal is token type `fst == 4`, so
it is NOT routed → falls through `parseArm` to `parseConsArm`, which emits a `(arm Cons 2 ..)` requiring a
Data scrutinee. **Fix gate:** extend the literal-pattern detection (the `fst == 0` check in `parseMatchArms`
and `parseGenArm`) to cover string/other scalar literal patterns, lowering them to an ordered
value-equality arm like the oracle. Verify the repro on F + fixpoint byte-identical + semantic unchanged.

## f-stmt-partial-function-block-dropped — F mishandles a `f { case … }` partial-function block arg
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 9898b4e67 -->

**Status:** FIXED 2026-07-22 by opus (`9898b4e67`). F-front (`specs/v2.2-p6.5-fsub.ssc`) only. Root
cause of the actors-pingpong / actors-typed-remote-spawn / auth-demo divergence on F.

**Fix:** `parseCaseLambda` (`fsub.ssc`) now routes through the SAME dispatch as a regular `x match { .. }`
(`parseMatchArms`): a bare-variable / typed / guarded / int / nested first arm goes to the ordered
resolver `parseGenMatch("(local 0)", ..)` (new `caseLamIsGen` / `parseCaseLamGen`); the pure ctor/tuple
form keeps the direct `(lam 1 (match (local 0) ..))` via `parseArms` (byte-unchanged → fixpoint neutral;
F's own source uses only ctor/tuple case-lambdas). Verified on self-contained repros of every
actor-relevant pattern (bare `case s`, typed `case s: String`, wild `case _`, ctor `case E.B(n)`, int
`case 1`) — F output byte-equal to default. Gates: fsub `--self` fixpoint byte-identical (386706 B);
semantic 248/248; dualrun slice 45/45 EQUAL. Direct actor-on-F run is gated on the (separate) drop-in
mechanism, but the exact bug construct is proven fixed. (String-literal patterns remain a SEPARATE
pre-existing gap — see f-string-literal-pattern-not-data.)

**Symptom:** a method call whose argument is a trailing PARTIAL-FUNCTION block — `f { case … => … }` —
is mis-lowered by F. In an actor, `receive { case … => <side effect> }` in statement position silently
skips the handler; a plain user fn (`handle { case s => println(..) }`) can crash the whole program (blank
output). Works when the result is bound (`val x = f { case … }`) or the block is a plain lambda
(`f { () => … }`). A paren-arg call in statement position (`sideEffect()`) also works.

**Minimal repro (no actors):**
```scalascript
def handle(f: String => Unit): Unit = f("v")
handle { case s => println("pf ran: " + s) }   // default: prints "pf ran: v"; F: prints NOTHING
println("after")
```
Narrowing (all via assembled `SSC_FRONT=F bin/ssc run`; default front correct in every case):
- T9c `sideEffect()` stmt-position → F OK (F does NOT drop stmt calls in general).
- T10 `applyIt { () => println(..) }` stmt-position (lambda block) → F OK.
- T11/`handle { case s => println(..) }` stmt-position (partial-function block) → **F FAILS**.
- T8/T9b `val _ = receive { case s => s }` (result bound, returns value) → F OK.
- T7 `receive(timeout=N){ case s => println(..) }` stmt-position → F FAILS (actor never prints).

**Why it matters:** actor code idiomatically calls `receive { case … => <side effect> }` in statement
position, so F silently skips message handling → `actors-pingpong` prints none of its pongs and the
`before timeout: Some(...)` delivery becomes `None`. Hidden today because such programs reference unbound
plugin globals and fall back to the default front; exposed by an F-drop-in probe that ran them directly on
F. A REAL F correctness gap, NOT a "deep effects gap."

**Located parser path (`specs/v2.2-p6.5-fsub.ssc`):** `parseAtom` (:383) → `parseAtom0/1` (:395) parse
the head ident, then `postfix` (:431) sees a following `{` (token type 2,28) → `postBlockArg` (:436) →
`blockArgApp` (:439). `blockArgApp(c,b)` = `if startsW(b,"(lam ") then (app c b) else (app c (lam 0 b))`.
Hypothesis: `parseBlock` on `{ case … }` does NOT return a partial-function `(lam 1 (match (local 0) …))`,
so `blockArgApp` wraps it as a 0-arg thunk → the callee gets a wrong-arity (0 vs 1) handler.

**Unreconciled nuance to resolve FIRST (do not fix blind):** `val _ = receive { case s => s }` (handler
RETURNS a value) runs correctly on F, but `val _ = handle { case s => println(..) }` (plain fn, handler
SIDE-EFFECTS) produced BLANK output. So the failure mode depends on context — is the trigger `{ case }`
arity, the handler-body type (Unit vs value), or the `postBlockArg` vs `postMethBlock` (:463, which uses a
different wrapper `blockArgOf` :464) path? Re-derive the exact trigger before editing.

**Fix gate:** make `f { case … }` lower identically to the default front in every position. Verify: the
T11 repro prints on F; fsub `--self` fixpoint byte-identical; semantic goldens unchanged; the dualrun full
sweep loses the actors-pingpong / actors-typed-remote-spawn / auth-demo residuals. Coordinate on
`fsub.ssc` (shared with v2-f5-kernel-shrink / v2-f4-flip lanes). See `specs/f-ambient-prelude-drop-in.md`.

## coroutine-error-conformance-null-proxy — native lane cannot validate the intended body failure
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: b8fd4a31c
     gate: tests/conformance/coroutine-error.ssc -->

**Status:** DONE (2026-07-21, `b8fd4a31c`; found and confirmed by codex-q4 in the real assembled
VM/direct-ASM paths; SPRINT Q4.3).

**Reproduce:** after installing the in-progress native Coroutine provider, both
`bin/ssc run tests/conformance/coroutine-error.ssc` and its `--bytecode` twin print `unexpected` for
the error arm. The test tries to induce failure with `val x: String = null; x.length.toString`, but
the native value semantics do not throw for that compatibility-only null dereference. A minimal
explicit `throw new RuntimeException("boom")` body produces `Errored(...)` on both native paths.

**Fix / verification:** the fixture now throws the portable `RuntimeException("boom")`, retains the
independent `case Errored(_)` observable, and opts into native v2 beside historical INT/JS. Fresh
focused conformance compares every selected lane with one expected output and passes; the dedicated
lifecycle case additionally passes direct ASM while printing the separate v1 JVM codegen known-red.

## f-imported-caseclass-default-arg-synth — F front missed defaulted params on OBJECT-METHOD calls
<!-- status: fixed
     lane: native
     area: front
     fixed-in: b1b72415e -->

**Status:** FIXED (2026-07-21, `b1b72415e`; f-caseclass-default-arg). ROOT CAUSE RE-PINNED by live
trace — the miss is NOT about *imported case-class ctors*: those synthesize correctly on F, local and
imported alike (`Box("hi")`, `Builder()` verified). The real gap was **object-method calls**.
`object O { def m(x, y = d) }` lowers `m` to a top-level `(def O_m ..)` (objDefE) and the call `O.m(x)`
to `(app (global O_m) ..)` via `emitMethodCall`'s isObjMember branch — which emitted the plain app with
NO default synthesis, so an under-applied `O.m(x)` hit `arity: 2 expected, 1 given` at run time. The
plain-def and case-class-ctor call paths already synthesized (dfltGo/synthCall); the object-method path
did not, and funcDflts had no entry keyed to the mangled `O_m`. (mcp-types' actual failing call was
`Resource.text("some text")` → `def text(s, mimeType = "text/plain")` in `object Resource`, NOT
`ToolResult`; `Tool.text` — which internally calls `ToolResult(content)` — already worked.)

**Fix (specs/v2.2-p6.5-fsub.ssc, byte-identical to the oracle):**
- `collectObjDflts` registers each `object` def member's default entry under the MANGLED name `O_m`,
  reading each member's OWN param list so same-named methods across objects never share an entry
  (`Tool.text/1` no-default vs `Resource.text/2` defaulted). Merged into funcDflts in `mkCxE`. Mirrors
  ssc1-lower `aliasFuncDefault` :4196 (regular `object` only; given_obj prefixDefs :4268 does not alias).
- `postMeth` intercepts an applied object-member call: when `O_m` has a default entry and the call is
  under-applied (no named args), it reuses scanArgs/dfltGo/synthCall to emit the nested-lambda default
  fill keyed to `(global O_m)` — byte-identical to a plain-def call under the same defaults.

**Verified GREEN:** objmeth/local/imported repros; `Resource.text` in mcp-types now prints "some text";
FIXPOINT (fsub.sh --self) stage1==stage2 byte-identical (369 543 B); fsub corpus 0 FAIL; semantic.sh
246/246 goldens; dualrun 44/45 EQUAL (1 pre-existing expected GAP), 0 unexpected.

**Two DISTINCT pre-existing bugs UNMASKED by this fix** (separate follow-ups — NOT default-args): see
`f-object-method-varargs` and `f-operator-extension-dispatch` below.

## f-object-method-varargs — F has no vararg collapse for object methods
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/mcp-types.ssc -->

**Status:** FIXED 029a454b8 (2026-07-21). F-lane only (found 2026-07-21 by f-caseclass-default-arg;
unmasked once the object-method default-arg fix let mcp-types run past `Resource.text`). Distinct from
default-args.

`object O { def m(xs: T*) }` — F emits `O_m` as an arity-1 def but lowers the call `O.m(a, b)` to
`(app (global O_m) a b)` (2 args) → `arity: 1 expected, N given`. F had no vararg machinery at all; the
oracle collapses trailing args into a list via `varargDefsCell` / `lastParamVarargCell`
(ssc1-front :577–586). Minimal repro: `object P: def many(xs: Int*): Int = xs.toList.size` +
`P.many(1,2,3)` → F `ssc: arity: 1 expected, 3 given`, oracle `3`. Blocked `tests/conformance/mcp-types.ssc`
(`Prompt.messages(m1, m2)`).

**Fix (mirrors collectObjDflts/postMeth, commit 46626844a):** new `collectObjVarargs` registry maps each
`object O { def m(.., xs: T*) }` (LAST param a token-level trailing `*` — opcode 25 — since `typeText`
erases the star) to `O_m → lead = paramCount-1`, held in its own cx slot (`objVarargsOf`, nested in the
extMethods payload alongside `mangleExt`). `postMethV` intercepts the object-method call path AFTER
`parseArgL`, registered-vararg-ONLY, emitting `(app (global O_m) <takeL(lead) items> <consChain(dropL(lead))>)`
byte-identical to the oracle; every non-vararg call is unchanged. Added a tower golden `tower__obj_varargs`.
Gates: fsub `--self` fixpoint byte-identical (obj_varargs IR 7524B==ref), semantic 247/247, dualrun 0
unexpected (mcp-types stays a manifest GAP). Verified on the typed-IR + dispatcher base after rebase.

**Follow-up unmasked (separate, not vararg): FIXED 886df94fe — see `f-enum-case-default-arg-qualified`.**
mcp-types ran past the vararg call but still diverged — `Transport.Http(8080)` matched by
`case Transport.Http(p, _)` printed `?` under F vs `http:8080` under the default front (an enum-case
default-arg gap on the QUALIFIED ctor path). This was the LAST v2-F4 dualrun residual; now closed.

## f-operator-extension-dispatch — F treats `++`/`/` extension operators as builtin infix ops
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 5b9940422 -->

**Status:** FIXED 2026-07-21 (dsl-ast-builder crash resolved; F now byte-identical to the default front).
F-lane only. The BUGS.md title was a MIS-DIAGNOSIS — see root cause below.

**Actual root cause (not operator dispatch):** F's `dsl-ast-builder` crash (`arity: 2 expected, 1 given`)
was an EXTENSION-BODY ABSORPTION bug, not the operator. `std/dsl/pretty.ssc` writes a braceless top-level
`extension (l: Doc)` ⏎ `def ++` / `def /`, and the pretty block is FOLLOWED by more top-level `def`s
(`renderDoc`, `render`). F's layout DEFERRED the oracle's extension frames, so the members were flattened
to `; def ++ ; def / ; def renderDoc …` with no block boundary — and `extMembers`/`collectEM` greedily
absorbed EVERY contiguous following `def` as another receiver-bearing member, prepending the `Doc`
receiver. `render` (1 param) thus became `lam2`, and the call `render(doc)` (1 arg) crashed with
`arity: 2 expected, 1 given`. (Same shape made `main` → `lam1` in single-file repros.)

**Fix (specs/v2.2-p6.5-fsub.ssc, commit `5b9940422`; mirrors ssc1-front layout E/X frames):** thread an
`eh` extension-head flag through the layout — a TOP-LEVEL `extension` ident pushes an `X` receiver frame
whose closing `)` opens an `L` block, wrapping the members in a virtual `{ … }`; `extMembers`/`collectEM`/
`collectTopEM` consume the `{` (`extOpenBrace`) and STOP at the `}`. A dedented top-level `def` is no
longer swallowed. Inert unless a real `extension` token appears at top level (fsub has none →
self-compile byte-identical). **Verified:** fixpoint stage1==stage2 byte-identical; semantic 247/247;
multi-file dsl-ast-builder now rc=0 with output BYTE-IDENTICAL to the oracle; `std/monaderror` absorption
also fixed (`attemptEither`/`handleEither`/`raiseEither` now match oracle arity); uuid/streams/actors/
script goldens neutral or unchanged. dsl-ast-builder GAP removed from `v2.2-p6.5-dualrun.expected`.

**Separate, still-open (SHARED front limitation, NOT F-specific — out of this fix's scope):** BOTH the
default front (ssc1-lower :2568/2558) and F lower `++`/`/` to `__arith__`, which at run-ir string-concats
user ADTs (`Doc(a)Doc(b)`) / returns Unit — it never dispatches to the `++`/`/` extension global. So the
Doc pretty-render is silently empty on BOTH fronts (dsl-ast-builder's `renderDoc` `case _ => ""` swallows
it → they still MATCH). The real dispatch fix is to guard `++`/`/` with `isExtensionMethod`→`__arithExt__`
(op, l, r, `(global op)`) — the shape `|` already uses (ssc1-lower :2582; `__arithExt__` correctly
dispatches non-numeric operands at Runtime.scala:2734). That must land in BOTH ssc1-lower and fsub to keep
F==default; filed as a follow-up (not required for the dualrun GAP, which is now EQUAL).

## f-litdoc-runtime-error-2 — litdoc fails `ssc: 2` on the F native front only
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-21, `f-litdoc`). Root cause: single-argument `String.substring(from)`.
F's `emitPrimMeth` (specs/v2.2-p6.5-fsub.ssc) emitted `(prim sslice recv from)` — only 2 args — for a
1-arg substring, dropping the end index. The runtime `sslice` prim reads `args(2)`
(`Runtime.scala:2635 → Prims.int:4444`), so F's IR crashed at runtime with `IndexOutOfBoundsException: 2`,
surfacing as `ssc: 2`. Scala's `s.substring(from) == s.substring(from, s.length)`; the reference
(ssc1-lower.ssc0:1434-1437) appends `(prim slen robj)` as the end. Fix: when a substring has exactly one
arg, `emitPrimMeth` now emits `(prim sslice recv from (prim slen recv))`; 2-arg substring is unchanged.
`std/litdoc.ssc` hit this in `leadingHashes` (`t.substring(1)`), `takeFence` (`.substring(3)`), and
`classifyLine` (`t.substring(lvl)`).

**Repro (bare, single-file):** `def main(): Int =` `\n  println("hello".substring(1))` `\n  0` — under the
original F the emitted IR crashes with `IndexOutOfBoundsException: 2`; after the fix F's IR is
byte-identical to the reference front's and prints `ello`.

**Verification:** X1 self-fixpoint byte-identical (154 ok / 0 FAIL); semantic gate 247/247 MATCH, 0
MISMATCH (no golden regression); the real flattened litdoc (std/litdoc.ssc defs + the conformance test)
runs under F with stdout BYTE-IDENTICAL to the reference (401 B, `kinds: front,heading,...`). The prior
default lanes (INT/JS/JVM) were already green. Note: F's IR still diverges from the untyped oracle on the
benign typed `+`/`++` string-concat op (F5b regime, by design) — output-equivalent, not byte-identical.

## v2-native-receive-bare-var-catchall — `receive { case msg => … }` misses on the native front
<!-- status: fixed
     lane: native
     area: front
     fixed-in: ede7efaa0 -->

**Status:** FIXED 2026-07-20 by `ci-native-front-defects` (`ede7efaa0`). `V2ActorCliTest`
"default and --v2 deliver sendAfter actor timers" verified green.

**Documented hypothesis — HELD; precise mechanism pinned down (verified by narrowing):** the
`receive { case msg => … }` block lowers to an effect-**handler** whose bare-var (named) catch-all
case did not register — confirmed. The exact site: a partial-function literal lowers via
`ssc1-lower.ssc0` `lowerHandlerMatch`. A NAMED catch-all (`case msg`) is flagged by `hasNamedCatchAll`
and takes the `general` path, which appends a synthetic `__handler_dispatch_fallback__` vpat whose
body is `miss`. `lowerMatch`'s `procArms` lets the LAST vpat win as the match default, so the
fallback's `miss` OVERWROTE the user's real catch-all body → the handler always missed. A WILDCARD
catch-all (`case _`) took the `lowerDirectHandlerMatch` path (no fallback) and worked — which is what
masked it. Narrowed minimal repro (native): `val f = (m) => m match { case msg => … }; f("hello")`
misses; `case _ =>` works; `case "x" => … case msg => …` works.

**Fix:** in `lowerHandlerMatch`, skip the miss-fallback whenever the arms already contain an
irrefutable catch-all (vpat OR wpat) — a catch-all handles the whole surface, so `miss` is
unreachable. Aligns named-catch-all handlers with the already-correct wildcard behaviour. Touches the
self-hosted tower (`v2/lib/ssc1-lower.ssc0`); the ssc0c self-compilation fixpoint is unaffected
(ssc0c does not import `ssc1-lower`) and conformance stays green.

---
### Original report (kept for context)

Surfaced by `V2ActorCliTest` → "default and --v2 deliver sendAfter actor timers" after the
`sendAfter` binding was fixed (`3964db7eb`).

**Symptom.** A bare, untyped catch-all pattern in a `receive` handler block fails:
```scalascript
runActors { val me = spawn { () =>
  val pid = self()
  pid ! "hello"
  receive { case msg => println("got: " + msg) }   // Actors scope failed: match: no matching case
}}
```
The trace goes through `Runtime.handlerDispatchMiss` / `HandlerDispatchShape.MissPrimitive` /
`__match_fail_prim__` — the `receive { … }` block is lowered as an effect-**handler** whose wildcard
(bare-var) case is not registered, so a delivered message finds no matching handler.

**Isolation (what works vs not) — the bug is specific to bare-var catch-alls in receive blocks:**
- `receive { case "hello" => … }` (literal)          → WORKS
- `receive { case msg: String => … }` (typed binder) → WORKS
- `val r = x match { case msg => … }` (plain match)   → WORKS
- `receive { case msg => … }` (bare untyped catch-all) → FAILS (dispatch miss)

**Not the sendAfter bug.** `sendAfter` itself is fixed and verified: `sendAfter(10,pid,"hello")` +
`receive { case "hello" => … }` prints `got hello`. This is a separate native-front lowering defect
in how `{ case <bareVar> => … }` handler blocks register their wildcard case (cf. the historical
"ssc0 match no bare-var binder" note). **Done-when:** a bare-var catch-all in a `receive` block
delivers the message; `V2ActorCliTest` goes green.

## ssc0c-string-escape-divergence — self-hosted lexer preserves escapes that the Scala seed decodes
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 3056aa3b8
     confirmed: no -->

**Status:** FIXED 2026-07-19 by `v2-f7-internal-gate` (`3056aa3b8`; awaiting full F7 exact-SHA CI).
Found while aligning the exact compiler-output line terminator on the working tree based on `a985605e2`.

**Real-harness repro:** append an LF with the source literal `"\n"` in both self-hosted compiler drivers,
then build and execute the compiler with the assembled v2 jar:

```bash
java -jar "$JAR" compile examples/ssc0c-self.ssc0 > /tmp/ssc0c-gen1.coreir
java -Xss512m -jar "$JAR" run-ir /tmp/ssc0c-gen1.coreir examples/ssc0c-self.ssc0 \
  > /tmp/ssc0c-gen2.coreir
cmp -l /tmp/ssc0c-gen1.coreir /tmp/ssc0c-gen2.coreir
```

The seed is 21050 bytes and lowers the literal as `(str "\n")` (one LF code unit); the self-hosted result
is 21051 bytes and lowers it as `(str "\\n")` (backslash plus `n`). The multi-file compiler fixpoint has
the same one-byte expansion, 25875 versus 25876. Root cause is `scanStr` in both
`v2/lib/ssc0c.ssc0` and its self-contained copy: it copies every source code unit until `"`, whereas
`v2/src/Ssc0.scala::lexString` decodes `\"`, `\\`, `\n`, `\r`, `\t`, and `\uXXXX`.

**Plan / done-when:** make the self-hosted scanner decode the seed's valid escape set, cover an escaped LF
inside the persistent two-file differential, and require both exact fixpoints. Silently constructing the
CLI newline by another spelling would hide the frontend divergence and is not an acceptable closure.

**Resolution:** both self-hosted scanner copies now decode `\"`, `\\`, `\n`, `\r`, `\t`, and `\uXXXX`
into code units exactly like the Scala seed. The imported regression contains all six forms and executes to
42 after compiling to byte-identical 259-byte Core IR. Single/multi exact fixpoints are restored at
22844/22844 and 27669/27669 bytes.

## f5-buildjvm-artifact-missing-relocated-jars — `build-jvm` jars NoClassDefFoundError after F5 kernel-slimming
<!-- status: fixed
     lane: v2-jvm
     area: codegen
     fixed-in: 0681d1f08
     gate: tests/e2e/v21-build-jvm-release-gate.sh -->

**Status:** FIXED 2026-07-19 (`v2-f5-buildjvm-fix`, commit fixing `NativeJvmArtifact.scala`). Found by the
coordinator on CI run `0681d1f08` (the `ScalaScript 2.1 compiler-free ASM artifact release gate` went red).

**Symptom/reproduce** — a `ssc build-jvm`-produced standalone jar threw at runtime:

```
bash tests/e2e/v21-build-jvm-release-gate.sh   # -> FAILED check 'app'
java.lang.NoClassDefFoundError: ssc/Emit$
  at ssc.plugin.NativeArtifactRuntime$.initialize(NativeArtifactRuntime.scala:21)
Caused by: java.lang.ClassNotFoundException: ssc.Emit$
```

**Root cause.** F5 relocated `ssc.Emit`→`scalascript-v2-jvm-runtime` and `ssc.NativeUiSites`→
`scalascript-v2-nativeui` out of `scalascript-v2-core`. Three INDEPENDENT jar allowlists gate relocated
classes; F5 fixed two (`cli`.dependsOn + root aggregate; `installBin`'s `standardJarPrefixes`) but missed
the third: `NativeJvmArtifact.{RuntimePrefixes,RequiredPrefixes}`, which bundle jars INTO the produced
standalone artifact. It still listed only `v2-core`, so the artifact couldn't find `ssc.Emit` (linked by
`NativeArtifactRuntime`) — nor `ssc.NativeUiSites` (linked by `UiNativePlugin.install` via `loadAll`, the
next failure once Emit is fixed). `bin/ssc run`/`--bytecode` green does NOT cover the produced-artifact path.

**Fix.** Added `scalascript-v2-jvm-runtime_` + `scalascript-v2-nativeui_` to both `RuntimePrefixes`
(bundle) and `RequiredPrefixes` (fail-fast). Gate `tests/e2e/v21-build-jvm-release-gate.sh` red→green
(PASS, `forbidden.references=0`). Lesson recorded in `SPRINT.md` F5 (the "SECOND STAGING LIST" note).

## scljet-jdbc-facade-bytecode-class-too-large — the JDBC-facade examples overflow the JVM class-size limit on the bytecode lane
<!-- status: open
     lane: native
     area: codegen
     kind: perf -->

**Status:** OPEN as a capacity gap — but **it stopped being invisible 2026-07-27** (opus,
`v2-bytecode-lane-silent-downgrade`, `af4725ed9`). The v2 JVM bytecode backend still emits one
monolithic `ssc/gen/Entry` and still cannot compile these programs; splitting it is v2 kernel work.

**What changed, and why it mattered more than the gap itself.** The symptom this entry describes
(`rc=1`, `Class too large`) had already vanished — not because the gap was fixed, but because a
link-time fallback in `RunNativeV2.runBytecode` now silently runs the VM instead. The fallback is
correct (nothing has executed yet, so there is no double side effect); its SILENCE was not.
`scripts/bc-parity-sweep` diffs `run` against `run --bytecode`, so a fallback made it compare the VM
against ITSELF and record `identical` — certified bytecode/VM parity for programs the bytecode
backend never compiled.

**A/B on one launcher:** the sweep at the previous `origin/main` reported `scljet-hello.ssc identical
0 0`; with the fix it reports `skipped-bytecode-fallback` with `MethodTooLargeException` as the
reason — note MethodTooLarge today, where this entry recorded ClassTooLarge.

**Full sweep after the fix: 214 cases, mismatch 0, bytecode-error 0.** THREE rows left `identical`
for `skipped-bytecode-fallback`: `scljet-hello.ssc`, `scljet-jdbc.ssc`, and
**`scljet-unique-index.ssc`** — the third is not named anywhere in this entry. The silent fallback
had already swallowed an example nobody knew about, which is exactly how a fail-open default drifts.

The runner now prints a stable marker on stderr (never stdout, so output comparisons are unaffected)
and `tests/e2e/bytecode-fallback-visible.sh` asserts BOTH directions — the oversized example must
announce, a small program that really compiles must not — because a one-sided test would pass just as
well if the marker were printed unconditionally, sweeping every case into the skip bucket.

**Historical status:** OPEN — backend capacity gap, not a correctness bug (found 2026-07-19 by `ci-negtc-gate`,
the last red `sbt — compile and test` step). **v2 JVM bytecode backend**, not the engine or the examples.

**Symptom/reproduce** — `examples/scljet-hello.ssc` and `examples/scljet-jdbc.ssc` run correctly on the
VM lane but abort on the direct-bytecode lane:

```
bin/ssc run           examples/scljet-hello.ssc   # -> ok (correct output, rc=0)
bin/ssc run --bytecode examples/scljet-hello.ssc  # -> ssc: Class too large: ssc/gen/Entry  (rc=1)
bin/ssc run --bytecode examples/scljet-jdbc.ssc   # -> ssc: Class too large: ssc/gen/Entry  (rc=1)
```

**Root cause.** The v2 JVM bytecode backend emits ONE monolithic `ssc/gen/Entry` class. These two are
the only examples that inline the whole scljet pure-.ssc SQLite engine PLUS the JDBC facade
(`scljet/jdbc.ssc`); the combined generated class exceeds the JVM `.class` size limit and ClassWriter
aborts. The seven other non-delegated scljet examples (crud/full/write-table/bytes/memory-vfs/
readonly-codecs/write-empty) all compile fine on `--bytecode` — the JDBC facade is what tips it over.
The output is CORRECT wherever it compiles (VM lane), so this is a codegen capacity gap, not a mismatch.

**Impact / fix direction.** In the negative-toolchain release gate, `bc-parity-sweep --strict` counted
these as `bytecode-error` and exited 1. Since the VM output is correct and there is simply no bytecode
output to compare, `scripts/bc-parity-sweep` now classifies them as a non-fatal `skipped-oversized-bytecode`.
As of `ci-negtc-robust` (2026-07-19) this is **auto-detected**, not a hardcoded allow-list: when the VM
lane succeeds (`vm_rc=0`) but the bytecode lane fails with `Class too large`/`Method too large` in its
stderr, the row is a capacity skip. VM success is the ground truth, so this masks nothing — a bytecode
failure for ANY OTHER reason, or one where the VM ALSO failed, still surfaces as a real error. New large
programs need no per-example churn. The real fix is splitting the monolithic `ssc/gen/Entry` across
multiple classes/methods in the v2 bytecode backend (v2 kernel work, out of scope for the gate).

**Related — `scljet-file.ssc` (added later).** A THIRD scljet example, but a different class: it is a
**tools-tier** example (`#!/usr/bin/env ssc-tools run --v1`, `backends: [int]`) that needs the JVM VFS
host (`jvmVfs*`), an external `sqlite3` CLI, and process `exec` — none available in the standard-only
negative env, so BOTH lanes fail there (VM: `match: no matching case` on the native tier; bytecode:
`Class too large`). It runs correctly on its declared lane (`bin/ssc-tools run --v1 examples/scljet-file.ssc`
→ SclJet writes, sqlite3 verifies + writes). `bc-parity-sweep` now auto-skips any example whose shebang is
`ssc-tools run --v1` as `skipped-tools-tier` (its delegated siblings `scljet-jvm-vfs`/`scljet-readonly` are
caught by the explicit-lane manifest first). Separately, its native-lane `match: no matching case` on
`ProcessResult` destructuring is a minor v2-native gap (the example correctly declares the v1 lane; not
tracked here).

## p65-fsub-toplevel-val-infinite-loop — F (P6.5 subset compiler) hangs on a top-level `val`
<!-- status: unknown
     lane: native
     area: front -->

**Status:** **STALE — CLOSED 2026-07-27 by opus (`p65-toplevel-val-stale`) on measurement, not on a
fix of mine.** F gained top-level statement support in a later F3 breadth slice and the hang is gone;
the entry had been carrying a claim that was no longer true, and it was being cited as the reason
216+/504 corpus programs TIMEOUT — so leaving it open was actively misleading planning.

**Re-measured today, on the same repro this entry specifies:**

| program | before (per this entry) | now |
|---|---|---|
| `val a = 10` + `def main(): Int = a` | TIMEOUT (infinite loop) | lowers, runs, prints **10** |
| `val xs = [1,2,3]` + `xs.length` | — | lowers, runs, prints **3** |
| `println(1)` + `def main` | dropped the expr | runs, prints **2** |

And the corpus-level consequence this entry predicted is gone too: the F corpus sweep measured
**TIMEOUT 0** (MATCH 205, DIFF 315, EMPTY 0) on 2026-07-27, against the 216+/504 recorded here.

Nothing was changed to close this — it is closed because the claim stopped being true, which is the
only honest reason to close a bug nobody fixed. The `emitDefs` non-progress analysis below stays as
the record of what the defect WAS.

**Historical report:** OPEN (found 2026-07-18 by `v2-p65-canonical` while building the F3 real-corpus gate
`specs/v2.2-p6.5-corpus.sh`). **`specs/v2.2-p6.5-fsub.ssc`** (F, the P6.5-subset front), not the
kernel. This is why 216+/504 corpus programs TIMEOUT rather than DIFF.

**Symptom/reproduce** — F's `compile` never returns on a program whose first top-level statement is a
`val` (not `def`/`case class`):

```
printf 'val a = 10\ndef main(): Int = a\n' > /tmp/t.ssc
gtimeout 8 java -Xss512m -jar <run-ir kernel jar> run-ir <F0.ir> /tmp/t.ssc   # -> TIMEOUT (loops)
printf 'def main(): Int = 1\n'            > /tmp/t.ssc  # -> ok
printf 'println(1)\n'                     > /tmp/t.ssc  # -> ok (drops the expr, no loop)
```

**Root cause (localised).** `emitDefs`/`emitDecl` assume every top-level item is `def` or
`case class`. On a leading `val`, `emitDef` mis-parses and returns a `rest` token list that does not
shrink, so `emitDefs(rest)` recurses forever. Only top-level `val` triggers it (top-level `expr` is
silently dropped, no loop). F was designed for a def/case-class-only top level; the real corpus is
full of top-level `val`/expr.

**Impact / fix direction.** Untrustworthy-if-unbounded: a single such program hung the whole corpus
gate (measured: stalled at 13/504 forever), so `specs/v2.2-p6.5-corpus.sh` bounds every file with a
timeout and buckets these as TIMEOUT. The real fix is **top-level statement support** in F
(reproduce ssc1-lower `lowerProg` topExprs/topValCellDefs, `v2/lib/ssc1-lower.ssc0`:5560-5647) —
tracked as the highest-impact F3 breadth slice in SPRINT §v2-finish. A cheap interim guard (make
`emitDefs` bail on non-progress) would stop the hang but emit semantically-broken IR; deferred in
favour of the real top-level-statement slice.

## v2-native-front-rejects-jdbc-facade — `scljet/jdbc.ssc` fails to parse on the native front
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded -->

**Status:** **FIXED (2026-07-18, `native-front-run-gaps`)** — the root cause was the `while (paren) do`
parse bug below; fixing the `while`-condition parse in `ssc1-front.ssc0` unblocked the whole façade.
`examples/scljet-jdbc.ssc` now runs on `bin/ssc run` byte-identical to v1, and its `[int, js]` scope
was dropped. Original triage kept below. Found 2026-07-17 by `scljet-jdbc-durability` while writing the missing
`examples/scljet-jdbc.ssc`). **v2 native front**, not the façade or the engine. Pre-existing: the
façade's conformance is `[int, js]`, so the native lane was never exercised.

**Symptom/reproduce** — importing the façade at all is enough:

```
[jdbcOpen](std/scljet/jdbc.ssc)   → bin/ssc run: "native frontend rejected incomplete parse …
                                     structural CoreIR contains parser sentinel _err"
[ByteSlice](std/scljet/index.ssc) → ok
[queryImageParams, …](std/scljet/sql.ssc) → ok
```

**ROOT CAUSE FOUND (2026-07-18, `native-front-run-gaps`) — a `while` whose condition starts with a
parenthesised expression is mis-parsed.** Bisected `jdbc.ssc` to `parseDoubleStr` line 14:

```scala
while (codeAt(s, i) >= 48 && codeAt(s, i) <= 57) && !fdone do   // → parse "_err" on native
while  codeAt(s, i) >= 48 && codeAt(s, i) <= 57  && !fdone do   // works (no leading paren)
```

Minimal repro (no scljet at all):

```scala
var i = 0; var d = false
while (i >= 0 && i < 3) && !d do        // native: "native frontend rejected … sentinel _err"
  i = i + 1; if i > 2 then d = true     // v1: fine
```

So this is a general native-front PARSER bug: `while ( … ) … do` — a parenthesised sub-expression at
the START of a `while` condition — is not parsed. It just happens to surface first through the JDBC
façade. Fix belongs in `v2/lib/ssc1-front.ssc0` (the `while`-condition parse). The façade needs no
change; when the parser is fixed, drop the `[int, js]` scope on `examples/scljet-jdbc.ssc`.

**Impact.** The portable JDBC façade cannot run on `bin/ssc run` (the default command); it works on
`int`/`js` (6/6 conformance) and via the JVM shim (56/56). `examples/scljet-jdbc.ssc` is therefore
scoped `backends: [int, js]` until the parser fix lands.

## v2-native-min64-literal-prints-0 — `println(-9223372036854775808)` gives `0`, silently
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 5b71ad2f6 -->

**Status:** FIXED `5b71ad2f6` (`int-literal-failopen`). Found 2026-07-17 by
`int-width-conformance`. Original SHA `cb35fffa6`, real harness (`bin/ssc run`, jar from
`scala-cli --power package v2/src --assembly`).

**FIX / confirmed root cause.** The self-hosted tower's `parseI` (`v2/lib/ssc1-lower.ssc0`)
defaulted an overflowing `#str->i` (`toLongOption` → None) to `0`, and unary `-` is lowered as an
eval-time `0 - x` (not folded), so `-9223372036854775808` = `0 - parseI("9223372036854775808")` =
`0 - 0` = `0`. **Also found and fixed the same fail-open for any bare literal past Int64** (e.g.
`99999999999999999999999999` → `0`). Fix: `lowerIntLit` fails closed via the `_err_int_range`
sentinel on overflow; the `pre -` case folds the sign (`#str->i("-" ++ digits)`) so the signed
min64 string parses directly to `Long.MinValue` (an `IntV`, not a BigInt). `RunNativeV2` surfaces
the sentinel with a clear "integer literal out of range for Int (64-bit)…" message. Gated to
overflow/min64 literals only — the **P6.5 self-compile fixpoint is byte-identical before and after**
(stage1==stage2, 79,667 B), verified with `specs/v2.2-p6.5-fsub.sh --self`. Pre-existing adjacent
gap (NOT this bug, noted for follow-up): v2 `BigInt("…")` past Int64 still errors `i->big: not Int`,
so v2 cannot yet build a BigInt larger than Int64 by any spelling.

**Repro** (v2 native):

```text
println(-9223372036854775808)      -> 0                      WRONG, fails open
println(-9223372036854775807 - 1)  -> -9223372036854775808    OK
println(9223372036854775807)       -> 9223372036854775807     OK
```

`-9223372036854775808` is `min64` — a legal `Int` per `specs/numeric-widths.md` §2. It is written as
unary `-` applied to `9223372036854775808`, which is one past `max64`, so the positive literal
overflows before the negation is applied. v2 yields `0`; the v1 interpreter at least **fails closed**
here ("Cannot apply unary - to 9223372036854775808"). Neither is correct: the value is representable
and must print. Scala 3 special-cases exactly this literal.

**Impact.** Silent wrong answer at a value the spec declares legal, on a lane
`specs/numeric-widths.md` §4 currently lists as **conforming**. Lower frequency than the entry above
(min64 literals are rare) but the same fail-open class.

## v2-tuple-pattern-cli-tests-bypass-staged-distribution — four tests abort on unset library path
<!-- status: fixed
     lane: native
     area: front
     fixed-in: e9567c555
     confirmed: no -->

**Status:** FIXED (2026-07-17, `e9567c555`; awaiting CI confirmation). Found by `ci-red-main` in
completed Linux run `29544412767`, SHA `73407430457effd61bb96307c4bb41c6d3df3179`, job
`87773372863`. Four `V2TuplePatternCliTest` cases failed before evaluating tuple semantics: typed
tuple patterns, nested tuple patterns, tuple val destructuring, and map-reduce worker calls.

**Real-harness repro.** In the GitHub sbt log, every case reports `native frontend requires a
staged installation (ssc.lib.path is unset); run scripts/sbtc "installBin" and use bin/ssc`.
Re-run the same suite locally with `scripts/sbtc "cli/testOnly
scalascript.cli.V2TuplePatternCliTest"` after staging current bits. The repeated prerequisite error
is the observable; do not classify the tuple implementation from tests that never reached it.

**Expected/fix plan.** Inspect the suite's process helper and make it invoke the real staged native
launcher (`bin/ssc`) or a shared helper with the identical `ssc.lib.path` contract. Do not set a
test-only semantic bypass, weaken the tuple assertions, or edit the live `v2-native-stack-overflow`
claim's `v2/src` scope. Done means all four cases execute and pass against the staged distribution,
with stdout, stderr, and exit code still reported on mismatch.

**Fix/result.** The suite now locates and invokes installed `bin/ssc`, whose launcher supplies the
real library root, rather than directly invoking the fat jar. Three tuple scenarios pass unchanged.
The fourth exposed the separately tracked Scala-style import no-op; its map-reduce assertions remain
unchanged while the fixture now declares dependencies with supported outside-fence Markdown module
links. The complete suite is 4/4 on rebased current bits; focused `tuples` passes INT/JS/JVM and
`distributed-map` passes its JVM lane.

## standalone-install-fixture-stale-java-command — test rejects the release launcher's stack flag
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 5bde29d37
     confirmed: no -->

**Status:** FIXED (2026-07-17, `5bde29d37`; awaiting CI confirmation). Confirmed by `ci-red-main`
with the focused current-source suite. `StandaloneInstallFixturesTest` failed 1/2 because it required
the literal adjacent substring `exec java -jar`; `releases/install.sh` emitted
`exec java -Xss64m -jar ...`.

**Real-harness repro.** Run `scripts/sbtc "cli/testOnly
scalascript.cli.StandaloneInstallFixturesTest"`. The failure prints the complete release installer
and the missing stale substring. This is the unclaimed CLI suite predicted in the old full-test tail.

**Expected/fix plan.** Preserve the 64 MB safe default but make it caller-overridable through
`SSC_XSS`, matching the staged launchers and avoiding another hardcoded command-line override. Pin
the actual contract (`exec java`, `SSC_XSS:-64m`, and `-jar <installed jar>`) instead of requiring
two tokens to remain adjacent. Re-run 2/2 and an affected conformance slice.

**Fix/result.** The generated standalone launcher now uses `-Xss"${SSC_XSS:-64m}"`. The Scala
fixture pins the source contract without adjacency assumptions, and a CI-wired shell e2e runs the
real release installer behind a fake downloader/java: it proves the generated source, default
`-Xss64m`, override `-Xss256k`, jar path, and user argv. Fixture 2/2 and shell e2e pass.

## v2-native-multiblock-auto-output-missing — standard native lane drops per-block non-Unit results
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 354807e64 -->

**Status:** **FIXED 2026-07-28** by `v2-auto-output-prim` (`354807e64`), on the second attempt —
detail below. Originally found 2026-07-17 by `ci-red-main` after correcting the all-examples
tools-command routing; that report is preserved further down, in the past tense.

**FIXED — on the SECOND attempt.** Each top-level code block's
last non-Unit expression prints again, in source order. The runner marks each code fence's end with
a `__sscBlockEnd__` sentinel; F consumes it in `walkTop`, where the item in front of it has just
been parsed, and wraps that item's entry expression in an `__autoOutput__` **primitive**; the VM/ASM
runtime and the v2 JS codegen each implement that primitive. Wrapping EVERY block including the last
is deliberate — the program's own final value then becomes Unit, so `V2Result.report` goes quiet and
there is no double-print and no last-block special case.

**The first attempt is the useful part of this entry, so it stays recorded.** It shipped the same
sentinel + `walkTop` machinery — which was correct, and whose F self-fixpoint held — but put the
Unit test in a helper written in `.ssc`. That does not work, and the reason is worth keeping:

| Unit test in `.ssc` source | native (VM/ASM) | JS/v2 codegen |
|---|---|---|
| `case _: Unit =>` | matches — correct | **does not match** |
| `case () =>` | **does not match** | **crashes**: `TypeError: Cannot read properties of null (reading 't')` |

So on JS/v2 the helper fell through to `println` and emitted a bare `()`. The full corpus caught it
as `deep-tail-recursion FAIL [JS/v2] line 4: expected=<missing> got=()` — a lane the author had not
probed by hand. **Unit-ness is a runtime property AND its representation differs per backend, so the
decision has to be a primitive each backend implements, never a source-level pattern.** That is the
whole difference between the reverted attempt and this fix.

**Measured after:**

| program | before | after | v1 |
|---|---|---|---|
| two fences, `a` then `b = a * 10` | `20` | `2` then `20` | `2` then `20` |
| `tests/conformance/multiblock-auto-output` | RED on V2 | PASS on INT/JS/JVM/V2 | — |
| `deep-tail-recursion` on JS/v2 | attempt 1: `got=()` | PASS | — |

Rendering goes through the `io.println` path, not `Show.show` — so a wrapped tail prints `HELLO!`
like v1 rather than `"HELLO!"`. (A *fenceless* `.ssc` still goes through `report`, so
`v2-native-program-tail-quotes-strings` remains open for that shape.)

**Gates:** `tests/conformance/multiblock-auto-output.ssc` (four blocks, one rule each: a non-Unit
tail prints; a later block still sees an earlier block's `val`; a Unit tail stays silent so no bare
`()`; a definition tail is not an expression and, being last, pins that the program value does not
double-print), and `specs/v2.2-p6.5-fsub.sh --self` at 173 ok / 0 FAIL with stage1 == stage2
byte-identical (416,911 B; pre-change baseline 173 ok / 0 FAIL at 416,071 B).

**STILL OPEN, a separate sub-case:** an auto-output block placed BEFORE a `render(...)` block loses
its value, while render-first matches v1 exactly — so the wrap is proven correct and something on
the content/`render` path drops preceding entry items. An explicit `println` in the same block
SURVIVES, so it is the auto-output item specifically. `examples/content.ssc` is that shape and still
differs from v1 by its three values. Next step there is `entryItems3`/`nItems3` in
`specs/v2.2-p6.5-fsub.ssc` and the `sscParseContents` projection in `v2/bin/ssc1-run-fsub.ssc0`.

**Legacy runner deliberately unchanged.** `v2/bin/ssc1-run.ssc0` parses each file to statements
rather than handing one source string to F, so a sentinel there would become an unbound identifier
statement in the legacy front. F is the default lane.

**SHARPER DIAGNOSIS 2026-07-28 (`v2-multiblock-auto-output`) — the machinery is not missing, it is
per-PROGRAM instead of per-BLOCK.** The framing below ("omits all three") sends the reader looking
for an absent feature. It is present, and exactly one value does print. Three-line A/B, one fence
vs two, nothing else different:

| program | native | v1 |
|---|---|---|
| bare `.ssc`: `val x = 1 + 1` / `x` | `2` | `2` |
| ONE fence: `val a = 2` / `a` | `2` | `2` |
| TWO fences: `val a = 2` / `a` ‖ `val b = a * 10` / `b` | **`20`** | **`2`** then **`20`** |

So the native lane prints the **whole program's** final value, and single-block programs are right
by coincidence of shape. The printer is `V2Result.report`
(`v1/tools/cli/src/main/scala/scalascript/cli/V2Result.scala:5`): `UnitV` → nothing, otherwise
`println(Show.show(v))` — structurally the same rule as v1's `autoOutput`, applied once at the
program boundary instead of once per block. `examples/content.ssc` "omits all three" only because
its final block ends in a `Unit` statement, so `report` prints nothing at all.

**What that changes about the fix.** Wrap **every** code block's tail expression, including the
last. Then the program's own final value becomes `Unit` and `V2Result.report` correctly prints
nothing — no double-print, and no special case for the last block. The needed runtime pieces
already exist and require **no change to `v2/src`**: `__isTag__(x, "Unit", -1)` is the Unit test
(`Runtime.scala` maps `UnitV` to the `"Unit"` tag), and `#io.println` renders any value through
the same display renderer `Show.show` uses. So an auto-output helper is expressible as an ordinary
prepended definition rather than a new prim.

**Real-harness repro.** Build the full distribution. `bin/ssc examples/content.ssc` is the v2 native
standard lane and omits the values; `bin/ssc-tools run --v1`, `emit-js`+node, and `run-jvm` use the
v1 frontend/runtime family and print them. The file explicitly states that the last non-Unit
expression in **a top-level code block** is automatically printed and contains three demonstration
fences. The initial ledger entry called `bin/ssc` the interpreter; that was wrong after the 2.1
cutover. Existing legacy `InterpreterTest` auto-output tests do not exercise the failing v2 path.

**Mechanism located 2026-07-27 (opus).** The entry above said what the fix must ACHIEVE but not where
the information dies, so anyone picking it up would start by re-deriving that. The block boundary is
destroyed *before the lexer runs*: `v2/bin/ssc1-run-fsub.ssc0` (F lane, default) concatenates every
scalascript fence into ONE string via `sscConcatSources`/`sscAppendSource` — joined with `"\n\n"` —
and hands that single string to F's `compile(userSrc, …)`; the legacy lane does the identical thing in
`joinScalaScript`. After the join, "which statement ended a block" is not recoverable by any later
stage, which is why no amount of work in the lowerer or the runtime can fix this.

Contrast v1, which keeps blocks separate all the way to the backends: the interpreter runs
`SectionRuntime.execBlockStats` with `printResult = rest.isEmpty` (per block), and `JsGen` emits the
`_auto` wrapper under `case t: Term if isLast && topLevel`. Both consume a PER-BLOCK statement list.

So the fix has to be one of: (a) F's `compile` learns a block-tail marker and lowers it to an
auto-output helper (mirroring v1's runtime `Value.UnitV => ()` test, since Unit-ness is a runtime
property, not a syntactic one), or (b) the driver compiles each block separately and merges the IR —
which must first answer how a later block still sees an earlier block's `val`. A text heuristic
("last line at column 0") is NOT viable: `examples/content.ssc` ends a block with `))` at column 0.

**BLOCKED, not deferred for convenience:** both (a) and (b) land in `specs/v2.2-p6.5-fsub.ssc`, held by
the live claim `v2-board-and-f5b`. Taking it would be exactly the collision the mutex exists to stop.
Ready for whoever holds F next.

**Expected/fix plan.** The v2 native frontend/runtime must eventually emit every non-Unit block tail
once in source order while Unit/definition tails stay silent. Its owning files overlap the live
`v2-native-stack-overflow` claim, so this CI lane must not patch them. Separately, the historical
all-examples matrix says INT/JS/JVM and must compare one v1 frontend family, just like conformance:
route INT through `bin/ssc-tools run --v1` rather than silently comparing v2 native against v1
codegens. Green v1 parity will not close this v2 user-facing bug.

## scljet-insert-null-literal-rejected — `INSERT … VALUES (…, NULL, …)` is rejected; `UPDATE … SET x = NULL` works
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/scljet-insert-null.ssc -->

**Status:** **FIXED 2026-07-27** by opus (`v2-f5b-typed-locals` batch C). The reporter's hypothesis was
right — INSERT's value list reads literals with `litValue`, which knew `num`/`str`/`real`/`bound` but
not the `NULL` keyword, while UPDATE's RHS goes through `parseExpr`. Two changes, because the second
one was the *reason* UPDATE appeared to work:

1. `litValue` accepts `NULL` (an `ident` token whose upper text is NULL — the lexer has no NULL kind,
   which is why `IS NULL` also matches via `isKw`) and the shared message no longer misnames the
   clause: `expected a literal in WHERE` → `expected a literal`.
2. `parseExprAtom` lowers a bare `NULL` to `SxLit(SqlNull)` **before** its generic `ident` branch.
   Previously `NULL` fell through to `SxCol("NULL")` and evaluated to NULL only because an unknown
   column reads as NULL — the right answer for the wrong reason, and it would have resolved to a real
   column named `null` if one existed.

Verified in the real harness (`bin/ssc run`, after `./install.sh --dev` — see the gotcha below): new
conformance case `tests/conformance/scljet-insert-null.ssc` **PASS [INT] [JS]**, covering NULL in
every position, both spellings, and the UPDATE form; full `scljet-*` sweep **103/104**, the one
failure (`scljet-sql-update-expr`) being a load flake that is byte-identical to its golden standalone
and PASSes on an in-harness re-run. `tests/conformance/scljet-address-read.ssc` has been switched
back from the omit-the-column workaround to the literal `VALUES (…, NULL)` form the reporter asked
for, with its expected output unchanged — which is itself the equivalence check.

⚠️ **Gotcha for the next agent (cost one full test cycle):** `bin/ssc run` resolves `std/scljet/*`
from the **staged** `bin/lib/standard/native-front/runtime/std/`, not from the source tree, so a
`scljet/*.ssc` edit is invisible until `./install.sh --dev` re-stages it. There are **two** staged
copies (`bin/lib/native-front/…` and `bin/lib/standard/native-front/…`) and the launcher prefers
`standard/` — patching the other one to A/B a change is a silent no-op that reads as "the test cannot
see this", which is exactly the wrong conclusion.

**Historical report:** OPEN (found 2026-07-16 by `scljet-address`). **Engine — the `scljet-m3-writes` lane.**
Found on `origin/main` `1832b5b22`.

**Symptom/reproduce** — a `NULL` literal in an INSERT's VALUES list fails in **any** position, and
the message misnames the clause (it is an INSERT, not a WHERE):

```
INSERT INTO emp VALUES (9, 'cat', 3.5)     → ok
INSERT INTO emp VALUES (9, 'cat', NULL)    → FAILED: expected a literal in WHERE
INSERT INTO emp VALUES (9, NULL, 3.5)      → FAILED: expected a literal in WHERE
INSERT INTO emp VALUES (NULL, 'cat', 3.5)  → FAILED: expected a literal in WHERE
INSERT INTO emp(id, name) VALUES (9,'cat') → ok      (omitting the column is the workaround)
UPDATE emp SET bonus = NULL WHERE id = 1   → ok      (NULL parses fine HERE)
```

**Root cause (hypothesis).** The asymmetry localises it: UPDATE's assignment parser accepts the
`NULL` keyword, INSERT's value-list parser (`parseValueList` / its literal reader) does not. The
"in WHERE" wording suggests the INSERT path reuses the WHERE-clause literal reader, so fixing the
reader (or its caller's error text) probably fixes both the parse and the message.

**Why it survived.** The workaround — omit the column — is the natural way to write a NULL row, so
existing tests never needed the literal form. `conformance scljet-address-read` documents the
detour in a comment; switch it back to `VALUES (…, NULL)` when this is fixed.

## v2-zero-arg-unknown-method-fails-open — a typo'd zero-argument method silently returns garbage instead of erroring
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-16 (`__method0__`, see "Fix" below) for every **applied** zero-arg call
(`recv.name()`) on all three v2 lanes (native / `--bytecode` / `build-jvm`). A **bare** selection
(`recv.name`, no parens) that is never applied still fails open — that residual is inherent to the
untyped runtime and is recorded below, not swept under the rug. Found 2026-07-16 by the
`control-interop-examples` agent while attempting `resume.save()` from a user's seat; the fail-open
is what made a broken example look like it ran. Not control-specific — it affected every `.ssc`
program on `bin/ssc run`.

**Symptom.** On the v2 lanes an **unknown zero-argument method call** does not raise. It silently
evaluates to an undispatched value (`<closure>`, or `Stub` for a list receiver) and the program
**exits 0**. v1 rejects the same source loudly with a position. Any typo'd zero-arg method name
therefore computes garbage rather than failing.

**Reproduce** (assembled launcher, no effects/control involved):

```scalascript
println("Int:    " + 42.bogusMethod().toString())
println("String: " + "hi".bogusMethod().toString())
println("List:   " + List(1,2).bogusMethod().toString())
```

- BEFORE — `bin/ssc run` (native — the default lane) → `Int:    <closure>` / `String: <closure>` /
  `List:   Stub`, **exit 0**. Same fail-open on `--bytecode` and `build-jvm` (all three route
  through `Prims`), and on `bin/ssc-tools run --v2` (bridge).
- AFTER — all three v2 lanes → `ssc: __method__: no dispatch for .bogusMethod on 42`, **exit 1**.
- `bin/ssc-tools run --v1` (the reference, unchanged) →
  `[ERROR] [line 1, col 22] No method 'bogusMethod' on IntV(42)`.

**Arity LOOKED like the discriminator** (it was really "which fallback you land in") — the same
unknown name with an argument always errored correctly:

```scalascript
val f = (x: Int) => x + 1
println(f.totallyBogus().toString())   // native: "<closure>", exit 0   ← fail-open
println(f.alsoBogus(1).toString())     // native: __method__: no dispatch for .alsoBogus  ← correct
```

**Root cause (VERIFIED — the earlier "currying" hypothesis was WRONG).** `__method__` is *not*
curried, and `JvmBackend.scala:369` was never on the native lane's path. The real cause is two
**deliberate fail-open fallbacks** in the VM's `Prims.__method__` (`v2/src/Runtime.scala`), reached
only after no dispatch case matched:

1. **The eta-expansion fallback** (added 2026-07-14 by `691334d4e` to make `list.exists(lc.contains)`
   work). When `margs.isEmpty` it returns `ClosV(_, 1, env => methodOp(name, recv, List(env.last)))`
   — i.e. `x => recv.name(x)` — instead of erroring. That closure renders as `<closure>`. Its commit
   note claimed it was "untyped-safe: a real field/nullary method matches an earlier dispatch case,
   so only a genuine method-ref-as-value reaches here". That is exactly the bug: **a typo reaches
   here too, and is indistinguishable from a method ref.**
2. **The `Stub` breadcrumb** (the `DataV(tag, fields)` arm): an unresolved method/field on a DataV
   receiver returns `DataV("Stub", "<tag>.<name>")` and keeps going — this is the `Stub` seen for
   List / case-class receivers, and it is why `resume.save()` in *statement* position ran silently.

**Why arity looked like the discriminator:** both fallbacks are guarded by `margs.isEmpty` / only
reachable with no args, so an applied `42.bogus(1)` fell to the `sys.error` line instead.

**The keystone fact (measured, not reasoned).** `42.bogusMethod` and `42.bogusMethod()` lower to
**byte-identical Core IR** — `(prim __method__ (lit (str "bogusMethod")) (lit (int 42)))`. Dump it
with `java -jar <run-ir.jar> run v2/bin/ssc1-run.ssc0 x.ssc` (build: `scala-cli --power package
v2/src --assembly -o /tmp/ssc.jar`). The lowerer **discards the `()`**. Therefore **no runtime-only
fix can distinguish a typo'd call from a method ref** — the distinction has to be carried in the IR.
The front does have it (`sel` vs `app(sel, [])`); only the lowerer collapsed them.

**Fix (additive; `__method__` semantics unchanged, so nothing that worked before breaks).**
`v2/lib/ssc1-lower.ssc0` now emits **`__method0__`** — "an APPLIED zero-arg call: dispatch or fail,
never eta-expand" — from the **call path only** (`resolveMethodCall`/`selMethodOr`, reached solely
from `app(sel(...), rargs)`; all 5 call sites verified). Bare selections keep `__method__` via
`selOrMethod`, so method refs still eta-expand. `Prims.__method__` marks the eta closure
(`ClosV.etaMethodRef`); `__method0__` dispatches through `__method__` and rejects **both** the marked
eta closure and a freshly-minted `Stub` (a `Stub` *receiver* still propagates its existing
breadcrumb). Other backends never eta-expanded, so they alias `__method0__` to `__method__`
(`JvmBackend`, `JsBackend`, `RustBackend`, `SwiftBackend`+`SwiftRuntime`).

**Residual (design, NOT fixed — do not file as a regression).** A **bare** selection of a
nonexistent member that is never applied stays silent: `val a = 42.bogusMethod` → `<closure>`;
`P(1).bogusField` / `List(1,2).bogusField` → `Stub`. This is inherent: a bare selection is exactly
the shape a legitimate method ref has, so failing it closed would break `list.exists(lc.contains)`.
Closing it needs a typed frontend (or making record method-refs a real feature and then erroring on
the rest). The reported bug — every *applied* call, which is what users actually write — is closed.

**Why it matters.** This was fail-open on the lane `bin/ssc run` uses by default, and it defeats
examples-as-evidence: a method that does not exist reads as a plausible value. It is also how a
`.ssc` attempt at the not-yet-existing control surface (`resume.save()`) appeared to succeed and
then failed downstream with the misleading `no dispatch for .run on <closure>`.

**Gate.** `v2/plugin-spi/src/test/scala/ssc/MethodDispatchFailClosedTest.scala` (10 tests) pins both
arities, all receiver shapes, both fail-open values, AND the method-ref eta-expansion that must keep
working. Base SHA `0891ed8cf`. Related but distinct:
`v2-native-front-in-fence-imports-not-followed` below is the other current "native lane reports
something misleading instead of the honest error" case.

**GOTCHA for whoever works here next.** The agent harness's `grep` is a **shell function** that
silently returns nothing for single-file greps — it will make you "prove" that `object Prims` does
not exist. Use `/usr/bin/grep`.

## v2-native-front-in-fence-imports-not-followed — the native lane silently ignores an import written inside a code fence
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/mcp-client-invoke -->

**Status:** **FIXED 2026-07-28** by `v2-native-import-graph`. `sscScanLines` now carries the
fence's scan mode: a `scalascript`/`scala` fence is TRANSPARENT (scanned exactly like prose,
wrapped multi-line links included) and every other fence — `sql`/`yaml`/`bash`/`text` and any
`@doc` block — stays OPAQUE. The code/doc predicate is the SAME one `sscFenceSource` uses to
decide what is program text, so the scanner and the source collector cannot disagree about which
fences are code; inside a code fence only a bare ` ``` ` closes it, matching `sscSourceLines`.
Both runners changed — `v2/bin/ssc1-run.ssc0` and `v2/bin/ssc1-run-fsub.ssc0` (F, the default) —
because they carried the identical scanner and editing one would have been a silent half-fix.

**The measurement that mattered: this fix could not land alone.** Exact A/B (same build, same
jar, only the staged tower swapped) on the program this entry opened with — the mcp discovery
example with its imports moved back INSIDE the fence:

| | result |
|---|---|
| before | `ssc: unhandled runtime effect: Transport.Spawn` |
| after | `Tools (3):` + `echo` / `add` / `get_weather` |

The same A/B over **all 35 sources in the tree that carry an in-fence import link** changed
exactly ONE line, for the better: `tests/conformance/mcp-client-invoke` went from the lying
`unhandled runtime effect: Transport.Spawn` to the honest `unbound global: mcpConnect` (that
extern lives in the mcp provider, which the standard tier does not load).

**But that probe set was scoped wrong, and it hid a real regression** — a lesson worth keeping:
it enumerated files that *contain* the trigger, not files whose *module graph* the trigger can
change. Computing the transitive graph both ways found the true regression set — exactly one
file, `examples/agent-mcp-toolsource.ssc`, which reaches `std/agent.ssc` only through
`std/agent-mcp.ssc`'s own in-fence import. It went `imported 3 MCP tools as agent tools` →
`_err`, because **`std/agent.ssc` did not parse on the native front at all** — measured
independently of this fix, through the out-of-fence path that was already followed:

```
[x](std/json.ssc)  → loaded      [x](std/http.ssc) → loaded      [x](std/agent.ssc) → _err
```

So the pre-fix "pass" was a false green: the example printed the right thing while running with
a silently truncated module graph. The blocker was `v2-front-try-in-def-body-shapes-break` shape
(c) (`postChatCompletionsOnce` and `postChatCompletionsStreamOnce` are both multi-statement
braceless `try` bodies). Staging that fix into this build and re-measuring closed it:
`std/agent.ssc` parses and `agent-mcp-toolsource` prints all three tools again — now with a
complete graph. This fix therefore landed **after** that one, not before.

**Also found by the compare-first gate, and filed separately:** v1's JsGen and JvmGen do not
follow an in-fence import either — only the INT interpreter does. See
`js-jvm-codegen-in-fence-imports-not-followed`; the new gate declares those two lanes known-red.

**Gate.** `tests/conformance/native-import-in-fence.ssc` — the `modules.ssc` shape with the
import link moved inside the fence, plus a second fence proving a later block still sees the
module. RED on the V2 lane before the fix (`ssc: unbound global: square`), green after.

**Original report follows.** The **symptom** that made `v21-explicit-lanes-gate` red was
worked around (2026-07-16, `4b8d09377`+) by moving the two mcp examples' imports out of the fence;
the underlying v1/native divergence below was NOT fixed at that time. Found while closing the
`ci-red-main` lane.

**Symptom.** `bin/ssc-provider mcp run examples/mcp-client-discover.ssc` →
`ssc: unhandled runtime effect: Transport.Spawn` (exit 1, no other output). Deeply misleading:
`Transport` is an **enum** (`v1/runtime/std/mcp/types.ssc:62`), not an effect. The message is a
red herring — see "why the message lies" below.

**Root cause.** `v2/bin/ssc1-run.ssc0`'s import scan (`sscScanLines`) treats a `[names](path)` link
as an import **only outside a fenced block** — inside a fence it skips every line to the closing
fence. The code parser then treats the same link as a parse-only no-op (`ssc1-front.ssc0`, the `[`
branch of `parseOneStmt`: *"the imported names resolve via the plugin registry / globals, so the
declaration is a parse-only no-op"*). Both halves assume in-fence imports name plugin globals. That
holds for externs like `mcpConnect`, but NOT for ordinary declarations in a `.ssc` module, so the
module is **never loaded** and every name it exports stays unbound. **v1 resolves in-fence imports
fine** — this is a pure v1/native divergence.

**Why the message lies.** A *field access* on the unbound name (`Transport.Stdio`) reports the
honest `unbound global: Transport`. A *call* (`Transport.Spawn(…)`) instead falls back to the
ambient-plugin-global path, which produces a `DataV("Op", …)` labelled `Transport.Spawn`; that Op
reaches the program's final value and `NativeArtifactRuntime.report` / `V2Result.report` render it
as `unhandled runtime effect: <label>`. **Any unbound qualified call on the native lane reports as
an "unhandled runtime effect"** — do not read that message as "the effects system is broken".

**Reproduce** (no effects, no mcp involved):

```bash
BT='```'
printf '%s\ndef greet(): String = "hi"\n%s\n' "$BT" "$BT" > /tmp/m.ssc
# the [greet](...) import sits INSIDE the fence:
printf '%sscalascript\n[greet](/tmp/m.ssc)\nprintln(greet())\n%s\n' "$BT" "$BT" > /tmp/u.ssc
bin/ssc run /tmp/u.ssc            # native → ssc: unbound global: greet
bin/ssc-tools run --v1 /tmp/u.ssc # v1     → hi
# move the [greet](...) line ABOVE the opening fence → both lanes print "hi"
```

**Why it was not simply fixed.** Making `sscScanLines` follow in-fence imports (track the fence
language, scan standalone link lines in `scalascript`/`scala` fences only) **works** and fixes both
mcp examples — but it widens the native module graph to modules the native front **cannot parse**:
`std/agent.ssc` (pulled in via `std/agent-mcp.ssc`) hits at least two independent native-front
parse gaps (below). So the correct fix regresses `agent-mcp-toolsource` from one error to another
and is blocked on those gaps. Queued in SPRINT; a green `main` came first.

**Affected sources** (in-fence imports that the native lane silently drops today; note
`std/mcp/server.ssc`'s is **multi-line**, so a one-line grep misses it):
`v1/runtime/std/mcp/client.ssc`, `v1/runtime/std/mcp/server.ssc`, `v1/runtime/std/agent-mcp.ssc`,
`v1/runtime/std/agent.ssc`.

## v2-native-front-try-catch — `try` / `catch` does not work on the native front
<!-- status: unknown
     lane: native
     area: front -->

**Status:** **LAYER 2 FIXED 2026-07-27** by opus (`bugs-native-front-trycatch`) — the entry below
called it "not diagnosed"; it is now diagnosed to the line and fixed.

**Root cause (layer 2, the real blocker).** A `catch { case e: Throwable => … }` lowers to a NOMINAL
tag test `__isTag__(v, "Throwable", -1)`, and `tryRun` (`v2/src/Runtime.scala`) delivers a host error
to the handler as `DataV("RuntimeException", [message])`. The v2 tag test was **flat** — a plain
`t == expected` with no notion of supertypes — so the tags never matched, control fell through to
`__handler_dispatch_miss__`, and the program died with `match: no matching case`. Nothing was wrong
with the parser or the lowering.

**Fix:** mirror the v1 reference rule verbatim — `PatternRuntime.isExceptionSupertype` and its use at
`PatternRuntime.scala:1321`: a pattern naming one of the four JVM exception SUPERtypes
(`Throwable` / `Exception` / `RuntimeException` / `Error`) matches the instance, because a `catch`
scrutinee is a synthesized exception carrying the throwable's simple name and users catch it by
supertype. Deliberately permissive — it is permissive in the reference, and the reference is the
oracle the frozen goldens encode.

**Verified.** Discriminating A/B on the SAME IR and the SAME runner, only the kernel differing:
old jar → `match: no matching case`, new jar → `err`. Real CLI, all lanes: braced and braceless
forms × {default F, `SSC_FRONT=legacy`, v1 reference} = **6/6 `err`**. Kernel gates: semantic
**248/248 GREEN (MISMATCH 0)** — the permissive rule moved no golden — X1 **155 ok / 0 FAIL**,
fixpoint byte-identical 406,964 B. New conformance case
`tests/conformance/try-catch-exception-delivery.ssc` (`[int, jvm]`) PASS, byte-identical to the v1
reference; every case in it actually throws, because a `try` whose body never fails passes even with
the bug present — which is exactly how this survived.

**Layer 1 correction:** the note below says the braceless multiline form is "STILL BROKEN". Measured
2026-07-27: it now works on the legacy front AND through the real CLI under F (the F4a fallback
covers F's own parse gap). What IS still broken are two *other* def-body shapes, filed separately as
`v2-front-try-in-def-body-shapes-break` — including one that fails **silently at exit 0**.

**Historical status:** OPEN, **not diagnosed** (time-boxed). Found 2026-07-16 behind the curried-def gap; it is
the second thing that stops `std/agent.ssc` (`postChatCompletionsOnce`, a braceless
`try … catch case e: Throwable => …`) from parsing on the native lane.

**Re-verified 2026-07-18 (`native-front-run-gaps`): PARTIALLY fixed, and the remaining form has TWO
independent layers — the second is the real blocker and is NOT the parser.**

- Braced (`try { } catch { }`) and single-line `finally` now parse+run. ✓
- Braceless MULTILINE `try NL body NL catch case … => …`: LAYER 1 is a parse `_err` — semicolon
  inference wedges a `;` between the body's `)` and `catch` (the same mechanism as the curried-def
  bug). A `skipSemis` before the `catch`/`finally` check in the try-parse (`ssc1-front.ssc0:~1082`)
  fixes the parse (verified: it turns `_err` into layer 2). NOT landed alone — see layer 2.
- LAYER 2 (the real blocker, RUNTIME/lowerer, NOT parser): when an exception is ACTUALLY thrown,
  the native runtime does not deliver the caught value to the handler's match. Even the BRACED
  form fails: `try { (10/0).toString } catch { case e: Throwable => "err" }` → native
  `ssc: match: no matching case` (v1: `err`); `case _` (wildcard) also does not match. So the whole
  native try/catch EXCEPTION path is broken regardless of braces — the parse layer above is moot
  until this is fixed. This lives in the `__tryCatch__` lowering/runtime (p65's lowerer lane or
  `v2/src`), not `ssc1-front`. `try { println(x) } catch …` works ONLY because nothing throws.

So: the parser half is diagnosed + has a one-line fix (`skipSemis`); the exception-delivery half is
a separate runtime bug that gates the feature. Land them together.

Original re-verify note (2026-07-18): PARTIALLY fixed — one form remains.

| form | native lane, 2026-07-18 |
|---|---|
| `try { … } catch { case e: Throwable => … }` (braced) | **works** (== v1) |
| `try body finally body` (single line) | **works** (== v1) |
| braceless MULTILINE `try` NL body NL `catch case e: Throwable =>` NL body | **STILL BROKEN** — `native frontend rejected … sentinel _err` |

So the surviving gap is narrow: a **braceless, multiline** `try`/`catch`. The single-line braceless
form and the braced form now parse. Original (2026-07-16) three-form symptom kept below for history.

Original symptom (2026-07-16) — three spellings, three *different* native-lane failures:

| form | native lane (2026-07-16) |
|---|---|
| `try { … } catch { case e: Throwable => … }` | `ssc: unbound global: try` |
| braceless `try` NL body NL `catch case e: Throwable =>` NL body | `native frontend rejected incomplete parse … sentinel _err` |
| `try body` NL `catch case e: Throwable =>` NL body | `ssc: match: no matching case` |

**Reproduce:**

```bash
printf 'def f(x: Int): String =\n  try\n    "ok " + (10 / x).toString\n  catch case e: Throwable => "err"\nprintln(f(0))\n' > /tmp/t.ssc
bin/ssc run /tmp/t.ssc            # native → ssc: match: no matching case
bin/ssc-tools run --v1 /tmp/t.ssc # v1     → err
```

**Notes for whoever picks this up.** `try`/`catch`/`finally`/`throw` are **not** keywords
(`isKw`, `ssc1-front.ssc0:63-85`) — they lex as `id` and `try` is handled in `parseAtom`
(`:1076`), which already has a braceless `catch case` path via `parseMatchArmsBraceless`. So the
code *intends* to support all three forms; `unbound global: try` means `parseAtom`'s `try` branch
is not even reached for the braced form. Diagnose before patching. This is feature-sized, not a
one-liner — it blocks `v2-native-front-in-fence-imports-not-followed` above.

## scala-control-api-v1-placement — FIXED / awaiting confirmation (2026-07-14, Sergiy)
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 9b477a128
     confirmed: no -->

**Status:** fixed in `9b477a128`; awaiting reporter confirmation. Reported by
Sergiy immediately after the Tier-1 implementation landed.

**Symptom:** the compiler-independent Scala 3 control SDK was added at
the legacy v1 language tree. The API implements the v2.2 bidirectional-control
host profile and is intended to compose with every future backend; that placement
falsely assigned semantic and lifecycle ownership to the v1 compiler/runtime.

**Reproduce:** before the fix, `build.sbt` pointed `scala3ControlApi` into the v1
language tree and README/user-guide example links exposed the same ownership. The
Scala host profile requires only that the leaf remain outside the bootstrap
dependency graph; it never assigns the module to v1.

**Root cause:** “compiler-independent and outside bootstrap” was incorrectly
translated into “put it beside the legacy v1 language libraries.” Those are
independent decisions: this is a v2 host-SDK capability whose source tree must
state that ownership while its sbt dependency graph stays a leaf.

**Fix/verification:** the canonical ownership is frozen in `5b24876ca`; the
unchanged artifact/package ABI moved to `v2/host/scala/control-api` in
`9b477a128`; build/docs/layout links were corrected in `b692338e7`. No tracked
file or live reference remains at the old exact path. Verification reran 39/39
module tests, the runnable example (`Vector(10, 20)` / `42`), package/POM and
production dependency checks, focused conformance 10/10, and the independent
interop harness 9/9 measurable axes.

## control-interop-runsh-installbin-task-name — FIXED (2026-07-14, claude)
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: unrecorded -->

**Status:** fixed in the `control-interop-runsh-installbin-doc` landing; reported by
codex-interop (rozum #interoperability, 2026-07-14).

**Symptom:** `tests/interop-conformance/run.sh:21`'s "ssc binary not found" hint tells the
user to run `sbt installBin`, but the scoped sbt task is `cli/installBin`
(`tests/interop-conformance/README.md:25` already uses `scripts/sbtc "cli/installBin"`).

**Reproduce:** `sed -n '21p' tests/interop-conformance/run.sh` shows `sbt installBin`;
compare with README line 25.

**Fix/verification:** the run.sh hint now names the scoped `sbt cli/installBin` task,
matching the README; the runner is otherwise unaffected (string only).

## coreir-canonical-codec-contract — canonical encoder/decoder violates the frozen wire contract
<!-- status: open
     lane: native
     area: codegen -->

**Status:** open (found 2026-07-14, Codex + `audit_coreir_control`, while validating
CoreIR as the persisted `SavedContinuation` capsule format; observed at `638d3f5fe`).

**Symptom:** the format documented as a bit-preserving canonical on-wire round trip is
not currently one:

- `Writer.floatStr(-0.0)` emits `0`, losing the sign bit, and integral finite doubles
  emit integer syntax although `12-ir-format.md` requires a decimal point or exponent;
- `IrEncode.const` has no `IrBytes` case although `Const.CBytes`, the canonical Writer,
  and `IrDecode` support bytes;
- the `coreir.encode` primitive returns `StrV`, while `10-core-ir.md` promises `Bytes`;
- the documented text/bytes `coreir.decode` primitive does not exist: `coreir.eval`
  consumes an already-built in-memory `IrProg`, and the textual `Reader` is a JVM API;
- emitted names/tags/opcodes are not checked against the documented `SYMBOL` grammar.

**Reproduce:** inspect the exact compiler/runtime sources and contract:

```sh
sed -n '195,207p' v2/src/CoreIR.scala
sed -n '40,105p' v2/specs/12-ir-format.md
sed -n '2342,2351p' v2/src/Runtime.scala
sed -n '3840,3950p' v2/src/Runtime.scala
```

The behavior follows directly from the production Writer/primitive/IrEncode match
tables used by the v2 runtime; in particular, `-0.0 == 0.0` takes the integral branch,
`IrBytes` reaches `bad const`, and `coreir.encode` constructs `StrV`.

**Notes:** tracked as `coreir-canonical-codec-hardening` in `SPRINT.md`. The dedicated
fix needs real assembled-runtime round-trip fixtures for every value/node, signed zero,
non-canonical inputs, invalid symbols, malformed/untrusted depth and size, plus parity
across the seed and every loader. This design task does not opportunistically change the
format; status remains open until that slice lands and is verified.

## custom-jsemitter-signal-list-literal — `StaticJsEmitter.jsLiteral` can't encode a List-valued signal registered from an event handler
<!-- status: open
     lane: native
     area: front -->

**Status:** open (found 2026-07-13, claude-sonnet-5, while building the
`select-from-signal` slice, `specs/std-ui-select.md` § "Reactive options
(selectFrom)"). Pre-existing, unrelated to that slice's own code — not fixed
here (out of scope: it's a `frontend/custom/StaticJsEmitter.scala` gap, not
UI-widget-specific, and a real fix means deciding how `jsLiteral` should
recursively encode `List`/`InstanceV`/`Map` values, which is its own slice).

**Symptom:** `ssc run <file>.ssc` (both the documented default — v2 VM,
`custom` frontend — and `--v1`) throws
`jsLiteral: unsupported value type ... List(...). Supported: String / Int /
Long / Double / Float / Boolean / null.` at startup for **any** program
where a `Signal[List[_]]` (of scalars OR case-class instances) is referenced
by an event handler compiled by `frontend/custom/StaticJsEmitter.scala`
(`CustomFrameworkBackend`/`serve(...)`'s pipeline). `registerSignal`
(`StaticJsEmitter.scala:222`) calls `jsLiteral(signal())` to seed the
signal's initial JS value whenever `compileEventHandler` needs to register
the signal a handler targets (e.g. `SetSignalLiteral` from a `signalButton`)
— and `jsLiteral` (`StaticJsEmitter.scala:1220`) has no `List`/`Seq` case at
all, only bare scalars.

**Reproduce:** this is not new/introduced by `select-from-signal` — it
already affects a previously-shipped example unrelated to this slice:

```
bin/ssc-tools run examples/frontend/keyed-for-demo/keyed-for-demo.ssc
```

throws immediately (before serving anything) with the error above, because
`rows` is a `Signal[List[String]]` referenced by
`signalButton(rows, ["gamma", "alpha", "delta", "beta"], "Reorder + insert")`.
That example's own docstring claims `ssc run … then open
<http://localhost:8080>` works — it currently does not, on either the
default v2-VM/`custom`-frontend path or `--v1`.

**Scope note:** this only affects `serve(...)`'s live-interpreter +
`frontend/custom/StaticJsEmitter.scala` pipeline (pipeline A). The
**production** static-compile pipeline (`bin/ssc-tools emit-js` /
`emit-spa`, `JsGen.scala` + `signals.mjs` — pipeline B, what busi's real
build uses) is unaffected: it compiles `.ssc` source to JS syntactically
(list/case-class literals become JS array/object literals in source text),
never needing to re-encode a *runtime* value back into a JS literal the way
`registerSignal` does. Confirmed empirically: `examples/frontend/keyed-for-demo/keyed-for-demo.ssc`
and `examples/frontend/select-reactive-demo/select-reactive-demo.ssc` (this
slice's own demo, which hits the identical error via `ssc run` for the same
underlying reason — its `contracts: Signal[List[Contract]]` referenced by a
`signalButton`) both emit clean, runnable JS via `bin/ssc-tools emit-js`.

**Notes for a future fix:** `jsLiteral` would need a recursive case for
`List`/`Seq` (encode each element, wrap in `[...]`) and probably `InstanceV`
(case-class values, encode fields, wrap in `{...}`) to close this — scoped
as its own follow-up, not attempted here.

## native-front-nativeui-site-annotation — anonymous computedSignal/eqSignal collide on `ssc run`
<!-- status: fixed
     lane: native
     area: front
     fixed-in: dc9814521
     confirmed: no -->

**Status:** fixed (2026-07-12, `dc9814521`), awaiting Sergiy confirmation; found while
migrating rozum's control center (`clients/control/center.ssc`, 20+ computedSignals) to the
latest v2.

- **Real-harness repro:** `bin/ssc run` a file with two anonymous derived signals whose
  computed defaults differ:
  ```
  import [signal, computedSignal](std/ui/primitives.ssc)
  val a = signal("a", "x")
  val b = computedSignal(() => a() + "1")
  val c = computedSignal(() => a() + "2")
  println(b()); println(c())
  ```
  Before the fix: `ssc: duplicate native UI signal '__computed__manual:computedSignal' in
  scope 'root' has conflicting kind/default`. After: prints `x1` / `x2`.
- **Root cause:** the self-hosted native frontend (`RunNativeV2`, the default `ssc run`
  path) lowers std/ui primitive calls to PLAIN globals and — unlike the scalameta
  `FrontendBridge` — never ran the `NativeUiSites.annotate` pass. So every anonymous
  `computedSignal`/`eqSignal` reached the ui plugin's fallback registration
  (`UiNativePlugin.siteNative`, id `manual:<name>`) with one shared id, and the second
  distinct-default signal collided.
- **Fix/verification:** `RunNativeV2.compile` now scans the structural CoreIR for the
  anonymous derived-signal primitives actually called (`App(Global(name))`) and runs the
  same `NativeUiSites.annotate` so each site gets a unique lexical id. New
  `NativeUiSiteAnnotationTest` (2), `NativeUiSitesTest` (7), `UiNativePluginTest` (14),
  std-ui conformance (7) all green.

## native-front-curried-vararg-and-attrs-map — native `ssc run` broke curried/vararg calls + Map attrs
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded -->

**Status:** fixed (2026-07-13); the vararg packing needed a follow-up correction (opus) —
see Fix item 1. Found bringing rozum's control center up on the latest native frontend
(was filed as `native-front-spa-arity-gap`).

- **Real-harness repro:** `bin/ssc run` a file using a trailing-vararg std/ui primitive with 2+
  varargs — `card(a, b, c)` → `arity: 1 expected, 3 given`; `vstack(gap = 16)(a, b)` →
  `arity: 2 expected, 1 given` (`Runtime.scala:178`); then `element` attrs →
  `NativeUiElement.attrs expected Map[String, Value], got List(("style", …))`.
- **Root cause (two independent native-front bugs):**
  1. **Varargs never packed.** A def whose last param is `T*` (e.g. `def card(body: T*)`,
     `def vstack(gap)(children: T*)`) lowers to a lambda binding that param as ONE list value
     (`children.toList`), but the parser flattened a call's clauses into individual args and
     the call reconciliation compared the unpacked count to the def arity — so `card(a,b,c)`
     (3 args vs arity 1) and `vstack(g)(a,b)` (curried, combined 3 vs arity 2) failed the
     CoreIR arity check. The front never even recorded which params are varargs.
  2. **Map literal → association list.** The native front lowers a `Map[String, Any]` literal
     to a proper `(k, v)` Pair/Tuple2 association list, but `NativeUiPortable.stringMap` (ui
     element attrs/events) only accepted a `MapV`.
- **Fix/verification:**
  1. Front (`ssc1-front.ssc0`) now detects a trailing `T*` param (`paramTypeIsVararg`) and
     registers such defs in `varargDefsCell`; the lowerer (`ssc1-lower.ssc0`) packs a call's
     trailing args into one Cons-list (`packVarargsArgs`) so the flattened call matches arity.
     **Correction (opus, 2026-07-13):** the first landing (`3f5c06e98`) called `packVarargsArgs`
     inside `resolveE`, but `lowerE` re-applies `resolveE` top-down at every recursion level, so
     the non-idempotent pack compounded into a triply-nested list `[[[elems]]]` — any def that
     READ the vararg (`xs.toList.length`) then saw a 1-element list (native `6/6/6`, `1/1/1` vs
     v1 golden `6/7/8`, `1/2/3`; the original `= gap` test masked it). Fixed by moving the pack to
     `lowerE`'s terminal `app→IrApp` step (once per Ir node, idempotent) and deleting the dead
     `calleeName`. Verified: `wrap(5)(1,2,3)→8`, `sumv(1,2,3)→3` on native == v1; control-center-live
     clears the arity error; native corpus (203 examples) byte-identical before/after; 9/9 v2 conformance.
  2. `NativeUiPortable.stringMap` normalizes a Pair/Tuple2 association list to a String map
     (left-to-right, duplicate key last-wins).
  Validated: `card(a,b,c)`, `vstack(gap=16)(a,b)`, `cardWithHeader(h)(a,b)` all run; rozum
  `center.ssc` and in-repo `control-center-live.ssc` now lower fully (past both errors). New
  `UiNativePluginTest` stringMap case; `v2NativeUiPlugin/test` + std-ui conformance (7) green.
- **Remaining native-`ssc run` SPA gaps (separate, NOT needed for the UCC, which builds via the
  tools-tier `ssc-tools emit-spa`):** (a) `eqSignal` created at ONE lexical site inside
  `std/ui/lower.ssc` but called per-row collides (`duplicate native UI signal
  '__equality__<siteId>'`) — the `(owner, siteId, occurrence)` counter work owned by the active
  `v2-swift-nativeui-i18n-json` claim; (b) `serve(view, port)` under `ssc run` tries to start a
  real TLS server (`native TLS server requires a future server-host extension`) instead of
  SSR-emitting. Track these for a fully SPA-complete standalone native `ssc run`.

## scljet-oracle-pin-stale — spec called SQLite 3.53.0 current after 3.53.3
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 7a6e2e70a
     confirmed: no -->

**Status:** fixed (2026-07-12, `7a6e2e70a`), awaiting Sergiy confirmation;
found by codex while validating primary sources for the SclJet M2 read-only
specification.

- **Repro:** compare `specs/scljet.md`'s M0 oracle paragraph with the official
  SQLite release history: 3.53.3 was released 2026-06-26 and is the current
  bug-fix release; the local Python oracle also reports 3.53.3.
- **Root cause:** the initial specification pinned the 3.53.0 feature release
  but described it as current without accounting for its subsequent patch
  releases.
- **Fix/result:** the M2 spec now pins exact 3.53.3 source id
  `d4c0e51e...d782c62`, requires compile options per fixture, and makes future
  oracle upgrades explicit. The M2d corpus gate must use the same source id.

## v2-frontend-scljet-memory-vfs-state-dispatch — full bridge gate rejects String.state
<!-- status: fixed
     lane: native
     area: front
     fixed-in: fe4dfb0ae -->

**Status:** done (2026-07-12, `fe4dfb0ae`); found and confirmed by codex in the
Swift NativeUi final release repeat after SclJet M1 landed; reported to
`@scalascript` in Rozum.

- **Real-harness repro:** run `scripts/sbtc "v2FrontendBridge/test"` on current
  `origin/main`. The suite is 200/201; only `v2-conformance:
  scljet-memory-vfs` fails with `__method__: no dispatch for .state on
  "/db.sqlite"` at `Runtime.scala:3038`. The same suite's money, tkv2 PWA and
  all Swift-adjacent rows pass.
- **Boundary:** this is the compiler-backed FrontendBridge lane for a newly
  landed SclJet fixture, independent of the Swift implementation and of the
  standard native tuple-map arity residual. The SclJet M1 claim is released;
  the active v2.1 release owner is already reconciling its SclJet delegated
  lane, so coordinate before editing shared dispatch/import code.
- **Done-when:** the faithful bridge fixture preserves the intended receiver
  for `.state` (or explicitly delegates the unsupported lane), a focused
  regression passes, and the full FrontendBridge suite is green.
- **Fix/result:** the fixture's declared `backends: [int]` is now an explicit
  documented skip in this extra compatibility-bridge harness, matching the
  landed v2.1 `ssc-tools --v1` JVM host-plugin delegation. Full FrontendBridge
  passes 200/200 with 39 intentional ignores; the separate imported-receiver
  implementation gap remains tracked independently.

## v21-negative-freeze-smoke-stale-frontend-mutation — drift test became a no-op
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 65d0db5e5
     confirmed: no
     gate: tests/e2e/v21-negative-toolchain-release-gate-smoke.sh -->

**Status:** fixed (2026-07-12, `65d0db5e5`), awaiting Sergiy confirmation;
found by codex while reconciling the 197-row release freeze after
`scljet-memory-vfs.ssc` landed concurrently.

- **Real-harness repro:** update the canonical negative report from
  `frontend.ok=195` to `196` and run
  `tests/e2e/v21-negative-toolchain-release-gate-smoke.sh`. Its frontend drift
  mutation still searches for `frontend.ok.195`, changes nothing, and then
  reports that the freeze “accepted frontend drift”.
- **Root cause:** the synthetic mutation encoded the old exact value separately
  from the canonical fixture.
- **Done-when:** the mutation changes the current exact value, the canonical
  report passes, every synthetic drift is rejected, and the smoke prints PASS.
- **Fix/result:** the mutation now targets the current exact value and is
  advanced with every corpus freeze. The canonical 200-row report passes and
  every synthetic count/membership drift is rejected.

## v21-unhandled-effect-smoke-x402-launcher — bridge assertion used standard `ssc`
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 3e90be0e7
     confirmed: no
     gate: tests/e2e/v21-unhandled-effect-smoke.sh -->

**Status:** fixed (2026-07-12, `3e90be0e7`), awaiting Sergiy confirmation;
found by codex while verifying the direct-ASM effect fix for
`v21-ti-retire-all-both-fail`.

- **Real-harness repro:** after installing the v2.1 tier split, run
  `tests/e2e/v21-unhandled-effect-smoke.sh`. Its “bridge ASM x402 Op” case
  invokes `bin/ssc run --bytecode examples/x402-metamask.ssc`; standard native
  correctly rejects compiler-backed x402 syntax with a structural parser
  sentinel, so the stale assertion never reaches the compatibility bridge.
- **Root cause:** the regression kept the pre-split launcher even though the
  compatibility frontend is now reachable only through explicit `ssc-tools`.
- **Done-when:** the bridge assertion invokes `ssc-tools` explicitly, standard
  `ssc` retains its compiler-free rejection, and the complete smoke passes.
- **Fix/result:** the compatibility assertion now invokes explicit `ssc-tools`;
  standard `ssc` keeps its structural parser rejection. Native-entry and the
  final consolidated release gate pass.

## v21-asm-top-val-effect-leak — direct ASM stores and prints an unhandled effect
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 3e90be0e7
     confirmed: no -->

**Status:** fixed (2026-07-12, `3e90be0e7`), awaiting Sergiy confirmation;
found by codex in the consolidated v2.1 release gate while closing
`v21-ti-retire-all-both-fail`.

- **Real-harness repro:** run `bin/ssc run --native --bytecode
  examples/graph-rdf4j-http-storage.ssc` without the explicit RDF4J provider.
  The process correctly exits nonzero with `unhandled runtime effect:
  Sparql.select`, but stdout also contains the raw
  `Op("Sparql.select", ...)` value after `Stored two books.`. The native VM
  prints only `Stored two books.` before rejecting the effect.
- **Root cause:** generic direct-ASM `cell.set`, `lcell.set`, and `dcell.set`
  primitive resolution stored a raw effect `Op`; the VM path lifted the write
  through the effect and therefore never exposed the raw operation.
- **Done-when:** direct ASM and VM both leave the raw `Op` unobservable, the
  focused native-entry regression passes in both modes, and the consolidated
  release gate is green.
- **Fix/result:** generic setters now lift writes over `Op` before mutating the
  cell. The bytecode regression, native-entry smoke, and final 200-row release
  gate pass with no raw effect output.

## v2-money-portable-native-front-arity — standard native release gate exits before allocation
<!-- status: fixed
     lane: native
     area: front
     fixed-in: b4b574c68
     gate: tests/conformance/run.sh -->

**Status:** done (2026-07-12, `b4b574c68`); found and confirmed by codex in the
Swift NativeUi combined conformance gate and coordinated in the `scalascript`
Rozum room.

- **Real-harness repro:** fresh
  `tests/conformance/run.sh --only 'money-*,effect-*,tkv2-*,v2-*' --no-memo`
  passes 27/28. `money-portable-v2` prints `$3.75` and `1.2100`, then its V2 lane
  exits before the allocation list with `ssc: arity: 2 expected, 1 given`.
  INT passes; all effect, toolkit-v2 and remaining v2 cases pass.
- **Narrowed boundary:** this branch predates `3e90be0e7`, ruling out the
  earlier `cell.set` suspicion. `bin/ssc run --native` fails after five correct
  rows, while `bin/ssc-tools run --v2` prints all six rows and exits zero, so
  the residual is in the standard native-front/VM route. It is owned by the
  active `v21-ti-retire-all-both-fail` release claim; do not edit that area
  concurrently.
- **Root cause:** native structural IR lowers the parenthesized two-parameter
  `base.zipWithIndex.map((u, i) => ...)` through synthesized `_sel_map`.
  `_sel_map` recursively matches `Cons` but invokes its `Lam(2, ...)` mapper
  with the `Tuple2` as one argument, bypassing Runtime's tuple-spreading
  `__method__("map", ...)` path and producing the exact arity error.
- **Done-when:** the owning agent lands the exact fix with an isolated money
  regression, this branch rebases it, and both isolated money plus combined
  28-case no-memo conformance pass.
- **Fix/result:** native lowering routes a sole multi-parameter `map` lambda to
  the runtime `__method__` seam while preserving `_sel_map` for one-parameter
  LazyCons/Option/Either behavior. The existing imported-money regression now
  prints all six rows under native VM and ASM; isolated money is 1/1, the
  28-case combined no-memo gate is 28/28, and native-entry smoke passes.

## v2-imported-receiver-methods-not-linked — native imports cannot execute receiver operations
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 2df8f6e3c -->

**Status:** FIXED (2026-07-13, opus) — was TWO independent gaps, NOT an import bug
(both reproduce same-file):
1. **`List.slice` missing intrinsic — FIXED.** The v2 native runtime had `take`/`drop`
   but no `slice` arm, so `xs.slice(a, b)` → `Stub` on VM/ASM. Added the arm at
   `v2/src/Runtime.scala` (next to take/drop). Verified + conformance `list-slice`
   [int, v2]. This was the actual cause of the `slice`-named symptoms (an extension
   literally named `slice` also recursed into the Stub → StackOverflow).
2. **Case-class BODY methods dispatch — FIXED.** Done in two parts: a sibling landed the
   dispatch machinery (`2df8f6e3c`) — front `caseMethodsCell` capture + lower mangled
   `Tag__method` globals + `__regmethod__` regs + `Runtime.registerTaggedMethod`, keyed by
   `(tag, method)` and consulted in the `__method__` `DataV(tag,fields)` arm AFTER built-in
   `methodOp` and BEFORE the `Stub` (so `List.size` etc. are never hijacked — the collision-free
   Part-3 design). But it only dispatched the FIRST method of a `:`-indented body: the layout
   pass let the inner type-annotation colon in `(data: List[Int])` consume the `declHead` bit,
   so the OUTER body colon never opened the virtual `{ }` block and 2nd+ member defs leaked as
   top-level defs. Completed (opus): in `layout` (`ssc1-front.ssc0`), a `:` inside a P/S/X
   (paren/bracket/ext-receiver) frame is a TYPE colon and must not consume `declHead`, so the
   body colon frames the body → routes through `captureBraced` (which already loops over all
   members). Verified on the DEPLOYED binary: `base.get(4)/base.size()` → `50/5` on native +
   ASM; `collide.ssc` → `5, 2` (List.size not hijacked); conformance `case-class-body-methods`
   [int, v2]; native corpus 192 identical + 1 FIX (`scljet-readonly-pager-btree`, multi-method
   bodies, now golden-exact); 171 conformance / V2 lane 13/13, zero regressions.
- **Fix plan for gap 2 (focused follow-up, bootstrap-frontend change):** in
  `parseCaseClass`, instead of skipping/leaking body defs, parse each `def m(ps) = body`
  and re-emit it through the existing extension machinery as `extension (self: X) def
  m(ps) = <body>` with the class fields bound at the top of the body (`let field =
  self.field in …`, avoiding an AST field-rename walk). Reuses the proven extension
  path (no runtime change). Higher-risk (touches the self-hosted tokenizer-layout +
  parser that compiles ALL native programs) → needs full v2 conformance verification;
  scoped separately rather than rushed. WORKAROUND meanwhile: export target-neutral
  top-level functions or use `extension` methods (both link + execute on native).
- **Done-when (gap 2):** a multi-file native VM/direct-ASM conformance case imports a
  case class with a body method, reads receiver fields, and produces non-`Stub` output.

## v21-native-tuple-field-patterns — tuple literal/typed fields are unchecked
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 2b87c57df
     confirmed: no -->

**Status:** fixed (2026-07-12, language `2b87c57df`, taxonomy `e0e7e98c3`),
awaiting Sergiy confirmation; found by codex after binding the distributed
registry provider ABI.

- **Real-harness repro:** after staging the distributed provider, run
  `bin/ssc run --native examples/distributed-join.ssc --
  tests/fixtures/v21-native/distributed-orders.csv
  tests/fixtures/v21-native/distributed-customers.csv` and repeat with
  `--bytecode`.
- **Observed:** VM prints five malformed rows (both customer and order tuple
  filters accept every `Tuple4`); ASM prints six malformed rows and duplicates
  two orders. Neither matches compatibility output. The generated CoreIR turns
  `case ("customer", _, _, _)` and `case ("order", _, _, _)` into unconditional
  `Tuple4` arms, and typed tuple fields such as `name: String` lose their binder.
- **Root cause:** `fldBinder` recognizes only `vpat`; `fldObligations` recognizes
  only nested `cpat`. Literal (`lpat`) and typed (`tpat`) constructor fields are
  therefore represented by dummy locals but never tested or correctly bound.
- **Expected/done-when:** ordered nested field obligations test literal equality
  and portable typed tags, retain typed binders at the correct field position,
  fall through to the next source arm on failure, and produce exact identical
  distributed-join output on native VM/direct ASM. Add a focused multi-file
  regression and rerun all release gates; do not weaken the CoreIR matcher or
  add host reflection/fallback.
- **Fix/result:** nested type annotations are retained as `tpat`; ordered field
  obligations now enforce literal `__eq__`, portable `__isTag__`, and nested
  constructors before guards/bodies while preserving exact binder positions and
  fallthrough. The imported regression and public join are byte-exact on
  VM/ASM/build-jvm; the full release gate and conformance 11/11 pass.

## v21-native-md-interpolator-unbound — self-hosted front misses the built-in prefix
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 50715b7a3
     confirmed: no -->

**Status:** fixed (2026-07-11, `50715b7a3`), awaiting Sergiy confirmation;
found by codex while closing the native content-helper cutover.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/content.ssc` and the same command
  with `--bytecode` exit nonzero with `unbound global: md`; the public example
  cannot reach the already-native `doc` / `render` helpers.
- **Expected:** normative `md"..."` uses ordinary `$name` / `${expr}` string
  interpolation, drops blank leading/trailing lines, removes the minimum common
  space indent from nonblank body lines, and produces identical text on native
  VM, direct ASM, and deterministic `build-jvm`. Other/user-defined
  interpolator prefixes retain their existing dispatch.
- **Root cause:** `v2/lib/ssc1-front.ssc0` recognizes only `s`, `f`, and `raw`
  before a string token. It therefore parses the reserved `md` prefix as an
  ordinary global application even though the self-hosted interpolation builder
  and runtime `__mdStrip__` primitive already implement the required pure
  pieces.
- **Plan/done-when:** specify the language-owned contract, emit the existing
  strip primitive directly from the self-hosted front without changing the
  separately claimed lowerer, add a faithful assembled regression, then require
  the full content example, stage-2, release/dependency, corpus/parity, and
  affected conformance gates before landing.
- **Fix/verification:** the front now recognizes only the reserved `md` prefix
  before a string token, reuses `buildSInterp`, and emits `__mdStrip__` directly.
  Focused/full VM, ASM, and `build-jvm` are exact; stage-2 is source-exact,
  dependency/plugin/standard/slim/JRE gates pass, native corpus runtime success
  rises to 47, standard parity is 36/30/129 with no mismatch/one-sided row,
  runtime blockers fall to 18, and affected conformance is 17/17.

## v21-native-sql-recovery-parser-sentinel — loaded recovery source leaves `_err`
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 1bf9c7c06
     confirmed: no -->

**Status:** fixed (2026-07-11, `1bf9c7c06`), awaiting Sergiy confirmation; found by codex after fixing the wrapped-import
loss exposed the complete `dsl-sql-recovery.ssc` module closure.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/dsl-sql-recovery.ssc` and its
  `--bytecode` twin exit 1 with `native frontend rejected incomplete parse ...
  structural CoreIR contains parser sentinel _err`. The focused wrapped-import
  fixture already passes `82` on both lanes, so module loading itself is fixed.
- **Root cause:** the recovery evaluator contains three ordinary ScalaScript
  bind patterns, `case ok @ ParseOk(_, _, _)`. The self-hosted pattern parser
  stops after the bare binder, consumes `@` as an unknown expression operator,
  and emits `_err`; nested constructor and tuple patterns themselves are already
  correct. The compatibility frontend supports `Pat.Bind`.
- **Expected:** isolate the exact imported/root source syntax that owns `_err`,
  repair its general self-hosted parser/lowerer boundary, and keep the strict
  structural sentinel rejection. Never execute partial IR or suppress `_err`.
- **Plan/done-when:** inspect structural CoreIR and the assembled statement
  stream, add a focused bind-pattern regression, implement source-level
  `name @ Constructor(...)` ownership with the whole scrutinee and inner fields
  in scope, then require exact public
  VM/ASM output and the complete release gates before taxonomy retirement.
- **Fix/verification:** explicit `bpat` parsing and ordered lowering reuse the
  once-evaluated whole value, preserve nested field scope, and pad failure scope.
  Imported regression and the full sixteen-line public example are exact on
  VM/ASM; consolidated release and fresh conformance 11/11 pass.

## v21-native-reactive-ctor-bypasses-provider — fresh install loses subscriptions
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 04b7f5fd1 -->

**Status:** done (2026-07-11, VM `04b7f5fd1`, ASM `8cf29ede2`); found by codex when the native `doc`/`render` slice reran the
full plugin and `build-jvm` gates from a clean current-source installation.

- **Real-harness repro:** run `scripts/sbtc "installBin"`, then
  `bin/ssc run --native examples/signals-demo.ssc` (and `--bytecode` or a fresh
  `build-jvm` artifact). Output stops after `c=5 d=10` and
  `n=3 sq=9 cube=27`; the expected updates for `7`, `11`, and `4` are absent.
  The older binary in the shared checkout prints the full output because its
  staged lowerer predates K62.33, while its checked-in source does not.
- **Root cause:** current self-hosted lowering emits `Ctor("Signal", ...)` and
  `Ctor("ComputedSignal", ...)`; `v2/src/Runtime.scala` handles those tags as
  legacy `ForeignV(Array)` cells before the core-free reactive provider's
  registered globals can create subscription-aware `ReactiveSignal` values.
- **Expected/fix:** when a native provider global exists, the ctor path invokes
  it with the evaluated fields; the legacy raw-cell behavior remains only for
  bare-kernel execution. Fresh-install VM/ASM/build-jvm output must match the
  complete public example and restore the previously claimed release gates.
- **Verification:** reactive provider unit 4/4, full `signals-demo.ssc` exact on
  fresh VM/direct ASM/build-jvm, plugin/dependency/standard/slim/JRE gates pass,
  and affected conformance is 17/17.

## v21-native-doc-render-unbound — standard native host omits core content helpers
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 5b6bb6b5d -->

**Status:** done (2026-07-11, through `5b6bb6b5d`); found by codex while isolating the `examples/content.ssc`
cutover blocker.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, a focused `.ssc`
  program containing `render(doc("one", 2, true))` fails on both
  `bin/ssc run --native` and `--native --bytecode` because the standard
  core-free native host registers no `doc` or `render` handlers. The public
  `examples/content.ssc` is currently stopped one boundary earlier by the
  independently queued `md` lowering bug.
- **Expected:** `doc(parts...)` preserves its runtime values in source order;
  `render(doc)` writes their ordinary deterministic text joined by `\n`, with
  exactly the normal `println` trailing newline. Native VM, direct ASM, and
  `build-jvm` must agree without v1 `DocV`, `PluginBridge`, Scalameta, or a
  parser/renderer dependency.
- **Plan/done-when:** specify the runtime value and output contract, implement
  only the host-owned handlers in the existing v2 native host provider, add a
  faithful assembled regression plus provider unit coverage, and pass plugin,
  dependency/class-load, artifact, and affected conformance gates.
- **Root cause/fix:** the native host exposed neither helper, while the ASM
  global loader also lacked the VM's unresolved-handler fallback. Both lanes
  now resolve lexical-safe provider handlers, reuse one deterministic display
  function, and leave local definitions authoritative. Unit is 2/2; focused
  VM/ASM/standard/build-jvm, dependency/plugin/standard/slim/JRE, and
  conformance 17/17 all pass.

## v21-content-bind-copy-lane-divergence — structural copy is not portable
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 208ec4c60
     gate: tests/conformance/run.sh -->

**Status:** done (2026-07-11, `208ec4c60`); found by codex while running the required
`content-tables` INT/JS/JVM conformance after moving `contentBind` to pure
ScalaScript.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'content-tables' --no-memo`. Native VM/ASM
  and JS bind the table, but INT exits at `Strong.copy` because the portable
  source's explicit `(field, value)` structural-copy arguments are interpreted
  as two positional case-class fields; JVM source compilation rejects `copy`
  on the statically widened `ContentInline`/`ContentBlock` scrutinee.
- **Expected:** one pure `.ssc` binding definition must rebuild the same public
  tags on INT/JS/JVM/native VM/direct ASM/build-jvm; no backend-specific source,
  provider identity fallback, or duplicated Scala binding algorithm.
- **Plan/done-when:** bind each match arm to its concrete case, use ordinary
  positional case-class `copy`, and make the permanent v2 seed's generic
  record-copy primitive accept positional overrides as well as its existing
  explicit-name form. Add a focused seed regression, then require exact
  `content-tables` conformance plus the native structural binding smoke.
- **Verification:** the v2 seed regression is 3/3, `content-binding` and
  `content-tables` pass on INT/JS/JVM, native VM/direct ASM are exact, and the
  deterministic `build-jvm` artifact emits the same recursive binding output.

## v2-swift-ios-run-unbounded-error — domain source leaks a JVM stack trace
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: 08735b15a
     gate: tests/e2e/v2-swift-cli.sh -->

**Status:** done (2026-07-11, `08735b15a`); found by codex in the assembled
`tests/e2e/v2-swift-cli.sh` gate after the Xcode application route was enabled.

- **Real-harness repro:** fresh `scripts/sbtc installBin`, then
  `bin/ssc run --v2 --target ios tests/conformance/money-portable-v2.ssc`.
  It exits 1 as intended because the checked domain program has no NativeUi
  application, but stderr includes `Exception in thread "main"` and the full
  Scala/JVM stack from `SwiftV2Cli.buildXcodeApplication`.
- **Root cause:** `runV2IosTargets` is the only v2 Apple run adapter without the
  bounded command-level exception handler already used by the macOS route.
- **Expected/fix:** preserve the exact command-scoped diagnostic
  `run --target ios: checked program does not define a NativeUi application`,
  print no stack, and keep exit 1/no-v1-fallback. Gate through the freshly
  assembled CLI e2e, not a direct helper invocation.
- **Fix/result:** the iOS v2 adapter now owns the same bounded command exception
  boundary as macOS. Fresh `installBin` plus assembled `v2-swift-cli` e2e emits
  the exact one-line diagnostic, exits 1, contains no JVM stack, and never
  retries v1.

## v21-native-typeclass-dictionary-sentinel — explicit dictionaries lose members
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 8822fa710 -->

**Status:** fixed (2026-07-11, implementation `8822fa710`, regression
`0b596a075`, taxonomy `77da8e8e2`); found by codex while reducing the reviewed
`typeclass.ssc` language/runtime blocker after exact `summon` landed.

- **Real-harness repro:** the installed compiler-free `ssc-standard run
  --native` VM and `--bytecode` lanes both print the same first thirteen lines
  through Eq/Ord and sorting, then exit 1 with
  `__method__: no dispatch for .empty on 0`. Explicit
  `bin/ssc run --compat-frontend` prints the five remaining Monoid/Functor lines
  (`sum 15`, concatenation, repeat, doubled, squared) and exits 0.
- **Expected:** a named given is a first-class immutable method dictionary.
  Passing it as an ordinary parameter, returning or aliasing it, importing it,
  or storing it in a collection preserves its properties and callable members.
- **Root cause:** the imported-declaration filter omitted `given_obj` entirely.
  For local declarations, self-hosted lowering emitted each member correctly
  under a static `<given>_<member>` global but emitted the given value itself as
  `IrInt(0)`. Static source-spelled calls bypassed that sentinel; generic
  dispatch after ordinary value flow saw only integer zero.
- **Fix/result:** imported given objects are retained and the self-hosted
  lowerer constructs the same ordered portable `__mk_method_obj__` already used
  by the compatibility bridge, without removing static globals. Distinct
  imported dictionaries survive parameters, returns, aliases, a list, direct
  access, and exact summon; full `typeclass.ssc` prints eighteen exact lines on
  VM/ASM. The exhaustive gate reports 44/82 runtime, parity 31/35/129 with zero
  mismatch/one-sided rows, and fresh conformance 11/11.

## v2-native-table-payload-validator-drift — row payload descriptors validate differently by adapter
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room after three review rounds.

- **Real-harness repro:** construct `Field`, `WholeRow`, and `Fields` payloads
  through the v2 UI provider, generated Swift Host, and v1 compatibility
  adapter. The v2 provider validates only post actions, while the v1
  `fieldsPayload` path collect-drops non-String members and accepts empty or
  duplicate names; the resulting public descriptor depends on the adapter.
- **Expected:** every entry point uses the frozen exact validator: a Field name
  is one non-empty dotted path; Fields is a non-empty list of unique non-empty
  dotted paths; malformed values fail before an action/request is constructed.
- **Plan/done-when:** route all action constructors through one exact validator
  per runtime boundary, add negative gates for wrong types, empty/duplicate/
  malformed paths across v1, v2, and generated Swift, and obtain Rozum approval.
- **Review round 2:** JS still scalarized `Fields` members (rejecting valid
  compound JSON), rejected the valid empty-String `Field` body, and normalized a
  forged `wholeRow` descriptor carrying names. JVM helper coverage only searched
  generated text instead of executing rejection. These remain part of this bug.
- **Fix/verification:** all v2, Swift Host, v1, generated JVM, and JS entry
  points now enforce the same exact descriptor shapes. JS preserves arbitrary
  JSON `Fields` values and an empty String `Field`; the emitted JVM helper is
  executed through `scala-cli`. Reviewer-confirmed gates: JS 52/52, JVM 2/2,
  v2 14/14, fetch 12/12, Swift 34/34, and CLI 6/6.

## v2-native-table-five-field-registry-drift — v2 field layout disagrees with constructed ABI value
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room.

- **Real-harness repro:** the v2 `UiNativePlugin` constructs
  `NativeUiDataTable(siteId, source, columns, actions, rowKeyPath)` but its
  registered named-field layout still declares four fields. Positional access
  can see the new value while named access/reflection cannot address it.
- **Expected:** the registry and every producer/consumer agree on the exact
  five-field ABI layout, including named `rowKeyPath` at index four.
- **Plan/done-when:** update the authoritative layout and add an executable
  named-field/arity regression before the five-field value reaches Swift.
- **Fix/verification:** registry reflection and `Prims.methodOp` now address the
  exact five-field layout; `v2NativeUiPlugin/test` passes 14/14.

## bridge-v2tov1-openapi-oom — imported OpenAPI conversion exhausts the heap
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 2f3994b31 -->

**Status:** done (2026-07-11, `2f3994b31`); reported and confirmed by busi
against the published runtime.

- **Real-harness repro:** assemble `bin/ssc` at `495467456`, then run busi's
  current `tests/v2/api.ssc` with `SSC_JIT=off`. All serializer assertions pass
  through annual PIT; evaluating/parsing imported `openApiJson` then exhausts
  even `-Xmx2g` in `PluginBridge.v2ToV1`. The identical current busi source
  exits 0 and reaches `ALL OK - v2 api` on assembled `3666ccb7a`.
- **Impact:** busi cannot pin the runtime that contains its required fair HTTP
  mutation gate and fast request/session parity fixes.
- **Expected:** imported nested JSON values convert in time and memory bounded
  by their finite tree, with no retained/cyclic bridge expansion.
- **Plan/done-when:** bisect the assembled range, retain a multi-file regression
  for the first failing boundary, fix the owning runtime path, and pass focused
  modules, affected conformance, the real busi API/OpenAPI gate and its full
  release suite on the same published SHA.
- **Root cause/fix:** `v2ToV1` recursively converted every `DataV` field three
  times to populate the named map, positional map and `fieldsArr`. The
  self-hosted JSON ADT exposed the pre-existing exponential `3^depth`
  expansion. It now converts each field once and shares the exact converted
  value across all three `InstanceV` layouts.
- **Verification:** the new 18-level imported JSON fixture OOMs the old
  published runtime with `-Xmx512m`, while the fixed assembled runtime reaches
  its sentinel. PluginBridge 31/31, JSON conformance 4/4 and the original busi
  `tests/v2/api.ssc` are green with the same 512 MiB bound. Busi then passed
  exact full `make v2-test`, `make v2-test-js`, all four live HTTP/restart
  checks and canonical Chromium 6/6 on the published runtime. The broader
  FrontendBridge run is 194/195; its sole `tkv2-pwa` failure is the separately
  claimed hf-6 standard-tier provider cutover, not this bridge change.

## v21-native-parser-dsl-stub-values — parser DSLs exit successfully with placeholders
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 9d5f13f95 -->

**Status:** fixed (2026-07-11); found by codex while validating the clean
`PMapped/2` assembly result against the compatibility frontend. Fixes:
`9d5f13f95`, `0b5d1c69c`, `d4cc66736`; verification: `8a411cd9a`.

- **Real-harness repro:** native VM/direct ASM are byte-identical and exit 0,
  but `dsl-json-parser.ssc` renders arrays/objects as `[Stub]` / `{Stub}` and
  `dsl-yaml-like.ssc` renders the parsed document and selected values as
  `Stub`. `bin/ssc run --compat-frontend` renders the complete JSON values and
  YAML tree/queries (`localhost`, `myapp`, `10`).
- **Expected:** the self-hosted native pipeline preserves the parser mapping,
  list/fold, tuple, and rendering semantics needed for the examples' canonical
  output; successful exit must not hide placeholder values.
- **Root cause:** the imported parser combinator module registered an extension
  named `map`. The self-hosted type-erased lowerer treated that name as a
  global override at every selected call site, so
  `items.asInstanceOf[List[Any]].map(f)` called the parser extension and built
  `PMapped(items, f)` instead of mapping the list. The following `mkString`
  therefore received a parser ADT and returned `Stub`. YAML then exposed two
  independent frontend losses: local tuple destructuring bound only `_1`, and
  typed match patterns discarded their nominal runtime test so a wildcard arm
  replaced the `IndentContext` arm.
- **Later first-loss boundaries:** after routing extension calls through
  `__methodOrExt__`, JSON is exact. YAML first exposes local tuple
  destructuring that binds only `_1` (`value` becomes an unbound global), then
  exposes erased typed-pattern semantics in imported `layout.ssc`: both
  `case ic: IndentContext` and `_` lower as defaults, so the wildcard wins and
  `block.withIndent(3)` executes at column 1. The direct probe prints a native
  indentation error where compatibility returns
  `ParseOk(List(host), , Position(6))`.
- **Fix/result:** imported selected extensions now use the existing
  `__methodOrExt__` member-first dispatcher; tuple destructuring binds every
  `_sel__N`; typed patterns retain their outer nominal head and lower ordered
  `__isTag__` checks. The focused fixture prints
  `1:alpha|2:beta|3:gamma`, the layout probe reaches column 3, and VM/direct ASM
  match all seven JSON and seventeen YAML expected lines with empty stderr and
  no `Stub`. The exhaustive release gate and fresh conformance 11/11 pass with
  zero mismatch or one-sided runtime rows.

## v21-k62-flat-tuple-pattern-regression — flat tuple values keep nested `Pair` patterns
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 7f6821856 -->

**Status:** done (2026-07-11, `7f6821856`); found and confirmed by codex in the
mandatory native-entry gate after K62.20 (`28061083a`) changed tuple
expressions of arity three or greater to flat `TupleN` values.

- **Real-harness repro:** `bin/ssc-standard run --native
  tests/fixtures/v21-native/nested-tuple-pattern.ssc` prints `left` followed by
  `()` on VM and direct ASM; the established exact output is `left` followed by
  `left+right`.
- **Expected:** pattern `Some((left, '+', right))` matches the flat `Tuple3`
  constructed for its nested tuple and binds `left`/`right` on both lanes.
- **Root cause:** K62.20 updated `lowerTuple` to emit flat `TupleN`, while
  `ssc1-front.ssc0::tuplePat` still emits the obsolete right-nested `Pair`
  pattern for arity three or greater.
- **Plan/done-when:** make tuple expression and pattern shapes agree (Pair for
  arity two, flat `TupleN` for arity three or greater), retain existing nested
  and flat tuple regressions, and rerun native-entry, corpus/parity/taxonomy,
  release, and fresh conformance gates.
- **Fix/verified:** `tuplePat` now emits the same Pair/2 versus flat `TupleN`
  representation as expression lowering. The existing fixture again prints
  `left` / `left+right` exactly on VM and ASM; every release gate and fresh
  conformance 11/11 pass.

## v2-swiftui-keyed-store-rollback-publication — failed provisional render leaks revisions
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the second
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** a keyed render writes a live signal and then a later
  item throws. Host restores signal/owner state, but provisional `onWrite`
  already advanced Store revisions, derived caches, and dependency edges.
- **Expected:** Host values/owners and Store cells/revisions/caches/dependencies
  commit or roll back atomically; no mounted subtree observes a failed render.
- **Plan/done-when:** buffer observer effects for the keyed batch and flush
  after commit (drop on rollback), or snapshot/restore Store state. Gate
  write-then-throw with unchanged value/revision/cache/dependencies and clean
  subsequent reconcile.

## v21-native-reactive-effect-parsed-as-declaration — top-level effects disappear
<!-- status: fixed
     lane: native
     area: front
     fixed-in: dae51ecab
     confirmed: no -->

**Status:** fixed (2026-07-11, `dae51ecab`), awaiting Sergiy confirmation;
found by codex while gating the core-free reactive provider.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run --native examples/signals-demo.ssc` (or `--bytecode`). Both
  assembled lanes print only `0`, `5`, `10`; the expected `c=...` and `n=...`
  effect lines are absent. Dumping the staged self-hosted CoreIR shows every
  top-level `effect { ... }` missing while the following signal writes remain.
- **Expected:** `effect { body }` is parsed as an ordinary global call with a
  zero-argument thunk and executes in document order; `effect Name:`
  declarations remain parse-only declarations.
- **Root cause:** the lexer classifies `effect` as a keyword and
  `parseOneStmt` unconditionally routes every keyword-led `effect` through the
  declaration skipper, including a reactive call whose next token is `{`.
- **Plan/done-when:** discriminate the `{` call form before the declaration
  branch, keep existing algebraic-effect declaration fixtures green, and pin
  the complete `signals-demo.ssc` output on assembled VM/direct ASM and
  standalone `build-jvm` lanes.
- **Fix/verified:** `parseOneStmt` now routes the keyword-plus-`{` form through
  ordinary expression/block-argument parsing and leaves named declarations on
  the erasure path. The complete demo is exact on assembled VM/direct ASM and
  build-jvm; algebraic `effects` conformance is green on all four lanes.

## v21-native-dynamic-toint-dropped — selected String conversion vanishes
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 63ab041a6
     confirmed: no -->

**Status:** fixed (2026-07-11, `63ab041a6`), awaiting Sergiy confirmation;
found by codex while the new core-free Storage provider advanced
`storage-demo.ssc` into `bumpCounter`.

- **Real-harness repro:** after staging the native frontend, inspect/run
  `Storage.get(key).getOrElse("0").toInt + 1` from `storage-demo.ssc`. The
  generated CoreIR applies `__arith__("+", <String>, 1)` and contains no
  `__str_toInt` call; VM later reports `i->str: not Int`, direct ASM reports
  `expected Int, got "01"`.
- **Expected:** zero-argument selected `.toInt` lowers through the existing
  portable `__method__("toInt", receiver)` contract on both lanes before
  arithmetic. Unlike the String-only `__str_toInt` helper, method dispatch also
  preserves established numeric receiver conversions and normal parse failure.
- **Plan/done-when:** add a focused dynamic String conversion fixture (including
  an Option/getOrElse receiver), repair selector lowering without changing
  numeric `.toString`, rerun `storage-demo.ssc` and every native release gate,
  and keep `fixed` until Sergiy confirms.
- **Root cause/fix:** `resolveField` erased selected `.toInt` whenever its
  resolved receiver was not syntactically recognized as a String, so a dynamic
  `Option.getOrElse` result reached arithmetic unchanged. It now emits portable
  `__method__("toInt", receiver)` dispatch, preserving both dynamic String and
  numeric conversions. The focused VM/ASM fixture and full Storage/release gates
  pass; keep this entry `fixed` until Sergiy confirms.

## v21-runtime-taxonomy-ui-remote-table-stale — successful UI row remains blocked
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 4cdca959c -->

**Status:** fixed (2026-07-11, `4cdca959c`); found by codex in the full
post-NativeUi v2.1 release gate after `ui-remote-table.ssc` moved from both-fail
to identical; waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** generate the current corpus/parity reports and run
  `scripts/v21-runtime-taxonomy`; it exits non-zero with
  `stale or reclassified manifest row: ui-remote-table.ssc`.
- **Expected:** a standard example that now runs identically on VM/direct ASM is
  absent from the blocking runtime manifest; taxonomy counts match the report.
- **Plan/done-when:** verify the row is identical with zero one-sided failure,
  remove only its stale manifest entry, tighten smoke ceilings/counts, and rerun
  runtime taxonomy plus the full portable release gates. Keep `fixed` until
  Sergiy confirms.
- **Fix/verified:** removed the single stale row and tightened standard-provider
  to 22, blockers to 40, and total rows to 52. Runtime/sentinel taxonomy,
  portable gates, and fresh 11/11 conformance pass.

## v21-native-serve-ownership-conflict — NativeUi duplicates HTTP `serve`
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 727c806e8
     confirmed: no -->

**Status:** fixed (2026-07-11, `727c806e8`); found by codex while re-running the
native release gates after rebasing onto NativeUi runtime commit `1f3ca3962`;
waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, every
  `bin/ssc run --native <portable-file>` invocation exits before evaluation with
  `native plugin ownership conflict for intrinsic 'serve': 50-http and 55-ui`;
  both VM and direct ASM are affected.
- **Expected:** the compiler-free native route loads HTTP and UI providers
  together with unique intrinsic ownership, and unrelated portable programs
  start normally.
- **Plan:** identify the new NativeUi declaration/handler that claims the
  HTTP-owned global, preserve the public UI surface without duplicate ownership,
  add an installed-binary regression that loads the full provider set, then rerun
  native-entry, corpus, strict parity, taxonomies, and fresh conformance.
- **Done-when:** full-provider startup has no duplicate owner, focused HTTP/UI
  behavior stays green, and the fix remains `fixed` until Sergiy confirms.
- **Fix/verified:** UI registers only the provenance-rewritten reserved ABI-v1
  name; HTTP remains the sole public `serve` owner. NativeUi is 14/14,
  installed native-entry passes, and the full corpus/parity gates load both
  providers without conflict.

## v2-swift-global-reg — generated Swift rejected ordinary top-level values
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 0174796ef
     confirmed: no -->

**Status:** fixed (2026-07-11, `0174796ef`); found by codex while running the
new user-facing Swift AppCore example, waiting for Sergiy confirmation before
`done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run `bin/ssc
  run-swift examples/swift/appcore-money.ssc` with a top-level `val total = ...`.
  Generation stopped with `swift backend: unsupported primitive 'global.reg'`.
- **Expected:** compiler-internal top-level registration updates the target
  runtime's dynamic global table, matching the v2 VM; it is not an unsupported
  user intrinsic and must not become a no-op when later definitions read it.
- **Root cause:** the checked bridge lowers top-level value initialization to
  `global.reg(name, value)`. JVM/Rust already own backend handling, but the new
  Swift primitive vocabulary omitted it because the initial checked Money gate
  used only inline expressions.
- **Fix/verified:** AppCore now stores the value in the machine's mutable global
  environment. `SwiftV2CliTest` builds and runs the unchanged example with real
  SwiftPM; it prints `$3.75`, `1.2100`, and the exact allocation list. Focused
  Money conformance remains 1/1.
- **Done-when:** the user example stays in the real Swift CLI test and Sergiy
  confirms ordinary top-level values in the Swift workflow.

## v2-swift-swiftui-native — v2 has no proven native Swift/SwiftUI path for macOS and iOS
<!-- status: unknown
     lane: native
     area: front -->

**Status:** CLI/BUILD LAYER FIXED by swift-sibling work since the report; the SwiftUI
run/simulator/device/signing half remains (feature-scale). Verified (opus, 07-13, Swift
6.3.2/Xcode 26.5): the CLI is now TIERED — `build`/`package`/`publish` are under `bin/ssc-tools`
(`bin/ssc` correctly rejects them). `bin/ssc-tools build --v2 --target macos <ios-hello.ssc>`
works and writes a real Swift package + xcodeproj (the old "`--v2 is not a directory`" and
flag-order failures are gone — `BuildCmd` parses `--v2`; `run --target macos|ios` routing is
present in Main.scala). The produced package builds to a real `.app` (see
`v2-swift-xcode-contract-gaps`). REMAINING: SwiftUI simulator/device runs, code signing, and
distribution — genuine multi-day feature work owned by the swift specialists. _Original report:_
reported by Sergiy in the Codex session.

- **Signing sub-slice — credential-free tier landed (opus, 07-13, CLI/packaging
  layer only; Swift 6.3.2/Xcode 26.5).** Plain `ssc-tools package --target macos
  <file.ssc>` (no `--distribution`, no team id) now ad-hoc codesigns the built
  `.app` (`codesign --sign -`, no cert) and proves it with `codesign --verify
  --deep --strict` before returning, so the packaged bundle is signed and
  launch-ready with zero Apple credentials. Verified end-to-end on both
  `examples/swift/appcore-nativeui.ssc` and the headline
  `examples/frontend/ios-hello/ios-hello.ssc`: BEFORE (old output, xcodebuild
  `CODE_SIGNING_ALLOWED=NO`) → no `_CodeSignature` seal, `codesign --verify` =
  "code object is not signed at all" (exit 1); AFTER → `valid on disk` /
  `satisfies its Designated Requirement` (exit 0), `Signature=adhoc` (not
  linker-signed), `TeamIdentifier=not set`, `CFBundlePackageType=APPL`. `spctl
  -a` still rejects ad-hoc (exit 3) — expected; Gatekeeper needs Developer ID.
  Changes confined to `SwiftV2Cli.packageMacos`/`adhocSignAndVerify`
  (`SwiftV2Commands.scala`) + `PackageCmd` (`Main.scala`); the 4 renderer files
  untouched. Contract documented in `specs/v2-swift-swiftui-native.md`; regression
  in `SwiftV2CliTest`. STILL REMAINING (credential-blocked, not implementable in
  this env): Developer-ID `--distribution` codesign+notarize+DMG, TestFlight/App
  Store publish, and real device runs — all need an Apple signing certificate /
  notary profile / provisioning we do not have.

- **Reported symptom:** ScalaScript 2.0 has a problem with both the Swift backend
  and the SwiftUI toolkit for desktop and mobile applications.
- **Real-harness repro (assembled `bin/ssc`, 2026-07-10):** after
  `scripts/sbtc "installBin"`, the local Apple toolchain was Swift 6.3.2 and
  Xcode 26.5. `bin/ssc build --v2 --target macos
  examples/frontend/ios-hello/ios-hello.ssc` exits 1 with `Error: --v2 is not
  a directory` because `BuildCmd` does not parse a v2 lane flag. Putting the
  flag before the command (`bin/ssc --v2 build ...`) dispatches `RunV2` on the
  positional filename `build` and throws `FileNotFoundException`. `bin/ssc run
  --v2 --target macos examples/frontend/ios-hello/ios-hello.ssc` exits 0 but
  produces no native package: `RunCmd` returns through `RunV2.run` before it
  computes or dispatches `targetSelection`, so the macOS target is ignored.
- **Legacy native-path repro:** `bin/ssc build --target macos
  examples/frontend/ios-hello/ios-hello.ssc --out
  target/swift-legacy-repro` reaches `JvmGen` but fails the real generated
  `.sc` compile with 27 errors. The first is `.style(padding = 8)` calling an
  overload without `padding`; the generated `std.ui.primitives` block then
  cannot resolve bare `View` and `EventHandler` in its imports/signatures and
  exposes a missing-default-argument call to `_ssc_ui_emit_to_dir`. No Swift
  package is written.
- **Expected:** selecting v2 must compile the checked v2 program through an
  explicit Swift backend and the shared SwiftUI View toolkit, producing native
  macOS and iOS packages from the same `.ssc` source. It must not silently parse
  or lower through v1 and must not choose SwiftUI for a web-serving route.
- **Root-cause direction:** there are three independent boundaries to fix:
  `BuildCmd` has no v2/native lane selection; `RunCmd` selects `RunV2` before
  native target dispatch; and `buildSwiftUIPackage` unconditionally parses via
  v1 `Parser` and executes `JvmGen.generate`. Even that compatibility route is
  red because `JvmRuntimeUiPrimitives.source` is inserted into generated code
  without a self-contained type/import contract and the checked-in iOS example
  uses a stale style surface. The v2 tree has no Swift generator. Preserve one
  shared View/toolkit IR rather than cloning toolkit semantics into a
  backend-specific parser.
- **V2 core/CLI progress (2026-07-11):** `f20b47b35` closes the checked AppCore
  domain backend and `159e45625` adds assembled `emit-swift`/`run-swift` plus
  v2-default Apple build/run routing. Both original flag-order failures are
  covered: `--v2` is consumed as a lane flag and Apple target dispatch happens
  before the generic VM return. `0174796ef` adds ordinary top-level globals.
  macOS executes exact Money under real SwiftPM; iOS generation declares the
  correct deployment platform. The bug remains open because NativeUi/SwiftUI
  app rendering, simulator/device, signing, and distribution gates are still
  the remaining user-reported half.
- **Done-when:** an assembled real-harness regression proves v2 owns Swift
  generation, a common toolkit example emits/builds for macOS and iOS as far as
  the installed Apple toolchain permits, affected conformance is green, and the
  landed SHA plus actual root cause are recorded here. Keep `fixed` until Sergiy
  confirms the original workflow.
- **Legacy native-path repro — FIXED (2026-07-10, `swiftui-legacy-real-harness`
  sub-slice, busi-side `claude-code` agent).** `bin/ssc build --target macos
  examples/frontend/ios-hello/ios-hello.ssc` now writes a real Swift package
  and `swift build` links it — the first time any real parsed `.ssc` module
  has compiled through this path (every prior SwiftUI test hand-builds a
  Scala `View` literal, bypassing `Parser`/`JvmGen` entirely). Six distinct,
  independently-verified bugs, not one:
  1. `JvmGen.hoistSscImportsIntoObjectStd`'s hardcoded `ui.primitives.{...}`
     import listed capitalized `Signal` (never a real member — extern-filtered
     out of the JVM backend's `object primitives`; a separate top-level
     `type Signal[A]` alias exists only in the swiftui-DSL preamble branch) and
     was missing six real names (`seedSignal`, `forKeyedView`, `emptyHeaders`,
     `fetchActionTo`, `fetchCaptureAction`, `rowEditAction`) that had silently
     drifted out of sync.
  2. `JvmGenPreamble`'s `frontendName == "swiftui"` branch never got the
     `text(String)` shadow-fix (`beats extension (r: Response.type) def
     text(body: Any)`) that the non-swiftui branch already had — the ONLY
     branch reachable via `--target macos|ios` lacked it.
  3. `JvmGenPreamble` re-declared `dataTableView` as a byte-for-byte duplicate
     of `JvmRuntimeUiPrimitives.scala`'s version, ambiguous once the (always-
     active for any frontend) hoisted import also brought it in; `dataTable`'s
     wrapper needed a qualified call + loosened return type instead.
     (`JvmRuntimeUiPrimitives.scala` itself needed NO change — its bare `View`/
     `EventHandler` correctly resolve via plain sibling-member visibility once
     the real `std/ui/primitives.ssc` module is genuinely merged in, which
     requires the caller to actually import from it, e.g. the CLI's own
     minimal test fixture never did.)
  4. `std/ui/lower.ssc`'s intentional idempotent-passthrough catch-all
     (`case alreadyLowered => alreadyLowered`, Layer 2 of
     `specs/js-backend-ui-render-gaps.md`) type-checks fine for the
     interpreter/JS backends (dynamically typed) but not JVM-generated Scala,
     where the match's static scrutinee type is `TkNode`, not the declared
     `View` return type — needs `.asInstanceOf[View]`.
  5. `std/ui/lower.ssc`'s `KeyedForNode` case pinned its callback parameter to
     an explicit `(item: Any)`, conflicting with `forKeyedView[A]`'s own
     existential `A` inferred from the case's own type parameter; removing the
     explicit annotation lets both infer consistently.
  6. `SwiftUIEmitter.scala`'s `View.ShowSignal` case appended the `if` block's
     closing brace unconditionally AND again at the start of a non-empty
     `elseClause`, emitting invalid Swift (`}\n} else {`) for every
     `showWhen`/dynamic-condition view — a real Swift syntax error, not a Scala
     one, only caught by actually running `swift build` on the output.
  New regression: `SwiftUiRealFixtureBuildTest` (`v1/tools/cli`), gated on
  `assume(swiftAvailable)`, drives the real `examples/frontend/ios-hello/
  ios-hello.ssc` fixture (rewritten off its stale `Signal`/`Column`/`Text`
  aspirational DSL onto the real `std/ui` API busi's production app actually
  uses) through `buildSwiftUIPackage(..., runSwiftBuild = true)` and asserts a
  real `Ioshello` executable is produced — mirrors `RustGenCargoSmokeTest`'s
  "actually run the toolchain, don't string-match" gate, which is exactly the
  class of bug (5 of 6 above) a string-match-only suite would have missed.
  118 `frontendSwiftUI` tests + 26 existing `SwiftUIBuildCliTest` cases +
  4 `std/ui/lower.ssc`-touching conformance fixtures (`tkv2-keyed-for`,
  `tkv2-raw-html`, `tkv2-textfield-reactive-label`, `tkv2-tri-state`, INT+JS)
  all still green — no regression. The v2-native Swift backend itself remains
  open, owned by the `v2-swift-swiftui-native` claim.

## v21-native-bytecode-vm-prepass-state — direct ASM run depends on VM compilation side effects
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: e4f16baaf
     confirmed: no -->

**Status:** fixed (2026-07-10, `e4f16baaf`); waiting for human confirmation
before `done`.

- **Found by:** codex while running the TI-6.3 post-source-map regression gate.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run `bin/ssc run
  --native --bytecode examples/hello.ssc`. The command exits nonzero with
  `run --native: None.get`; the same source succeeds through native VM and the
  self-contained `build-jvm` direct-ASM JAR.
- **Expected:** in-memory direct ASM consumes the same checked `Program` and
  initializes generated globals through `JvmByteGen.install`, just like the
  artifact entry; unrelated VM compiler optimizations/dispatch must not change
  its result.
- **Root cause:** `RunNativeV2.runBytecode` calls
  `Compiler.compileWithGlobals` solely to seed `Emit.globalsRef` before emitting
  ASM. Since TI-6.1 the generated `install()` evaluates/registers value defs
  itself. The redundant VM prepass now observes installed plugin handlers and
  takes the new VM-only App(Global)->Prim route, leaving state that makes the
  generated hello path evaluate `None.get`.
- **Fix direction:** initialize a fresh mutable generated-global map and rely on
  `JvmByteGen.install`, eliminating the VM compilation prepass from the direct
  ASM lane. Keep the artifact and in-memory lanes on the same initialization
  contract.
- **Done-when:** native VM, in-memory direct ASM, and `java -jar` hello/import/
  ordered-value fixtures all pass; `v2-*` conformance stays green.
- **Fix:** the in-memory ASM lane now starts from an empty mutable generated
  global map and lets the generated `install()` method evaluate/register every
  lambda and value definition. The VM compiler is no longer invoked merely for
  initialization side effects.
- **Verified:** `tests/e2e/v21-native-entry-smoke.sh` PASS across VM/direct ASM,
  `tests/e2e/v21-native-plugin-boundary-smoke.sh` PASS, artifact e2e PASS, and
  affected conformance 8/8.

## v21-native-front-eager-plugin-val — plugin-backed top-level `val` runs before earlier statements
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 5db137a20
     confirmed: no -->

**Status:** fixed (2026-07-10, `5db137a20`); waiting for human confirmation
before `done`.

- **Found by:** codex while adding the native SQL provider boundary.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run a native file
  containing `Db.execute(CREATE)`, `Db.execute(INSERT)`, then
  `val rows = Db.query(SELECT)` through both `bin/ssc run --native` and
  `bin/ssc run --native --bytecode`. Both fail with H2 `Table "PEOPLE" not
  found`; replacing the `val` with an inline query after the writes succeeds.
- **Observed:** the native lowerer emits the plugin-backed `val` as eager global
  initialization, so its SELECT runs before preceding entry statements. The
  same ordering trap previously forced the native HTTP server fixture to inline
  `httpGet` after `serveAsync`. A second assembled repro in the native State
  provider showed the scope variant: `val inside = runState(...)` inside an
  outer `runState` thunk is hoisted out of the thunk and its following use fails
  as `unbound global: inside`; constructing the nested result inline succeeds.
- **Expected:** effectful/plugin-backed top-level values preserve source order;
  an initializer may not run before preceding statements on either VM or ASM.
- **Root cause:** `ssc1-lower` emitted every top-level immutable `val` as an
  eager `IrDef`, outside the document-ordered entry. Independently, the layout
  pass discarded newlines directly inside explicit braces, so a line beginning
  with `(` after a block-valued initializer was parsed as extra application
  arguments and the local binder appeared as an unbound global.
- **Fix:** top-level immutable values and tuple bindings now use entry-initialized
  global cells, while Scala-style newline inference emits separators only when
  the adjacent tokens can end/start statements. The assembled plugin-boundary
  gate runs both faithful fixtures on VM and direct ASM and compares exact
  output.
- **Owner/slice:** `v21-ti-native-front-parity`. The active native-front sibling
  owns named/default-argument work; rebase and coordinate before editing the
  shared self-hosted frontend files.
- **Done-when:** an assembled VM/ASM regression keeps the `val rows = Db.query`
  shape after DDL/DML and prints the row, with `v2-*` conformance green.
- **Verification:** `tests/e2e/v21-native-plugin-boundary-smoke.sh` PASS;
  `tests/e2e/v21-native-entry-smoke.sh` PASS; `tests/conformance/run.sh --only
  'v2-*' --no-memo` 8/8; `scripts/native-front-corpus` completes all 195 rows
  without frontend/checker timeout or crash.

## v2-run-plugin-temp-tree-leak — RunV2 leaves extracted plugin JAR trees
<!-- status: fixed
     lane: native
     area: front
     fixed-in: 0ccecb44d
     confirmed: no -->

**Status:** fixed (2026-07-10, `0ccecb44d`); waiting for human confirmation
before `done`.

- **Found by:** codex while designing the TI-3 in-process native entry after
  fixing the analogous `SscpkgLoader` lifecycle bug.
- **Real-harness repro:** run `bin/ssc run --v2 examples/hello.ssc` with an
  isolated `java.io.tmpdir`; after process exit, an `ssc-v2-plugins*` directory
  and its extracted JAR children remain.
- **Expected:** the temporary URLClassLoader inputs live for the CLI process and
  the complete extraction tree disappears at process exit.
- **Root cause:** `RunV2.loadPluginJars` calls `tmp.deleteOnExit()` before
  `extractIntrinsicsJars` writes child JARs, but never registers those children.
  JVM shutdown cannot delete the non-empty root. This path is separate from
  `SscpkgLoader`, so `784ac95d3` does not cover it.
- **Fix direction:** register each extracted JAR after copying it, preserving
  reverse-order file-before-root cleanup; extend the assembled temp-lifecycle
  smoke to reject both `sscpkg-*` and `ssc-v2-plugins*` survivors.
- **Owner/slice:** `v21-ti-native-front-production-entry` (`RunV2.runNative`
  reuses this plugin loader).
- **Fix:** `RunV2.loadPluginJars` now registers every extracted JAR with
  `deleteOnExit()` after it is copied. Java therefore deletes the children
  before the already-registered root at process shutdown.
- **Verified:** assembled `tests/e2e/sscpkg-temp-cleanup-smoke.sh` and
  `tests/e2e/v21-native-entry-smoke.sh` PASS with an isolated
  `java.io.tmpdir`; the cleanup smoke rejects both `sscpkg-*` and
  `ssc-v2-plugins*` survivors; affected `v2-*` conformance 8/8.

## sscpkg-loader-temp-tree-leak — every CLI process leaves extracted plugin directories
<!-- status: fixed
     lane: native
     area: cli
     fixed-in: 784ac95d3
     confirmed: no -->

**Status:** fixed (2026-07-10, `784ac95d3`); waiting for human confirmation
before `done`.

- **Found by:** codex while running the TI-2 assembled-CLI corpus baselines; the
  host temp directory reached tens of thousands of `sscpkg-*-intrinsics*`
  entries and eventually failed plugin loading with `No space left on device`.
- **Real-harness repro:** create an empty sandbox and run
  `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<sandbox> bin/ssc run
  examples/hello.ssc`; after the JVM exits, `<sandbox>` still contains one
  non-empty extraction directory per packaged plugin.
- **Expected:** extracted intrinsic/source trees live for at most the CLI JVM
  lifetime and are gone after process exit.
- **Root cause:** `SscpkgLoader.load` creates the root with `os.temp.dir`, whose
  default `deleteOnExit=true` registers only the then-empty directory. It later
  writes intrinsic JAR children without registering them. JVM shutdown cannot
  delete the non-empty root, so every invocation leaks the whole tree. The
  analogous `extractSources` path also creates unregistered descendants.
- **Fix direction:** register every extracted file and nested directory for
  reverse-order deletion (or own an explicit recursive shutdown cleanup), while
  preserving the paths for the lifetime of URL classloaders/callers. Add a
  subprocess/assembled-CLI regression that asserts an isolated temp root is
  empty after exit.
- **Owner/slice:** urgent `v21-ti-sscpkg-temp-lifecycle`, before more corpus
  sweeps and the slim/plugin-runtime work.
- **Fix:** after intrinsic/source extraction, `SscpkgLoader` walks the complete
  temp tree and registers descendants parent-first. Java's reverse-order
  shutdown hook therefore removes files, nested directories, and finally the
  root while keeping every path alive for the process lifetime.
- **Verified:** `core/testOnly scalascript.compiler.plugin.SscpkgLoaderTest`
  12/12; assembled `tests/e2e/sscpkg-temp-cleanup-smoke.sh` PASS with an
  isolated `java.io.tmpdir`; affected `v2-*` conformance 8/8; `git diff
  --check`.

## v2-type-ascription-pattern-no-op — `case _: T =>` silently matched everything (type test dropped)
<!-- status: fixed
     lane: native
     area: front
     fixed-in: unrecorded
     confirmed: no -->

**Status:** fixed (2026-07-10); waiting for human confirmation before `done`.

- **Found by:** claude, while root-causing why `case _: PSameIndent =>` never
  fired in std/parsing's `runLayout` (surfaced during the dsl-yaml audit —
  the ctor pattern `case PSameIndent(r) =>` matched but the type-ascription
  `case _: PSameIndent =>` did not).
- **Repro before fix** (`bin/ssc run`):
  ```
  case class A(x: Int) extends P
  def check(p: Any) = p match { case _: A => "is-A"; case _ => "not-A" }
  check(A(1))   // v1: is-A   v2 + --bytecode: not-A   (WRONG)
  ```
- **Root cause:** the frontend dropped the ascribed type in BOTH lowering paths.
  `convertPat` mapped `Pat.Typed(Pat.Wildcard(), T)` → `(None, Nil)` (a plain
  wildcard → the CT.Match *default* arm), and `flattenPattern`'s
  `Pat.Typed(inner, _)` recursed into `inner` discarding the type. So
  `case _: A => …; case _ => …` compiled to two default arms; the later `case _`
  overwrote the first, and `_: A` never matched. Both v2 lanes (VM + bytecode)
  shared the bug because it is purely in FrontendBridge; v1 was correct.
- **Fix:** emit a runtime tag test for a type-ascription pattern when the type
  resolves to a KNOWN concrete DataV tag set — a registered case class (single
  tag), a sealed trait / enum (its transitive subtype tags, via a new
  `subtypesOf` registry populated from `extends` clauses + enum cases incl.
  `Defn.RepeatedEnumCase`), or `Option`/`Either`. Unknown / type-parameter /
  `Any` / scalar / non-DataV-collection types return `None` and keep the
  historical unconditional-wildcard behavior (conservative — the fix only ever
  ADDS a discriminating test where the tag set is fully known, never a false
  negative). `case _: T =>` now routes to the general if-chain (needsGeneralChain)
  so `flattenPattern` can attach the test; the test is arity-independent via a
  new `__isTag__` sentinel arity `-1` (avoids the Request injected-field landmine).
- **Verified:** minimal + comprehensive (concrete class, sealed trait, enum incl.
  comma-grouped zero-arg cases, `x: T` binding form, non-DataV negatives) all
  correct on v1 / v2 / --bytecode. Gate: tests/conformance/v2-type-ascription-pattern.ssc.
  V2ConformanceTest 102 pass / 2 pre-existing fail (graph-edge-display,
  tkv2-typed-client-derived); corpus batch 155 PASS / 7 FAIL (all 7 environmental
  — missing files/env/DB — no pattern-match regressions), up from 154/8.

## v2-jvm-backend-echo-macos — shell `echo "$text"` can corrupt generated/source text on macOS
<!-- status: fixed
     lane: v2-jvm
     area: runtime
     fixed-in: a4f7662be
     confirmed: no -->

**Status:** fixed (2026-07-10, `a4f7662be`); waiting for external
confirmation only if a macOS reporter rechecks the helper paths.

- **Found by:** codex while working the BACKLOG
  `v2-jvm-backend-echo-macos` harness gotcha.
- **Repro class:** any shell helper that stores generated/source text containing
  backslash escapes such as `split("\n", -1)` in a variable and later pipes it
  with `echo "$var"` can corrupt the text on shells whose `echo` interprets
  `\n`. The historical symptom was JVM/Rust generated source becoming
  `split("` + real newline + `", -1)` before scalac/rustc.
- **Observed current surface:** `v2/backend/check.sh` already uses files and
  redirects for generated backend sources and carries the macOS warning, but
  `v2/scripts/bench.sh` still piped source/IR variables with `echo "$src"` /
  `echo "$ir"`, and `v2/ssc1` piped generated IR with `echo "$IR"`.
- **Expected:** generated/source text should be written to stdin with `printf`
  or direct file redirects so backslash escapes stay byte-preserving.
- **Root cause:** shell `echo` is not a byte-preserving serialization primitive
  for arbitrary generated/source text.
- **Fix:** replaced live text-to-stdin `echo "$..."` uses in
  `v2/scripts/bench.sh` and `v2/ssc1` with `printf '%s\n'`.
- **Verified:** `v2/backend/check.sh fact` (1 fixture x JVM/JS/Rust),
  `v2/scripts/bench.sh arith-loop` (`13.5810 ms`, warmup/reps 1/1),
  `v2/ssc1 v2/examples/kc13-hello.ssc` (`Hello, World!`),
  `v2/ssc0c v2/examples/fact.ssc0 | v2/ssc run-ir /dev/stdin` (`120`),
  `scripts/sbtc "installBin"`, `tests/conformance/run.sh --only 'litdoc'
  --no-memo` (1/1 across INT/JS/JVM), and `git diff --check`.

## v2-scala-cli-stack-option-wrappers — v2 shell wrappers used rejected `-J-Xss512m`
<!-- status: fixed
     lane: v2-jvm
     area: runtime
     fixed-in: a4f7662be -->

**Status:** fixed (2026-07-10, `a4f7662be`).

- **Found by:** codex while verifying `v2-jvm-backend-echo-macos`.
- **Repro:** `v2/ssc1 v2/examples/kc13-hello.ssc` exited before running the
  program with `Unrecognized argument: -J-Xss512m`.
- **Root cause:** the v2 helper wrappers still used the old Scala CLI
  `-J-Xss512m` spelling; current Scala CLI accepts the stack option through
  `--java-opt=-Xss512m`.
- **Fix:** updated `v2/ssc`, `v2/ssc0c`, and `v2/ssc1` to pass
  `--java-opt=-Xss512m`.
- **Verified:** same wrapper gates as `v2-jvm-backend-echo-macos` above.

## v2-serve-view-frontend-default — serve(view,port) crashes 'swiftui native-only' instead of serving the web SPA
<!-- status: fixed
     lane: v2-rust
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/serve-view-frontend-v2-smoke.sh. -->

**Status:** FIXED 2026-07-10 — on `--v2`, `serve(view, port)` for any UI-serving
program crashed `the active frontend backend 'swiftui' is native-only` instead of
serving the web SPA. v1 reads the front-matter `frontend:` value and selects the
framework (`Interpreter.scala`: `m.frontendFramework.foreach(FrontendFrameworks.setBackend)`);
the v2 bridge never wired this, so `serve` fell to `FrontendFrameworks.current()` →
`impls.head` (swiftui, native-only). Invisible to the corpus (serve is stubbed in
batch mode) but broke every real `ssc run --v2` serving a UI view
(content-introspection, datatable-static-spa, …). Fix: `FrontendBridge.selectFrontendFromFrontmatter`
reads the `frontend:` value and calls `FrontendFrameworks.setBackend`, mirroring v1
(only when nothing is selected, so CLI `--frontend` / `setFrontendFramework` still win).
Verified: content-introspection + datatable-static-spa serve `frontend=react` on --v2
(matching v1); corpus 154/8. Gate: tests/e2e/serve-view-frontend-v2-smoke.sh. Fixed by lucky-perch.

## v2-rust-recursion-tco-bench-fold — `fixed` (2026-07-09)
<!-- status: unknown
     lane: v2-jvm
     area: codegen -->

- **Found by:** codex during `v2-source-backend-production-perf-sweep`.
- **Repro:** after `scripts/sbtc "installBin"` on current `origin/main`, run
  `scripts/bench v2-backends recursion-tco`.
- **Observed failure:** the v2-rust row reports `0.000000 ms/iter` while the
  same public command reports nonzero work for the other lanes
  (`v2=0.301 ms`, `v2-jvm=3.18 ms`). This is below any plausible execution
  floor and means `rustc -O` still constant-folds this benchmark shape.
- **Expected:** the v2-rust benchmark path must defeat LLVM folding for
  tail-recursive zero-input workload helpers before a `recursion-tco` source
  backend row can be accepted as green.
- **Impact:** `v2-source-backend-production-perf-gates` cannot count the
  v2-rust `recursion-tco` row as closed until the harness emits an honest,
  nonzero measurement.
- **Root cause:** the existing v2-rust bench anti-folding made the zero-arg
  `g_workload_long()` literal opaque, but LLVM still converted the
  single-self-call tail-recursive Long helper into a closed-form result.
- **Fix:** `3d514f411` extends the benchmark-only `BenchCmd.timeV2Rust` patch
  to wrap the first simple loop-carried `wrapping_add` update inside Long
  helpers that contain exactly one self-call. This blocks the tail-recursive
  closed-form fold without penalizing non-tail `fib`, whose helper contains two
  self-calls and is intentionally not patched this way.
- **Verified:** short real smoke
  `bin/ssc --backend v2-rust bench --machine --warmup-time 10 --reps 1 bench/corpus/recursion-tco.ssc`
  reports `BENCH v2-rust 0.6620`; public
  `scripts/bench v2-backends recursion-tco` reports `v2-rust=0.721 ms`; and
  `scripts/bench v2-backends recursion-fib` remains stable at
  `v2-rust=1.46 ms`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-rust-bench-zero-input-helper-fold — `fixed` (2026-07-09)
<!-- status: unknown
     lane: v2-rust
     area: codegen -->

- **Found by:** codex while implementing
  `v2-source-rust-recursion-fib-perf`.
- **Repro:** after changing the v2 Rust source backend to emit direct
  Long-specialized helpers for `bench/corpus/recursion-fib.ssc`, run the real
  v2-rust bench path:
  `bin/ssc --backend v2-rust bench --machine --warmup-time 10 --reps 1 bench/corpus/recursion-fib.ssc`.
  The generated Rust wrapper contains a zero-arg `g_workload_long()` helper
  returning `g_fib_long(30i64)`.
- **Observed failure:** `rustc -O` can constant-fold the whole zero-input
  helper chain, producing a near-zero `BENCH_MS` value even though the
  workload is recursive and should run at about the same order as the v2 JVM
  direct helper lane.
- **Expected:** the benchmark-only v2-rust path must keep production codegen
  unchanged while making benchmark input opaque enough that LLVM cannot
  precompute the measured helper chain.
- **Manual confirmation:** patching the generated bench-only Rust source to
  call `g_fib_long(std::hint::black_box(30i64))` makes the same smoke run
  honest and fast (`BENCH_MS: 1.44545`, `BENCH_SINK: 1385346600`).
- **Impact:** once the production backend stops paying generic closure/vector
  dispatch, the public `scripts/bench v2-backends recursion-fib` row can become
  falsely green unless the v2-rust bench harness adds its own anti-folding.
- **Fix:** `3d975bda7` patches only `BenchCmd.timeV2Rust` before writing its
  temporary `main.rs` to `rustc -O`. Zero-argument Long helpers have their first
  integer literal wrapped with `std::hint::black_box(...)`, while public
  `emit-rust` output and the corpus workload remain unchanged.
- **Verified:** `bin/ssc --backend v2-rust bench --machine --warmup-time 10
  --reps 1 bench/corpus/recursion-fib.ssc` reports `BENCH v2-rust 1.56`
  instead of near-zero; final `scripts/bench v2-backends recursion-fib` reports
  `v2-rust=1.44 ms`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-scripts-bench-mktemp-template — `fixed` (2026-07-09)
<!-- status: fixed
     lane: native
     area: codegen
     fixed-in: ed680a585
     confirmed: no -->

- **Found by:** codex while verifying `v2-backend-check-ssc1c-wrapper-app-lit`.
- **Repro:** run two `v2/scripts/bench.sh` instances concurrently, for example
  `BENCH_WARMUP=1 BENCH_REPS=3 ./scripts/bench.sh bool-predicate` and
  `BENCH_WARMUP=1 BENCH_REPS=3 ./scripts/bench.sh mutual-recursion` from `v2/`.
- **Observed failure:** one process can fail immediately with
  `mktemp: mkstemp failed on /tmp/v2-bench-XXXXXX.jar: File exists`.
- **Expected:** each bench process gets a unique temporary jar path.
- **Impact:** parallel agents or local parallel probes can spuriously fail while
  checking v2 corpus rows; the semantic benchmark itself is not at fault.
- **Fix:** `ed680a585` changes `v2/scripts/bench.sh` to use the suffix-free
  `mktemp /tmp/v2-bench-XXXXXX` template so macOS substitutes the trailing Xs
  and concurrent processes get distinct temporary jar paths.
- **Verified:** short `bool-predicate` and `mutual-recursion` bench probes
  completed concurrently with unique temp jars (`/tmp/v2-bench-JUGk7f` and
  `/tmp/v2-bench-qu9Sqy`), followed by `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-money-decimal-regression — money amounts became Int (Decimal shadowed by payments Money companion)
<!-- status: fixed
     lane: native
     area: front
     fixed-in: d255f18f8
     gate: tests/conformance/v2-user-type-shadows-function-ctor.ssc. -->

**Status:** FIXED 2026-07-09 — busi's v2 domain sweep dropped 61/61 → 25/61 after
bumping past `d255f18f8` (feat(v2): bridge payments examples surface). That commit
registered `Money`/`Currency` as `functionConstructors` and a global `Money`
companion whose `apply(amount, currency)` coerces a `Decimal` amount to minor-units
`Long`. This SHADOWED std/money.ssc's `case class Money(amount: Decimal, currency)`:
`Money(Decimal("3000.00"), cur)` routed to the payments companion → `IntV(300000)`,
so `amount.setScale(...)` downstream hit `no dispatch for .setScale on IntV(300000)`
and money math was ×100 off (take_gig 15000000 vs 1500). Fix: `registerCaseClass`
records `userCaseClasses`; the functionConstructor path (FrontendBridge) skips names
the user DEFINED/imported as a `case class`, so their `X(args)` builds a plain Ctor
for that compile unit while payments-only files still use the companion. busi sweep
restored to 61/61; corpus 154/8 (payments examples still green). Reported by busi
(fable, n=105); fixed by lucky-perch. Pin: tests/conformance/v2-user-type-shadows-function-ctor.ssc.

## green-main-conformance-7fail — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: codegen
     gate: tests/conformance/run.sh -->

- **Found by:** codex, while closing the `p4-bc-unboxed-arith` bytecode perf
  slice. The affected bytecode/arithmetic gate is green, but the broader default
  conformance gate is not.
- **Repro:** stage the CLI with `scripts/sbtc "installBin"`, then run
  `tests/conformance/run.sh --only 'case-classes,dataset-shape,direct-control-flow,effect-imported-handler,effect-transitive-handler,fenceless-bare-code,js-applyunary-effect-cps,sealed-traits' --no-memo`.
- **Observed failure:** the fresh targeted repro reports 1 passed, 7 failed out
  of 8 tests. Failing rows: `case-classes` JS (`78.54` becomes `NaN`, and
  expected ordinals `0, 1, 2, 1` become `7, 7, 7, 7`); `dataset-shape` JVM
  missing all stdout; `direct-control-flow` JS missing all stdout;
  `effect-imported-handler` JS missing stdout; `effect-transitive-handler` JS
  missing stdout; `js-applyunary-effect-cps` JS missing stdout; `sealed-traits`
  JS (`3.14` becomes `NaN`). `fenceless-bare-code` passed in the fresh
  targeted `--no-memo` repro even though the earlier full non-`--no-memo` run
  reported a JS missing-stdout failure.
- **Expected:** every enabled lane in the focused repro passes, and a full
  `tests/conformance/run.sh --no-memo` has no deterministic failures beyond
  explicit pending/skips.
- **Impact:** the default top-level conformance gate is red, so v2 production
  readiness still has a deterministic blocker independent of bytecode
  arithmetic performance.
- **Notes:** the current bytecode slice's affected gate is green:
  `tests/conformance/run.sh --only
  'arithmetic,recursion,tail-recursion,mutual-recursion' --no-memo` reports
  4 passed, 0 failed; focused `FrontendBridgeTest -- -z "v2 bytecode"` reports
  2/2. Direct repro notes 2026-07-09: `direct-control-flow` JS throws
  `RangeError: Maximum call stack size exceeded` in `iterateWhileMOption`;
  the three effect JS rows throw `ReferenceError: query__ssc is not defined`;
  `case-classes` and `sealed-traits` JS run but produce `NaN` for numeric fields
  and wrong enum ordinals; `dataset-shape` JVM fails at scalac with
  `_Dataset.mkString must be called with () argument` from generated
  `xs.map(_show).mkString`.
- **Progress 2026-07-09:** fixed `dataset-shape` JVM by making the generated
  `_Dataset.mkString` no-arg overload parameterless to match Scala collections,
  and bumping the JVM `.scjvm` codegen cache key so stale artifacts regenerate.
  Verified direct `bin/ssc run-jvm tests/conformance/dataset-shape.ssc`,
  `tests/conformance/run.sh --only 'dataset-shape' --no-memo` (1/1), and the
  original eight-row repro now reports 2 passed, 6 failed (`dataset-shape` and
  `fenceless-bare-code` pass).
- **Progress 2026-07-09:** direct generated-JS inspection narrowed
  `case-classes` and `sealed-traits` to a JS lexical-shadowing bug. Pattern
  binders and lambda params are declared with local names (`const r = ...`,
  `p => ...`), but body references choose the top-level collision-safe names
  (`r__ssc`, `p__ssc`) when the module also has top-level `r` / `p` values. That
  makes circle radius arithmetic read a `Rect`/`Rectangle` object (`NaN`) and
  `points.map(p => p.x + p.y)` read the top-level point (`7, 7, 7, 7`).
- **Progress 2026-07-09:** fixed JS local-scope precedence for lambda,
  pattern, generator/CPS match, receive, and handler binders. Direct
  `emit-js | node` for `case-classes` prints `3/4/78.54/24/4/0, 1, 2, 1`;
  `sealed-traits` prints `circle/rect/tri/3.14/12/12`; focused conformance
  `case-classes,sealed-traits` reports 2 passed, 0 failed. The original
  eight-row repro now reports 5 passed, 3 failed: only `effect-imported-handler`,
  `effect-transitive-handler`, and `js-applyunary-effect-cps` still fail in the
  JS lane with missing stdout.
- **Progress 2026-07-09:** direct generated-JS inspection of the remaining
  effect rows shows a cross-module JS import alias bug. The parent module maps
  imported `query` references to `query__ssc` because `query` collides with the
  JS runtime top level. The imported child module then emits the actual
  unqualified `def query` as `query__ssc1` because `query__ssc` was already
  reserved by the parent. Since `genImport` skips binding when source and local
  names are both `query`, no `const query__ssc = query__ssc1` alias exists, so
  effect calls throw `ReferenceError: query__ssc is not defined`.
- **Progress 2026-07-09:** after those semantic fixes, a post-rebase full
  conformance run exposed an infrastructure flake: `strings`,
  `fenceless-bare-code`, and early actor JVM lanes could report missing stdout
  when Scala.js or JVM `scala-cli` paths reused a broken Bloop BSP socket. Direct
  stderr showed `Scala.js compilation failed` with `InterruptedException` /
  BSP socket timeout. `--cold-jvm` actor slices passed, proving the rows were
  not semantic regressions.
- **Fix:** `bd85a5f95` fixed stale JVM dataset artifacts, `bf0402b12` fixed
  JS local-scope precedence for lambda/pattern binders, `76b9432ef` fixed
  unqualified JS import aliases when parent and child top-level runtime
  collision renames diverge, `7f4cb82d7` makes Scala.js standard-block
  `scala-cli --js` package/run calls serverless, and `1291ed03b` makes the
  conformance JVM lane serverless by default while keeping `--warm-jvm` as an
  opt-in.
- **Verified:** `backendJs/compile; installBin`; `backendScalajs/compile;
  installBin`; direct `emit-js | node` for `case-classes`, `sealed-traits`,
  `effect-imported-handler`, `effect-transitive-handler`, and
  `js-applyunary-effect-cps`; focused conformance for the JS semantic slices;
  original eight-row repro 8/8; actor Bloop repro slice 4/4; fenceless /
  standard-Scala slice 4/4; full default `tests/conformance/run.sh --no-memo`
  reports 145 passed, 0 failed out of 145 tests (+2 pending); `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-jvm-user-request-shadow — `fixed` (2026-07-09)
<!-- status: unknown
     lane: v2-jvm
     area: codegen
     gate: tests/conformance/user-request-shadow.ssc -->

- **Found by:** codex, during the final `unmask-payments-bridge` affected
  conformance gate after the sibling `user-request-collision` fix landed.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run-jvm tests/conformance/user-request-shadow.ssc` or
  `tests/conformance/run.sh --only 'user-request-shadow' --no-memo`.
- **Observed failure:** INT and JS lanes print `7`, `9`, `7`, `42`, but the JVM
  lane produces no stdout because scala-cli compilation fails. The generated
  Scala source contains both the always-inlined HTTP runtime
  `case class Request(method, path, ...)` and the user's
  `case class Request(alpha: Int, beta: Int)`, so scalac reports
  `Request is already defined`.
- **Expected:** non-HTTP user code may define a top-level `Request` case class,
  and `run-jvm` must behave like INT/JS for field access and `copy`.
- **Impact:** `origin/main` has a conformance regression in the JVM lane; the
  v2 production gate cannot be considered green until this lane passes.
- **Root cause:** non-server JVM codegen always inlined the HTTP runtime model
  (`Request`, `Response`, `StreamResponse`) even when the script did not use an
  HTTP server. That leaked public runtime case-class names into ordinary user
  modules, so a user top-level `Request` collided at scalac time.
- **Fix:** `d5538d66a` keeps HTTP/server modules on the existing `commonRuntime
  + serveRuntime` path, but switches non-server scripts that define
  `Request`/`Response`/`StreamResponse` to a collision-safe JVM preamble. Actor
  and HTTP-effect stubs use private `_SscRuntime*` names there, and the JVM
  artifact codegen version was bumped so stale `.scjvm` artifacts regenerate.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (42/42); `scripts/sbtc 'installBin'`; direct
  `bin/ssc run-jvm tests/conformance/user-request-shadow.ssc` prints
  `7`, `9`, `7`, `42`; `tests/conformance/run.sh --only
  'money-multisection,v2-*,user-request-shadow' --no-memo` (7/7); full
  `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-multiline-list-literal-desugar — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: front
     gate: tests/conformance/v2-multiline-list-literal.ssc. -->

`bin/ssc --v2` crashed with scala.meta `illegal start of simple expression`
on any fence containing a MULTI-LINE `[ … ]` list literal (bracket opens a
line, elements follow, `]` closes a later line). Corpus repro:
`datatable-static-spa.ssc` (`staticDataTable(rows, [ fcol… ], [ rowPost… ])`),
v1 green. Root cause was NOT the `[…]`→`List(…)` desugarer (it never ran) but
`FrontendBridge.filterImportLines`: a bare `[` opening a multi-line list was
misclassified as the start of a multi-line import directive
(`[A,\n B](path.ssc)`); finding no closing `](….ssc)` line it swallowed the
rest of the fence, so the merged source ended mid-expression and scala.meta
failed on the next `def`. Fix (`FrontendBridge.filterImportLines`): only
consume a multi-line import when a real `](….ssc)` close actually follows
through ident-list continuations; otherwise the line is code and is kept.
Corpus 152/10 → 154/8 (`datatable-static-spa` now green; no regressions — the
whole corpus still resolves its multi-line std-imports). Pinned by
tests/conformance/v2-multiline-list-literal.ssc. Fixed by lucky-perch.

## v2-payments-bankrails-op-stub-leaks - `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, during the v2 production unmasking loop after standard
  `scala` fences became runnable.
- **Repro:** from a clean worktree, stage the CLI with
  `scripts/sbtc "installBin"`, then run:
  `bin/ssc run --v2 examples/traditional-payments.ssc`,
  `bin/ssc run --v2 examples/bank-rails-pix.ssc`, and
  `bin/ssc run --v2 examples/bank-rails-fednow.ssc`.
- **Observed failure:** all three commands exit 0, but the payment/bank-rails
  bridge surface is not actually handled. `traditional-payments.ssc` prints
  `Op("PaymentProvider.named", "stripe", <closure>)`; `bank-rails-pix.ssc`
  prints `Transfer initiated: Stub, status: Stub`, `Transfer status: Stub`,
  and an unhandled `PixQrCode.buildStatic` operation; `bank-rails-fednow.ssc`
  prints `FedNow transfer Stub submitted - status: Stub` and an
  `Op("Instant.now", ...)` leak.
- **Expected:** documented payment examples that are runnable on v2 should
  execute through deterministic bridge objects and print concrete provider,
  transfer, QR, and poll results, without `Op(` or `Stub` in stdout.
- **Impact:** the production v2 lane reports success while user-facing payment
  examples expose unresolved plugin-boundary values in output.
- **Notes:** `--v1` is not a valid oracle for this bug: the rollback lane fails
  earlier on undefined `PaymentProvider`, `PixConfig`, and `FedNowConfig`.
  The v2 oracle is the documented example behavior plus the explicit absence of
  unresolved `Op`/`Stub` output.
- **Root cause:** `FrontendBridge` treated several payment/bank-rails
  companion/factory names as ordinary constructors or unresolved method-object
  selects, while `PluginBridge` did not register the payment provider,
  bank-rails provider, Pix QR, Money/Currency, or small time/poll method objects
  needed by the examples. Several illustrative server/webhook/negative-path
  snippets were also still marked runnable even though they depend on route,
  webhook, platform, or disconnected example state.
- **Fix:** `d255f18f8` adds the v2 payments bridge surface: `v2PluginBridge`
  depends on the existing payments/Pix/FedNow modules; `FrontendBridge`
  pre-registers payment/bank-rails field names and method-object/factory names;
  `PluginBridge` registers deterministic no-network Stripe/Pix/FedNow provider
  method objects, `Money`/`Currency`, pure `PixQrCode` generation, and
  `Instant`/`Thread` helpers; the v2 runtime handles basic `Money` arithmetic.
  `69aad3c3f` marks non-self-contained payment snippets `scala no-run` and
  keeps the runnable money section on supported bridge behavior.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (42/42); `scripts/sbtc 'installBin'`; direct
  `bin/ssc run --v2` for `examples/traditional-payments.ssc`,
  `examples/bank-rails-pix.ssc`, and `examples/bank-rails-fednow.ssc` all exit
  0 and a shell guard confirms stdout contains no `Op(` or `Stub`;
  `tests/conformance/run.sh --only 'money-multisection,v2-*' --no-memo` (4/4);
  full `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-serve-noop-minimalctx — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: front -->

- **Found by:** busi (rozum 07-09 n=24): hub boots on --v2 (banner + pairing code)
  but serve() never binds — no listener, curl 000.
- **Root cause:** `serve(port)` on the v2 run lane resolves to the frontend-plugin
  native, which calls `ctx.startServer`/`ctx.startTlsServer` — and the
  NativeContext trait DEFAULTS are silent no-ops (IntrinsicImpl.scala:132-138).
  The v2 bridge's MinimalCtx never overrode them, so every serve variant
  "succeeded" without a socket. (serveAsync was unaffected — it has its own real
  registerWebServer bridge.)
- **Fix:** MinimalCtx overrides startServer/startTlsServer (BLOCKING on the
  calling thread — v1 serve semantics, the program stays alive serving),
  startServerAsync/startTlsServerAsync (daemon thread + bound latch), stopServer,
  and registerHealthDefaults (/_health + /_ready on the bridge route registry,
  mirroring ClusterRoutesRuntime).
- **Verified:** busi hub on --v2: `lsof` shows the listener, `curl /` → 200 (busi
  routes), `/_health` → {"status":"ok"}, `/_ready` → 200. Gates: corpus 148/14 =
  main, conformance batch 123/37 = main, run.sh — all fails accounted
  (case-classes[JS] pre-existing on main — the unowned f57c74da8-family report;
  actors/async flakes pass idle; tls-smoke green).
- **Ladder:** busi drives the full storefront+money loop on --v2 next.

## v2-vm-effect-handlers-regression — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, while verifying `v2-vm-production-jit-gate` after
  rebasing on current `origin/main`.
- **Repro:** in a worktree with a staged v2 runtime, run
  `./v2/conformance/check.sh`. Minimal direct repro from a packaged v2 jar:
  `java -jar <v2-jar> run examples/effects-state.ssc0` returns
  `Op("get", (), <closure>)` instead of `Pair(2, 2)`.
- **Observed failure:** current `origin/main` (checked in detached diagnostic
  worktree at `ab78c6cac`) reproduces the same failures before the arith-loop
  optimizer commits: `effects-state`, `effects-nondet`, `async-tasks`, and
  `hm-eff-comp` all return unhandled `Op(...)` values on the VM lane while the
  JS/Rust lanes in the same conformance script pass. The full run also reports
  the same shape across effect rows such as `effrow`, `eff2`, `eff-traverse`,
  `eff-handle`, `eff-userstate`, `eff-do`, `eff-decl`, `eff-rowann`,
  `eff-typed`, `typed resume`, and `handleM`.
- **Impact:** the v2 VM conformance gate is red for algebraic effects/typed
  effect handlers, which is a production blocker independent of the current
  scalar-loop performance slice.
- **Notes:** this is not caused by the `v2-vm-production-jit-gate` arith-loop
  recognizer: the same minimal failures reproduce on clean `origin/main` at
  `ab78c6cac`, whose latest change is only a `.work/active/` claim.
- **Root cause:** `Compiler.C.compile` lifted every `DataV("Op", ...)`
  scrutinee over `Match` before ordinary ADT matching. That lift is only
  correct for bridge/runtime auto-thread operations; pure free-monad handlers
  need to match `Op("get", ...)`, `Op("choose", ...)`, and typed-effect
  `Op("double", ...)` as data.
- **Fix:** `b6f88744c` guards the `Match` lift with `Runtime.isAutoThreadOp`,
  matching the existing `Let`/`Seq` behavior. Added focused
  `FrontendBridgeTest` coverage for `examples/effects-state.ssc0` and
  `examples/hm-eff-comp.hm` compiled through `bin/mirac.ssc0` to CoreIR.
- **Gates:** focused
  `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z "effect handlers"'`;
  full `./v2/conformance/check.sh`; `scripts/sbtc "installBin"`;
  `tests/conformance/run.sh --only 'litdoc'` passed INT/JS/JVM.
- **Status:** fixed; waiting for human/reporter confirmation before `done`.

## v2-source-backend-bridge-bench-prims — `fixed` (2026-07-09)
<!-- status: unknown
     lane: v2-jvm
     area: front -->

- **Found by:** codex, while implementing `v2-backend-performance-harness`.
- **Repro:** stage `bin/ssc`, then run
  `bin/ssc --backend v2-jvm bench --warmup-time 100 --reps 3 bench/corpus/arith-loop.ssc`
  and
  `bin/ssc --backend v2-rust bench --warmup-time 100 --reps 3 bench/corpus/arith-loop.ssc`.
- **Observed failure:** the v2 VM label reported `BENCH v2 ...`, but the v2 JVM
  and v2 Rust source backend labels returned `n/a`. With `SSC_BENCH_DEBUG=1`, JVM
  generated source failed on missing `global.reg`/bridge method support, and Rust
  generated source failed on missing `g_println` plus missing bridge method support.
- **Impact:** the Phase-3 backend performance harness cannot produce v2 source
  backend timing columns, so `v2 JVM backend within 2x` and `v2 Rust backend
  within 1.5x` cannot even be measured on the corpus shape.
- **Root cause:** the standalone v2 source generators did not provide the small
  FrontendBridge standard global/method subset that the VM path already sees
  (`println`, `print`, `System.nanoTime`, `__autoPrint__`, `global.reg`, and the
  simple method calls used by the benchmark wrapper).
- **Fix:** `01d9abf32` keeps the v2 benchmark wrapper portable and adds the
  minimal JVM/Rust generated-runtime bridge globals plus `__method__` dispatch
  needed by the harness.
- **Gates:** `./v2/backend/check.sh tco`; `./v2/backend/check.sh bool`;
  `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`;
  `scripts/sbtc "cli/testOnly scalascript.cli.GlobalFlagsTest"`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'litdoc'`;
  `scripts/bench v2-backends arith-loop` reported non-`n/a` rows
  (`v2=9.68 ms`, `v2-jvm=0.265 ms`, `v2-rust=66.8 ms`).
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-stream-family-output-parity — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: cli -->

- **Found by:** codex, in the valid full production parity sweep after
  `v2-v1-side-mismatch-classification`.
- **Repro:** with a staged runner, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/distributed-streams.ssc examples/streams.ssc`.
- **Observed failure:** `distributed-streams.ssc` is still a strict mismatch:
  v1 prints the word-count block (`=== 2. Word count ===` plus sorted
  `KV(...)` rows) and v2 omits that block. `streams.ssc` is also a strict
  mismatch: after `=== 2. Stream block ===`, v1 stops at the header while v2
  prints `1`, `4`, and `9`.
- **Impact:** after moving known v1-side/better-output rows out of strict
  byte-parity, these are the only remaining unexplained production output
  mismatches in the default v2 gate.
- **Plan:** claim `v2-stream-family-output-parity`; reproduce both rows in the
  real assembled harness; determine whether each is a v2 stream/section
  execution bug, standard-Scala multi-section behavior, or a documented v1-side
  row; then fix v2 with focused conformance/regression coverage or classify the
  row explicitly.
- **Reproduced 2026-07-09:** `distributed-streams.ssc` v1 reaches the word-count
  block and then fails later on missing `String.toIntOption`; v2 fails earlier
  inside DStreams `combinePerKey` with `NoSuchElementException: key not found:
  key`. The likely bridge gap is that v2-created `KV(...)` reaches the v1
  DStreams plugin as positional `_0`/`_1` fields instead of named
  `key`/`value`. `streams.ssc` v1 fails in `stream { emit(...) }` with
  `emit called outside a stream body`; v2 correctly emits the stream block and
  then fails at `Source.runFold(z)(f) — outer`, likely because v2 invokes the
  curried native method with both arguments in one call. The next code pass
  should register v2 field names for `KV` and make stream/DStream `runFold`
  accept both curried and flattened two-argument calls.
- **Progress 2026-07-09:** after registering `KV` fields, accepting flattened
  `runFold`, and making v2→v1 list conversion iterative for large Cons/Nil
  chains, both examples advance further. `distributed-streams.ssc` now prints
  sections 1-4 and fails in section 5 with `__method__: no dispatch for .value
  on 10`, meaning DStreams stateful callbacks receive the raw input value where
  the example expects a per-key `KV(key, value)` shape. `streams.ssc` now prints
  sections 1-7 through `Buffer and timing` and fails at
  `Source.throttle: rate elements must be > 0`, meaning v2 invokes
  `Source.throttle(rate, per)` as flattened two-arg native call while the plugin
  currently expects the old curried shape. Next code pass: normalize DStreams
  stateful callback input and accept flattened rate arguments in stream timing
  natives.
- **Root cause:** the remaining strict mismatch bucket mixed real v2 plugin
  bridge gaps with rollback-v1 short-output rows. v2 needed named `KV`/`Rate`
  fields at the v2↔v1 plugin boundary, iterative Cons/Nil conversion for large
  stream ranges, flattened curried-call compatibility for stream/DStream
  natives, DStreams tuple/option result shapes that v2 pattern matching can
  consume, and a signal `.bind` method on bridged v1 `ReactiveSignal` values.
- **Fix:** `d1d0bc1fd` fixes those bridge/runtime gaps and classifies
  `distributed-streams.ssc` and `streams.ssc` as v1-side/better-output rows in
  `scripts/v2-output-parity`, because v2 now runs both documented examples to
  completion while rollback v1 stops early.
- **Gates:** `git diff --check`; `streamsPlugin/testOnly
  scalascript.compiler.plugin.streams.StreamsPluginInterpreterTest` 83/83;
  `dstreamsPlugin/testOnly
  scalascript.compiler.plugin.dstreams.DStreamsPluginInterpreterTest` 66/66;
  `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` 26/26;
  `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest` 29/29;
  `tests/conformance/run.sh --only 'signals' --no-memo` passed INT/JS/JVM;
  direct `bin/ssc run --v2` for both stream examples exits 0; targeted parity
  reports `2 v1-side`; full parity is
  `68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only` with `4 v1-side`
  skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-output-parity-temp-write-fail-fast — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, while re-running the full v2 output-parity sweep during
  `v2-v1-side-mismatch-classification`.
- **Repro:** with a nearly full host disk, run
  `PARITY_TIMEOUT=45 SSC="/Users/sergiy/work/my/scalascript/bin/ssc" scripts/v2-output-parity --all`.
- **Observed failure:** once `target/v2-output-parity-tmp` cannot accept writes,
  `run_one` logs `No space left on device` for the shared RC file but the script
  keeps reading the previous RC value. Later rows are then misreported as
  `both-fail`, and the final summary looks like a valid corpus result even
  though the run is corrupted.
- **Impact:** production parity baselines can be polluted by false counts when
  the host has insufficient disk. This happened during the attempted
  2026-07-09 full sweep; that full-sweep output must not be recorded as a
  baseline.
- **Root cause:** the parity harness wrote every backend exit code through one
  shared RC file but did not check whether creating or writing that file
  succeeded. When the filesystem filled, later rows reused stale RC state and
  the summary looked valid.
- **Fix:** `18ee5ecfc` moves parity temp files into a repo-local temp dir and
  makes temp-dir creation, RC-file creation, RC writes, RC reads, and temporary
  port-rewrite files fail fast with exit 2.
- **Gates:** artificial unwritable `SSC_PARITY_TMPDIR` exits with `rc=2` and
  `cannot create rc file`; the subsequent full parity sweep completed without
  temp-write errors and produced
  `68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only`
  with `2 v1-side` skips.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-v1-side-mismatch-classification — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: front -->

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-scala-fence-multiblock-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/effects.ssc examples/dsl-calc-parser.ssc`.
- **Observed prior findings:** `effects.ssc` is a strict v1/v2 mismatch because
  v2 prints the documented six-line example output while v1 stops after the
  first three sections. `dsl-calc-parser.ssc` is a strict mismatch because v1
  truncates parser round-trips to the first number, while v2 prints the full
  parsed/pretty expression strings.
- **Impact:** the production output-parity gate still reports these as v2
  mismatches even though the durable notes identify them as v1-side or
  better-output rows. That hides the smaller remaining stream-family surface
  that likely needs real v2 work.
- **Root cause:** the strict byte-parity gate had no bucket for rows where the
  rollback v1 runner is the bad side and v2 matches the documented behavior.
  That made two known v1-side/better-output rows look like active v2
  production regressions.
- **Fix:** `18ee5ecfc` classifies `effects.ssc` and `dsl-calc-parser.ssc` as
  v1-side/better-output skips in `scripts/v2-output-parity`. The example and
  runtime behavior are unchanged.
- **Gates:** targeted parity for `examples/effects.ssc` and
  `examples/dsl-calc-parser.ssc` reports `2 v1-side` skips; targeted parity for
  `examples/scala-js-demo.ssc` and `examples/lang-split.ssc` still reports
  2/2 identical; `tests/conformance/run.sh --only 'effects' --no-memo` passed
  INT/JS/JVM; full parity is now
  **68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only** with
  `2 v1-side` skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-scala-fence-multiblock-parity — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: front -->

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-mcp-oauth-secret-nondet-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/scala-js-demo.ssc examples/lang-split.ssc`.
- **Observed failure:** `examples/scala-js-demo.ssc` is a standard-Scala-only
  document with three `scala` fences; v1 prints all collection, ADT, and
  recursive-sort lines, while v2 only matches the first collection line(s).
  `examples/lang-split.ssc` explicitly documents that `scala` and
  `scalascript` blocks may coexist, but v2 omits the standard `scala` block
  output (`Distance`, `Evens`, `Primes`) and only runs the ScalaScript block.
- **Impact:** default v2 no longer silently drops all standard `scala` documents
  after `v2-standard-scala-fences-skipped`, but the production parity gate still
  has deterministic user-visible gaps for multi-fence and intentional mixed
  `scala`/`scalascript` runnable documents.
- **Initial hypothesis:** `FrontendBridge.extractCode(..., allFences = true)`
  has two remaining policy/shape gaps: standard `scala` fences are included only
  for standard-Scala-only documents, which is too strict for examples that
  explicitly mark both languages runnable; and multi-block Scala source may still
  expose top-level conversion or auto-print ordering differences.
- **Reproduced 2026-07-09:** the two rows have different root causes. For
  `scala-js-demo.ssc`, v2 extracts and starts the later standard `scala` fences
  but exits after the first two lines with
  `__method__: no dispatch for .takeWhile on "Circle(3)"`; adding the missing
  string predicate method should expose the remaining ADT/sort output. For
  `lang-split.ssc`, v2 exits 0 but only prints the ScalaScript block, confirming
  that intentional mixed runnable `scala` fences are still excluded by
  `extractCode`.
- **Second repro pass 2026-07-09:** after `String.takeWhile` and mixed-fence
  opt-in, `lang-split.ssc` matches. `scala-js-demo.ssc` then exposes two more
  narrow v2 gaps in existing support code: `f"..."` interpolation is lowered as
  raw string concatenation (`Circle%-12s area = ...%.2f`), and guarded
  constructor-pattern arms abort the whole match on guard failure instead of
  falling through to the next case (`mergeSort` needs the later
  `case (_, bh :: bt)` arm).
- **Plan:** add focused extraction/runtime regressions for an all-`scala`
  multi-fence document and an intentional mixed runnable document; adjust the
  extractor policy narrowly so documented runnable `scala` fences are included
  without re-enabling arbitrary illustrative snippets in mixed ScalaScript docs;
  then run targeted parity plus affected conformance before pushing.
- **Root cause:** the all-fences extraction path only ran standard `scala`
  fences for standard-Scala-only documents, mixed runnable examples had no
  explicit opt-in, and the follow-on `scala-js-demo.ssc` path exposed missing
  standard-runtime/lowering shapes (`String.takeWhile`/`dropWhile`, `f"..."`
  formatting, guarded constructor-pattern fall-through).
- **Fix:** `f57c74da8` adds the mixed-fence opt-in
  (`runScalaFences: true` / `run-scala-fences: true` or
  `scalaFences: runnable` / `scala-fences: runnable`), executes standard
  multi-fence documents in order, adds the runtime/lowering support, and adds
  focused frontend + conformance regressions.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  passed 25/25; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'standard-scala-*' --no-memo` passed 3/3
  across INT/JS/JVM; targeted parity for `examples/scala-js-demo.ssc` and
  `examples/lang-split.ssc` is 2/2 identical; full parity is now
  **68/95 identical · 4 mismatch · 0 v2-error · 23 v1-only** with 5 nondet
  skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-mcp-oauth-secret-nondet-parity — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: front
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-os-env-nondet-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/mcp-server-protected.ssc examples/oauth-mcp-full-stack.ssc`.
- **Observed failure:** both examples print generated OAuth/MCP client ids and
  secrets, so v1/v2 runs produce different credentials. They also include
  server startup/banner lines that are not the semantic client/server contract.
- **Impact:** this is generated-output noise in the strict byte-parity gate, not
  a v2 production runtime failure. Keeping these rows as mismatches hides the
  smaller set of semantic parser/stream-shape rows still needing investigation.
- **Root cause:** the strict parity harness was treating generated credentials
  and server banners as deterministic stdout. Those examples are useful demos,
  but their startup output cannot byte-match across independent v1/v2 runs.
- **Fix:** `2142f8e0d` classifies `mcp-server-protected.ssc` and
  `oauth-mcp-full-stack.ssc` as nondeterministic-output by design. The examples
  and runtime behavior are unchanged.
- **Gates:** `scripts/sbtc "installBin"` passed; targeted parity for the two
  examples now reports nondeterministic-output skips;
  `tests/conformance/run.sh --only 'mcp-*' --no-memo` passed enabled
  `mcp-types` on INT/JS with the server/client cases skipped by requirements;
  full parity is now **66/95 identical · 6 mismatch · 0 v2-error · 23 v1-only**
  with 5 nondet skips across 195 examples.

## v2-os-env-nondet-parity — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime
     gate: tests/conformance/std-os.ssc -->

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-async-parallel-timing-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/os-env.ssc`.
- **Observed failure:** v1 prints unresolved native placeholders for platform
  values (`<native:platform>`, `<native:cwd>`, `<native:sep>`), while v2 prints
  real host values such as `JVM`, the current worktree path, and `/`.
- **Impact:** this is not a v2 regression: v2 is doing the useful thing, and
  the example's output is host-dependent by design. Keeping it in the strict
  byte-parity mismatch bucket makes the production gate noisier.
- **Root cause:** the strict parity harness was treating a host-dependent demo
  as a deterministic v1/v2 output comparison. The mismatch was useful as a
  visibility signal, but not actionable as a v2 production blocker.
- **Fix:** `6e82f20b2` classifies `os-env.ssc` as nondeterministic-output by
  design in `scripts/v2-output-parity`, alongside `sql-sqlite-file` and
  `uuid-v7`. The example and runtime behavior are unchanged. Added
  `tests/conformance/std-os.ssc` to cover deterministic std/os helpers.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/os-env.ssc`
  now reports nondeterministic-output skip; `tests/conformance/run.sh --only 'std-os' --no-memo`
  passed INT; full parity is now
  **66/97 identical · 8 mismatch · 0 v2-error · 23 v1-only** with 3 nondet
  skips across 195 examples.

## v2-async-parallel-timing-parity — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-graph-neo4j-foreign-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/async-parallel-demo.ssc`.
- **Observed failure:** v1 and v2 both compute `List(50, 50, 50)` for the
  `runAsync`/`runAsyncParallel` examples, but the example prints measured
  wall-clock milliseconds (`took ~Nms`), so byte-for-byte parity mismatches even
  when semantics agree.
- **Impact:** default v2 production gate has a false output mismatch. The
  example itself says output stays byte-identical for code that does not depend
  on timing, but its stdout currently depends on timing.
- **Root cause:** the example itself was nondeterministic: it printed live
  elapsed milliseconds for the sequential and parallel handlers. v1/v2 semantic
  results matched, but byte-for-byte stdout parity could not.
- **Fix:** `ea62f9d38` keeps the result lines and timing guidance, but removes
  live elapsed milliseconds from stdout. No runtime semantics changed.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'async-parallel' --no-memo` passed INT/JS/JVM;
  targeted `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/async-parallel-demo.ssc`
  passed 1/1; full parity is now
  **66/98 identical · 9 mismatch · 0 v2-error · 23 v1-only** across 195
  examples.

## v2-arith-dispatch-split — `fixed` (2026-07-09)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, while promoting BACKLOG `v2-arith-unification` for v2
  production readiness.
- **Related history:** `v2-arith-table-divergence` fixed the immediate busi
  litdoc regression by keeping literal op names in place through `OpAnf` and by
  patching Map+Tuple2 into the non-literal table. That entry intentionally left
  full unification to BACKLOG.
- **Repro shape:** construct CoreIR where `__arith__` receives the operator from
  a local binding rather than a literal, e.g. `let op = "+" in __arith__(op,
  attrs, ("id" -> "demo"))`. This forces `resolve("__arith__")` instead of the
  literal-op `Prims.arithOp` fast path. The same operator/value pair should have
  identical semantics regardless of whether `op` is a literal or a local.
- **Impact:** production v2 has two arithmetic semantics tables. A future ANF,
  bridge, or optimizer change can silently switch programs between the richer
  `Prims.arithOp` path (Op lifting, Map+Tuple2, char-code comparisons,
  Cons-minus) and the table path (historically weaker plus table-only Decimal,
  actor-send, and declaration fallbacks).
- **Plan:** move table-only behavior into `Prims.arithOp`, make
  `resolve("__arith__")` delegate to `arithOp`, and add focused regressions for
  non-literal operator dispatch so this split cannot reappear.
- **Root cause:** `resolve("__arith__")` carried a second, hand-maintained
  arithmetic table. Literal-op fast paths went straight to `Prims.arithOp`, but
  non-literal op names used the table and could miss richer semantics or preserve
  table-only cases independently.
- **Fix:** `a2985d911` makes `resolve("__arith__")` a thin delegate to
  `Prims.arithOp` and moves the table-only Decimal, actor-send, `:=`, list/tuple,
  string/numeric, char-code, and unknown-declaration fallback cases into
  `arithOp`.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  passed 20/20; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'litdoc,arithmetic' --no-memo` passed
  `arithmetic` on INT/JS/JVM and skipped `litdoc` because no
  `expected/litdoc.txt` exists. Direct litdoc real-harness A/B still has the
  separate inline-bold mismatch tracked as `v2-litdoc-inline-bold-parity`; the
  arith/map data line agrees.

## v2-vm-effect-handlers-return-raw-op — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, during `p4-rust-wasm-lanes` full
  `./v2/conformance/check.sh` after list/float expectation realignment.
- **Repro:** run `./v2/conformance/check.sh`, or focus these rows:
  `examples/async-tasks.ssc0`, `examples/hm-async.hm`,
  `examples/hm-eff-multiop.hm`, and the inline `handleM row composition`
  program through `bin/mirac.ssc0` + `run-ir`.
- **Observed failure:** the VM `run`/`run-ir` lane returns unhandled effect
  values such as `Op("log", 1, <closure>)`, `Op("yield", 0, <closure>)`, and
  `Op("QA", Pair("ask", 0), <closure>)` where the JS/Rust generated lanes for
  the same typed programs produce the expected results (`List(...)` or `42`).
- **Impact:** the self-hosted v2 conformance gate remains red after the
  Rust/WASM target fixes, and production cannot treat the VM as authoritative
  for typed async / multi-op effect handler examples.
- **Fix direction:** inspect VM effect handling in `v2/src/Runtime.scala` and
  the generated CoreIR for the failing programs. Do not update expectations to
  raw `Op(...)`; the JS/Rust rows show the intended semantics still runs to a
  value.
- **Done-when:** the focused rows above and the full `./v2/conformance/check.sh`
  pass, with the fix SHA and root cause recorded here.
- **FIXED (2026-07-08, `84d7ac77f`):** VM `Let`/`Seq` auto-threading treated
  every `DataV("Op", ...)` as a runtime statement effect. That is correct for
  bridge-emitted runtime ops with dotted labels such as `Console.writeLine`, but
  wrong for pure v2/typed free-monad values such as `Op("log", ...)`,
  `Op("yield", ...)`, and `Op("QA", ...)`, which handlers/schedulers must match
  as ordinary data. `Runtime.isAutoThreadOp` now limits statement/binding
  auto-threading to dotted bridge labels, preserving free-monad Ops as values.
- **Verified:** minimal `run-ir` repro now returns `List(1)` instead of raw
  `Op("log", 1, <closure>)`; focused rows
  `async-tasks.ssc0`, `hm-async.hm`, `hm-eff-multiop.hm`, and `handleM row
  composition` pass; full `./v2/conformance/check.sh` is green.

## v2-run-cli-argv-not-forwarded — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: codegen -->

- **Found by:** codex, during `p4-js-lane-bridge` direct argv smoke.
- **Repro:** after `scripts/sbtc "installBin"`, run a temp `.ssc`:
  `println(args.length); println(args(0))`. Then compare
  `bin/ssc run-js --v2 /tmp/args.ssc one two` with
  `bin/ssc run --v2 /tmp/args.ssc one two`.
- **Observed failure:** `run-js --v2` prints `2` and `one`; `run --v2`
  prints `0` and then fails with `IndexOutOfBoundsException: 0`.
- **Impact:** the v2 VM runner has an `argv` parameter internally, and
  `PluginBridge` documents `args` as runner-provided command-line args, but
  `RunCmd` currently treats every non-flag as another source file and calls
  `RunV2.run(..., Nil)`. User code reading `args` under the default/explicit v2
  runner cannot receive program argv.
- **Fix direction:** add an explicit argv separator for `ssc run`, most likely
  `ssc run [flags] <file.ssc> -- [args...]`, so existing multi-file run
  semantics are not reinterpreted. Forward the trailing argv to `RunV2.run` and
  `RunV2.runBytecode`; keep legacy/default behavior clear in usage text.
- **Done-when:** a real assembled-CLI regression covers `run --v2 <file> --
  one two`, default v2 if applicable, and `run-js --v2` remains green.
- **FIXED (2026-07-08, `64de9b9af`):** `ssc run` now treats `--` as the
  explicit separator between source files and program argv for v2 VM runners.
  Default `ssc run <file> -- one two`, explicit `ssc run --v2 <file> -- one
  two`, and `ssc run --bytecode <file> -- one two` forward argv into
  `Runtime.argv`. The bytecode lane also gained list-application fallback parity
  so `args(0)` works through `Emit.app`.
- **Verified:** `scripts/sbtc "cli/compile; cli/assembly; cli/testOnly
  *V2RunArgvCliTest"`; `scripts/sbtc "installBin"`; direct installed CLI smokes
  for default/`--v2`/`--bytecode`; conformance `collections`; combined
  assembled-CLI smoke `*V2RunArgvCliTest *V2JsLaneCliTest`.

## v2-busi-testsweep-gaps batch — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime
     gate: tests/conformance/var-topdef-shared.ssc -->

Seven root causes closed working busi tests/v2 47/61 → 61/61 on --v2 (v1 = 61/61
same launcher; every fail was a real engine gap). One entry per cause:

- **v2-topvar-def-split-cell** — a top-level `var` referenced from def bodies was
  TWO cells: entry-local Let cell vs the defs' auto-created `Global("@name")`.
  Assignments from defs vanished (busi persistence: `def saveLocal(t) = localCell = t`).
  Fix: convertStats pre-pass (sharedTopVars) + global.reg of the entry cell under
  "@name". Regression: `tests/conformance/var-topdef-shared.ssc`. Killed sync,
  sync_http, local_journal, deferred_action.
- **v2-fbc-string-eq-optimistic** — tryFBc kept `==`/`!=` UNGUARDED on the Long
  fast path while ordering ops required provably-Long operands; tryFLC reads a
  StrV Local as 0L → string equality of two locals was ALWAYS true inside If
  conditions (`if p == period` matched every period — July facts leaked into June
  folds). Fix: extend the flcProvablyLong guard to equality. Regression:
  `tests/conformance/string-eq-locals.ssc`. Killed income, invoicing, trust, vat,
  meeting_room.
- **v2-hof-effect-threading** — a perform inside a list-HOF lambda returned a raw
  Op that map/filter/fold/foreach collected as DATA (busi operator: hPlan was a
  list of Ops). Fix: mapThreadOp/foldThreadOp — per-element Op results defer the
  REST of the traversal into the op's continuation (letThreadOp protocol);
  bridge-only by construction (__method__ dispatch). Killed operator.
- **v2-array-companion-list** — `Array.fill/tabulate` returned Cons-lists;
  `m(i) = v` then hit arr.set with "expected Array, got List". Fix: Array
  companion returns ForeignV(ArrayBuffer); + ArrayBuffer indexing in
  applyFallback, ArrayBuffer length/size/isEmpty/toList in dispatch. Part 1 of qr.
- **v2-fastcode-length-tolerant** — the length/size FastCode returned `0L` for ANY
  unrecognized receiver (and cons-cell field count 2 for lists): every
  `while i < msg.length` over an Array.fill result ran ZERO iterations (busi qr:
  a data-less, mask-only QR matrix that STILL passed structural checks). Fix:
  honest lengths (unlist walk for Cons/Nil, ArrayBuffer size) + sys.error for
  unknown receivers. Part 2 of qr.
- **v2-fence-regex-midline** — the all-fences extractor matched fence opens
  MID-LINE (inside a string literal holding markdown), desyncing the fence walk —
  prose after the next real close parsed as code ("illegal unicode codepoint:
  0xab"). Fix: (?m)^ anchors + newline-anchored first-fence search. Killed model.
- **v2-arith-table-divergence** — TWO arith implementations: Prims.arithOp (full:
  Map+(k->v), char semantics) for LITERAL op names vs the resolve-table __arith__
  (string-concat fallback) for non-literal names. OpAnf's letify was binding
  `Lit("+")` into a Local — demoting map-extend to string concat (busi litdoc:
  attrs became "Map()(id, Str(demo))…"). Fix: OpAnf keeps pure args (Lit/Global/
  Lam/Local) IN PLACE (also preserves FastCode shapes), and the table arith gained
  the Map+Tuple2 case. Full unification of the two ariths → BACKLOG. Killed
  litdoc_content.
- **v1-content-imported-doc-fallback** — contentToolkitSection/contentSection/
  contentBlock/contentData resolve only the CURRENT document; on v1 an imported
  module runs with its own document, but the v2 bridge inlines imports under the
  entry file's document, so a module's sections were unreachable from its own
  code. Fix (v1 content-plugin, fires only where it previously errored/None'd):
  fall back to the registered ContentImportedModules documents. Killed
  content_toolkit.

## root-test-v2-conformance-toolkit-regressions — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` full root
  `scripts/sbtc "test"` after the sealed-extension and cluster blockers were fixed.
- **Repro observed in root gate:** `V2ConformanceTest` failed:
  `std-ui-jobpanel` rendered `?` labels instead of `2:Jobs` / `2:New job`;
  `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` threw
  `RuntimeException: __method__: no field 'set' on named-method-obj (None)`;
  `tkv2-pwa` threw `RuntimeException: unbound global: pwa`.
- **Impact:** v2 default is not production-ready for the tk/std-ui conformance
  cluster until these cases either pass or are explicitly classified out of the
  production gate with a documented reason.
- **Fix direction:** split into focused repros from `V2ConformanceTest` and fix
  the underlying bridge/runtime gaps. Start with the shared `named-method-obj.set`
  family because it blocks three tk cases, then `pwa`, then the jobpanel label
  rendering gap. Verify the selected `V2ConformanceTest` cases and the affected
  conformance slice before pushing.
- **Progress (2026-07-08, `dad57a70b`):** the shared
  `named-method-obj.set` family is fixed. v1 `ReactiveSignal` values converted
  into v2 `NamedMethodObj`s now expose `get`/`set` and writes use host raw
  values, so `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` pass targeted
  `V2ConformanceTest` filters. Affected conformance
  `tkv2-busi-home,tkv2-forms,tkv2-offline` is 3/3 green across INT+JS.
  Remaining failures from this root entry: `tkv2-pwa` (`unbound global: pwa`)
  and `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `a9028b830`):** `tkv2-pwa` is fixed. Root causes:
  `pwaPlugin` was absent from the v2 plugin bridge classpath, `pwa(...)` named
  args were not pre-registered in the v2 frontend bridge, and plugin-owned
  `ctx.registerRoute(...)` calls were no-ops under `MinimalCtx`, so PWA routes
  never reached the v2 web server registry. Gates: `V2ConformanceTest -z
  tkv2-pwa` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'tkv2-pwa' --no-memo` green (INT pass;
  JS/JVM skipped by metadata). Remaining failure from this root entry:
  `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `0facf7506`):** `std-ui-jobpanel` is fixed
  on the rebased `green-main-full-sbt-test-gating` branch. Root cause:
  `FrontendBridge` registered curried vararg defs such as
  `cardWithHeader(header)(body*)` as ordinary direct-vararg defs, so the first
  clause call lowered as `cardWithHeader(List(heading))`; the UI header became
  `List(List(HeadingNode(...)))` and the label extractor fell through to `?`.
  Gates after rebasing on `origin/main@9e48204e5`: `V2ConformanceTest -z
  std-ui-jobpanel` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'std-ui-jobpanel' --no-memo` green
  (INT+JS pass; JVM skipped by metadata).
- **New blocker after rebase (2026-07-08, `origin/main@9e48204e5`):** full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` now fails only
  `array-companion-statics` with
  `RuntimeException: __method__: no dispatch for .sum on <foreign>`. Repro:
  `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`.
  Root cause confirmed below: the fresh real-array runtime semantics batch left
  read-only collection dispatch list-only.
- **FIXED (2026-07-08, `f6e6383ac`):** `array-companion-statics` is fixed.
  `ForeignV(ArrayBuffer)` is now list-like for read-only collection dispatch
  (`sum`, `mkString`, HOFs, etc.) while keeping mutable array operations
  (`arr.get/set`, indexed apply, `length`) on the real ArrayBuffer. Gates:
  `V2ConformanceTest -z array-companion-statics` green,
  `tests/conformance/run.sh --only 'array-companion-statics' --no-memo` green
  (INT+JS+JVM), and full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` green
  (76 succeeded, 54 ignored, 0 failed). No known deterministic blocker remains
  in this `V2ConformanceTest` root entry.

## v2-actors-sendafter-cli-default-noop — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** codex, while fixing `root-test-cluster-cli-runtime-readiness`.
- **Repro:** after `scripts/sbtc "cli/assembly"`, run a fat-jar script containing
  `runActors { val me = spawn { () => val pid = self(); sendAfter(10, pid,
  "hello"); receive { case msg => println("got: " + msg) } } }`.
  `java -jar v1/tools/cli/target/scala-3.8.3/ssc.jar <file>` and `--v2`
  exit 0 with no `got: hello`; `--v1` prints `got: hello`.
- **Impact:** v2/default does not yet execute delayed actor flows that v1's actor
  scheduler supports. The root `sbt test` blocker below was fixed by making v1
  cluster integration fixtures explicit `--v1`; this entry tracks the actual v2
  production gap separately instead of hiding it behind test harness selection.
- **Fix direction:** implement/parity-check actor timer handling in the v2 runtime
  path or explicitly reject unsupported actor APIs under `--v2` with a diagnostic
  until v2 actor support is complete. Verify with the fat-jar repro above plus
  actor/cluster conformance slices.
- **Root cause:** `PluginBridge.registerActors` had a partial v2 actor runtime:
  `spawn`, `receive`, `self`, and `runActors` existed, but scheduled sends were
  not registered and `runActors` waited only for the root actor thread. In the
  fat-jar path the root actor completed immediately after `spawn`, so the JVM
  exited with virtual child/timer work still pending and no diagnostic.
- **Fix:** `a6c9d8b7c` adds v2 actor run-state/quiescence tracking, real
  `sendAfter` / `sendInterval` / `cancelTimer` globals, queue wakeups, and a
  real assembled-CLI regression covering default and `--v2`.
- **Verified:** `scripts/sbtc "v2PluginBridge/compile"`; `scripts/sbtc
  "cli/assembly"`; the original fat-jar repro now prints `got: hello` under
  default, `--v2`, and `--v1`; `scripts/sbtc "cli/testOnly *V2ActorCliTest"`;
  `scripts/sbtc "installBin"` followed by `tests/conformance/run.sh --only
  'actors-*' --no-memo` (8/8 passed; first pre-install run was invalid because
  the conformance runner uses `bin/ssc` / `bin/lib/ssc.jar`, not the freshly
  assembled `v1/tools/cli/.../ssc.jar`).

## v2-op-arg-lifting — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** claude-fable-5, working busi's ledger repro past the append/2 fix.
- **Symptom:** a strict call (user fn OR native) with an unresolved effect `Op` as an
  ARGUMENT executes immediately instead of deferring into the Op's continuation.
  busi `tests/v2/ledger.ssc` now fails at check #2: `accountBalance` (imported fn
  performing `Journal.read` inside) returns a raw `Op(Journal.read, …)`; `formatMoney`
  / `println` then consume the Op as a value. Same family: conformance
  `js-applyunary-effect-cps.ssc` on the v2 lane (`__unary__: - on Op(...)`), and a
  perform whose argument is effectful (`Journal.append("sum", xs.foldLeft(...))`
  where `xs` came from a read) leaks the Op into the handler's payload.
- **What works vs not:** val-binding (`letThreadOp`), statement sequencing
  (`seqThreadOp`), method-receiver (`methodOp`), arith operands, and fn-position
  (`applyFallback`) all lift Ops; **call-argument position does not** — neither for
  closures nor for plugin natives.
- **Repro (minimal):** inside `runJournal(() => { … })`:
  `println("balance=" + formatMoney(accountBalance(...)))` prints the Op raw.
  Full: `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  (on ≥ d2340f85e) → `FAIL: cash debit = 100.00` after `ok: entry balances`.
- **Fix (landed):** NOT at the runtime call chokepoint — a blanket runtime lift
  would break the Mira/hm kernel lane, where passing Op VALUES to functions is
  legitimate (`runState(k(r), s)` must receive the op raw; deferring forwards it
  past its own handler). Instead: `OpAnf` — a bridge-side CoreIR pass (bridged
  lane only) that Let-binds potentially-Op arguments (App args, Prim args, Ctor
  fields, Match scrutinees, If conditions), so the kernel's existing
  letThreadOp/seqThreadOp threading performs the deferral. De Bruijn
  cutoff-shifting for the inserted binders. Exclusions: `handle(expr)(handler)`
  paren form (the body's Op must reach handle RAW — effect-multishot bench
  caught this); `While` untouched (per-iteration re-evaluation). GATED: the pass
  runs only when the merged source mentions `effect `/`handle` — ops cannot
  materialize otherwise (context runners intercept pre-Op), and unconditional
  wrapping made pattern-match-heavy 3-4× slower; with the gate it's at baseline.
- **Bench A/B (bin/ssc bench --machine --backend v2):** pattern-match-heavy
  26.2-26.9 vs baseline ~28 ✓; effect-multishot 5.19 vs 5.04-6.01 ✓;
  streams-pipeline 0.0080 vs 0.0085 ✓; arith-loop/nested-loop/list-fold/
  hof-pipeline parity.
- **Verified:** busi ledger.ssc ALL OK on --v2 (was: FAIL check #2); examples
  corpus 153/9 = baseline; tests/conformance v2 batch 109/39 (was 108/40 —
  `js-applyunary-effect-cps` FLIPPED TO PASS: `-Op` unary operand now threads);
  run.sh effect family 4/4 INT/JS/JVM; v2 kernel check.sh 8×3 ALL GREEN
  (kernel lane untouched by construction).
- **busi full sweep (tests/v2, 61 files):** --v2 47/61 PASS (was 0 — died on the
  first test); --v1 same launcher/flags 61/61 → the 14 remaining fails are real
  v2 parity gaps, queued as SPRINT `v2-busi-testsweep-gaps`. run.sh full
  conformance 123/123.

## v2-effect-multiarg-op — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** busi agent (rozum `scalascript` room, 2026-07-08 seq31), while bumping
  busi to scalascript pin `0a6358787` with `v2-prod-default-switch` active.
- **Repro:** `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  → `RuntimeException: match: no arm for append/2` at `PluginBridge.runEffectLoop`
  (PluginBridge.scala:2165 → Runtime.scala:367). Reproduced locally 1:1.
- **Root cause:** multi-argument effect operations lost their arity crossing the
  Free-monad Op protocol. `Runtime.scala` effect dispatch packed
  `Journal.append(scope, fact)` as `DataV("Op", [label, Tuple2(scope, fact), k])`;
  `PluginBridge.runEffectLoop` called the handler with **append/2** while the user
  arm `case Journal.append(scope, fact, resume)` compiles to **append/3**. Nothing
  in the corpus exercised a >1-arg effect op. v1 delivers payload args unpacked.
- **Fix:** `2ef288004` — multi-arg payloads pack under the internal `__EffArgs__`
  marker (NOT `TupleN`: a genuine single tuple argument must stay op/2);
  `runEffectLoop` unpacks to `op(a1…aN, resume)`. Companion fix `d2340f85e` —
  std/ imports on the v2 lane now fall back to `libPath/runtime/<path>` (mirrors
  v1's ImportResolver), fixing `unbound global: money` when running the assembled
  jar from busi's cwd (std resolution was cwd-sensitive).
- **Verified:** regression `tests/conformance/effect-multiarg-op.ssc` (+
  `lib/effect-journal.ssc`, imported-module handler, 2-arg + 1-arg ops) green on
  INT/JS/JVM + v2 engine; `run.sh --only 'effect*'` 4/4; examples corpus at the
  153/9 baseline; busi ledger repro gets past both failures (now blocked by
  `v2-op-arg-lifting` above — reported to busi).
- **Awaiting:** busi re-runs its 62-test suite on `--v2` (blocked on
  v2-op-arg-lifting for ledger.ssc at least).

## conformance-js-product-show-synthetic-tag — `fixed` (2026-07-08)
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: 4e8cbb635
     gate: tests/conformance/prisms.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/prisms.ssc`.
- **Observed:** JS prints product values with an extra synthetic numeric field, e.g.
  `Circle(0, 5)` instead of `Circle(5)`, `Rect(1, 3, 4)` instead of `Rect(3, 4)`,
  and `User(0, bob, false)` in optics/optional cases. INT/JVM pass.
- **Status:** fixed in `4e8cbb635`; root cause was JS runtime product handling
  treating the internal `_tag` field as user data. `_show` now skips `_tag`, and
  positional `.copy(...)` maps arguments over user fields only (`_type` / `_tag`
  excluded), so enum tags remain available for pattern matching without leaking
  into display or copy semantics.
- **Verified:** `scripts/sbtc "installBin"`; direct
  `bin/ssc run-js tests/conformance/prisms.ssc` and
  `bin/ssc run-js tests/conformance/optic-polish.ssc`; and
  `tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`
  (**4/4 green**).

## v2-invoice-email-nondet — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: cli -->

- **Found by:** codex, during `v2-prod-plugin-boundary` full-corpus production parity
  verification.
- **Symptom:** a full `scripts/v2-output-parity --all` run after the dataset stack fix
  reported one extra mismatch in `examples/invoice-email.ssc`: the generated artifact
  byte-count line differed (`2681` vs `2685`). An immediate targeted rerun matched, so
  this is treated as nondeterministic/generated-output exposure rather than a v2-error.
- **Repro:** after `scripts/sbtc "installBin"`, run targeted parity repeatedly:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc`.
- **Suspect:** the example prints generated MIME/PDF byte length instead of stable
  semantic facts. Byte-exact generated artifacts can vary across runners and should not
  be the user-facing example contract for the v2 production gate.
- **Root cause:** the user-facing example contract exposed exact generated MIME/PDF
  message length. That length is not semantically important and can vary when the PDF
  renderer/MIME generator changes incidental bytes.
- **Fix (d8e0ecee4):** keep building the PDF and MIME message, but print a stable
  semantic line after confirming the message is non-empty:
  `MIME message assembled: PDF attached`.
- **Verification:** direct v1/v2 runs print the same stable line; repeated targeted
  parity for `examples/invoice-email.ssc` was **5/5 MATCH**; neighbor parity for
  `examples/invoice*.ssc examples/pdf-extract-demo.ssc` was **3/3 MATCH**. Conformance
  globs `invoice*`, `*pdf*`, and `*mime*` have **0 cases**, so there is no affected
  conformance case to run.

## v2-list-unlist-stack-overflow — `fixed` (2026-07-08)
<!-- status: unknown
     lane: native
     area: runtime
     gate: tests/conformance/run.sc -->

- **Found by:** codex, during `v2-prod-plugin-boundary` production parity work.
- **Symptom:** `examples/dataset-parallel-sum.ssc` was the only remaining full-parity
  v2-error. Direct v2 execution crashed before stdout with `StackOverflowError`.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/dataset-parallel-sum.ssc`.
- **Root cause:** `Prims.unlistPub` recursively converted ScalaScript `Cons/Nil`
  lists to Scala `List`; `Dataset.fromList(List.range(1, 100_001))` crosses that
  boundary with 100k elements and overflowed the JVM stack. `Prims.listOf` also used
  `foldRight`, which had the same large-list stack-risk in the opposite direction.
- **Fix (44f3d4a24):** `Prims.unlistPub` and `Prims.listOf` are iterative. The stale
  `dataset-parallel-int` expected snapshot was updated to match the numeric sorted
  output already produced by both v1 and v2 direct runs.
- **Verification:** `dataset-parallel-sum.ssc` parity is MATCH; `scala-cli
  tests/conformance/run.sc -- --only 'dataset*' --no-memo` passes **15/15**;
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset*.ssc`
  has **0 v2-error**; full corpus has **0 v2-error**.

## plugin-cli-oslib-shadow — `fixed` (2026-07-07)
<!-- status: unknown
     lane: native
     area: cli -->

- **Found by:** codex, while stabilizing the red `origin/main` CI run
  `28832706348`.
- **Symptom:** CI `sbt - compile and test` builds the launcher but fails during
  `Test via sbt` while compiling
  `v1/tools/cli/src/test/scala/scalascript/plugin/PluginCliTest.scala`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`.
  The failure reports `type Path is not a member of scalascript.compiler.plugin.os`
  and missing `temp`, `remove`, `makeDir`, `read`, `write`, `exists`, `list`,
  `copy`, and `walk` members.
- **Root cause:** because `PluginCliTest` is in package `scalascript.compiler.plugin`,
  the local `scalascript.compiler.plugin.os` package shadows os-lib's root `os`
  package.
- **FIXED (2026-07-07, `6d133361a`):** qualified all os-lib references in
  `PluginCliTest` as `_root_.os`, so the test no longer resolves the local plugin
  package.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`;
  `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/testOnly scalascript.compiler.plugin.PluginCliTest"`
  (8/8).

## v2-cellset-flc-corruption — `fixed` (2026-07-05)
<!-- status: unknown
     lane: native
     area: runtime -->

- **Found by:** claude (v2-recursion-opt slice), via `map-ops` regressing to
  `SKIP(no-main)` in the v2 bench sweep.
- **Symptom:** `expected Map, got 0` crash on `map-ops` via the ssc1c pipeline; the
  failure class is broader — SILENT data corruption: any generic-cell `var` reassigned
  from a non-Int expression could store `IntV(0)` instead of the real value.
- **Root cause:** the `cell.set` FLC fast path in `FastCode` (`v2/src/Runtime.scala`)
  assumed "tryFLC fails for Float/String expressions", but the FastCode phase-1/2 batch
  (2026-07-04) added OPTIMISTIC leaves to `tryFLC` (`App(Global)`, `cell.get`,
  `arr.get`, `fieldAt`, `Local`) that coerce non-Int values to `0L`. So
  `m = m.updated(k, v)` (App body returning a Map) FLC-compiled and stored `IntV(0)`
  over the map.
- **Fix:** new `flcProvablyLong(t)` structural predicate; `cell.set` takes the FLC
  fast path ONLY when the body is provably Long (int literals, `lcell.get`, int
  arith, `.toInt/.toLong/.length/.size`). `lcell.set` is unaffected (lcells hold Long
  by construction).
- **Blast radius audit:** all 10 var-reassign corpus programs compared old-vs-new —
  identical outputs everywhere except map-ops (now correct: 124750); no other corpus
  program had engaged the corrupt path.

## v2-conformance-empty-output-flake — `fixed` (2026-07-01)
<!-- status: unknown
     lane: native
     area: conformance -->

- **Found by:** codex, while continuing K49 after the K48 multi-op typed handler work.
- **Symptom:** `cd v2 && ./conformance/check.sh` can report a contiguous block of unrelated
  `got []` failures. Direct reruns of the first failing examples pass, so the useful failure
  signal is lost when Java/Rust stderr is discarded.
- **Repro:** run the assembled-jar harness, not a dev runner: `cd v2 && ./conformance/check.sh`.
  K48 observed two full runs failing in different sections after otherwise unrelated Rust/Java
  activity.
- **Root cause:** the harness built every run into the shared path `/tmp/ssc-conformance.jar`.
  Parallel agents or repeated harness runs could overwrite that jar while an earlier run was still
  executing it, producing `NoClassDefFoundError: ssc/Program$` followed by `Invalid or corrupt
  jarfile`. Rust failures were downstream: empty generated `.rs` files had no `main`.
- **FIXED (2026-07-01, `d4ca120bf`):** `check.sh` now builds the assembled jar inside the run's
  unique diagnostic log directory, captures Java/Rust stderr and stdout artifacts, retries empty
  Java stdout once, and prints a diagnostic summary on failure.
- **Verified:** `bash -n v2/conformance/check.sh`; reproduced the old flake once and captured the
  corrupt-jar root cause; after switching to the per-run jar, two consecutive full
  `cd v2 && ./conformance/check.sh` runs passed (`run1 exit=0`, `run2 exit=0`). After rebasing on
  KC7, a final full run with the KC7 tests also passed (`final exit=0`).

## v2-conformance-echo-backticks — `fixed` (2026-06-29)
<!-- status: unknown
     lane: native
     area: front -->

- **Found by:** codex, while running full `v2/conformance/check.sh` for K46 async/actor breadth.
- **Symptom:** the conformance assertions were green, but the harness printed shell noise such as
  `show: command not found`, `method: command not found`, `effect: command not found`, and
  `a,b,c,d: command not found` to stderr.
- **Repro:** `cd v2 && conformance/check.sh`; the offending `echo "..."` lines contained Markdown
  backticks, so the shell performed command substitution before printing the heading.
- **Root cause:** double-quoted shell strings around headings that intentionally contained literal
  backticks.
- **FIXED (2026-06-29):** changed those headings to single-quoted strings. `bash -n
  v2/conformance/check.sh` passes, and the final full K46 conformance rerun completed successfully
  with captured stdout/stderr checked for `FAIL` and `command not found` (none present).

## v2-head-field-dispatch-shadow — a case-class field named `head` (non-zero index) breaks List.head
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: unrecorded
     gate: tests/conformance/head-field-shadow.ssc -->

**Status:** FIXED 2026-07-09 (was OPEN; guarded by tests/conformance/head-field-shadow.ssc).
**Fix:** the 3-arg `fieldAt(recv, idx, name)` now resolves by the RECEIVER's own
registered field names (`lookupFieldNames(tag)` → index of `name`), falling back to
full dynamic `methodOp` dispatch when the tag has no such field (Cons/Nil/Some/… —
builtin members stay builtin). Also fixes the same-name-at-different-index case
across classes. Companion: foldLeft/map/foreach for ForeignV(ArrayBuffer) and
foldLeft for ForeignV(mutable.Map) (the hub folds over Array.fill tables at module
load — the next boot blocker after head). **busi hub BOOTS on --v2**
("listening on 0.0.0.0:8392"). instance-field bench A/B flat (the tag check rides
the already-generic 3-arg path; the 2-arg match/tuple fast paths are untouched).

Importing/loading ANY module that defines e.g. `case class Ref(name: String, head: String)` makes
`xs.head` on a List resolve through the case-class FIELD accessor — by field INDEX — for other
modules: a 1-element list yields Nil (element #1 missing), and a downstream `.trim` surfaces as an
unhandled `Op("Nil.trim", (), <closure>)`. With `head` as the FIRST field (index 0) the bug hides.
This is the busi hub-boot blocker: busi core/repo_commit.ssc `RepoRef(name, head)` ×
http/context.ssc `busiCfGet` (`hits.head.drop(n).trim` at module load). Repro:
`bin/ssc --v2 tests/conformance/head-field-shadow.ssc` (expected file has the correct v1 output).
Likely general: any builtin member name (head/tail/name/…) reused as a non-first case-class field.
Found+minimized 2026-07-09 by busi (fable) while attempting the v2 hub conformance pass.

## v2-option-exists — Option.exists is unimplemented on v2
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: unrecorded
     gate: tests/conformance/v2-option-exists.ssc -->

**Status:** FIXED 2026-07-09 — the v2 VM Option method dispatch
(Runtime.scala) had map/flatMap/filter/foreach/fold/orElse/getOrElse but was
missing `exists`/`forall`/`contains`/`nonEmpty`, so they fell through to
Op/Stub. Added all four arms (same idiom as the list methods): `Some.exists`
runs the predicate → Boolean, `None.exists` → false; `forall` mirror
(None → true); `contains` by `==`; `nonEmpty`. Repro
tests/conformance/v2-option-exists.ssc green (`false`/`true`); forall/contains/
nonEmpty verified identical v1==v2; corpus 154/8 (no regression). Reported by
busi (fable); fixed by lucky-perch.

`Option.exists(pred)` is not dispatched at all on v2, for EITHER arm:
`None.exists(pred)` raises an unhandled `Op("None.exists", <closure>, <closure>)`
instead of returning `false`; `Some.exists(pred)` returns `Stub("Some.exists")`
instead of evaluating `pred` and returning a `Boolean`. v1 is correct on both.

Repro (self-contained):
```
val n: Option[Int] = None
println(n.exists(x => x > 0))   // "false" on v1, unhandled Op on v2
val s: Option[Int] = Some(5)
println(s.exists(x => x > 0))   // "true" on v1, Stub on v2
```

Found 2026-07-09 by busi (fable) driving a live v2 hub through the full
JDG money-loop simulator cycle: EVERY auth-gated route
(`isPaired`/`isOwner`/operator role checks —
`identity.exists(i => i.roles.contains(role))` on an `Option[Identity]`)
crashes or misbehaves the moment it reaches a role check, because this is
the idiomatic way busi (and presumably most v2 programs) writes an
Option-guarded predicate. This is a foundational stdlib gap, not specific
to HTTP or money-loop — it should be one of the highest-priority items to
close for v2 parity given how common the pattern is.

## v2-read-gigs-handle-leak — GigSource.fetch handle{} effect leaks unhandled inside busi's real hub.ssc/mcp.ssc (isolated minimization did NOT reproduce)
<!-- status: fixed
     lane: native
     area: runtime
     fixed-in: dd42da430
     confirmed: no -->

**Status:** FIXED 2026-07-09 in `dd42da430` and `615ed5f8f`; awaiting
reporter confirmation on busi's next pinned ScalaScript update. The original
live hub failure was reduced to a multi-import field-dispatch shadow, and the
fix was verified against the real busi hub.

Found completing a full live JDG money-loop pass on `--v2` (busi's first
successful end-to-end run of find-work→contract→track→invoice→get-paid
against real simulators through a live hub, after `v2-option-exists` and
`v2-req-form-type-collision` were fixed). Every MCP tool worked EXCEPT
`read_gigs`:

```
POST /mcp {"method":"tools/call","params":{"name":"read_gigs","arguments":{}}}
→ HTTP 500; hub log: Error: if: condition not Bool: Op("GigSource.fetch", (), <closure>)
```

busi's own domain test `tests/v2/gigs.ssc` (isolated, part of the 61/61
v2 sweep) exercises the SAME `handle { body() } { case
GigSource.fetch(resume) => resume(simGigs()) }` pattern
(`src/v2/domain/gigs.ssc`, `runSimGigSource`) successfully — so the
`handle{}` construct itself works in isolation. The tool is invoked from
`src/v2/http/mcp.ssc`'s `runTool` dispatcher:
`case "read_gigs" => runSimGigSource(() => gigsJsonStr(scoutGigs()))`
— something about calling this handle{}-wrapping function FROM a generic
`runTool(name, args)` dispatch (rather than as a top-level call, as
`tests/v2/gigs.ssc` does) drops the handler, letting the raw
`GigSource.fetch` Op reach an `if` condition somewhere downstream
un-lifted. The `(condition not Bool)` phrasing suggests the Op itself
ends up being tested as a boolean, not that `scoutGigs()`'s result is
malformed — a clue for whoever bisects this.

2026-07-09 update while claiming `v2-read-gigs-handle-leak-minimize`:
current ScalaScript `origin/main` has a newer/stronger regression before the
original live-hub-only symptom can be minimized. With this worktree's staged
CLI, `cd /Users/sergiy/work/my/busi &&
/Users/sergiy/work/my/scalascript-wt-v2-read-gigs-handle-leak-minimize/bin/ssc
--v2 tests/v2/gigs.ssc` fails during v2 compilation with
`java.lang.RuntimeException: arity: 1 expected, 3 given` at
`ssc.Runtime.run(Runtime.scala:144)`. The same busi test still passes with
busi's pinned ScalaScript submodule via
`SSC_LANE_FLAG=--v2 scripts/ssc tests/v2/gigs.ssc`. Treat this arity regression
as the first blocker in this bug: fix/pin the isolated `tests/v2/gigs.ssc`
shape on current ScalaScript first, then return to the original live hub
`/mcp tools/call read_gigs` handle leak if it still reproduces.

2026-07-09 root cause after fixing the Currency arity blocker: this is the
same field-dispatch shadow family as earlier `head` bugs, but the effectful
list receiver made it look like a `handle{}` leak. Reduced shape:
`std/json + requests.ssc + gigs.ssc` is green; adding
`runRepoJournalFrom` from `repo_journal.ssc` is enough to fail. That import
pulls in `case class RepoRef(name: String, head: String)`, after which
FrontendBridge's global `fieldIndex("head")` lowers every `.head` to
`fieldAt`. In `scoredGigs`, `gigs.foldLeft(gigs.head)(...)` then evaluates
`List.head` as eager `fieldAt` instead of the dynamic `__method__` path that
lifts over `Op("GigSource.fetch", ...)`. Self-contained repro: define
`RepoRef(name, head)`, define the gigs effect/scorer, print `ref.head`, then
run `runSimGigSource(() => gigsText(scoutGigs()))`; current v2 prints `abc`
and then fails with
`if: condition not Bool: Op("GigSource.fetch", (), <closure>)`.

2026-07-09 fix: first, payments' one-field `Currency(code)` bridge metadata
shadowed std/money's three-field `Currency(code, scale, symbol)` constructor,
so current ScalaScript failed busi's isolated `tests/v2/gigs.ssc` with
`arity: 1 expected, 3 given`; `dd42da430` keeps `Currency.apply` compatible
with both arities and returns full std/money-compatible values. Second,
`615ed5f8f` keeps common zero-arg collection/string members such as
`List.head` on the dynamic `__method__` dispatch path even when a case class
also has a same-named field; same-named data fields still resolve at runtime
through the tag/arity-aware field lookup.

Verification: focused `FrontendBridgeTest` for Currency and `List.head`,
`installBin`, reduced `RepoRef.head` + effectful `List.head` repro, busi
`tests/v2/gigs.ssc`, real busi hub `/api/gigs`, real busi hub
`/mcp tools/list`, real busi hub `/mcp tools/call read_gigs`, affected
conformance `tests/conformance/run.sh --only 'head-field-*,money-multisection'
--no-memo`, full `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest`,
and the three payments/bank-rails v2 examples all passed.

Workaround used to complete the money-loop pass: busi's tool set also
exposes `open_opportunity` as a direct entry point (bypassing
`read_gigs`/`take_gig`), which worked correctly and let the rest of the
pipeline (send_proposal → win_opportunity → sign_contract → log_work →
invoice_from_work [real KSeF send] → bank_reconcile [real match+pay] →
file_tax [real UPO]) complete successfully end to end.

Minimization attempt (did NOT reproduce): a `handle{}`-wrapping function
called from inside a `runTool(name, args)`-shaped dispatcher (an
`if name == "x" then wrapper(() => effectfulCall())` chain) — works fine
on both v1 and v2 in isolation. The trigger is something else about
hub.ssc/mcp.ssc's actual scale (imports, prior effect regions already
active from other routes/middleware, or the specific
`{"content":[...],"isError":false}` JSON-wrapping around the tool result)
that a small dispatcher didn't capture. Found 2026-07-09 by busi (fable),
same session as `v2-req-form-type-collision`.
