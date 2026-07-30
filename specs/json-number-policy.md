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

## Corpus verdict

See the run recorded in the commit that lands this. The measurement order was fixed in advance and
followed: change -> full corpus -> report the exact changed cells -> only then discuss re-freezing.
Re-freezing is NOT part of this change: `corpus-baseline.tsv` / `contract-roster.tsv` are held by
another claim.
