# ssc 2.0 — clean-room, self-hosting ScalaScript

`ssc2/` is a **clean-room redesign** of ScalaScript, built from a minimal kernel
upward and bootstrapped on itself (dogfood). It is **isolated**: its own (future)
build, zero dependency on the existing `ssc 1.0` tree under the repo root. The old
implementation is never touched and never reused (pure clean-room — see
[`specs/00-overview.md`](specs/00-overview.md)).

## The one idea

Scope the permanent, hand-written kernel to **what is needed to host the
ScalaScript self-compiler — not to run the whole language.** Everything else
(the type system, the Markdown+Scala parser, effects, actors, UI, the JVM/JS/WASM
backends, the standard library) is written *in ScalaScript* and compiled down to a
tiny untyped **Core IR**. That keeps the trusted base small and permanent, and makes
the rest dogfood.

## Two layers

- **Inner (untyped):** Core IR + a reference evaluator, written in Scala. The trusted
  computing base. Knows nothing about types.
- **Outer (typed):** the full typed ScalaScript a user sees. The **type checker is a
  pass written in ScalaScript** that runs over user programs, then *erases* types and
  lowers to untyped Core IR. Types are an outer library, not a kernel feature.

## The permanent trusted base (minimal, forever)

| Component | Language | Spec |
|---|---|---|
| **Core IR** — nodes + operational semantics | (data) | [`specs/10-core-ir.md`](specs/10-core-ir.md) |
| **Evaluator** — Core IR → values | Scala | (K1) |
| **Primitives** — the FFI boundary | Scala | [`specs/10-core-ir.md`](specs/10-core-ir.md) §Primitives |
| **`ssc₀`** — thin surface skin over Core IR | (grammar) | `specs/15-ssc0.md` (planned) |
| **Seed** — `ssc₀` → serialized Core IR | Scala | `specs/20-bootstrap.md` (planned) |

The **seed is kept permanently** (not thrown away after bootstrap): it makes the whole
system reproducible from source with no binary bootstrap blob, and stays a second
independent path into Core IR for differential testing. See
[`specs/00-overview.md`](specs/00-overview.md).

## Status

Design / spec phase. No implementation yet. Start point: **`specs/10-core-ir.md`**
(the keystone — everything else derives from it). Roadmap: [`ROADMAP.md`](ROADMAP.md).
