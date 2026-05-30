# Exact Numerics — BigInt, Decimal, Money (Specification)

Status: **planned** (v1.64). Tracked as `exact-numerics` milestone in
`BACKLOG.md` / `WORK_QUEUE.md`.
Companions: [`docs/architecture.md`](architecture.md),
[`docs/arch-stable-spi.md`](arch-stable-spi.md),
[`docs/config-system.md`](config-system.md).

---

## 1. Goals

Give ScalaScript a correct, cross-backend foundation for **exact numbers** —
the kind accounting, finance, and business software require:

- **`BigInt`** — arbitrary-precision signed integer.
- **`Decimal`** — arbitrary-precision signed decimal with explicit *scale* and
  *rounding* (the workhorse for money). `BigDecimal` is an alias.
- **`Money` / `Currency` / `RoundingMode`** — a std library on top of `Decimal`,
  including correct **allocation** (splitting an amount into parts without
  losing or inventing cents).
- A well-defined **numeric tower** and coercion rules that *forbid silent
  Decimal↔Double mixing* (the classic source of money bugs).
- Identical results on all three backends (**interpreter, JVM, JS**), pinned by
  cross-backend conformance tests.

## 2. Non-goals

- `Rational`/fraction type, complex numbers, fixed-point SIMD — not now.
- Arbitrary-radix literal syntax beyond what Scala/scalameta already parse.
- Replacing `Int`/`Double`; they stay as-is. We *add* exact types.
- Locale/i18n money formatting beyond a basic, pluggable formatter (full CLDR
  is a later, separate concern).
- FX rate sourcing — `Money.convert(rate)` takes a rate; where rates come from
  is out of scope.

## 3. Current state (the gap)

ScalaScript parses its surface syntax with **scalameta** (`import scala.meta.*`).
Numeric literals arrive as `Lit.Int` / `Lit.Long` / `Lit.Double` / `Lit.Float`.

- The interpreter `Value` model has **only** `IntV(Long)` and `DoubleV(Double)`
  (`lang/core/.../interpreter/Value.scala:215-216`).
- Literal lowering truncates everything to `Long`/`Double`
  (`runtime/backend/interpreter/.../EvalRuntime.scala:41-44`).
- There is **no `BigInt` handling anywhere** in the interpreter. `BigInt(5_000_000)`
  in `examples/x402-client.ssc:46` is not actually evaluated as arbitrary
  precision — it has no constructor binding; YAML front-matter `BigInteger` is
  even explicitly down-cast via `n.longValue` (`Parser.scala:691`).
- Arithmetic dispatch (`DispatchRuntime.scala:2227+`, `infix2`) only matches
  `IntV`/`DoubleV`/`StringV` pairs.

So a program that needs more than ±9.2×10¹⁸, or needs exact decimal scale, is
silently wrong today. That is unacceptable for the accounting use case driving
this work (see the `busi` project).

## 4. Design

### 4.1 Types

| Type | Backing — interpreter / JVM | Backing — JS | Notes |
|---|---|---|---|
| `BigInt` | `scala.math.BigInt` | native `bigint` | arbitrary-precision integer |
| `Decimal` (= `BigDecimal`) | `scala.math.BigDecimal` | runtime `_Decimal {unscaled:bigint, scale:int}` | exact decimal w/ scale |
| `RoundingMode` | enum mirror of `java.math.RoundingMode` | enum mirror | HALF_UP, HALF_EVEN, … |
| `Money` (std) | `(Decimal, Currency)` | `(Decimal, Currency)` | library, not core |
| `Currency` (std) | `{code, scale, symbol}` | same | configurable table |

JS has native `BigInt`, so `BigInt` is free there. JS has **no** native decimal;
`Decimal` lowers to a compact runtime helper carrying `(unscaledValue: bigint,
scale: number)` — i.e. Java-`BigDecimal` semantics implemented over JS BigInt.
The helper ships in the JS preamble, **capability-gated** (per the v1.61 preamble
split) so programs that never touch `Decimal` don't pay for it.

### 4.2 Construction (no new literal tokens in Phase 1)

scalameta won't emit a `Lit.BigInt`, so construction is via **built-in
constructors** recognised in `BuiltinsRuntime` (interpreter) and mapped natively
by each codegen:

```scala
BigInt(123)                       // from Int/Long
BigInt("123456789012345678901234567890")   // from String — unbounded
Decimal("12.3456")                // canonical, scale = 4
Decimal(123, scale = 2)           // = 1.23
Decimal(other: BigInt)            // scale 0
n.toBigInt   n.toDecimal          // conversions from Int
```

`Decimal(d: Double)` exists but is **discouraged** and lint-warned: constructing
exact decimals from inexact doubles reintroduces the bug we are removing. Prefer
`Decimal("…")`.

**Optional sugar (Phase 7, behind a preprocessor pass):** suffix literals
`123n` → `BigInt`, `12.34m` → `Decimal`, and auto-promotion of an integer
literal that overflows `Long` → `BigInt`. Kept out of Phase 1 because it touches
the preprocessor, not just the runtime.

### 4.3 Numeric tower & coercion

