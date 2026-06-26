# 00 — ssc 2.0 Overview & Architecture

> Status: **design**. This file is the durable record of the architecture decisions
> taken for the ssc 2.0 clean-room bootstrap. Implementation has not started.

## Goal

Rebuild ScalaScript from a minimal kernel and bootstrap the full language *on itself*
(dogfood / self-hosting). Clean room: the existing `ssc 1.0` tree (~368 KLOC Scala, 242
sbt modules, 96 std plugins) is **not modified and not reused**. ssc 2.0 lives in an
isolated `ssc2/` folder with its own future build.

## The governing principle

**Measure the kernel by the self-compiler, not by the language.**

The wrong question is "what is the smallest subset of ScalaScript powerful enough to
*run* every program?" — it drags effects, actors, UI, and the JIT into the kernel.

The right question is "what is needed to *host the ScalaScript compiler*?" The compiler
is an almost-pure function `bytes(source) → bytes(IR)`. It needs recursion, data
(records / ADTs), pattern matching, strings/bytes, dictionaries, and file I/O. It does
**not** need actors, async, effect handlers, UI, codegen-to-JVM/JS, or a JIT. All of
those are libraries/backends written *in ScalaScript* and compiled by the now
self-hosting compiler. They never enter the kernel.

This is the lever that turns a 68-KLOC interpreter into a kernel of a few thousand lines.

## Two layers: untyped inner / typed outer

```
        ┌─────────────────── OUTER  (written in ScalaScript, typed) ────────────────────┐
.ssc →  │  lexer/parser (Markdown+Scala) → typed AST → TYPER → erase types → lowering    │
        └──────────────────────────────────────────────────────────────────┬────────────┘
                                                                             ↓ untyped Core IR
        ┌─────────────────── INNER  (written in Scala, untyped) ─────────────────────────┐
        │  loader(serialized IR) → evaluator(Core IR) → primitives (FFI)   ← trusted base │
        └─────────────────────────────────────────────────────────────────────────────────┘
```

Consequences of the split (all decided):

1. **The type checker is a library, not a kernel feature.** Type classes, `given`/`using`,
   HKT, subtyping, overloading — all live in the outer layer, written in ScalaScript. The
   kernel never sees them. This is the single biggest size lever.
2. **Kernel and type system evolve independently.** Core IR can be frozen without waiting
   for the type system to settle. A type-system change physically cannot break the kernel;
   kernel conformance is just the operational semantics of untyped Core IR.
3. **The type checker itself runs as untyped code on the kernel.** The typed outer layer
   *is a program* executing on the untyped inner kernel. Types are bootstrapped as untyped
   code.
4. **Gradual / optional typing falls out for free**, since erasure is the bridge.

## Decisions taken (this design session)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Untyped inner Core IR; typed outer language.** | Minimal kernel; types as an outer library. |
| D2 | **Pure clean room — do not reuse `ssc 1.0`.** | Full control of semantics from zero; no inherited complexity. |
| D3 | **Kernel reads *only* serialized Core IR** (no concrete-syntax parser in the kernel). | Kernel stays maximally dumb and stable; pretty syntax is an outer library. |
| D4 | **Permanent minimal Scala *seed*: `ssc₀` → serialized Core IR.** Kept forever, not thrown away. | Reproducible-from-source forever (no binary bootstrap blob → no "Trusting Trust" problem); a permanent independent oracle for differential testing; ordinary maintenance instead of bootstrap archaeology. |
| D5 | **`ssc₀` = thinnest practical surface over Core IR** (λ, `let`/`letrec`, ADTs, `match`, literals, primitive calls, named recursion, simple modules). Untyped. No type-system features, no Markdown. | Keeps the seed small (hundreds of lines, not thousands) while remaining comfortable enough to author the real compiler in. `ssc₀ ≈ Core IR + names + match sugar`. |
| D6 | **Bottom layer (forever): `sscc` is written in `ssc₀`, compiled by the seed** (single stage). A self-typed dogfood compiler (the outer compiler rewritten in full typed ScalaScript, type-checking itself via a 2-stage build) is an **optional later layer** — deferred, does not change the bottom. | Simplest, fully reproducible bottom; self-typed dogfood is a "nice to have", not a bootstrap requirement. |
| D7 | **Kernel is strict CBV with guaranteed proper tail calls (TCO).** Laziness is a thunk library (`Lam 0` + memo `cell`), not a kernel feature. Kernel strategy is **separable** from the surface language's strict-vs-lazy default. | CBV is forced by direct effects in `Prim` (lazy + impure ⇒ unpredictable effect order). TCO as a semantic guarantee lets the outer compiler lower loops to tail-recursive `LetRec` — no loop node. A CBV kernel still hosts a lazy surface via lowering, so the choice constrains nothing upward. |
| D8 | **`BigInt` and `Float` (IEEE-754 double) are primitive kernel values**, alongside 64-bit wrapping `Int`. | They satisfy the "primitive only if unexpressible" rule: arbitrary-precision arithmetic and exact IEEE-754 cannot be faithfully/efficiently emulated in-language. No implicit coercion in the kernel — conversions are explicit primitives. |
| D9 | **One binary `v2/ssc` = front + compiler + runtime, fused.** It reads code, *generates a program*, and runs it — a **runtime compiler**. The K1 VM (`ir → run`) and the K-seed front (`ssc0 → ir`) live in the same binary; `ir` is also a standalone artifact, so `ssc` runs source *or* bytecode (the "both / dual" resolution of the front-built-in question). | A single self-sufficient kernel program is the minimal, practical foundation; the duality (`ssc run` vs `ssc run-ir`) needs no second tool. |
| D10 | **Pipeline `ssc0 → ir → ssc(VM) → cpu`; `ir` (Core IR) is a first-class bytecode.** `ssc compile` emits canonical `ir`; `ssc run-ir` runs it; `ssc run` does both. | The intermediate is a real, inspectable, canonical artifact — good for the fixpoint/diff (byte compare) and for the tower (each layer emits `ir`). |
| D11 | **The VM compiles `ir` to closures (compile-to-closures), not a tree-walker.** Each node is compiled **once** into a closure that does exactly its work — no run-time dispatch on the node (the **JIT philosophy**: once we know what to do, do exactly that). TCO preserved via a trampoline + `Step`/`Call`. | A genuine runtime *compiler* (generates a program), portable and minimal, faster than tree-walking; bytecode JIT is a later backend, not in the kernel. |

