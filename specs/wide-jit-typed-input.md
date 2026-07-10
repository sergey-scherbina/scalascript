# Wide JIT — typed input to the code generator

**Status:** spec / design (no code yet). Author: lucky-perch, 2026-07-10, at Sergiy's
direction ("широкий джит значит что он работает для всех случаев").

## Goal

Make the register-VM JIT (`VmCompiler`) **wide** — compile (nearly) every function
instead of bailing to tree-walk on most shapes. Today only a small Int/Double/Ref
subset compiles; the dominant bail is "can't prove the type here". A wide JIT gives
consistent steady-state performance (no bimodal JIT-vs-tree-walk variance — cf. the
`recursionFib` high-variance we saw) and removes the tree-walk fallback for typed code.

This is a **program**, not one slice. This spec covers the *foundation*: giving the JIT
**static types** so it stops re-guessing them. Closures/HOF and effects (which need a
closure/heap model and ANF respectively) are separate follow-ons — see Non-goals.

## Why the JIT is narrow today (root cause, traced 2026-07-10)

The frontend's inferred types **never reach the code generator**. Evidence:

1. **The IR is untyped.** `NormalizedModule` / `IrExpr` (`v1/lang/ir/.../Ir.scala:188,346`)
   carry no `.tpe`. The `Lambda` node comment says it verbatim: *"Parameter names only
   (no types) since the IR doesn't yet carry a type system at this layer."*
2. **`Normalize` consumes the untyped parsed AST** (`ast.Module`, not `TypedModule`) and
   **erases** the scalameta trees — backends re-parse from the `source` string
   (`transform/Normalize.scala:35`, header comment 15-19).
3. **`TypedModule` keeps types separate** — a table of top-level `DefSummary(name, kind,
   tpe: SType, paramTypes: List[SType])` + `errors` (`typer/Typer.scala:1755,1768`). It
   does **not** annotate the tree; no per-node types exist.
4. **`run` never typechecks.** `RunCmd` → `compileViaBackend` does parse-error +
   `CapabilityCheck` only, then `Normalize` + `backend.compile` (`cli/Main.scala:257-288`).
   `Typer.typeCheck`/`typeCheckIncrementalModule` run **only** in `check`/`emit`/watch.
5. **The interpreter re-parses `source` → scalameta**, then `VmCompiler.compile(fn:
   Value.FunV)` works on the re-parsed `Term` + *string* `paramTypes`
   (`Value.scala:32-41`), re-inferring `VmType` with `typeOf` **defaulting to `TInt`**
   (`VmCompiler.scala:34,176`). Param types come from `decltpe` (present only if the user
   wrote an annotation, `VmCompiler.scala:778-784`) or from runtime arg values
   (`JitRuntime.withParamHints`, `.scala:94-112`).

**Structural consequence:** adding a `tpe` field to `IrExpr` alone would NOT help — the
interpreter does not execute `IrExpr`; it runs the re-parsed scalameta `Term`s. Any real
plumbing must reach the `Term`/`FunV` layer, **or** stop discarding the typed tree.

## Design — where types enter the JIT

The highest-leverage, lowest-blast-radius seam (from the trace, attachment point #1):
**`Value.FunV`'s typed signature** — its `paramTypes` (today `List[String]`) plus a NEW
return type. `VmCompiler` already keys its whole type machinery off `paramTypes`
(`buildInstructions` 900-907; `fnIsDouble` 203-207). Enrich that seam with *real* types
and the JIT stops defaulting to `TInt`.

Three strategies, in increasing blast radius:

- **(A) Local typecheck at JIT-compile time.** When `VmCompiler` compiles a hot `FunV`,
  run a lightweight local type inference (or the real `Typer`) over its `Term` + declared
  signatures of its callees to produce param/return/local types, seeding `regType` from
  facts instead of `TInt`. LOCAL, per-function, additive; no pipeline re-architecture.
  Cost: type inference on the JIT-compile path (amortized over many calls — acceptable,
  JIT-compile already only fires after warmup).
- **(B) Thread the typer's `DefSummary` signatures to `FunV`.** For top-level defs, the
  typer already computes `paramTypes: List[SType]` + `tpe: SType`. Make the run/interp
  path compute these (it doesn't typecheck today) and attach them to each `FunV`. Seeds
  param + return types for top-level functions (the common case) without full per-node
  inference. Locals still need (A)-style propagation, but from correct param seeds.
- **(C) Thread a fully-typed tree end-to-end**, avoiding the `source` round-trip. Largest
  change (touches IR, Normalize, InterpreterBackend); deferred.

**Recommendation: (B) then (A).** (B) gives correct param+return types for top-level defs
cheaply — that alone unblocks the "unknown param type → TInt default → bail" class and the
"ret: unknown type" class for typed functions. (A) extends to locals/intermediates and
closures-as-values. (C) only if profiling shows the round-trip itself is the wall.

## Phased plan (SPRINT)

- **wj-0 baseline** — capture the CURRENT JIT miss histogram (foreground, NOT `scripts/sbtc`
  which detaches: `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` run in the foreground, or
  drive the examples corpus through the `--v1` assembled binary with the stats hook). Record
  per-reason counts as the before-number. Confirm the June "199 call: no compilable target /
  N unknown-type" split is still current (p8/p10 may have shifted it).
- **wj-1 typed FunV signature** — add a return-type field to `FunV` and make `paramTypes`
  carry the typer's `SType` (rendered to the domain `fieldVmType` reads, or a small typed
  enum). Populate from `DefSummary` for top-level defs; for the `run`/`--v1` path, compute
  the signatures (run the typer on the module, or a bounded signature-only pass). Gate:
  miss count for unknown-param/unknown-ret reasons drops; conformance green; no regression
  in `patternMatch*`/`recursionFib` benches (when the machine is quiet — see wj-gate).
- **wj-2 local type propagation from seeds** — with correct param/return seeds, extend
  `regType` propagation so locals/intermediates that are provably typed no longer default
  to `TInt`. Removes the "type-unknown local → bail" residue. Gate: further miss-count drop.
- **wj-3 return-type-driven RET/CALLREF completeness** — with real return types, compile
  functions returning any statically-known type; align call sites. (Some of this landed as
  p8 RETREF/CALLREF — verify + close gaps.)
- **wj-gate** — on a QUIET machine, A/B `scripts/bench interp patternMatch*|recursionFib`
  and `scripts/bench cross` to confirm wider coverage doesn't regress the hot paths and
  ideally removes the `recursionFib` bimodal variance. (Deferred: current load 30 makes
  timing meaningless — this is the "(1)" work.)

## Non-goals (this spec) — the rest of "all cases"

Static types are necessary but NOT sufficient for literally-all-cases:

- **Closures / HOF (the dominant `call: no compilable target`, ~199).** Compiling a call
  to a closure needs a closure-passing model (compiled target + captured env), not just a
  type. Separate program; types help (know the closure's signature) but don't complete it.
- **Effects** (`perform`/`handle`) need the ANF/handler machinery, not codegen types.
- **Term.Function as a value** (p9) — anonymous lambdas as heap values; needs a heap model.

Call these out so a later agent doesn't expect wj-1..3 to reach 100% JIT coverage; they
reach "all TYPED first-order code", which is the large majority and the right foundation.

## Open decision for the user

**Strategy (B)-then-(A) vs jumping to (C).** (B)+(A) is incremental and low-risk but keeps
the `source`→re-parse round-trip (a per-run cost, already paid today). (C) removes the
round-trip and gives a clean typed tree but is a pipeline re-architecture. Recommend
(B)+(A) unless the round-trip is proven to be a wall. Confirm before wj-1.
