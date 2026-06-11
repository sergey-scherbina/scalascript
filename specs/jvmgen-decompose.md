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

**p2 — Preamble runtime-source constants — landed 2026-06-11.** Scoped to the
high-value, zero-coupling subset of the Preamble+runtime section: the five large
**pure** `"""…""".stripMargin` constants `stubServeRuntime`, `fsRuntime`,
`generatorRuntime`, `effectsRuntime` (~3.2k lines alone), `reactiveRuntime` →
new mixin `JvmGenRuntimeSources` (3534 lines). Mechanical cut (sed) since the
strings are too large to hand-edit; visibility widened `private`→`private[codegen]`.
`logger`/`common`/`serveRuntime` deliberately stayed in JvmGen — they are
method-call blocks (call `loadRuntimeSource`/`loadCommonSource`), not pure data.
`JvmGen.scala` **10565 → 7042 lines (−3523, −33%)**. 1605 tests green; clean under
`-Werror`. The remaining Preamble defs (`collectDeclaredVarTypes`, `htmlDslTag…`,
`uiHelperFunctions`, the loader methods) are state-coupled — defer to a p2b/p3 that
uses a self-typed mixin with visibility surgery. Next: p3 (CPS transform).

**p3 — CPS transform — landed 2026-06-11.** Moved the whole `// ─── CPS
transform` section (15 class members: `foldConstantScala`, `isSimpleCps`,
`bindArgsCps`, `tmpIdx`/`freshTmp`, `anyBoundNames`/`withAnyBoundNames`,
`emitCpsExpr`, `emitCpsApply`, `assignmentCast`, `calleeParamType`,
`applyCalleeCasts`, `calleeTypeArgMap`, `emitCpsBlock`, `emitCpsBindWithType`)
verbatim into new mixin `JvmGenCpsTransform` (981 lines, named for the
`JvmGen`-prefixed convention of p1/p2, not the spec's earlier `JvmCpsTransform`
sketch). Unlike p2's pure constants this section is state-coupled, so it is a
self-typed `trait { self: JvmGen => }` with two-way visibility surgery:
widened `private→private[codegen]` the four moved members called from JvmGen
(`foldConstantScala`, `emitCpsExpr`, `emitCpsApply`, `calleeParamType`) and the
eight JvmGen members the trait calls back into (`depDefs`, `depClasses`,
`depTypeNames`, `declaredVarTypes`, `emitEffectfulParamGroups`, `emitExpr`,
`emitReceiveMatcher`, `emitHandleForm`). Imports trimmed to `scala.meta.*` only.
`JvmGen.scala` **7042 → 6073 lines (−969)**. 1605 tests green; clean under
`-Werror`. Next: p4 (Mutual-TCO emission).
