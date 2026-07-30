# JSON number policy — unifying `jsonParse` with v1's own JSON core

**Bug:** `v1-json-two-contradictory-number-policies`. **Approved by Sergiy 2026-07-30**
("Исправляй то что можешь. Начни с v1-json-two-contradictory-number-policies").

## What was wrong

v1 held two contradictory policies for the same question, and `jsonParse` used the lossy one:

| site | policy |
|---|---|
| `v1/lang/core/src/main/scala/scalascript/interpreter/JsonParser.scala:93` | `Value.doubleV(s.toDouble)` — **binary64** |
| `v1/runtime/std/json-plugin/.../V1JsonCore.scala:127` | exact `BigDecimal`, commented *"never a lossy `Double`"* |
| `v2/runtime/std/json-plugin/.../NativeJsonCodec.scala:223` | exact decimal — agrees with V1JsonCore |

`jsonParse` reaches the first site through `PluginApi.parseJson` (`PluginApi.scala:63`), so the corpus
GOLDEN recorded v1's undocumented policy while v2 implemented the documented one. The `json-read`
DIVERGE was two v1 policies disagreeing with each other, surfaced through v2.

The parser is not only `jsonParse`'s. Call sites: HTTP request bodies
(`InterpreterHttpHandler.scala:195`), typed REST handler bodies (`TypedHandlerWrapper.scala:115`), and
actor cluster wire messages (`ActorScheduler.scala:871/950/2028`). So an amount arriving as JSON was
going through binary64 on the lane the whole corpus treats as ground truth.

## The change

`JsonParser.parseNumber` returns `Value.DecimalV(BigDecimal(s))` for a literal containing `.`/`e`/`E`.
Integral literals are unchanged (`Value.intV`). On `BigDecimal` overflow it falls back to the OLD
double path rather than to `V1JsonCore`'s `BigDecimal("0.0")` — a pathological exponent should degrade
to the previous behaviour, not silently become zero.

## Measured: reading is now identical on both lanes

Same input, `println(jsonParse(x))`, one fresh worktree build:

| input | INT before | INT after | v2 (unchanged) |
|---|---|---|---|
| `0.0` | `0` | `0.0` | `0.0` |
| `0.10` | `0.1` | `0.10` | `0.10` |
| `1.50` | `1.5` | `1.50` | `1.50` |
| `2.0` | `2` | `2.0` | `2.0` |
| `0.1000000000000000055511151231257827` | `0.1` | all 34 digits | all 34 digits |
| `1e2`, `1.0e2` | `100` | `100` | `100` |
| `3.14`, `-7` | unchanged | unchanged | agree |
| `[1.5, 2.50]` | `List(1.5, 2.5)` | `List(1.5, 2.50)` | `List(1.5, 2.50)` |

INT and v2 now agree byte-for-byte on all ten probes. **v2 was already a working implementation of
this policy**, which is the strongest evidence available that it is viable: the change aligns int with
a lane that has been shipping it.

## Measured: the honest cost

`Decimal ⊕ Double` is a DELIBERATE error in ssc (exact-numerics §4.3 — mixing exact decimal with
inexact binary float silently loses precision; `DispatchRuntime.decimalDoubleMix`). A parsed JSON
number is now a Decimal, so mixing it with a `Double` LITERAL changes behaviour:

| expression | before | after |
|---|---|---|
| `jsonParse("1.5") + 1.5` | `3` | **ERROR** `cannot mix Decimal and Double in '+'` |
| `jsonParse("1.5") > 1.0` | `true` | **ERROR** `No method '>' on DecimalV(1.5)` |
| `jsonParse("1.5") == 1.5` | `true` | **`false` — SILENTLY** |
| `jsonParse("1.5") + 1` | `2.5` | `2.5` — Int mixes fine |
| `jsonParse("1.5") * 2` | `3.0` | `3.0` |
| `jsonParse("2") + 1.5` | `3.5` | `3.5` — integral literals stay Int |
| `jsonParse("1.5").toDouble + 1.5` | `3` | `3` — the documented escape hatch |

Decimal is well-behaved among itself — `>`, `<` work and `jsonParse("1.5") == jsonParse("1.50")` is
`true`, so trailing zeros do not break equality. Only mixing with a Double is affected.

**The `==` row is the dangerous one**: no error, just a different answer. Anyone reviewing this change
should weigh that above the two loud failures.

**Two diagnostics are worse than they need to be** and are NOT fixed here because both live in
`DispatchRuntime` (held by claim `infix2-jit-split` at the time of writing):
* `>` says `No method '>' on DecimalV` instead of the `cannot mix Decimal and Double` message that
  `+` gives for the same mistake.
* `==` returning `false` across the two numeric kinds is arguably correct Scala-ish behaviour and
  arguably a trap; it is a policy question, not a bug, and is left as one.

## Corpus verdict — measured on all three lanes

`scala-cli tests/conformance/contract.sc` (default lanes int, js, v2), 527 cases, 1063 cells:

* **`json-read v2 DIVERGE` -> PASS.** The divergence this whole investigation started from is closed on
  the real gate, not just in a probe.
