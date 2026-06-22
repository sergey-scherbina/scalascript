# emit-js whole-program effect analysis

## Overview

The JS backend (`ssc emit-js` / `emit-spa`) lowers an effectful function to CPS —
its body returns a Free value (`_perform` / `_bind`) so a handler or a `_bind`
chain can interpret it. The decision of *which* functions are effectful comes from
`EffectAnalysis.analyze`, run by `JsGen.analyzeEffects` once per module on that
module's own code blocks.

That per-module view is wrong for a multi-module program. A function is effectful
if it transitively performs an effect — including via a function defined in an
**imported** module. But `analyzeEffects` only sees the current module's trees, so:

- a function calling a transitively-imported effectful function is NOT marked
  effectful → not CPS-lowered → the imported function's Free value leaks into a
  direct (non-CPS) context and the program throws at runtime
  (`TypeError: ...reduce is not a function`, `Method not found: ...`, or
  `[object Object]` where a domain value was expected).

Two narrower facets were already fixed (`jsgen-emitjs-effect-handler`, origin/main
`6def53541`): `genImport` now analyzes the *directly* imported module and merges
its effect sets back, and effectful lambdas emit a CPS body. That covers a single
import level. This spec closes the remaining gap: effect, effectful reader, and
handler split across a **3+-level import chain**
(busi: `ledger.accountBalance` → `journal.query` → `Journal`), where the
per-module analysis order means the transitive effectfulness is never discovered.

The JIT path (`SSC_JIT_BACKEND=js`) handles effects whole-program already and is
green; this brings raw `emit-js` standalone bundles in line.

## Interface

Affects only the JS backend's effect analysis. No new flags, no source-level
changes for users. `emit-js` / `emit-spa` of a multi-module effectful program now
produces a bundle whose runtime behaviour matches the interpreter and the JIT.

## Behavior

- [ ] A module's effect analysis accounts for effects reachable through its import
  graph: a function calling a transitively-imported effectful function is marked
  effectful (and therefore CPS-lowered).
- [ ] The effect sets (`effectOps`, `effectfulFuns`, `multiShotEffects`) are
  consistent across the entry generator and every child generator, so each module
  is emitted against the same whole-program view.
- [ ] The reference busi case runs under raw `emit-js`:
  `ssc emit-js tests/v2/ledger.ssc | node` executes the `runJournal { … }` body,
  reads facts via the `Journal` handler, folds them through the `View`, and
  formats the resulting `Money` — output matching the interpreter.
- [ ] No regression: single-file programs are unaffected (their analysis already
  saw all their code); `CrossBackendPropertyTest`, the conformance suite, and busi
  `make v2-test` / `make v2-test-js` stay green.

## Design

`EffectAnalysis.analyze(trees, builtins)` already computes a fixpoint over a flat
tree list: it collects effect-op declarations + function bodies, then iterates
marking a function effectful when it calls a known effect op or known effectful
function. Given the **combined** trees of the whole import graph it derives the
correct transitive result in one pass (it sees `Journal.read` as an op, `query`
calling it, `accountBalance` calling `query`).

So the fix is two parts:

1. **Whole-program tree collection.** `analyzeEffects` collects trees not just from
   the entry module but recursively from every imported module — resolving import
   paths with the same logic `genImport` uses
   (`ImportResolver.resolve` + the std/project-tree fallback), parsing each once,
   guarded by a visited-set for diamonds/cycles. `EffectAnalysis.analyze` then runs
   on the union.

2. **Shared effect sets.** `effectOps` / `effectfulFuns` / `multiShotEffects` become
   constructor parameters shared across the entry generator and all child
   generators (threaded exactly like `topLevelConsts` / `declaredBindings`). The
   entry generator's whole-program `analyzeEffects` (run before any emission)
   populates them; child generators inherit the complete view instead of
   re-analyzing their own module in isolation. The per-`genImport`
   `analyzeEffects` + merge (the single-import fix) is removed as redundant.

## Decisions

- **Analyze the union of all trees, not per-module-with-seeds** — chosen because
  `EffectAnalysis.analyze` already fixpoints over a flat tree list, so feeding it
  the whole graph is the least code and is order-independent (no dependency-sort
  needed). Rejected: threading per-module seeds + re-running, which needs a
  topological order and a seed parameter the analyzer doesn't have.
- **Share the effect sets across generators** — chosen so the analysis runs once
  and every module emits against the same view (mirrors `topLevelConsts`).
  Rejected: per-child whole-program re-analysis (re-parses the graph per import).
- **Keep `_run` placement as-is** — the single-import fix already routes
  handler-body lambdas through CPS; with whole-program effectfulness the remaining
  direct-context `_run` wraps are correct (true top-level self-handling calls).

## Out of scope

- Caching parsed modules between the analysis pre-pass and emission (a perf
  follow-up; correctness first).
- The interpreter / JVM / Rust / WASM backends (their effect handling is separate
  and already whole-program).
- Any source-level effect-row annotation surface.

## Verification

- `tests/conformance/effect-transitive-handler.ssc` (+ `lib/`) — effect, reader,
  and handler split across two imported modules (3 levels); INT == JS == JVM.
- `ssc emit-js tests/v2/ledger.ssc | node` (busi) runs end-to-end.
- `CrossBackendPropertyTest`, `MoneyCrossBackendTest`, conformance suite, busi
  `make v2-test` + `make v2-test-js` green.
