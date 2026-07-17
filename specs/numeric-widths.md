# numeric-widths — THE normative numeric width table

> **Status: NORMATIVE.** This file is the single source of truth for how wide every ScalaScript
> numeric type is. Where any other document, comment, or table disagrees with §2, **§2 wins** and
> the other document is a bug.
>
> Sprint item: `SPRINT.md` §`int-width-conformance` (W1, W2). Companion:
> [`numeric-width-reconciliation.md`](numeric-width-reconciliation.md) (the ABI decision and its
> rationale), `v2/specs/10-core-ir.md` §2 (the frozen Core IR value domain).

## 1. The law

**`Int` is 64-bit on EVERY backend. A backend that truncates it is NON-CONFORMING.**

This is a *conformance requirement*, not an implementation detail and not a quality of a
particular backend. It follows directly from binding design principle #1 (`AGENTS.md`):

> *One source, many targets. Source semantics are target-independent. Backends translate; **they do
> not reinterpret.***

A backend that maps ssc `Int` onto a 32-bit host integer reinterprets the source. `2147483647 + 1`
is `2147483648` in ScalaScript — on every target, or the target is non-conforming. There is no
"32-bit mode", no per-backend `Int`, and no flag that changes it.

**Why this file exists.** The width was decided long ago and implemented correctly by the
interpreter, by v2 (native/JS/bytecode) and by the ABI descriptor — but it was stated as a
requirement *nowhere*. Its most load-bearing statement was a **comment inside
`tests/conformance/run.sc`**, and `SPEC.md` §4.1 — the canonical language specification — actively
said the **opposite** ("`Int` | 32-bit integer") until 2026-07-17. Every consumer therefore
independently guessed the width by reading a type name that lies: `Int` reads as "32 bits" to
everyone alive. That guess is exactly how
`coreir-abi-int-width-declared-i32-actually-i64` (the descriptor declaring 32 bits for a 64-bit
value, silently truncating at every foreign-host boundary) happened. **A table nobody is required
to agree with is a table everybody re-derives, differently.**

## 2. The table (NORMATIVE)

`Width` is the runtime width in bits. `ABI` is the `AbiPrimitive` the v3 descriptor declares for
that spelling. `Evidence` is the retained `NumericWidthEvidence` that keeps overload identities
distinct once two spellings collapse onto one ABI primitive.

<!-- BEGIN NORMATIVE TABLE: ssc-numeric-widths -->

| Spelling | Width | Semantics | ABI | Evidence |
|---|---|---|---|---|
| `Int` | 64 | two's-complement, wrapping | `I64` | `DeclaredInt` |
| `Long` | 64 | two's-complement, wrapping | `I64` | `DeclaredLong` |
| `Double` | 64 | IEEE-754 binary64 | `F64` | none |
| `Float` | 64 | IEEE-754 binary64 | `REJECTED` | none |
| `BigInt` | arbitrary | arbitrary precision | `BigInt` | none |

<!-- END NORMATIVE TABLE: ssc-numeric-widths -->

`Width = arbitrary` means unbounded; it has no fixed bit count by construction.

`ABI = REJECTED` means the spelling is a legal ScalaScript type that **cannot cross the ABI
boundary**: `PreBodyApiDescriptorProducer` rejects it with `UNSUPPORTED_NUMERIC_WIDTH`, and
`AbiPrimitive` has no `F32` case to hold it. **This is deliberate and it fails CLOSED** — a module
exporting a `Float` is refused whole, rather than marshalled at a guessed width. Keep it that way.
Do not "fix" it by inventing an `F32`: per the table `Float` is 64-bit, so an `F32` would
re-introduce precisely the declared-narrower-than-actual lie this file exists to prevent. The
honest options are (a) leave it rejected, or (b) map `Float` → `F64` with evidence, exactly as
`Int`/`Long` are handled. That is a language decision, not a cleanup — see §5.

### 2.1 `Int` == `Long` and `Float` == `Double` — the same runtime type, not merely the same width

This is the part that surprises every reader, so it is pinned here as a checked fact rather than
left as folklore. **Measured 2026-07-17** (v1 interpreter — the INT conformance reference — and v2
native agree):

```text
val a: Int  = 9223372036854775807   // compiles: Int holds Long.MaxValue
val b: Long = 9223372036854775807
a == b                              // true
0.1f + 0.2   => 0.30000000000000004 // double arithmetic, not float
val f: Float = 0.1; val d: Double = 0.1; f == d   // true
```

`Int` and `Long` are the same runtime type; so are `Float` and `Double`. The distinct spellings are
**source-level evidence only** — they carry no width difference. This is why the descriptor must
retain `NumericWidthEvidence`: without it, `def f(x: Int)` and `def f(x: Long)` are byte-identical
in the descriptor and collide on `stableSymbolId`/`overloadId` (measured: the factory then rejects
the module with `DUPLICATE_SYMBOL_ID` — fail-closed, but it makes the overloads unexportable).

## 3. Host carriers (NORMATIVE)

Every host profile must marshal each ABI primitive through a carrier of the declared width. A host
that uses a 32-bit carrier for `I64` truncates every value above 2^31−1 **at the boundary**, in
another language, silently.