## The permanent trusted base

Minimal, hand-written, and intended to stay small forever:

- **Core IR** — node set + big-step operational semantics. Frozen. (`specs/10-core-ir.md`)
- **Evaluator** (Scala) — Core IR → values. Reference semantics; correctness over speed.
- **`ssc₀`** — thin surface over Core IR. Frozen-ish. (`specs/15-ssc0.md`, planned)
- **Seed** (Scala) — `ssc₀` → serialized Core IR. Parse + resolve + lower; **no type
  checking**. (`specs/20-bootstrap.md`, planned)
- **Primitives** — the FFI boundary the kernel provides. Frozen-ish.
  (`specs/10-core-ir.md` §Primitives)

Everything else is `.ssc` / `ssc₀` source compiled through the seed or `sscc`:
`sscc` (lexer, parser, typer, erasure, lowering), the standard library, effects, actors,
the JVM/JS/WASM backends (themselves `.ssc` programs `CoreIR → target`), plugins, UI.

## Bootstrap & the fixpoint test

Because the seed is permanent, the system is always rebuildable from source:

```
seed (Scala)        : ssc₀ source            → Core IR          (frozen contract)
sscc.ssc            : written in ssc₀         (the real compiler: full surface + typer)
build(sscc)         : seed(sscc.ssc)          → sscc.coreir      (run on the evaluator)
self-check          : run sscc.coreir on sscc.ssc → sscc.coreir'
fixpoint            : sscc.coreir == sscc.coreir'  ⇒ self-hosting verified
```

The fixpoint is **not** a one-time gate that lets us delete anything (we keep the seed) —
it is a **permanent CI invariant**: the seed's lowering of `sscc` and `sscc`'s own
lowering of itself must agree. Two independent paths into Core IR keep each other honest.

## Milestones

See [`ROADMAP.md`](../ROADMAP.md). In short: **K0** freeze Core IR → **K1** Scala
evaluator → **K-seed** `ssc₀` + seed → **K2** write `sscc` in `ssc₀`, reach fixpoint →
**K3** regrow stdlib / typer / backends as ScalaScript.

## Planned spec set

```
00-overview            (this file)
10-core-ir             nodes + big-step semantics + primitive table     ← keystone
11-primitives          (split out of 10 once it grows)
12-ir-format           canonical serialization (kernel-owned)
15-ssc0                ssc₀ grammar + ssc₀→Core IR lowering (= seed contract)
20-bootstrap           seed, the fixpoint CI invariant, stage layering
30-erasure-lowering    typed outer AST → erase → untyped Core IR
40-typer-as-library    the type system as an outer ScalaScript pass
```

## Explicit non-goals (for the kernel)

The kernel deliberately does **not** contain: a type checker, the Markdown+Scala parser,
name/import resolution of the surface language, effect handlers / continuations, actors,
async, a JIT, any target backend (JVM/JS/WASM/native codegen), or the standard library.
Each is an outer concern. If something *can* be written in ScalaScript and compiled to
Core IR, it **must** be — only true primitives live in Scala.