* **No JSON-related regression on any lane.** Not one `json-*` row appears in the regression list.

The run does report 10 regression rows, and NONE of them belong to this change: they are the six cases
the previous claim (`skip-triage-golden-lane`) removed from the SKIP list, whose js/v2 cells the
contract now sees for the first time. "Was SKIP, now has a non-PASS cell" is classified as a
regression row.

**Correcting my own earlier report.** That claim reported those six as "2 v2 PASS + 4 v2 FAIL" — I had
measured only int and v2. Across all three lanes the honest count is **2 v2 PASS, 4 v2 FAIL, and 6 js
FAIL**. Three distinct pre-existing js defects, all previously hidden by the SKIP:

| case | js failure |
|---|---|
| `dsl-yaml-like` | `SyntaxError: Invalid or unexpected token` — reduced to a 3-line repro, filed as `jsgen-char-literal-escape` |
| `dsl-sql-recovery` | malformed emitted module around a merged `std.parsing` destructuring |
| the 4 mcp cases | `not callable` — the MCP extern is absent on js as it is on v2 |

## Corpus verdict

See the run recorded in the commit that lands this. The measurement order was fixed in advance and
followed: change -> full corpus -> report the exact changed cells -> only then discuss re-freezing.
Re-freezing is NOT part of this change: `corpus-baseline.tsv` / `contract-roster.tsv` are held by
another claim.

## Follow-up: there was a FIFTH site, and the JS fix is host-dependent

The section above says "no JSON-related regression on any lane". The per-push gate disagreed within
the hour: `Conformance shard 0/4` went red on `json-read line 8: expected=0.0 got=0` — on **JS and
JVM**. Two separate defects, and each one is a hole in how the change was measured rather than a
mistake in the policy itself.

### 1. The JVM lane — a fifth implementation the census missed

The census listed four number policies. There are five. `jsonParse` on the JVM lane does not reach
any of the four:

| site | policy |
|---|---|
| `v1/runtime/http-server/jvm/.../RestRuntime.scala:378` | `jsonParse` -> `_fromJson` -> `_JsonParser.parseNumber` -> `s.toDouble` — **binary64** |

Why it was invisible: the corpus verdict was measured with `contract.sc`, whose default lanes are
**int, js, v2**. The JVM lane is not in that set, so a JVM-only regression cannot appear in that run
however carefully it is read. The run was complete for what it covered and silent about the rest.

The tell was in the same file. That commit taught `JsonValue.asInt` / `asDouble` twenty lines below
to ACCEPT a `BigDecimal` — the file was prepared to receive a shape its own parser never produced.

Fixed by mirroring `JsonParser.parseNumber` exactly, including the overflow fallback, plus a
`BigDecimal` arm in `_toJsonValue`: without it an exact decimal fell through to
`case other => _jsonQuote(_show(other))` and a parse/stringify round-trip silently turned a JSON
number into a JSON string. `toPlainString`, matching `V1JsonCore.toCore`.

And a test that can fail. The file's only number test was
`jsonParse("3.14") shouldBe 3.14` — which passes under EITHER policy, because
`BigDecimal.equals` compares numerically against a `Double`. That is why this was the site that
drifted: it was the one whose test could not observe the drift. The replacement asserts the type and
the exact digits (`0.10`, `1.50`, 34 significant digits, `0.10e1 -> 1.0`), and the boundary of the
overflow fallback is MEASURED, not assumed: `1e2147483648` is still exact (scale = `Int.MinValue`),
`1e2147483649` is the first input that throws and degrades to `Double`.

### 2. The JS lane — the same program prints different things on different Node versions

`_jsonNumberReviver` gets the literal from the ES2025 `JSON.parse` reviver's `context.source`. Where
that is unavailable it returns the plain `Number`, which the comment describes as keeping "today's
behaviour instead of erroring". But today's behaviour is now the WRONG answer, so the fallback does
not preserve compatibility — it makes exactness depend on the host:

* local Node 26: `json-read` **PASSES** on js;
* CI's `node-version: '20'` (`ci.yml:216`, and every other workflow): **FAILS**, `0.0` reads back `0`.

`v2/host/js/*/package.json` declares `"node": ">=20"`, so Node 20 is a SUPPORTED host. Bumping CI
would turn the golden green while leaving a supported host printing a different number — the
apparatus would stop reporting the defect without the defect being gone. Not done.

The fix belongs in `core-collections.mjs` (held by another claim at the time of writing) and is
one path instead of two: a small exact JSON parser that produces `_Decimal` directly, with
`_jsonNumberReviver` deleted. `JSON.parse` may stay as a fast path only under
`!/[.eE]/.test(s)`, where the text provably contains no fractional literal and both paths agree.
Positional matching of pre-scanned literals does NOT work: JS reorders integer-like object keys, so
reviver order is not source order.

### What to take from this

Two lanes, one shape: **a measurement that does not cover a lane says nothing about that lane**, and
that includes the host a lane runs on. `contract.sc`'s default lane set excludes jvm, and the local
Node is not CI's Node. Neither gap is exotic; both were invisible because the report said "all three
lanes" and the reader had no reason to ask which three.