<!-- BEGIN NORMATIVE TABLE: ssc-host-carriers -->

| ABI | js-ts | rust | swift | wasm-wasi | jvm |
|---|---|---|---|---|---|
| `I64` | `bigint` | `i64` | `Int64` | `i64` | `Long` |
| `F64` | `number` | `f64` | `Double` | `f64` | `Double` |
| `BigInt` | `bigint` | `i64` | `Int64` | `i64` | `BigInt` |

<!-- END NORMATIVE TABLE: ssc-host-carriers -->

Notes, each binding:

- **js-ts `I64` is `bigint`, never `number`.** A `number` truncates above 2^31−1 through any
  int-coercing operation and cannot represent values above 2^53 exactly *at all*. This is the
  common case, not an edge case: ssc `Int` declares `I64`.
- **swift `I64` is `Int64`, never `Int`** — Swift's `Int` is platform-width; the canonical width
  must not depend on the host platform.
- **wasm-wasi `I64` is `i64`** — an `i64` at the JS embedder boundary is a `BigInt`, never a
  `number`.
- The `BigInt` row pins the *64-bit-capable carrier class* each profile must not fall below; the
  full arbitrary-precision contract (an approved wrapper with a pinned codec, or a canonical byte
  encoding through linear memory) lives in each host profile spec. `I32` is unreachable from
  ScalaScript source and is reserved for a future explicit narrowing ABI — see
  [`numeric-width-reconciliation.md`](numeric-width-reconciliation.md) §2.

## 4. Backend conformance status

| Backend | Lane | `Int` | Status |
|---|---|---|---|
| v1 interpreter | `ssc-tools run --v1` | 64 | **conforming** — the INT conformance reference |
| v2 native | `bin/ssc run` | 64 | **conforming** |
| v2 JS | `ssc-tools run-js --v2` | 64 | **conforming** |
| v2 bytecode | `bin/ssc run --bytecode` | 64 | **conforming** |
| **v1 JVM codegen** | `ssc-tools run-jvm` | **32** | **NON-CONFORMING — expiring** |
| **v1 JS codegen** | `ssc-tools emit-js` + node | **32** | **NON-CONFORMING — expiring** |

**Measured 2026-07-17**, `println(2147483647 + 1)` (correct answer `2147483648`): the four
conforming lanes print `2147483648`; both v1 codegen lanes print `-2147483648`. On the project's own
`tests/conformance/deep-tail-recursion.ssc`, `run-jvm` prints `705082704` for `sumTco(100000, 0)`
where the answer is `5000050000` — exactly `5000050000 − 2^32`, **exit 0, no error**.

**The v1 codegen is NOT to be fixed.** It maps ssc `Int` onto Scala's 32-bit `Int` (JVM) and emits
`|0` coercions (JS). Making it 64-bit means `Int`→`Long` through every emitted Scala plus removing
`|0` and threading BigInt through the JS emitter — real work on a corpse: the v1/scalameta hybrid
tier is slated for deletion (`MILESTONES.md` stream 1 — retire ssc0/scalameta → one v2 chain).
**Expiry:** these two rows are deleted together with the v1 codegen. Until then the divergence is
carried as a *declared, visible* known-red (see §6), never as a silent reroute.

## 5. Open — not decided here

- **`Float` across the ABI.** Per §2 `Float` is 64-bit, but the descriptor rejects it
  (`UNSUPPORTED_NUMERIC_WIDTH`) rather than declaring `F64`. Fail-closed is correct as a default;
  whether to *widen* it to `F64` + evidence (the `Int`/`Long` treatment) is a language decision.
  Raised 2026-07-17.
- **`Byte`, `Short`, `Decimal`, `BigDecimal`** are likewise in `UnsupportedNumericTypes` and are
  out of scope of this table until they have a decided runtime width.

## 6. How this table is enforced

A table nobody checks decays into folklore, so §2 and §3 are **parsed out of this file** and
compared against the real consumers by
`v1/lang/core/src/test/scala/scalascript/artifact/NumericWidthTableAgreementTest.scala`:

| Consumer | How it is checked |
|---|---|
| ABI descriptor | `PreBodyApiDescriptorProducer` is *run* on a real `.ssc` source per row; its declared `AbiType` must equal the row's `ABI` + `Evidence`. `REJECTED` rows must produce `UNSUPPORTED_NUMERIC_WIDTH`. |
| The four host tables | §3 is compared against the width tables in the host profile specs (`javascript-typescript-`/`rust-`/`swift-bidirectional-control.md`, `wasm-wasi-control-runner.md`). A profile that drifts fails. |
| `Int` == `Long`, `Float` == `Double` | asserted through the real producer (identical ABI, distinct evidence). |
| Interpreter / v2 / v1 codegen (runtime) | `tests/conformance/int-width.ssc` runs on **every** eligible backend. The v1 codegen lanes are declared known-red there (§4), so the divergence is visible in the gate output rather than routed around. |

The test **parses this table** rather than restating it: a restated table is a second guess, which
is the very failure this file exists to end. If you change §2 or §3, the test tells you which
consumer now disagrees — that is the point.
