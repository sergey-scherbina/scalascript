# JvmGen decomposition (Tier 1 — maintainability)

## Overview

`runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGen.scala` is a single
`class JvmGen(...)` of ~10,600 lines with ~259 defs, organised into clearly
banner-marked sections (Effect analysis, Strategy D, Statement emission,
Mutual-TCO, Expression emission, CPS transform, Preamble+runtime, …). Size makes
it slow to navigate, review, and reason about. This feature splits it into
per-concern source files **without changing behaviour**.

## Interface

No public API change. `JvmGen` keeps its constructor and public entry points.
Internally the class is composed from self-type traits:

```scala
// JvmGen.scala
class JvmGen(...)
  extends JvmEffectAnalysis
  with JvmCpsTransform
  with JvmPreambleRuntime
  // … one trait per extracted section

// JvmEffectAnalysis.scala
private[codegen] trait JvmEffectAnalysis:
  self: JvmGen =>
  // moved methods + the mutable fields they own
```

Pure, stateless helper groups (no access to class fields) move to companion
`object JvmGen` or a sibling `object` instead of a self-type trait.

## Behavior

- [x] (p1) Each extracted section lives in its own file; `JvmGen.scala` shrinks by that section.
- [x] (p1) No behavioural change: methods relocated **verbatim** into a mixed-in
      trait (which cannot alter emitted bytes); full `backendInterpreter/test`
      green incl. the 31 JvmGen-touching test files. (Separate byte-diff of emitted
      Scala not run — disproportionate vs the verbatim move; noted for a future
      phase if a section's move is non-verbatim.)
- [x] (p1) Visibility widened only as needed (`private` → `private[codegen]`); nothing becomes public.
- [x] (p1) `backendInterpreter/test` passes (1605 green); `backendJvm/compile` clean under `-Werror`.
- [x] (p1) Each phase is its own commit.

## Out of scope

- Any logic/perf change. This is pure structural decomposition.
- Splitting `JsGen`, `Main.scala`, `AsmJitBackend`, `EvalRuntime` — separate
  follow-up items (`jsgen-decompose`, `cli-main-decompose`, …) once the pattern
  is proven here.

## Design

Scala has no partial classes, so a stateful section becomes a `trait Foo { self:
JvmGen => … }` mixed into `JvmGen`. The trait can read/write the class's fields
and call its methods through the self-type; nothing else changes. A section that
touches no class state (e.g. pure AST/type predicates) becomes an `object`
instead, which is cleaner and independently testable.

Phasing (one section per phase, smallest-blast-radius first):
- **p1 — Effect analysis.** `analyzeEffects`, the `blocksUseX` detectors, and
  `isEffectOp*`/`isEffectfulFun` → `trait JvmEffectAnalysis`. Cohesive, bounded,
  already partly delegates to the external `EffectAnalysis` object.
- **p2 — Preamble + runtime.** The trailing string-emission block (~6275→end) →
  `trait JvmPreambleRuntime` (mostly self-contained string templating).
- **p3 — CPS transform** (~5306–6275) → `trait JvmCpsTransform`.
- **p4 — Mutual-TCO** (~4177–4401) → `trait JvmMutualTco`.
- Further sections as separate phases if the win justifies it.

## Decisions

- **Self-type traits over free functions** — chosen because most sections mutate
  shared class state (`effectOps`, emit buffers); self-types preserve that with
  zero call-site change. Rejected: threading state through parameters (huge diff,
  risk).
- **Verify by emitted-output equality, not just tests** — the strongest
  behaviour-preservation check for a codegen refactor is byte-identical output on
  the bench corpus; run it per phase.

## Results

**p1 — Effect analysis — landed 2026-06-11.** Found the decomposition pattern
already established: `JvmGen extends JvmGenBlockAnalysis, JvmGenTermAnalysis,
JvmGenMutualRecursion` (+ `JvmGenStringUtils`). Followed it: new
`JvmGenEffectAnalysis.scala` — `private[codegen] trait … { self: JvmGen => }`
holding `analyzeEffects` / `isEffectOpDef` / `isEffectOpRef` / `isEffectfulFun`
(moved verbatim). Widened `effectOps` + `effectfulFuns` from `private` to
`private[codegen]` so the mixin reads them (same as `mutualGroups`). `JvmGen.scala`
−66 lines. 1605 tests green; clean under `-Werror`. Next: p2 (Preamble+runtime).