Widening for mixed binary ops:

```
Int(Long)  ⊆  BigInt  ⊆  Decimal          (exact world)
Double                                     (inexact world — separate)
```

Rules (enforced by the Typer; interpreter mirrors them):

- `Int ⊕ Int → Int` (unchanged; wraps as today — checked variants offered).
- `Int ⊕ BigInt → BigInt`; `BigInt ⊕ BigInt → BigInt`.
- `Int|BigInt ⊕ Decimal → Decimal`.
- `Decimal ⊕ Decimal → Decimal`.
- **`Decimal ⊕ Double` and `BigInt ⊕ Double` → type error.** Require an explicit
  `.toDouble` or `.toDecimal`. This is the central safety rule.
- `Double ⊕ Double → Double` (unchanged).

### 4.4 Operations

- **BigInt:** `+ - * / % pow abs gcd min max signum compareTo bitLength
  shiftLeft shiftRight toInt toLong toDecimal isProbablePrime`.
- **Decimal:** `+ - *` exact; `/` requires a rounding context (see 4.5);
  `setScale(n, mode) round(mode) abs signum scale precision pow(int)
  min max compareTo toBigInt toDouble`.
- **Money (std):** `+ -` (same currency, else error); `* / by scalar`;
  `allocate(weights) distribute(n)` (cent-preserving split); `convert(rate)`;
  `round(currencyScale, mode)`; `negate isZero isPositive compareTo`; format/parse.

### 4.5 Division, scale & rounding

`Decimal / Decimal` can be non-terminating (`1/3`). Bare `/`:

- uses a **default `MathContext`** (precision 34, `HALF_EVEN`), which is
  **per-org configurable** (config-system) — matching the "everything
  configurable, sane defaults" principle; and
- an explicit `a.divide(b, scale, mode)` is always available and preferred for
  money.

`*` adds scales (Java semantics); `+`/`-` use `max(scale)`. Money operations
round to the **currency's** scale with the configured `RoundingMode`.

### 4.6 Equality & hashing

Java `BigDecimal.equals` is scale-sensitive (`1.0 != 1.00`). For accounting that
is surprising and wrong. Therefore in ScalaScript:

- **`==` on `Decimal` is numeric** (`compareTo == 0`), so `Decimal("1.0") ==
  Decimal("1.00")`.
- `hashCode` is computed on the **normalised** value (trailing zeros stripped)
  so `Decimal` works correctly as a `Map`/`Set` key consistent with `==`.
- A separate `eqExact` exposes scale-sensitive comparison when genuinely needed.

This divergence from `java.math.BigDecimal` is intentional and documented.

### 4.7 Serialization

Arbitrary precision does not fit JSON numbers, so:

- `Decimal` ⇄ **canonical string** (`"12.34"`, scale preserved); `BigInt` ⇄
  decimal string.
- Covers `.scim`/`.scjvm` artifacts (`ValueSerializer`), JSON (`JsonSupport`),
  and the distributed wire codec.

## 5. Backend lowering

| Backend | BigInt | Decimal |
|---|---|---|
| **Interpreter** | new `Value.BigIntV(BigInt)`; ops in `DispatchRuntime.infix2`/`dispatch`; ctor in `BuiltinsRuntime` | new `Value.DecimalV(BigDecimal)`; same dispatch + ctor; numeric-/value-equality in `Value.show`/eq |
| **JVM** (`JvmGen`) | emit `scala.math.BigInt` | emit `scala.math.BigDecimal` — native, exact, trivial |
| **JS** (`JsGen`) | native `BigInt` | `_Decimal` runtime helper (BigInt-backed), capability-gated in preamble |

Typer (`lang/core/.../typer/Typer.scala`) registers the new types, implements the
tower in §4.3, and raises the Decimal↔Double error.

## 6. Std library: Money

New module `runtime/std/money/` (a `.ssc` std module, optionally a plugin):

- `Currency` with a **built-in ISO 4217 table** (code → scale, symbol) that is
  **per-org overridable** (config-system).
- `Money = (amount: Decimal, currency: Currency)`.
- `RoundingMode` enum.
- **`allocate(total, weights): List[Money]`** — largest-remainder split so the
  parts sum *exactly* to the total (e.g. split `$0.05` three ways →
  `[0.02, 0.02, 0.01]`). This is the single most important correctness helper for
  invoicing/tax/splits.
- `format(money, locale?)` / `parse(str, currency)` — basic, pluggable.

This is the layer `busi` (and any commerce/payments code) consumes.

## 7. Phased roadmap (v1.64.x)

Each phase: one PR, conformance tests on all relevant backends, no regression in
the existing suite.

- **v1.64.0 — Spec** (this doc) + BACKLOG/WORK_QUEUE entries.
- **v1.64.1 — BigInt (interpreter):** `BigIntV`, `BigInt(...)` ctor, arithmetic,
  methods, `show`/serialize, Int↔BigInt tower. Tests.
- **v1.64.2 — Decimal (interpreter):** `DecimalV`, construction, exact `+-*`,
  `divide`/MathContext, `setScale`/rounding, numeric `==` + normalised hashing,
  serialize. Tests.
