# JsGen decomposition (Tier 1 — maintainability)

## Overview

`runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala` is a single
`class JsGen(...)` of ~5,800 lines, the JS counterpart of `JvmGen`. The
self-typed-mixin decomposition pattern is already established here too — JsGen
`extends JsGenAnalysisQueries` and pulls pure helpers from `JsGenStringUtils`
(jsgen-split-p1). This feature continues that split for the large cohesive
codegen sections, **without changing behaviour**, following the exact pattern
proven by `jvmgen-decompose` (p1–p4 + p2b: JvmGen 10565 → 5019, −53%).

## Interface

No public API change. `JsGen` keeps its constructor and public entry points.
Each extracted section becomes a `private[codegen] trait JsGenXxx { self: JsGen
=> }` mixed into `JsGen`, exactly as `JsGenAnalysisQueries`.

## Behavior

- [x] Each extracted section lives in its own file; `JsGen.scala` shrinks by it.
- [x] No behavioural change: methods relocated **verbatim** into a mixed-in
      trait; full `backendInterpreter/test` green (1605).
- [x] Visibility widened only as needed (`private` → `private[codegen]`).
- [x] `backendJs/compile` clean under `-Werror`.
- [x] Each phase is its own commit.

## Out of scope

- Any logic/perf change. Pure structural decomposition.
- The core expression/statement dispatch (`genExpr` ~900 lines at the heart of
  the file, `genApply`, `genStat`): central, very high fan-in/fan-out. Moving it
  would require widening dozens of members for little maintainability gain over
  high risk. Deliberately left in `JsGen.scala`. Re-evaluate only on a forcing
  function.

## Design

Same as `specs/jvmgen-decompose.md`: Scala has no partial classes, so a stateful
section becomes a `trait Foo { self: JsGen => … }` mixed into `JsGen`. The trait
reads/writes the class's fields and calls its methods through the self-type;
compile-driven `-Werror` visibility surgery widens each cross-boundary `private`
member to `private[codegen]`.

Phasing (largest cohesive non-core section first):
- **p1 — CPS codegen for effectful contexts** (~4758–5626) → `trait
  JsGenCpsCodegen`. The CPS expr/apply emitters plus the pattern-match and
  for-comprehension codegen grouped under that banner. Mirrors jvmgen p3.

## Results

(filled in per phase)
