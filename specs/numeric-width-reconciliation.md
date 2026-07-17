# numeric-width-reconciliation — `Int` → `I64` (option A)

> Status: implementing. Decision by Sergiy, 2026-07-16. Bug:
> `coreir-abi-int-width-declared-i32-actually-i64`. Sprint item:
> `SPRINT.md` §`control-interoperability` → `numeric-width-reconciliation`.

## 1. The contradiction (measured, not read off source)

`PreBodyApiDescriptorProducer.scala:2066` maps source `Int` → `AbiPrimitive.I32`, `:2067`
maps `Long` → `I64`. But ssc `Int` is **64-bit**. Re-measured on a jar built from this
worktree (`scala-cli --power package v2/src --assembly`):

```text
2147483647 + 1          = 2147483648            <- did NOT wrap at 32 => Int is not I32
9223372036854775807 + 1 = -9223372036854775808  <- DID wrap at 64     => Int is I64
```

Corroborated by `v2/specs/10-core-ir.md` §2 ("`Int` is 64-bit two's-complement, wrapping
(matches ssc 1.0's `Int = Long`)").

⇒ The v3 descriptor — whose entire job is to tell JS/TS, Rust, Swift and WASM-WASI hosts
how to marshal our values — **declares 32 bits for a 64-bit value**. A host that believes
it silently truncates every value above 2^31−1 *at the ABI boundary*. It fails open, and
it is cross-language.

## 2. The decision — (A), and what it is not

**(A) `Int` → `I64`.** Make the descriptor truthful to the measured semantics.

- **Not (B)** (surface `Int` genuinely 32-bit): contradicts the frozen `10-core-ir.md` §2
  value domain, `Int = Long` v1 parity, and measured behaviour; would force a Core IR
  version bump across every backend and the whole corpus.
- **Not (C) as a whole** (I64 public + a fully-implemented narrowing ABI) — but **(A) is
  (C)'s first slice**. This spec is deliberately designed so (C) remains reachable: see §4
  (`AbiPrimitive.I32` stays in the schema, and the evidence node is exactly where a
  narrowing contract binds). (C)'s analysis in SPRINT is not deleted.

## 3. The whole difficulty: identity collapse (proven, not assumed)

Once `Int` and `Long` both map to `I64` they are **indistinguishable in the descriptor**.
`stableSymbolId`/`overloadId` are SHA-256 over the canonical JSON of the callable identity
(`DescriptorHashes.overloadId` → `ApiWire.writeSymbolIdentity` → `TypeWire.writeType`), and
the parameter's `AbiType` is the *only* thing distinguishing two same-name overloads.

**Demonstrated** (not asserted) by applying the bare one-line mapping flip with no other
change and running the probe `def widen(value: Int)` / `def widen(value: Long)`:

```text
before flip: overloadId dde22763… vs 922b20fa…      (distinct, but only because I32 != I64)
after  flip: DUPLICATE_SYMBOL_ID at $.symbols: duplicate value: ssc:symbol:v1:5ddf0353…
```

Note the failure mode is **closed, not open**: the factory rejects the whole module rather
than silently merging two overloads. Good — but it makes `Int`/`Long` overloads
*unexportable*, so the bare mapping flip is not a fix. Width evidence is mandatory, not
garnish.

## 4. The design

### 4.1 Model (`v2/interop/descriptor/…/Model.scala`)

```scala
enum NumericWidthEvidence:
  case DeclaredInt, DeclaredLong

enum AbiType:
  case Primitive(value: AbiPrimitive, declaredWidth: Option[NumericWidthEvidence] = None)
  …
```

`AbiPrimitive` keeps **all nine cases unchanged** — it is the frozen Slice A width
vocabulary and it stays the *wire* truth. `I32` becomes unreachable from ssc source under
(A) and is **reserved for (C)**'s explicit narrowing.

Two orthogonal facts, deliberately separated:

| Field | Meaning | Audience |
|---|---|---|
| `value: AbiPrimitive` | the **wire width** — how many bits actually cross the boundary | every host; this is the marshalling contract |
| `declaredWidth` | the **source spelling** the declaration used (`Int` vs `Long`) | identity; and the binding point for (C) |

A host marshals on `value` alone and is always correct. `declaredWidth` is evidence.

### 4.2 Mapping (`PreBodyApiDescriptorProducer`)

```text
Int  -> Primitive(I64, Some(DeclaredInt))
Long -> Primitive(I64, Some(DeclaredLong))
```

Both declare 64 bits — truthful. They remain distinct nodes — identity preserved.

### 4.3 Invariants (fail-closed, in `DescriptorValidator.validateType`)

1. `declaredWidth` present ⇒ `value ∈ {I32, I64}`, else `INVALID_NUMERIC_WIDTH_EVIDENCE`.
   (No `Primitive(String, Some(DeclaredInt))` nonsense.)
2. `value ∈ {I32, I64}` ⇒ `declaredWidth` present, else **`AMBIGUOUS_NUMERIC_WIDTH`** —
   this *is* "reject legacy ambiguous exports": a bare `I64` cannot be read as `Long` by
   default, because that guess is exactly the class of silent lie this item exists to kill.

### 4.4 Wire (`TypeWire`)

The `Primitive` node gains a `declaredWidth` field, encoded with the existing option-array
convention (`[]` / `[{"tag":"DeclaredInt"}]`). `WireSupport.exactObject` requires an exact
field set, so a **legacy** `{"tag":"Primitive","value":…}` fails loudly with
`SCHEMA_MISMATCH … missing=[declaredWidth]` instead of decoding to an ambiguous primitive.

### 4.5 Blast radius — accepted, deliberate

- **Every descriptor re-hashes.** `AbiPrimitive` feeds the frozen Slice A `apiHash`;
  `apiHash`/`stableSymbolId`/`overloadId` all move. Accepted by the decision.
- **6 live expectations flip** (`PreBodyApiDescriptorProducerTest.scala:100,130,132,136,267,1212`).
  They are updated because **the truth changed**, not to make a suite green.

## 5. Public I32/I64 semantics

- `I64` = 64-bit two's-complement, **wrapping** — identical to the Core IR value domain
  (`10-core-ir.md` §2). No narrowing happens at an (A) boundary, because none is declared:
  under (A) every ssc integer is I64 end to end. There is deliberately **no wrap to
  implement at the boundary** — that is (C)'s narrowing ABI, and inventing one here would
  be the same guess in the other direction.
- `I32`: declared-32-bit. Unreachable from ssc source under (A); reserved for (C).
- `declaredWidth` never changes marshalling. A host that ignores it stays correct.

## 6. Vectors — each must FAIL LOUDLY on truncation

The point of this item is a truthfulness fix, so **a vector that passes when it should fail
defeats the entire point**. Each per-host vector must therefore be *shown* to fail against
a deliberately truncating (32-bit) binding, not merely pass against the correct one.

Values pinned per host (JS/TS, Rust, Swift, WASM-WASI):

| Vector | Value | Why |
|---|---|---|
| `boundary` | `2147483647` (2^31−1) | largest value a 32-bit lie survives |
| `overflow32` | `2147483648` (2^31) | **the truncation witness** — 32-bit marshalling gives `-2147483648` |
| `max64` | `9223372036854775807` | full declared width |
| `min64` | `-9223372036854775808` | full negative width |
| `wrap64` | `max64 + 1` → `min64` | the declared wrapping semantics |

Each host profile additionally asserts the descriptor's declared type maps to a 64-bit-capable
host type (e.g. JS `BigInt`, not `number`; Rust `i64`; Swift `Int64`; WASM `i64`).

## 7. Gates

| Gate | Baseline (measured in this worktree, before any change) |
|---|---|
| `specs/v2.2-p6.5-fsub.sh --self` | **89 ok / 0 FAIL**, `stage1 == stage2` byte-identical at **79,667 B** |
| `core/testOnly …PreBodyApiDescriptorProducerTest` | green before change |
| descriptor suites (codec/identity/validation) | green before change |

The descriptor leaf lives outside `v2/src`, so the fixpoint bytes **must not move**. If they
do, the contract moved: stop, and coordinate with the P6.5 agent — do not "fix" the gate.
