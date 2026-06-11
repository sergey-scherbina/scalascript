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

- [ ] Each extracted section lives in its own file; `JvmGen.scala` shrinks by that section.
- [ ] No behavioural change: byte-identical emitted Scala for the full bench corpus + test suite.
- [ ] Visibility widened only as needed (`private` → `private[codegen]`); nothing becomes public.
- [ ] `backendInterpreter/test` + the JVM-codegen suites pass after each phase.
- [ ] Each phase is its own commit (one section per commit) for reviewability.

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

<!-- per phase, at verify -->