- **v1.64.3 — Typer:** register types; numeric tower; **Decimal↔Double error**;
  literal/inference rules.
- **v1.64.4 — JVM codegen:** lower to `scala.math.BigInt`/`BigDecimal`; map all
  ops; cross-backend conformance vs interpreter.
- **v1.64.5 — JS codegen:** native `BigInt`; `_Decimal` runtime helper
  (capability-gated); ops; node-run conformance vs interpreter/JVM.
- **v1.64.6 — Money std lib:** `Currency`/`Money`/`RoundingMode`, configurable
  currency table, **allocation**, format/parse. Tests across backends.
- **v1.64.7 — Sugar (optional):** ✅ suffix literals `123n` → `BigInt("123")`,
  `12.34m` → `Decimal("12.34")`, and oversized-int auto-promotion (integer
  literal > `Long.MaxValue` → `BigInt("…")`) — implemented as the
  `numeric-literals` preprocessor (priority 15), so they work on every backend
  uniformly. The `money"…"` interpolator is **deferred** to the Money std lib
  (it belongs with `Currency`/locale parsing, not the core literal lexer).

**Gate:** v1.64.1–v1.64.6 are required for `busi`. v1.64.7 is ergonomics.

## 8. Verification

- A shared **conformance corpus** (`tests/conformance/numerics-*.ssc`) run on
  interpreter, JVM, and JS must produce byte-identical output — especially for
  rounding-boundary, division, allocation, and large-value cases.
- Property tests: `(a/b)*b + a%b == a` for BigInt; allocation parts always sum to
  the total; `setScale` round-trips; `Decimal` `==`/`hashCode` agreement.
- No regression in the existing ~1100-test interpreter suite.

## 9. Risks

- **R1 — Decimal/Double mixing:** silent precision loss. *Mitigation:* Typer
  rejects; explicit conversion required (§4.3).
- **R2 — JS decimal correctness/perf:** the `_Decimal` helper must match
  `BigDecimal` rounding exactly. *Mitigation:* shared conformance corpus; port a
  well-understood algorithm; capability-gate so non-users don't pay.
- **R3 — `==` scale-insensitivity** diverges from Java. *Mitigation:* documented;
  `eqExact` escape hatch; normalised hashing.
- **R4 — Non-terminating division** throws without a context. *Mitigation:*
  default configurable `MathContext` + explicit `divide(scale, mode)`.
- **R5 — Map/Set keys:** hashing must agree with numeric `==`. *Mitigation:*
  canonicalise (strip trailing zeros) for hash.
- **R6 — Hot-path cost:** boxing BigInt/Decimal. *Mitigation:* keep the `IntV`
  fast path; promote only when operand types require it.

## 10. Files to touch (implementation cross-reference)

**Core / interpreter:**
- `lang/core/src/main/scala/scalascript/interpreter/Value.scala` — add
  `BigIntV`, `DecimalV`; `show`; equality/hash.
- `runtime/backend/interpreter/.../EvalRuntime.scala` — literal/ctor lowering.
- `runtime/backend/interpreter/.../DispatchRuntime.scala` — arithmetic,
  comparison, methods, tower coercion in `infix2`/`dispatch`.
- `runtime/backend/interpreter/.../BuiltinsRuntime.scala` — `BigInt`/`Decimal`
  constructors.
- `runtime/backend/interpreter/.../ValueSerializer.scala`,
  `lang/core/.../interpreter/JsonSupport.scala` — string serialization.

**Typer / IR:**
- `lang/core/src/main/scala/scalascript/typer/Typer.scala` — types + tower +
  Decimal/Double guard.
- `lang/ir/src/main/scala/scalascript/ir/Ir.scala` — `LitValue` may gain
  `BigIntL`/`DecimalL` (string-backed) if Phase 7 literals land.

**Codegens:**
- `runtime/backend/jvm/.../JvmGen.scala` — native BigInt/BigDecimal.
- `runtime/backend/js/.../JsGen.scala` — native BigInt + `_Decimal` preamble
  helper (capability-gated).

**Std library:**
- `runtime/std/money/` (new) — `Money`, `Currency`, `RoundingMode`, allocation,
  format/parse, configurable currency table.

**Docs / planning:**
- `docs/exact-numerics.md` (this), `BACKLOG.md`, `WORK_QUEUE.md`, `CHANGELOG.md`.

## 11. Open questions

- **Q1 — Bare `Decimal /` default context:** precision 34 / `HALF_EVEN` like
  Java, or require explicit context always? *Lean: configurable default, explicit
  preferred for money.*
- **Q2 — `BigDecimal` alias:** expose both `Decimal` and `BigDecimal` names, or
  only `Decimal`? *Lean: both; `Decimal` is the documented primary.*
- **Q3 — Money as core vs plugin:** ship `Money` in a std `.ssc` module or a
  loadable plugin? *Lean: std `.ssc` module (always available), currency table
  pluggable.*
- **Q4 — Phase 7 literal suffixes:** worth the preprocessor complexity, or is
  `BigInt(...)`/`Decimal("…")` enough? *Defer; revisit after v1.64.6.*
