# Changelog

Completed milestones, newest first. Each entry is a brief summary; git history has full implementation notes.

---

## 2026-06-04 — perf(interpreter): object-returning recursive ADT builder JIT

- **interp-opt-recursive-build-floor** — Phase 1B added `LongToObject` /
  `resultIsRef` plus a narrow Javac object-expression walker for pure recursive
  ADT builders. Default `scripts/bench interp recursiveEval`: `recursiveEval`
  **0.067 +/- 0.004 ms/op**, `recursiveEvalMixed` **0.068 +/- 0.001 ms/op**;
  208 targeted interpreter/JIT tests passed. ASM parity remains tracked as
  `interp-opt-recursive-build-floor-asm-parity`.

## 2026-06-04 — perf(interpreter): invariant recursive eval loop fold

- **interp-opt-recursive-eval** — Phase 1A folded invariant bytecode-JIT direct
  calls out of recursive ADT eval accumulation loops while preserving the old
  path for effects and dynamic calls. `recursiveEvalMixed`: **3.641 -> 1.924
  +/- 0.174 ms/op** with `scripts/bench interp recursiveEvalMixed`; compile,
  208 targeted interpreter/JIT tests, short benches, full mixed bench, and
  profile bench passed. Residual ~1.9 ms/op floor is now tracked separately as
  `interp-opt-recursive-build-floor`.

## 2026-06-04 — perf(interpreter): cold init allocation

- **interp-opt-init-builtins-cache** — `effectPure` cold interpreter floor is
  down from 0.010 to **0.005 ms/op**. Profile allocation is down from 32,208 to
  **8,728 B/op** by lazily initializing unused interpreter/actor/cluster state
  and using direct `System.getenv` reads in actor-cluster init. Shared pure
  builtins cache was measured and deferred. Compile, 238 targeted tests, and
  final bench/profile pass.

## 2026-06-04 — feat(types): ssc check-types GraphQL section — P4d-γ

- **type-evidence-graphql-p4d-gamma** — `ssc check-types` now prints a third
  section "GraphQL evidence:" with object/interface/input type and field counts.
  Exit code now gates on both route and GraphQL evidence being fully declared.
  Success message: "All routes and GraphQL types have declared types."
  8 `CheckTypesCliTest` pass. Completes P4d (α+β+γ all landed 2026-06-04).

## 2026-06-04 — feat(types): GraphQL evidence inventory helper — P4d-β

- **type-evidence-graphql-p4d-beta** — `GraphQLEvidenceCounts` +
  `GraphQLEvidenceInventory.count(module: ir.NormalizedModule)` in `TypeEvidence.scala`.
  Walks all sections/subsections; counts Object/Interface/Input types and their fields
  from `graphql` `EmbeddedBlock` evidence. Legacy blocks without evidence count as 1
  unknown type. `allDeclared` predicate for CI gating. 7 new tests.

## 2026-06-04 — feat(types): GraphQL SDL type evidence — P4d-α

- **type-evidence-graphql-p4d-alpha** — GraphQL SDL type evidence in IR.
  New `GraphQL{Field,Type,Block}EvidenceWire` types in `Ir.scala`; additive
  `evidence: Option[GraphQLBlockEvidenceWire]` field on `Content.EmbeddedBlock`.
  `GraphQLSourceLanguage.compileBlock` now retains the `TypeDefinitionRegistry`
  instead of discarding it and builds evidence: field types classified as
  `Declared` (SDL built-in scalars + same-block types + `ScopeContext.resolve`)
  or `Unknown` (unresolved). Invalid SDL → `evidence = None`. 8 new tests in
  `GraphQLEvidenceTest`. Backward-compatible: legacy `.scir` without `evidence`
  field still reads. P4d-β (inventory) and P4d-γ (check-types) pending.

## 2026-06-04 — feat(types): ssc check-types command (P4c)

- **type-evidence-check-cmd-p4c** — New `ssc check-types <file.ssc>` command.
  Parses and normalizes the module without running the interpreter, prints a
  two-section evidence inventory table (route evidence: endpoints/handlers
  declared/unknown; symbol evidence: Any-typed exports by evidence kind), and
  exits 0 if all routes have Declared evidence or 1 otherwise. CI-friendly gate
  for route type coverage. Uses `RouteEvidenceInventory` (P4a) and
  `AnyEvidenceInventory` (P1) internally.
  **Tests:** 6 `CheckTypesCliTest`, `CommandRegistryTest` updated.

---

## 2026-06-04 — feat(types): OpenAPI evidence diagnostics (P4b)

- **type-evidence-openapi-p4b** — Added `openApiEvidenceDiagnostics(module: Module)`
  to `EmitCommands`: normalizes the parsed AST module and returns a warning
  string for each API endpoint or remote handler whose request or response
  evidence is not `Declared`. Added `--require-declared` flag to `ssc emit-openapi`:
  runs the diagnostic check after generating the spec; if warnings exist, prints
  them to stderr and exits 1. Without the flag the check is skipped (no behavior change).
  **Tests:** 7 `EmitOpenapiCliTest` (4 existing + 3 new).

---

## 2026-06-04 — feat(types): route evidence inventory (P4a)

- **type-evidence-schema-p4a** — Added `RouteEvidenceCounts` and
  `RouteEvidenceInventory.count(ir.Manifest)` to `TypeEvidence.scala`.
  Reads `ApiEndpointTypeEvidenceWire` from each endpoint and handler; both
  request and response must be `Declared` for the route to count as declared;
  missing `typeEvidence` (legacy artifacts) counts as Unknown. `allDeclared`
  convenience predicate for CI gating.
  **Tests:** 7 `RouteEvidenceInventoryTest`.

---

## 2026-06-04 — feat(types): route metadata type evidence

- **type-evidence-routes-p3** — Added optional `ApiEndpointTypeEvidenceWire`
  on normalized IR `ApiEndpointDecl` and `RemoteHandlerDecl`. `Normalize`
  derives declared/unknown evidence from legacy request/response strings;
  generators keep reading the existing strings for this slice. `.scir`
  round-trip and legacy no-field reads are covered.
  **Tests:** 4 `RouteTypeEvidenceTest`, 17 `ArtifactIOTest`,
  8 `ApiClientsFrontmatterTest`, 9 `ClusterFrontmatterTest`,
  `core / Test / compile`.

---

## 2026-06-04 — feat(uuid): uuid-p4 + uuid-p5 — raw tier, unsafeFromString, withFixedUuid, effect wiring

- **uuid-p4/p5** — Completed the UUID stdlib across all three backends (interpreter, JVM, JS).
  New surface: `Uuid.unsafeFromString` (named coercion, throws on bad input), `.version / .isNil / .isMax / .variant` extension methods, `rawV4/rawV7` (no effect annotation, library-author escape hatch), `runSideEffect` (identity handler), `withFixedUuid(fixed)(body)` (thread-local override for deterministic tests).
  Effect wiring: `Uuid.v4/v7` registered in `containsEffectPrimitive` and `DepEffectfulnessFixpoint`; `rawV4/rawV7` intentionally excluded.
  `withFixedUuid` implemented as an AST-pattern-matched handler in `EvalRuntime` (same approach as `runRandomSeeded`) so the body term is evaluated after the thread-local is set.
  **Tests:** 15 `UuidPluginTest` cases (all pass), 4 `ContainsEffectPrimitiveTest`, 4 `DepEffectfulnessFixpointTest`.

---

## 2026-06-04 — fix(js): effect-stream JS while-loop — side-channel Stream.emit

- **js-effect-stream-while** — `effect-stream` JS now produces valid output.
  Root cause: `Stream.emit(i)` calls inside while loops returned Free monad
  `_Perform` nodes that the while loop discarded — they never reached `_handle`.
  Fix: `Stream.emit` now pushes to a module-level `_streamBuf` side-channel
  buffer when inside a `runStream` call (returning `undefined`), and the
  `runStream` body is emitted with `genExpr` (plain JS, not CPS-transformed)
  so while/var loops work correctly. `_mkStreamSource` wraps the collected
  buffer with synchronous `runToList()` / `toList()` methods.
  **Result:** `effect-stream` JS **0.327 ms/iter** (was n/a in bench.sh).

---

## 2026-06-04 — fix(jvm): effect-pure + effect-stream JVM backend — T!Eff, ThreadLocal stream

- **jvm-effect-types** — JVM backend now compiles and runs `T ! Eff` effect-typed
  functions and `runStream` with while/var loops. Four-part fix:
  1. `JvmGenTermAnalysis` — add `stdEffectRunners` to `termUsesEffects` so
     `runLogger`/`runStream` calls trigger `blockNeedsRewrite` → `emitStats`.
  2. `JvmGen emitStat` — strip `T ! Eff` return-type annotation (emit as `: Any`);
     add `.runtimeChecked` to `val (a,b) = runner(...)` tuple-destructures.
  3. JVM preamble — replace CPS `_handle`-based `runStream` with a `ThreadLocal`
     `ArrayBuffer` approach: `Stream.emit()` pushes to the buffer directly so
     `while`/`var` loops work without a CPS trampoline. `_Source.runToList()`
     returns `List[Any]` so `.length` resolves.
  4. `emitExpr`/`emitCpsExpr` — switch runner body from `emitCpsExpr`→`emitExpr`;
     intercept `.runToList()` with `asInstanceOf[_Source]` cast.
  Also: `EffectAnalysis.verify` — suppress false "declares no effect row" warning
  for sub-effecting (pure body in effect-typed def is valid). `Typer` — accept
  `() => Any !Eff` block args for runner type-checking.
  **Results:** `effect-pure` JVM **0.005 ms/op**; `effect-stream` JVM **0.067 ms/op**.

---

## 2026-06-04 — feat(ui): seedSignal editable draft primitive

- **ui-seed-signal** — Added `seedSignal(name, source: Signal[String])` for
  forms that need a writable text draft seeded from another signal.
  The draft mirrors the source while pristine; `inputChange` / `setSignal`
  marks it dirty, so later fetch refreshes do not overwrite user edits.
  Includes interpreter/JVM/JS shims, browser runtime pristine-source wiring,
  React/Vue/Solid/Custom/SwiftUI/Swing/JavaFX emitter lowering, docs, and
  `examples/seed-signal.ssc`.

---

## 2026-06-04 — perf(js): js-codegen-opt-p3 — emit-js field/arith fix + _forEach bypass

- **js-codegen-opt-p3** — Fixed `genModuleSegmented` (used by `emit-js`/`run-js`/bench)
  missing `caseClassFieldsByType` + `caseClassFieldTypeMap` initialization; added `_forEach`
  array-bypass helper. `pattern-match-heavy` JS: 35.8 ms → 5.0 ms (7.2×). 1279 tests.

---

## 2026-06-04 — feat: ssc lint-jit --include-while coverage

- **jit-lint-while-coverage** — `ssc lint-jit --include-while` now reports JIT
  coverage for top-level while loops alongside def coverage.  Source of truth is
  `interp.whileJitCache` (no interpreter changes needed).  New API:
  `JitLintWhileReport`, `JitLintWhileCompareReport`, `JitLint.lintWhileLoops`,
  `JitLint.lintWhileLoopsCompare`, `JitBailReason.WhileCondShape / WhileBodyShape`.
  12 new tests; all 39 pass.

---

## 2026-06-04 — perf: ASM JIT while/map parity

- **asm-jit-parity-optimizations Phase 2** — `AsmJitBackend` now matches the
  current Javac while/match subset: ref globals/functions in `WhileJitEntry`,
  hoisted TLS refs/ref-fns in generated while methods, `ObjToObject` ref-arg
  chains, inline ref-match RHS helpers, qualified ADT constructor patterns,
  wildcard / named catch-all ADT arms, ListV/SetV fused foreach, and MapV
  foreach key/value fusion via `mapIsKeyMode` and the runtime-provided
  pre-extracted `Object[]`. Verified with `SscVmTest` plus
  `SscVmTest`/`InterpreterTest`/`JitLintTest` under `SSC_JIT_BACKEND=asm`
  (183/183).

---

## 2026-06-04 — perf: phase-c-bytecode-wider-match (wildcard/catch-all arms)

- **phase-c-bytecode-wider-match** — `Pat.Wildcard` and `Pat.Var` catch-all arms now compile
  in all JIT arm walkers (`walkArm` switch form, `walkArmAsIfBranch` if-chain form,
  `walkArmExpr` switch-expression form). `walkMatchBody` and `walkMatchExpr` skip the
  throw-default when a wildcard arm is present. `walkRefArm`/`walkRefMatchBody` already
  supported `Pat.Var`. 17 JitLintTest + 1251 full suite green.

---

## 2026-06-04 — perf: while-jit-map-foreach (11.4×)

- **while-jit-map-foreach** — Fuses `while i < N do m.foreach((k,v) => acc += v)` into a
  single generated Java method. Key insight: the bottleneck was not Tuple2 allocation (already
  eliminated via `valuesIterator()`) but the per-outer-iteration `Iterator` object creation
  (100K × ~5 ns ≈ 500 µs). Fix: pre-extract `MapV.entries().valuesIterator().toArray` once
  at call time, pass it as `refs[0]`; generated Java iterates over `Object[] _mvals` with a
  plain `for` loop — zero per-iteration allocation.
  - `WhileJitEntry.mapIsKeyMode: Boolean = false` — tells runtime whether to extract keys or values
  - `tryCompileWhileMapForeach` emits `Object[] _mvals = (Object[]) JitGlobals.getRefs()[0]`
    with a `for (int _mi = 0; _mi < _mlen; _mi++)` inner loop
  - `tryWhileJitMixed` pre-extracts the array based on `entry.mapIsKeyMode`
  - `mapForeach`: **2.142 ms → 0.187 ms (11.4×)**. 1248 tests green.

---

## 2026-06-04 — perf: ASM JIT function parity

- **asm-jit-parity-optimizations Phase 1** — `AsmJitBackend` now matches the
  current Javac function-backend subset for shared boolean-return bails,
  unary `+`/`-`, multi-statement expression blocks, guarded ADT matches,
  direct `ObjToObject` ref-returning matches, and long-returning
  sibling/mutual co-emit including ref-param ADT match functions. String-chain
  fallback now clears `typeName` before arm-label jumps. Verified with
  `SscVmTest`/`InterpreterTest`/`JitLintTest` under `SSC_JIT_BACKEND=asm`
  (174/174). Phase 2 while-backend parity remains open in `WORK_QUEUE.md`.

---

## 2026-06-04 — test: jit-match-recursive-descent verification

- **jit-match-recursive-descent** — Verified that `JavacJitBackend.walkArm`
  correctly marks arm-bound variables passed to recursive self-calls as
  ref-typed (`bindingIsRef`), and `walkLong`'s self-call case emits
  INVOKESTATIC for both the 1-param (`def eval(e: Expr)` → ObjToLong) and
  2-param (`def gEval(scale: Int, e: Expr)` → LongObjToLong) shapes.
  Added 4 JitLintTest cases: lint + direct-interface correctness for `eval`
  (`eval(build(3)) == 27` via ObjToLong) and `gEval`
  (`gEval(2, Add(Num(1),Mul(Num(2),Num(3)))) == 26` via LongObjToLong).
  Performance confirmed at 3.57 ms / 3.66 ms (8–12× vs JIT-off baseline);
  this is the achievable floor for INVOKESTATIC traversal of a 1021-node ADT
  tree at ~3.5 ns/node. 1243/1243 tests pass.

---

## 2026-06-04 — perf: js-codegen-opt-p2

- **js-codegen-opt-p2** — Loop-invariant constant-tuple hoisting in JS codegen.
  When `(1,2)++(3,4)` appears inside a while-loop body and all elements are literals,
  the compile-time-folded result is hoisted as `const _k0 = Object.freeze(Object.assign([1,2,3,4],{_isTuple:true}))`
  before the loop; the body becomes `last = _k0; i++` with zero heap allocations.
  Three while-loop codegen sites instrumented (genFunctionBody, genBlockStats, genBlockAsIife).
  tuple-monoid: 4.24 ms/iter → 0.025 ms/iter (−99%, 170×). 1236/1236 tests passed.
  Full analysis in `docs/specs/js-codegen-opt-p2.md`.

---

## 2026-06-03 — perf: phase-c bytecode mutual co-emit

- **phase-c-bytecode-mutual** — `JavacJitBackend` now co-emits JIT-compatible
  sibling defs as static methods in the same generated Java class, so
  long-returning sibling calls and mutual-recursion cycles no longer bail out
  of the bytecode backend. Covered by direct Javac bytecode tests for pure-int
  sibling calls, pure-int mutual recursion, and ref-param ADT match mutual
  recursion. `recursiveEval`/`recursiveEvalMixed` post-change bench stayed in
  noise versus baseline.

---

## 2026-06-03 — perf: phase-d-instancev flag flip

- **phase-d-instancev-array-repr-flag-flip** — StatRuntime case-class constructors
  pass Map.empty + populate `fieldsArr + fieldNames` in parallel; IMap.Map1/Map2/
  Map.from no longer allocated per hot InstanceV. `effectiveFields` method + overridden
  `equals`/`hashCode` unify StatRuntime vs deserialized InstanceV comparisons.
  Value.show, DerivesRuntime, DispatchRuntime, OpticsRuntime, PatternRuntime,
  SectionRuntime, ValueSerializer all updated. instanceVArrayEnabled flag removed.
  1233/1233 green. Bench: patternMatchSet 0.283 → 0.197 ms (~30%).

---

## 2026-06-03 — js-codegen-opt-p1

- **js-codegen-opt-p1** — Landed four targeted JS codegen fixes in
  `JsGen.scala`: non-recursive functions no longer get the self-TCO
  `while(true)` wrapper; tail/return-position `Term.Match` lowers to if-else
  statements instead of an IIFE; TCO multi-param reassignment uses temporary
  constants instead of array destructuring; and `++` infix calls with multiple
  RHS args preserve the full tuple. All 1233 conformance tests passed.
  Measured results and p2 follow-up notes are in `docs/specs/js-codegen-opt-p1.md`.

---

## 2026-06-03 — coord-status clean landed worktrees

- **coord-status-clean-worktrees** — `scripts/coord-status` now reports a
  `clean landed worktrees` section for linked worktrees that are clean, unlocked,
  not ahead of `origin/main`, and whose `HEAD` is already contained in
  `origin/main`; each entry includes an explicit cleanup command. `AGENTS.md`
  documents the signal as advisory so agents can prune stale landed worktrees
  without touching dirty or live work.

---

## 2026-06-03 — while-jit-mixed-foreach-set: extend fused while+foreach JIT to SetV receivers

- **while-jit-mixed-foreach-set** — `tryCompileWhileMixed` (JavacJitBackend) and
  `tryWhileJitMixed` (EvalRuntime) now accept `Value.SetV` receivers alongside
  `Value.ListV`. For SetV, emits `Set.iterator()/hasNext()/next()` inner loop;
  all other codegen (outer while, int-assign RHSes, accumulator writeback)
  unchanged. EvalRuntime receiver-resolution match adds `sv: Value.SetV` arm.
  Bench (5i wi=3 ms/op): **patternMatchSet: 0.797 → 0.283 ms (~2.8×)**.
  1233/1233 tests green.

## 2026-06-03 — while-jit-mixed-foreach: fuse outer while + inner foreach into single JVM method

- **while-jit-mixed-foreach** — The `{ xs.foreach(s => acc = acc + fn(s)); i = i+1 }` body
  pattern ran via `tryMixedLongWhile` (a Scala `while` loop) with a
  `PreResolvedForeach` virtual dispatch per outer iteration, preventing JVM
  devirtualization of the monomorphic `fn.apply(item)` call and leaving
  per-iter TLS round-trips in place. New `tryCompileWhileMixed` in `JitBackend`
  SPI generates a single Java class with a fused outer while + inner `for` loop
  over `list.items()`: `while (cond) { for item in list: acc += fn.apply(item); i++; }`.
  `WhileJitEntry` gains `refDoubleFns: Array[ObjToDouble]` for the Double-acc
  case; `JitGlobals` adds a 4-arg `withRefs` overload and `getRefDoubleFns()`.
  `tryWhileJitMixed` in `EvalRuntime` attempts the fused JIT first, then falls
  back to the existing `tryMixedLongWhile` + `PreResolvedForeach` path.
  Bench (2f, wi=3, mi=10, ms/op):
  **patternMatchHeavy: 0.936 → 0.397 ms (2.37×);
  patternMatchWide: 1.628 → 1.389 ms (1.17×);
  interp_patternMatch (RuntimeBench): 1167 → 676 µs (1.73×, now 1.21× above JVM floor)**.
  1233/1233 tests green.

## 2026-06-03 — jit-fieldsarr-no-null-check: remove dead fieldsArr null-check from JIT arm emission

- **jit-fieldsarr-no-null-check** — After `phase-d-instancev-array-repr-activation`,
  `StatRuntime` always populates `fieldsArr` at every InstanceV construction site, so the
  defensive `faVar != null ? faVar[i] : inst.fields().apply(name)` ternary emitted by all
  four JIT arm-emission sites (`walkArm` switch, `walkArmAsIfBranch`, `walkMatchBody`,
  `walkRefArm`) was dead code. The dead branch prevented the JVM from proving `faVar`
  non-null, blocking implicit null-check elimination on the hot array-read path. Replaced
  with a direct `faVar[i]`; removed the now-unused `val fname = fieldOrder(fi)` in each
  site (4 insertions, 18 deletions). Bench (2f, wi=3, mi=5, ms/op):
  **patternMatchHeavy: 1.128 → 0.861 ms (~24%)**. 1233/1233 tests green.

## 2026-06-03 — fast-map-foreach-preresolved: PreResolvedFast Map foreach variants

- **fast-map-foreach-preresolved** — `PreResolvedFastLongMapForeach` and
  `PreResolvedFastDoubleMapForeach` complete the fast-variant series for
  `tryPreResolveForeach`. Previously the MapV path re-ran ~5 guard checks
  (enabled, params.length, usingParams, `analyzeMapAccum` IdentityHashMap
  cache, `globals.getOrElse`) and 2 TLS probes (`accSlotTls` + `accNameTls`)
  on every outer iteration. New `ResolvedLong/DoubleMapAccum` structs carry
  `accName + useFirst`; `tryResolveLong/DoubleMapAccum` check these guards
  once at setup; `runLong/DoubleAccumForeachMapFast` use the pre-wired
  `cachedSlot` field directly, bypassing TLS on each inner call.
  Bench (2f, ms/op): **mapForeach: 2.238 → 2.023 ms (~10%)**.
  1233/1233 tests green.

## 2026-06-03 — jit-lint-recognisers-pure-predicates: JitLint precision + shared predicates

- **jit-lint-recognisers-pure-predicates** — `JitPredicates` package-private object
  factors out `isBoolReturning` so `JavacJitBackend.doCompile` and
  `JitLint.classifyBailReasons` share one implementation (can't silently diverge).
  Three new `JitBailReason` variants replace `UnknownShape` for common cases:
  - `BoolBody` — body is a comparison/logical expression (`<`, `>`, `&&`, …);
    the JIT emits `long`, a bool-typed result would be mis-wrapped as Int 0/1.
  - `ZeroParams` — zero parameters; JIT requires ≥ 1 typed param.
  - `TooManyParams(n)` — > 2 parameters; JIT supports 1- and 2-param only.
  `PatternGuard` description updated: guards on ADT (InstanceV) scrutinee matches
  ARE compiled (via `walkArmAsIfBranch`); the reason only fires for Int/Long-scrutinee
  guarded matches. JitLintTest: 10 tests (was 7). 1233/1233 interpreter suite green.

## 2026-06-03 — js-while-pmatch: JS codegen — IIFE elimination + numeric arithmetic fast-path

- **js-while-pmatch** — Four optimizations to JsGen to speed up numeric JS output:

  1. **While-loop IIFE removal**: `genFunctionBody` now special-cases `Term.While`
     to emit `while(cond){body}` directly instead of routing through `genExpr`
     (which wraps in an outer IIFE). New `genWhileBodyInline` helper flattens
     `Term.Block` bodies as `;`-separated statements, eliminating the inner IIFE
     that `genBlockAsIife` emitted per iteration. **arith-loop: 5.65→1.79 ms (3.2×)**.

  2. **Case class field name access**: `caseClassFieldsByType` pre-pass (already
     scanned for API client warnings) is now stored at module scope and used in
     `genPattern`. Case class destructuring emits `scrutVar.fieldName` instead of
     `Object.values(scrutVar).slice(1)[i]`.

  3. **Double/Float numeric tracking**: `numericVars` (var/val/param declarations)
     and `numericFunctions` (`:Double`/`:Float` return types) parallel the existing
     `intVars`/`intFunctions`. Case class field types scanned in
     `caseClassFieldTypeMap`; Double/Float-typed pattern-bound variables added to
     `numericVars`. New `isNumericExpr` predicate mirrors `isIntExpr`.

  4. **Direct arithmetic for numeric expressions**: `genArith` emits `(a op b)`
     directly (no `_arith` call, no `typeof` string guard) when `isNumericExpr`
     holds for both operands. Covers `*`, `+`, `-`, `/`, `%`, `<`, `>`, `<=`, `>=`,
     `==`, `!=`. **pattern-match-heavy: all `_arith` calls eliminated from hot path**.

  Commit: `b575547c`. Tests: 1230/1230 green.

---

## 2026-06-03 — while-jit-inline-match: inline match on val-bound InstanceV

- **while-jit-inline-match** — Added `Term.Match` case to `walkLocalSlotCtx`
  in `tryCompileWhileLong`. When the match scrutinee resolves to a val-bound
  `InstanceV` ref slot, a static helper method `fn_imatch_HASH(Object scrutName)`
  is co-emitted using the existing `walkMatchBody` infrastructure — typeTag
  switch + Int field extraction compiled to native bytecode. Call site:
  `fn_imatch_HASH(_rN)`. Guard: `!ctx.isCallee` (callee static methods have
  no ref preamble). Covers `total + (p match { case Pair(a,b) => a+b })`
  inline ADT matches in tight while loops.
  Commit: `3f05c7f0`.  Tests: 1230/1230 green.
  **Bench win (wi=3 mi=5 ms/op):**
    `instanceFieldAccess`: 8.4 → 0.043 ms (~195×)

---

## 2026-06-03 — tco-mutual-tail-jit-bypass: JIT bypass for wrapper→TCO calls

- **tco-mutual-tail-jit-bypass** — Fixed 1218× regression in `recursion-tco`
  benchmark (313 ms → 0.257 ms) caused by mutual-tail-call detection.
  
  **Root cause**: `def workload() = sumTco(100000, 0)` — the tail call to
  `sumTco` sets `tcoInfoFor(workload).tailTargets = Set("sumTco")` → `hasMutualTail=true`
  → `tcoTrampoline` activated for `workload`. Inside the trampoline, `sumTco` is
  replaced by a `MutualTailCall` stub. When fired, the trampoline switches to
  `curFun=sumTco` and tree-walks `sumTco.body` 100K times (each iteration throws
  a `TailCall` exception), bypassing the bytecode-JIT while-loop entirely.
  
  **Fix**: in the `MutualTailCall` handler of `tcoTrampoline`, try
  `JitRuntime.tryRunList(next, mc.args, eager=true)` before re-entering the
  trampoline loop for `next`. If JIT (bytecode or register-VM) handles the
  call, return the result directly; tree-walk only when JIT returns null.
  
  Commit: `4e22abb5`. Tests: 1230/1230 green.
  **Bench wins (bench.sh --warmup 3 --reps 5):**
    `recursion-tco` (`sumTco(100000, 0)`):  313 ms → 0.257 ms (1218×)
    `recursion-fib` (`fib(30)`):            1.32 ms (unchanged)

---

## 2026-06-03 — while-jit-ref-select-chain: field-select + ObjToObject chain args

- **while-jit-ref-select-chain** — Extended `walkRefArgCtx` in
  `tryCompileWhileLong` with two new term shapes:
  1. `Term.Select(Name(n), field)` — field access on a val-bound `InstanceV`
     global: resolves `n.field` at compile time, registers dotted key `"n.field"`
     in `refNames`. At invocation time `tryWhileJit` resolves dotted keys via a
     two-level `globals → InstanceV.fields` lookup.
  2. `Term.Apply(fn, [refArg])` where `fn` is `ObjToObject`-compiled — chained
     ref call: emits `_objFnN.apply(innerRef)` in generated Java. New
     `refObjFns: Array[ObjToObject]` field on `WhileJitEntry`; new TLS slot
     `refObjFnsTls` + `getRefObjFns()` in `JitGlobals`. `withRefs` gains a 3rd
     arg for `ObjToObject` instances.
  Commit: `225d7e32`.  Tests: 1230/1230 green.
  **Bench wins (wi=3 mi=5 ms/op):**
    `refFieldArg`  (`f(item.right)`):          9.2 → 0.046 ms (~200×)
    `refChainArg`  (`leafVal(getLeft(tree))`):  9.7 → 0.308 ms (~31×)

---

## 2026-06-03 — while-jit-ref-args: ObjToLong calls in tryCompileWhileLong

- **while-jit-ref-args** — Extended `tryCompileWhileLong` (the Java-source
  while-loop JIT) to compile loops that call a JIT-compiled `ObjToLong`
  function with a val-bound `InstanceV` argument. New `WhileJitEntry` replaces
  bare `Method` in the `JitBackend` SPI; carries `refNames` (variable names
  to read from `interp.globals` at each invocation) and `refFns`
  (pre-resolved `ObjToLong` instances). `JitGlobals.withRefs(refs, fns)` TLS
  sets both arrays around each `method.invoke`; generated Java reads
  `JitGlobals.getRefs()` / `getRefFns()` once before the loop.
  Guards: `isInstanceOf[ObjToLong]` prevents ObjToObject functions from
  being misidentified; `isCallee = true` blocks the ref path inside
  co-emitted callee static methods (which have no ref preamble).
  Commit: `b1c728af`.  Tests: 1230/1230 green.
  **Bench wins (wi=5 mi=5 ms/op):**
    `patternGuard` 12.4 → 0.044 ms (282×)
    `matchBodyBaseline` 8.4 → 0.043 ms (196×)
    `nestedMatchExpr` 8.6 → 0.042 ms (205×)

---

## 2026-06-03 — DataTable Phase 3 (ColumnKind + RowPayload)

- **datatable-column-action-expressiveness** — Added `ColumnKind` sealed trait
  (Text/Date/Money/StatusBadge/Link) and `RowPayload` sealed trait
  (Field/WholeRow/Fields) replacing the old `bodyField: String` contract.
  Extended `FieldColumnDef` with `kind` + `width`. Added `View.FormattedField`
  for kind-aware cell rendering. All emitters (React/Vue/Solid/Custom/SwiftUI/
  Swing/JavaFx), `ModelPathValidator`, `ViewTraversal`, `FetchIntrinsics`, and
  `std/ui/primitives.ssc` / `data.ssc` updated. New intrinsics:
  `fieldPayload`, `wholeRowPayload`, `fieldsPayload`, `dateColumn`,
  `moneyColumn`, `statusColumn`, `linkColumn`. Shorthands: `dcol/mcol/scol/lcol`,
  `fieldBody/wholeRowBody/fieldsBody`. 58 tests green.

## 2026-06-03 — claim protocol hardening

- **coord-claim-protocol-hardening** — Tightened coordination docs around the
  canonical `.work/active/<slug>.claim` filename, documented repair steps for
  suffix-less active markers, clarified that read-only status audits should use
  remote `git show`/`git ls-tree` from the main checkout, and updated
  `scripts/coord-status` to report invalid markers explicitly instead of
  silently printing "active claims: none".

---

## 2026-06-03 — datatable-source-abstraction (Phase 2)

- **datatable-source-abstraction** — Introduced `TableDataSource` sealed trait
  (`Remote(FetchUrlSignal)`, `StaticRows(List[Map[String,Any]])`,
  `SignalRows(ReactiveSignal[?])`) in `frontendCore`. Changed
  `View.DataTable(signal)` to `View.DataTable(source)`. All 7 backends gate
  Remote-specific logic on the Remote variant; StaticRows/SignalRows emit a
  header-only stub. New `staticRowsSource`/`signalRowsSource` intrinsics in
  `FetchIntrinsics`; `staticDataTable`/`signalDataTable` helpers in
  `std/ui/data.ssc`. FetchIntrinsics legacy path preserves bare-signal callers.
  47+56+58+6 tests green across frontendCore/React/Vue/fetchPlugin.

---

## 2026-06-03 — dual-bank-lapply-r1-to-ref + A.2/A.3 JIT slices

- **dual-bank-lapply-r1-to-ref** — `ObjToObject` typed JIT interface;
  `walkRefArm` + `walkRefMatchBody` in `JavacJitBackend` compile
  ref-returning match bodies to Java switch (handles `Pat.Extract` and
  `Pat.Var` wildcard arms with duplicate-`default` guard); `doCompile`
  tries ObjToObject path first for 1-param ref-scrutinee matches;
  `LApplyR1ToRef(argR: LRefExpr, ObjToObject)` in `EvalRuntime`;
  `compileRefExpr` `Term.Apply` case wired in both while-loop entries.
  **Bench `refChainArg` (`leafVal(getLeft(tree))` × 1M): 191 → 9.9 ms (19×).**
  1230/1230 green.

- **phase-c-bytecode-if-in-while** (Direction A.2, commit `b4ae788c`) —
  `walkLocalSlotCtx` covers `Term.If` ternary and single-stat `Term.Block`.
  Loops with `x = if cond then a else b` now compile via while-JIT.

- **phase-c-bytecode-pure-fn-call** (Direction A.3, commit `4a4a1e09`) —
  `walkLong` `Term.Apply` emits static call to globals-bound `def`.
  **Bench `pureCallSum`/`pureCallSum2`: 13 → 0.28 ms (47×, JVM parity).**

- **asm-jit-lapplyobjref-parity** — fully complete: `5152e001` (AsmJit
  2-param ref-mixed interfaces) + `f7fc2b34` (LApplyR1 routes through
  `JitBackend.default.tryCompile`). All ref-arg bench gates locked.

---

## 2026-06-03 — dual-bank-lref-match + AsmJit 2-param ref-mixed parity

- **dual-bank-lref-match** — `LRefMatch(scrutR: LRefExpr, cm: CompiledMatch)`
  extends `LRefExpr`. When `cm.valueCapable`, eval calls `cm.runValue(scrutV,
  emptyEnv)` — no Computation allocation. `compileRefExpr` in both
  `tryLongWhileAssign` and `tryMixedLongWhile` gains a `Term.Match` case,
  enabling `f(e match { ... })` ref-arg patterns inside hot while loops.
  Test: LRefMatch with val-bound Shape in 100-iteration loop. 1229/1229 green.
  Commit `2305e321`.

- **asm-jit-lapplyobjref-parity** (partial) — `AsmJitBackend.determineInterface`
  now returns `LongObjToLong`, `ObjLongToLong`, `LongObjToDouble`,
  `ObjLongToDouble` for 2-param ref-mixed functions, matching
  `JavacJitBackend`. Commit `5152e001`.

---

## 2026-06-03 — JIT Direction A.5 — multi-stat block bodies

- **phase-c-bytecode-block-multistat** — `JavacJitBackend` gains
  `walkBlockStmts` / `blockStmtsCtx`: multi-stat `Term.Block` bodies are now
  JIT-compiled. Non-final `Defn.Val` bindings emit as Java `long`/`double`
  locals; the final `Term` compiles via the existing walkers (including
  block-ends-with-match). Expression-context multi-stat blocks use a
  `LongSupplier`/`DoubleSupplier` IIFE. `AsmJitBackend` gets the parallel
  `emitValBindings` helper (LSTORE/DSTORE bytecode + slot allocation via
  `ctx.allocSlot`). Both backends handle the block-ends-with-`Term.Match`
  path. Test: `sumSquares(5)` (two val-bindings, recursive call) = 55.
  1228/1228 tests green. Commit `6e11cc62`.

---

## 2026-06-03 — perf/interpreter-opt merge (63 micro-opts + 5 fixes)

- **interpreter-opt branch merged** — 63-commit null-optimisation branch
  (`perf/interpreter-opt`) landed into main via `--no-ff` merge commit
  `06aa0a5b`. Brings null-based dispatch tables, IMap direct constructors,
  CharV pool, FrameMap chain iteration, pure-path short-circuit, and a
  ReusableFrame1 for hot iteration callbacks. Five correctness bugs found and
  fixed during test runs: (1) `ImportResolver.discoverStdRoot` / `jarDir`
  removed from the branch — restored 6-candidate std-path chain; (2)
  `evalBlock` closure iteration overwrote FrameMap params when a child
  interpreter's builtins share the same name as a param — fixed with
  `!b.contains(k)` guard; (3) `infix2` `++` fallback skipped
  `extensionDispatch` so `Doc.++` produced wrong output — fixed; (4) `callValue1/2`
  fast paths bypassed TCO for named self-tail-recursive functions — added TCO
  guard; (5) effect test used one-shot resume on a `multi effect` — fixed test
  declaration. All 1227 tests green. Merge commit `06aa0a5b`.

---

## 2026-06-03 — JIT pattern guard if-chain + unary minus

- **jit-pattern-guard-conditional-arm** — `walkMatchBody` now detects when
  any arm has a guard (`c.cond.nonEmpty`) and emits an if-chain form instead
  of a Java switch. New `walkArmAsIfBranch` (~80 lines) emits
  `if (inst.typeTag() == N) { bindings; if (guard) { return body; } }`;
  guard conditions are compiled via the existing `walkBool` walker. Also adds
  `Term.ApplyUnary("-"/"+"...)` support to `walkLong` and `walkDouble`.
  Bench `patternGuard` (4 × 1M pre-built val calls): 13,570 → 11.7 ms/op
  (~1,160×). JitLint updated: Pat.Extract guarded match now `willJit = true`.
  1227/1227 tests green. Commit `8924f4e6`.

## 2026-06-03 — AsmJitBackend — direct AST→JVM bytecode JIT

- **asm-jit-backend** — Second implementation of the `JitBackend` SPI: emits
  JVM class files directly via ASM 9.7 instead of roundtripping through
  `javax.tools.JavaCompiler`. Full parity with `JavacJitBackend`: arithmetic,
  TCO while-loop, ADT pattern match, instance field access, Double functions,
  free globals, pure-function call inlining. Selected via
  `SSC_JIT_BACKEND=asm`; Javac remains the default. Build dep: `org.ow2.asm
  9.7` in `backendInterpreter`. Three correctness bugs found and fixed during
  parity testing: (1) `Lit.Double(v)` in scalameta 4.17 yields `String` at
  runtime — must call `Double.parseDouble(v.toString)` before `visitLdcInsn`;
  (2) bridge method `bSlot` increment for Object (ref) params was `+= 2`,
  must be `+= 1`; (3) `returnsThrows` guard missing from `doCompile` —
  throws-typed functions must fall back to tree-walk so the interpreter can
  auto-wrap the result in `Right`. 1218/1220 tests pass in ASM mode (same
  pre-existing flaky failures as default mode). Bench parity: `recursionFib`
  1.4 ms, `recursionFibD` 1.7 ms, `recursionTco` 36 µs, `arithLoop` 0.27 ms.

## 2026-06-03 — CI green audit (batch-1 + batch-2)

- **ci-green-audit** — Fixed all CLI test failures introduced by the
  May 2026 directory refactor (`cli/`→`tools/cli/`, `std/`→`runtime/std/`)
  and MessagePack binary artifact format migration. Three commits:
  batch-1 (a800cb69, 11 failures), SimpleYaml colon-in-value parse error
  (bb6d5fa0), batch-2 (b15dbffb, 9 failures). All `cli/` tests now pass
  or cancel gracefully when optional prerequisites are absent.

## 2026-06-03 — DataTable path validation

- **datatable-path-validation** — Extended `ModelPathValidator` to validate
  typed `View.DataTable` column/action field paths against the row model carried
  by `FetchJsonSignal` / `CodecHint.Json`, while keeping raw fetch-backed tables
  permissive. Added focused frontend-core coverage.

## 2026-06-03 — DataTable authoring surface cleanup

- **datatable-authoring-surface-cleanup** — Documented the post-`FetchTable`
  `DataTable` authoring contract, fixed std/ui `rowEdit` wiring, made the
  interpreter fetch plugin accept std/ui default `editable`/`emptyHeaders`
  shapes, migrated examples/docs to `fetchUrlSignal(...)` +
  `dataTable(signal, columns, actions)`, and added focused regression coverage.

## 2026-06-02 — JvmGen UI bridge split

- **jvmgen-ui-bridge-split** — Extracted the frontend
  `std.ui.primitives` generated-source block from `JvmGen.scala` into
  `JvmRuntimeUiPrimitives.source`. Generated Scala for the dashboard frontend
  example remains byte-identical after normalizing absolute jar directive paths.

## 2026-06-02 — CLI command result flow

- **cli-command-result-exitcode** — Added internal `ExitCode` /
  `CommandResult`, `CliCommand.runResult`, registry result dispatch, and
  top-level exit-code propagation. `LspCmd` is the first migrated command; the
  public command SPI remains `run(args): Unit` for compatibility.

## 2026-06-02 — frontend view traversal core

- **frontend-view-traversal-core** — Added `frontend/core` `ViewTraversal`
  (`children` + `foreachDepthFirst`) so backend collectors can share one
  exhaustive `View[?]` walk without sharing renderer logic. The first migrated
  collector is React's fetch-signal pass, which now finds typed JSON fetches
  inside semantic containers such as `Column`.

## 2026-06-01 — ui-fetch-auth v1 + v2

- **ui-fetch-auth-v1** — `fetchAction`/`fetchActionClear` gain optional
  `headers: Signal[String] = emptyHeaders` param; header value read at click time
  from `_sv[headersId]` → `JSON.parse` → passed to `fetch()`; `data-ssc-fetch-headers`
  attr in `renderBody`; all frontend emitters (React/Vue/Solid/Custom/Swing/JavaFX/Swift)
  updated; interpreter intrinsic handles 4-arg and 5-arg forms.
- **ui-fetch-auth-v2** — `fetchUrlSignal` performs a real HTTP GET on mount + on
  `refreshTick` increment (was a stub returning `Signal('')`); `_fetchGet` metadata
  on the Signal object drives `data-ssc-fetch-get-*` attrs on the text node span;
  `_ssc_ui_mount` now queries `[data-ssc-fetch-get-url]` and sets up fetch + tick
  subscription; `fetchTableView` similarly gains `headers`; new
  `FetchUrlSignal.headersId: Option[String]` field. Example: `examples/fetch-auth.ssc`.

## 2026-05-30 — lightweight perf regression guard added

- **perf-regression-guard** — Added a checked-in performance workflow manifest,
  benchmark README, ignored raw runtime/JMH outputs, `ssc bench --smoke` with
  optional `--target-ms/--require-target`, and `scripts/perf-smoke.sh --jmh` for
  an opt-in short JMH smoke. README, docs/performance, user guide, baseline
  policy, queue, and backlog now distinguish informational runs from explicit
  blocking gates.

## 2026-05-30 — typer real-type roadmap specified

- **typer-real-types-roadmap-spec** — Added
  [`docs/specs/typer-real-types-roadmap.md`](docs/specs/typer-real-types-roadmap.md),
  defining the planned type-evidence pipeline for reducing accidental `Any` in
  exported symbols, interfaces, routes/remotes, OpenAPI/GraphQL schemas, typed
  data codecs, Dataset/Spark mapping, and plugin metadata. README, docs index,
  architecture, typed data, route clients, OpenAPI, GraphQL, contract validation,
  queue, and backlog now link the planned work.

## 2026-05-30 — contract validation platform specified

- **contract-validation-spec** — Added
  [`docs/specs/contract-validation.md`](docs/specs/contract-validation.md), defining the
  planned shared OpenAPI/GraphQL validation model: route/resolver source checks,
  type-shape compatibility, diagnostics, profile leak checks, overlays/imports,
  CLI commands, compatibility diffs, baselines, contract tests, and rollout
  phases. README, docs index, OpenAPI/GraphQL specs, user guide, queue, and
  backlog now mark it as planned and not implemented yet.

## 2026-05-30 — quality roadmap queued and JMH output ignored

- **quality-roadmap-and-jmh-ignore** — Added a new Quality / Contracts / Type
  System queue/backlog section for contract validation, real-type propagation,
  performance regression guarding, and the next CLI helper split. Ignored JMH
  per-benchmark output directories so local benchmark runs do not leave shared
  `main` visibly dirty.

## 2026-05-30 — interpreter FrameMap and Option hot-path follow-up

- **perf/interpreter-framemap-option-hotpaths** — Ported two more safe slices
  from `perf/interpreter-opt`: direct `FrameMap.foreachEntry` iteration plus
  cheaper `FrameMapN.flat`, and direct-match `Option.map` / `Option.flatMap` /
  `Option.filter` / one-arg `fold` / `toRight` / `toLeft` paths adapted to
  the current null-sentinel `OptionV` representation. Also added the
  one-argument `Double.max` / `min` / `pow` / `atan2` dispatch fast path and
  mixed `Int`/`Double` comparison fast paths in `infix2`. List single-arg
  calls for `mkString`, `zip`, `takeRight`, `dropRight`, `splitAt`,
  `intersect`, `diff`, `count`, `collect`, and `span` now bypass the generic
  `arg :: Nil` fallback. `List.takeWhile`, `dropWhile`, and `sortWith` now
  have working one-argument interpreter dispatch with regression coverage.
  Compound assignment (`x += e` and siblings) now uses an all-pure path when
  the variable read, RHS, and infix operation complete synchronously. Known
  non-pure continuations in `if`, assignment, and list count now construct
  `FlatMap` directly instead of re-checking through `.flatMap`. Pattern-match
  cache lookup now happens before scrutinee evaluation, so pure scrutinees avoid
  an extra continuation. Plugin and stdlib helper lookups in `globalOrStub`,
  `Using.resource`, `McpSchema.derived`, derives fallback, and `Storage.get`
  now use null-sentinel map access instead of temporary `Option` wrappers.
  Actor system messages and interpreter HTTP response helpers now use direct
  small immutable map constructors for fixed-shape values. User interpolator
  and `summon` / `summonInline` lookups now use null-sentinel direct lookup on
  the common path. Ordinary user instance dispatch now skips plugin-bridge
  fallback checks, and single-argument extension dispatch uses the direct
  two-argument call helper. Type-method calls now avoid allocating a self-ref
  native function when the method body never references its own name. Lambda
  parameter names and type annotations are cached per AST parameter clause.
  More single-argument `String` operations now stay on the direct dispatch path.
  `Map` higher-order single-argument calls now do the same for `foldLeft`,
  `exists`, `forall`, `count`, and `find`. Callable instances, signal handles,
  and response header updates now avoid temporary `Option` wrappers in their
  hot lookups. Coroutine handles, typed HTTP handler requests, remote-handler
  request bodies, and PID serialization now use the same direct field lookup.
  Actor PID send, monitor/link, registry, scheduling, seed-resolution, and
  actor-group delivery paths now avoid the same temporary `Option` wrappers.
  Actor receive-loop dispatch now avoids boxing the matched computation before
  returning it to the scheduler.
  Named one- and two-argument function fast paths now reuse the interpreter's
  cached self-closure frame, and top-level/block statement execution no longer
  allocates a `zipWithIndex` tuple list just to find the last statement.
  Function and type-method vararg checks now avoid temporary `Option` wrappers
  from `List.lift` on every generic call.
  Two-field case-class pattern matching now skips field materialization for
  wildcard-only slots, reducing work in compiled match handlers.
  Tail-recursive trampolines now snapshot profiler state per current function
  and build stable self/mutual-call environments with `FrameMap` instead of
  `Map.updated ++`, reducing overhead in TCO-heavy benchmarks.
  Primitive infix expressions whose operands are direct names or literals now
  bypass subterm `Pure` wrapper creation and the generic infix dispatcher when
  debugger hooks are disabled, improving tight arithmetic/recursive benchmarks.
  Top-level `while` loops now reuse the interpreter globals view directly when
  there are no local shadow slots, avoiding a synthetic side frame and per-iteration
  refresh in tight assignment loops. `while` bodies made only of primitive direct
  assignments now take a guarded JVM-loop fast path after a dry-run confirms that
  no generic calls/extensions are involved; nested primitive infix expressions use
  the same recursive fast evaluator.
  Built-in HTML rendering and `attr := value` dispatch now use direct field
  lookups for `_Raw`, `Attr`, component `css`/`render`, and `AttrKey`.
  ActorGroup state operations, `Async.await`, and optic composition helpers now
  avoid short-lived `Option` chains in their hot field lookup paths.
  Path optic `getOption`, `set`, and traversal modify paths now carry absence
  with the interpreter's existing null sentinel instead of transient `Option`s.
  `Traversal.getAll` field and map-key steps now avoid `Option.map/getOrElse`
  dispatch chains while preserving absent-field behavior.
  Stream effect finalization now resolves `Source.from` / `Source.failed` with
  direct global lookups.
  Cluster seed resolution and Source / RemoteSource / ReactiveSignal bridge
  dispatch now use direct global or field lookup paths.
  Typed row projection now avoids temporary `Option`/sequence allocations while
  mapping SQL result maps into case-class-shaped values.
  Fixed-shape built-in `McpSchema`, HTML raw nodes, `Response`, `Pipeline`, and
  `KeyedStateSpec` values now use direct small immutable map constructors.
  Actor cluster events, local PIDs, and timeout receive `Some` wrappers now use
  the same direct small-map construction path.
  Future, Signal, serialized Pid, and typed-handler Either wrappers now avoid
  tuple/array allocation from the generic `Map(...)` factory.
  Parametric-given factory markers and synthetic opaque-type companions now use
  direct small-map constructors in statement execution.
  Validation/Either wrappers, user interpolator `StringContext`, Dataset empty
  errors, and restartable exception shims now use direct one-field map values.

## 2026-05-30 — WebSocket 10k load test made explicit

- **fix/ws-load10k-env-gate** — Removed the last ScalaTest `@Ignore` from
  `WsLoad10kTest`; the expensive 10k WebSocket load test is now visible but
  env-gated behind `SSC_WS_LOAD10K=1` for default test runs.

## 2026-05-30 — JS WebSocket runtime smoke restored

- **fix/jsgen-ws-ignored-test** — Re-enabled `JsGenWsTest` and restored the
  Node JS runtime's public `serve(port[, tls])` alias so `JsHttpIntrinsics`
  no longer emits calls to an undefined runtime symbol.

## 2026-05-30 — Swing JvmGen runtime smoke restored

- **fix/swing-runtime-ignored-test** — Re-enabled
  `JvmGenSwingRuntimeTest` and updated typed-client assertions for the current
  headers/cancel-token route-client signature and BackendRequest transport.

## 2026-05-30 — shard module smoke restored

- **fix/shard-module-ignored-test** — Re-enabled `ShardModuleTest`; the
  in-process `std/cluster/shard.ssc` solo-node owner/send smoke passes without
  additional runtime changes.

## 2026-05-30 — obsolete core UI ignored tests removed

- **fix/obsolete-core-ui-ignored-tests** — Removed stale ignored
  `ToolkitDemoValidateTest` and `StdUiSmokeTest` copies from
  `backendInterpreter`; their active plugin-dependent coverage lives in
  `backendInterpreterServer`.

## 2026-05-30 — SQL ignored tests restored

- **fix/remaining-sql-ignored-tests** — Re-enabled the JvmGen SQL runtime
  scala-cli smoke tests, moved SQL examples/conformance interpreter coverage
  into `backendInterpreterPluginTests` where the SQL plugin is available, and
  updated conformance capture paths to `tests/conformance/...`.

## 2026-05-30 — interpreter-server test overlap cleanup

- **fix/interpreter-server-test-overlap** — Removed the duplicate
  `HttpClientTest` copy from `backendInterpreterServer` now that HTTP plugin
  coverage lives in `backendInterpreterPluginTests`, and made the active
  toolkit-demo validation run in headless emit-only mode so it does not bind
  or hang on port 8080.

## 2026-05-30 — ignored interpreter tests restored

- **fix/ignored-interpreter-tests** — Re-enabled the coordinator conformance
  smoke by reading `tests/conformance/actors-cluster-coordinator.ssc`, moved
  HTTP client integration coverage to interpreter plugin tests with explicit
  `HttpInterpreterPlugin`, and made interpreter call-stack tracking
  thread-local so `runAsyncParallel` thunks cannot race on shared stack
  buffers.

## 2026-05-29 — bench{} language block + ssc bench + cross-backend JMH

- **feat/bench-tooling** — Three complementary benchmark tools:
  1. `bench("label") { expr }` / `bench("label", warmup, reps) { expr }` special form in the interpreter: re-evaluates the body AST term warmup+reps times, prints `[bench] label  p50=Xms  min=Yms  max=Zms  (N reps)` to stdout, returns the last result. Works inside any ScalaScript program.
  2. `ssc bench [--no-interp|--no-jvm|--no-js] [--warmup N] [--reps N] [--baseline] <file.ssc>` — runs a file through all three backends and prints a markdown comparison table. Interpreter runs in-process (no JVM startup overhead); JvmGen + JsGen run as subprocesses via the running ssc.jar. Auto-detects scala-cli / node availability.
  3. `CrossBackendBench.scala` JMH suite in `interpreterBench`: `jvmGen_*/jsGen_*` benchmarks measure codegen time (in-process, no subprocess), `interp_*` benchmarks measure execution. Run via `sbt "interpreterBench/Jmh/run .*CrossBackend.*"`. `build.sbt` adds `backendJvm + backendJs` deps to `interpreterBench`.
  Also committed: `scripts/runtime-bench.sh` shell harness for full multi-workload wall-clock comparison with pre-warm, median calculation, and optional `--baseline` write.

## 2026-05-29 — runtime test blocker fixes

- **fix/runtime-test-blockers** — Added generic `Foreign` method dispatch through
  `<Type>.<method>` plugin globals (restoring `ReactiveSignal.bind`), updated
  OAuth/OIDC installer tests to use the `HttpCap` adapter, finished the remaining
  `OptionV` null-sentinel plugin call sites, and fixed optimized `List.sorted` /
  `sortBy` loops that could hang and exhaust heap.

## 2026-05-29 — OptionV optimization follow-up

- **fix/interpreter-optionv-followups** — Completed the null-sentinel
  `OptionV` migration across DAP, interpreter-server, JSON/request/auth/OAuth/
  MCP/GraphQL/graph/sql/streams/remote plugins, and added a regression test for
  optional optics preserving `None` as absent.

## 2026-05-29 — interpreter unary pure fast path

- **perf/interpreter-unary-pure** — Ported the direct-match fast path for
  `Term.ApplyUnary` from `perf/interpreter-opt`. Pure unary operands now avoid
  allocating an extra `flatMap` continuation.

## 2026-05-29 — interpreter mapSequence three-element fast path

- **perf/interpreter-mapseq3** — Ported the three-element specialization for
  `Computation.mapSequence` from `perf/interpreter-opt`, avoiding
  `ArrayBuffer` allocation for common three-field/three-value sequencing.

## 2026-05-29 — interpreter Map.updated tuple-allocation cut

- **perf/interpreter-map-updated** — Ported the map update allocation cut from
  `perf/interpreter-opt`. Map `updated` and `+` paths now use
  `m.updated(k, v)` instead of allocating an intermediate `(k -> v)` tuple.

## 2026-05-29 — interpreter Map.get hit-path allocation cut

- **perf/interpreter-map-get-sentinel** — Ported the `Map.get` null-sentinel
  fast path from `perf/interpreter-opt`. Map lookup hits now avoid creating an
  intermediate Scala `Some` before returning `Value.OptionV`.

## 2026-05-29 — interpreter toList and zipWithIndex builders

- **perf/interpreter-zip-tolist** — Ported reverse-cons builders for
  `String.toList`, `String.zipWithIndex`, and `List.zipWithIndex` from
  `perf/interpreter-opt`, preserving element order while avoiding
  `ArrayBuffer` allocation in these common collection conversions.

## 2026-05-29 — interpreter split and indices list builders

- **perf/interpreter-split-indices** — Ported reverse-cons builders for
  `String.split`, `String.lines`, and `List.indices` from
  `perf/interpreter-opt`, preserving element order and trailing empty string
  parts without allocating an intermediate `ArrayBuffer`.

## 2026-05-29 — interpreter range construction fast path

- **perf/interpreter-ranges-map2** — Ported the range cons-from-end fast path
  and `AttrKey :=` `Map2` allocation cut from `perf/interpreter-opt`.
  Empty ranges keep their existing empty-list behavior while non-empty
  `to`/`until`/`List.range` calls avoid the intermediate `ArrayBuffer`.

## 2026-05-29 — interpreter runtime Map1 constructors

- **perf/interpreter-runtime-map1** — Extended the direct immutable `Map1`
  constructor optimization to direct blocks, throws auto-wrapping, coroutine
  state values, and core `Left`/`Right` intrinsics.

## 2026-05-29 — interpreter small InstanceV maps

- **perf/interpreter-small-instance-maps** — Ported the small `InstanceV`
  field-map allocation cuts from `perf/interpreter-opt`. Common one- and
  two-field runtime instances now use immutable `Map1`/`Map2` constructors
  directly instead of building maps through tuple-based syntax.

## 2026-05-29 — interpreter case constructor fast paths

- **perf/interpreter-case-ctor-fast** — Ported a safe version of the
  `perf/interpreter-opt` case-class/enum constructor fast path. Constructors
  without defaults now avoid default-application overhead for valid arities
  while preserving the existing missing-argument error behavior.

## 2026-05-29 — interpreter parameter array cache

- **perf/interpreter-param-array-cache** — Ported the `params.toArray` cache
  from `perf/interpreter-opt`. Calls to functions, methods, and TCO frames
  with three or more parameters now reuse the parameter-name array instead of
  allocating it on every invocation.

## 2026-05-29 — interpreter closure capture allocation cuts

- **perf/interpreter-closure-capture** — Ported the closure-capture allocation
  reduction from `perf/interpreter-opt`. Lambda/def/block capture now uses
  mutable builders and `foreachEntry` to avoid per-slot `Tuple2` allocation,
  and the direct-monad lift path uses the existing one-argument dispatch fast
  path.

## 2026-05-29 — interpreter for-comprehension pure fast paths

- **perf/interpreter-for-pure** — Ported the `for`/`yield` and `for`/`do`
  pure RHS/guard fast paths from `perf/interpreter-opt`. Pure generator,
  guard, and value enumerator evaluations now avoid unnecessary `FlatMap`
  nodes while preserving the trampoline fallback for suspended computations.

## 2026-05-29 — interpreter while pure fast path

- **perf/interpreter-while-pure** — Ported the all-pure `while` loop fast path
  from `perf/interpreter-opt` and snapshots `Profiler.enabled` once per call
  helper invocation. Pure tight loops now avoid allocating immediate
  condition/body `FlatMap` nodes on every iteration.

## 2026-05-29 — interpreter String single-arg fast paths

- **perf/interpreter-string1** — Ported `dispatchString1` higher-order string
  fast paths from `perf/interpreter-opt`. `String.map`, `filter`, `foreach`,
  `takeWhile`, `dropWhile`, plus one-arg `indexOf` / `codePointAt`, now avoid
  the generic one-argument dispatch list allocation.

## 2026-05-29 — interpreter CharV ASCII pool

- **perf/interpreter-char-pool** — Ported the ASCII `CharV` pool from
  `perf/interpreter-opt`. Character literals and string character-producing
  paths now use `Value.charV`, so common ASCII string iteration and indexing
  avoid fresh `CharV` allocation.

## 2026-05-29 — interpreter Int single-arg fast path

- **perf/interpreter-int1** — Ported the `dispatchInt1` fast path from
  `perf/interpreter-opt`. Common `Int.max`, `Int.min`, `Int.to`, and
  `Int.until` calls now avoid building the one-argument dispatch list.

## 2026-05-29 — interpreter computation sequence fast path

- **perf/interpreter-sequence** — Ported the `Computation.sequence` fast path
  from `perf/interpreter-opt`. Mixed pure/suspended computation lists now resume
  from the first non-pure element instead of rebuilding a `FlatMap` chain over
  already-collected leading pure values.

## 2026-05-29 — interpreter instance one-arg fast path

- **perf/interpreter-instance1** — Ported a safe `dispatchInstance1` /
  `callTypeMethod1` slice from `perf/interpreter-opt`. One-argument
  user-defined class methods, `Right.map` / `Right.flatMap`, `Left.getOrElse`,
  `Left.map` / `Left.flatMap`, and `Pid.tell` now avoid the generic
  `arg :: Nil` dispatch allocation while preserving the existing two-argument
  `Either.fold` behavior.

## 2026-05-29 — interpreter list aggregator fast paths

- **perf/interpreter-list1-aggs** — Ported high-impact list aggregation
  fast paths from `perf/interpreter-opt`. Curried calls such as
  `foldLeft(init)(f)`, `foldRight(init)(f)`, `scanLeft(init)(f)`,
  `reduceLeft(f)`, `partition(f)`, and `groupBy(f)` now stay in
  `dispatchList1` and avoid the extra one-argument dispatch list allocation.

## 2026-05-29 — interpreter dispatch2 built-in fast path

- **perf/interpreter-dispatch2** — Ported the two-argument built-in dispatch
  fast path from `perf/interpreter-opt`. Common two-argument methods such as
  `Map.getOrElse`, `Map.updated`, string `substring`/`replace`/`slice`, integer
  `clamp`, and list `slice`/`zip` now avoid constructing `arg1 :: arg2 :: Nil`
  in the interpreter dispatch path while preserving primitive extension-method
  dispatch.

## 2026-05-29 — interpreter two-argument apply fast path

- **perf/interpreter-apply2** — Ported the two-argument `Term.Apply` fast path
  from `perf/interpreter-opt`. Calls like `obj.method(a, b)` now bypass generic
  argument collection when the receiver and two arguments can be evaluated
  directly, reducing overhead in fold/reduce-style call sites.

## 2026-05-29 — interpreter dispatch1 fast path

- **perf/interpreter-dispatch1** — Ported the single-argument dispatch fast path
  from `perf/interpreter-opt`. The interpreter now routes many one-argument
  built-in method calls through `dispatch1`, avoiding `arg :: Nil` allocation
  in common `map`/`filter`/collection/string/option-style calls.

## 2026-05-29 — interpreter FrameMap small-field fast path

- **perf/interpreter-small-hotpath** — `FrameMap.fromMap` and
  `FrameMap.fromMapWithSelf` now use `FrameMap1`/`FrameMap2` for empty, one,
  and two-field overlays instead of allocating `FrameMapN` arrays. This targets
  case-class and instance method dispatch, where one- and two-field objects are
  common.

## 2026-05-29 — openapi-p5 CLI OpenAPI export

- **openapi-p5** — Added `ssc emit-openapi` for standalone OpenAPI 3.1 export without starting a server. The command runs an abort-at-first-serve interpreter dry-run, writes JSON to stdout by default, supports YAML via `--format yaml` or `-o *.yaml`, and accepts `--title`, `--version`, and repeatable `--server` overrides while preserving route metadata and security schemes.

## 2026-05-29 — openapi-p4 OpenAPI security schemes

- **openapi-p4** — Added `openApiSecurity(...)` declarations and `@openapi(security = List(...))` route requirements. Shared OpenAPI generation now emits `components.securitySchemes` plus per-operation `security` arrays for bearer/http and api-key schemes; interpreter and JVM generated server paths carry the metadata. Updated the OpenAPI example to include bearer security.

## 2026-05-29 — openapi-p3 per-route OpenAPI metadata

- **openapi-p3** — Added user-facing `@openapi(...)` route metadata for summary, description, tags, and deprecation. The parser rewrites the annotation into a marker call before `route(...)`; interpreter and JVM generated runtimes consume the marker on the next route registration; shared OpenAPI output emits the metadata. Added `std/openapi.ssc`, `examples/openapi-annotation.ssc`, and parser/generator/interpreter/plugin/JVM coverage.

## 2026-05-29 — openapi-p2b JVM OpenAPI response schemas

- **openapi-p2b** — JVM generated front-matter routes now propagate non-`Any` response type metadata from matching `apiClients:` endpoints into the generated OpenAPI response schema. Raw `route(...)` handlers continue to use the generic `200 OK` fallback. Added code-shape coverage and a scala-cli JVM e2e check for `/_openapi.json` schema output.

## 2026-05-29 — openapi-p2 JVM OpenAPI routes

- **openapi-p2** — OpenAPI generation now has a shared `OpenApiGenerator` model in backend SPI, and JVM-generated HTTP servers register `GET /_openapi.json` plus `GET /_swagger` from the generated `_routes` table when `serve()` / `serveAsync()` starts. The interpreter uses the shared generator while preserving typed handler query/body inference. Added SPI, interpreter, JvmGen code-shape, and scala-cli JVM e2e tests. Automatic response-type propagation is split to `openapi-p2b`.

## 2026-05-29 — arch-meta-v2-p4d richer quoted macro diagnostics

- **arch-meta-v2-p4d** — Restricted quoted macro unsupported-body diagnostics now classify common misses before the generic fallback. `Expr.asValue match` points at not-yet-implemented compile-time branching, `Expr(...)` points users back to direct quote syntax, and nested/non-top-level quotes or splices outside a direct quoted expression explain the current body-shape restriction. Added linker tests for each targeted diagnostic while preserving direct quoted-expression expansion.

## 2026-05-29 — arch-meta-v2-p4c quoted macro diagnostics

- **arch-meta-v2-p4c** — Restricted quoted macros now fail explicitly for unsupported forms. Parser preprocessing turns entrypoints without quoted args, such as `${ impl(x) }`, into a diagnostic helper requiring `${ impl('x) }`; interpreter `ssc run` reports `quoted macro error: ...`; linker normalization rejects non-quoted macro implementation bodies and explains that the restricted subset must return a direct quoted expression like `'{ $x + 1 }`. Added parser/linker/interpreter negative tests while preserving the p4/p4b happy path.

## 2026-05-29 — checkout `bin/ssc` staging fix

- **fix-ssc-installbin-classpath** — `bin/ssc` in a fresh worktree failed before staging because `bin/lib/` is intentionally generated, and after `sbt cli/installBin` it failed with `NoClassDefFoundError: scalascript/compiler/plugin/deploy/DeployError`. `cli / assembly` itself was healthy. `installBin` now includes `deployPlugin / packageBin` in `bin/lib/jars/` because the CLI deploy subcommand directly references deploy SPI/runtime classes at startup; other std plugins remain lazily loaded from `.sscpkg`. Added project `.jvmopts` (`-Xmx4G`, G1) so `cli / installBin` does not OOM while compiling/staging all std plugins. Verified `bin/ssc --help` and `bin/ssc examples/hello.ssc`.

## 2026-05-29 — arch-meta-v2-p4b quoted macro interpreter parity

- **arch-meta-v2-p4b** — Restricted quoted macros now have interpreter/run-path parity for the direct quoted-body subset. Parser helper lowering now carries both quoted parameter names and runtime values (`'x` → `__ssc_quote__("x", x)`, `$x` → `__ssc_splice__("x", x)`); linker/interface extraction remain backward-compatible with the old helper shape. The interpreter registers lightweight `Expr`, `QuotedContext`, `__ssc_macro__`, `__ssc_quote__`, `__ssc_quote_expr__`, and `__ssc_splice__` helpers, so direct quoted macro bodies work under `ssc run`. `Expr.asValue` returns the quoted value as `Option[A]`; `Expr.asTerm` returns an opaque `ScalaScriptTerm(name, value)`. Added `examples/quoted-macro-interpreter.ssc`, 3 interpreter tests, and updated macro/linker/parser tests.

## 2026-05-29 — arch-meta-v2-p5 runtime Mirror derives

- **arch-meta-v2-p5** — Runtime/interpreter slice for Mirror-based user typeclass derivation. The interpreter now registers summon-able `Mirror.Of[T]`, `Mirror.ProductOf[T]`, `Mirror.SumOf[T]`, and `deriving.Mirror.*` aliases when product/sum types are declared; `Mirror.of[T]` returns the same metadata. Mirror values now expose `label`, `fields`, `elemLabels`, `elemTypes`, `variants`, `isProduct`, `isSum`, `fromProduct`, and `ordinal`. Custom `derives` now works for user-defined typeclasses that provide `derived(m: Mirror)`; the existing `TC.derived` dispatch reuses the richer mirror. Added focused tests for `summon[Mirror.Of[Person]]` and `case class Person(...) derives Csv`. Source-level `inline match` over Mirror and broader generated-backend conformance remain planned follow-ups.

## 2026-05-29 — arch-meta-v2-p4 restricted quoted macro slice

- **arch-meta-v2-p4** — First restricted `QuotedMacro[A]` slice. Parser preprocessing now accepts `${ impl('x) }` macro entrypoints and `'{ $x + ... }` quoted bodies by lowering them to stable helper calls for Scalameta while preserving original code-block source. `.scim` interfaces carry `MacroImplRef` metadata on inline entrypoints plus `isMacroImpl` / `macroQuotedBodySource` metadata on direct implementation helpers. IR now has a `MacroImpl` node and `AstToIr` lowers the parser helper call into it. `Linker` builds a macro expansion table and expands direct quoted-expression macro bodies in `CodeBlock.source` before existing inline/FQN rewrites. 3 parser tests, 1 interface test, and 3 linker tests cover preprocessing, metadata, IR/link expansion, and cross-module expansion. `Expr[A].asValue`, `Expr[A].asTerm`, richer quoted terms, diagnostics for unsupported bodies, and interpreter/run-path parity remain planned follow-ups.

## 2026-05-29 — opt-const-fold constant folding in JsGen / JvmGen

- **opt-const-fold** — Compile-time constant folding in JS and JVM code generators (roadmap §4b). `JsGen`: new `foldConstant` helper evaluates binary infix expressions at codegen time when both operands are literals — covers `Int`/`Long`/`Double` arithmetic (+, -, *, /, %), comparison (< > <= >= == !=), `Boolean` logic (&& ||), and `String` + concatenation. `if(true/false)` with literal condition eliminates the dead branch. Unary `-` and `!` fold on literals. `JvmGen`: matching `foldConstantScala` helper wired into `emitExprDeep` (effectful path) and `emitExpr` (non-effectful infix check) with `Defn.Val`/`Defn.Var` always routed through `emitExpr`; same `if(true/false)` elimination in `emitExprDeep`. For JvmGen non-effectful vals, scalac performs its own constant folding at compile time so codegen-level folding is only critical in the effectful CPS path (where `_binOp` dispatch is avoided). 25 new `ConstFoldJsGenTest` tests + 11 new `ConstFoldJvmGenTest` tests; all 36 pass. No regressions (897/1060 pass vs 878/1060 baseline).

## 2026-05-29 — arch-meta-v2-p3 cross-module inline expansion

- **arch-meta-v2-p3** — Cross-module `inline` expansion for `ssc link`. Extended `ExportedSymbol` in `lang/ir/Ir.scala` with three new fields (all defaulted for backward compatibility): `isInline: Boolean`, `inlineParamNames: List[String]`, `inlineBodySource: Option[String]`. `InterfaceExtractor` now populates these via a new `extractInlineInfo(d: Defn.Def): Option[(List[String], String)]` helper that checks for `Mod.Inline`, filters out `using` clauses, and captures all regular param names plus the body's `.syntax`. Top-level `inline def`s are handled via a new `topLevelInlineInfo` map that feeds into the `rawExports` builder; nested `inline def`s inside `Defn.Object` are handled via the existing `buildNestedSymbol` path. Linker gains `buildInlineTable` (collects `isInline = true` exports from all modules into a `Map[String, (List[String], String)]`) and `expandInlineSource` (source-level expansion using a parenthesis-counting scanner with string-literal skipping and word-boundary checks). The expansion strategy is lambda-lifting: `f(arg)` → `((p) => body)(arg)`, which is hygienic (no capture, no alpha-renaming needed) and reduces via normal compilation. `rewriteSections` renamed to `expandAndRewriteSections`; the new path runs inline expansion on `CodeBlock.source` before the existing `IrExpr` VarRef rewriter. 5 new tests in `InterfaceExtractorTest` (isInline population for top-level, zero-arg, multi-param, nested) and 11 new tests in `LinkerRewriteTest` (`expandInlineSource` unit tests + end-to-end link test).

## 2026-05-29 — arch-stable-spi-p3 full plugin SPI migration + evalLegacy

- **arch-stable-spi-p3** — Phase 3 of the stable plugin SPI. Added `RemoteCap` capability trait (exposes `remoteHandlers` + `invokeRemoteHandler`) and `PluginNative.evalLegacy` migration helper to `PluginApi.scala`. Inlined `LegacyNativeContext` anonymous class into `PluginContext.fromNative`; removed it as a named type. Migrated all 16 `*Intrinsics.scala` in `runtime/std/` from `NativeImpl { (ctx, args) => }` to `PluginNative.evalLegacy { (ctx, args) => }` (mechanical substitution via script). Fixed oauth-plugin helper method signatures: `OAuthHttp.installRoutes`/`register` now take `ctx: HttpCap`; `OidcHttp.installRoutes`/`register` likewise; `OAuthIntrinsicHelpers.serveAuthServer` takes `ctx: HttpCap`, `makeGuardCurry` takes `ctx: MountCap`; `OidcIntrinsicHelpers.serveOidc` takes `ctx: HttpCap`. Updated private helper method signatures in `HttpIntrinsics`, `StreamsIntrinsics`, `DStreamsIntrinsics`, `McpIntrinsics`, `FrontendIntrinsics`, `PwaIntrinsics`, `RemoteIntrinsics`, `SqlIntrinsics` from `ctx: NativeContext` to `ctx: PluginContext`. Deleted `isStdPluginInterpreterTest` band-aid function from `build.sbt` and the corresponding `Test / unmanagedSources` filter in `backendInterpreter`. Moved 59 plugin-dependent test files (`Mcp*`, `OAuth*`, `Oidc*`, `GraphInterpreterIntrinsicTest`, `MountHandlerTest`, `PubSubTest`, `SqlBlockInterpreterTest`, `TypedHandlerTest`, `TypedRpcBinaryTest`) from `runtime/backend/interpreter/src/test/` to the new proper home `runtime/backend/interpreter-plugin-tests/src/test/scala/scalascript/`; `backendInterpreterPluginTests` now uses standard source layout. Added classpath boundary test to `PluginApiTest` verifying `scalascript.interpreter.Value` cannot be loaded from the plugin-api classpath.

## 2026-05-29 — arch-registry-p3 GitHub Pages registry site generator

- **arch-registry-p3** — Static site generation for the package registry. New `RegistrySiteGenerator` object in `lang/core/imports`: `generate(entries, outputDir)` writes `site/packages/{group}/{artifact}/index.json` (per-package machine-readable JSON with all fields + `install` field), `site/search-index.json` (lunr.js-compatible array of `{ref, name, version, description, body}` documents for client-side indexing), and `site/index.html` (self-contained searchable HTML page with `<table>`, client-side JS filter on name/description/keywords, deprecated row opacity). `packageJson` escapes JSON special chars (`\`, `"`, `\n`, `\r`); `indexHtml` escapes HTML special chars (`&`, `<`, `>`, `"`); deprecated entries get `style="opacity:0.5"` and a `[deprecated]` badge. New standalone `tools/registry-site/generate.sc` scala-cli script (self-contained, no sbt dep): reads `registry/packages.yaml`, generates `registry/site/` with the same 3 outputs. New `registry/site/CNAME` pointing to `registry.scalascript.io`. 16 tests covering all public methods, filesystem output, HTML escaping, JSON structure, empty-list edge cases.

## 2026-05-29 — arch-registry-p4 private registry support

- **arch-registry-p4** — Private registry support. `RegistryClient.effectiveUrl(registryArg)` resolves the URL with priority: CLI `--registry <url>` arg > `registry.url` in `~/.config/scalascript/config.yaml` > built-in default. `registryUrlFromConfig()` reads `registry.url` from `config.yaml` (SimpleYaml). `fetchYaml` now handles `file://` and `file:` URLs (reads directly from the filesystem without HTTP, useful for local mirrors and tests). All three registry commands (`ssc search`, `ssc add`, `ssc info <pkg>`) accept `--registry <url>` and pass it through to `RegistryClient.load`. 9 tests: file:// fetch, URL priority (CLI > config > default), config.yaml read/absent, search on locally-fetched entries.

## 2026-05-29 — arch-registry-p2 ssc search/info/add + RegistryClient

- **arch-registry-p2** — Package registry CLI commands and client. New `RegistryClient` in `lang/core/imports`: `load(url, refresh)` fetches `packages.yaml` from the registry URL (default `https://registry.scalascript.io/packages.yaml`) or returns from `~/.cache/scalascript/registry/packages.yaml` when fresh (1-hour TTL); `fetchAndCache` writes cache + timestamp; `search(query, entries)` returns scored matches (name prefix > name contains > desc/keyword); `formatRow` and `formatInfo` produce CLI output. New `ssc search [<query>] [--refresh]` command; `ssc add <name> [<version>]` appends dep entry to `ssclib-manifest.yaml` (creates it if absent); `ssc info <group>/<artifact>` now also dispatches to registry info when the argument is a package name (no file extension). 14 tests: fetch+cache, TTL, search scoring, format helpers.

## 2026-05-29 — arch-registry-p1 packages.yaml schema

- **arch-registry-p1** — Package registry schema foundation. New `RegistryEntry` case class in `lang/core/imports` models the `packages.yaml` entry format: `name` (required, `<group>/<artifact>`), `version` (required, semver), `description`, `keywords`, `backends`, `url` (allowed schemes: `github:`, `jitpack:`, `dep:`, `https://`), `license`, `author`, `homepage`, `changelog`, `scala-script-version`, `deprecated`. `RegistryEntry.parseAll` returns `Either[List[String], List[RegistryEntry]]`; `validate` checks name format, version semver shape, URL scheme whitelist, and HTTPS homepage requirement. `toYaml` serialises the list back to YAML. New `registry/packages.yaml` seeds 5 first-party packages (`io.scalascript/json`, `http`, `streams`, `actors`, `sql`). 15 schema validation tests including seed file round-trip.

## 2026-05-29 — arch-dsl-hooks-p4 InterpolatorCheckRegistry

- **arch-dsl-hooks-p4** — Compile-time interpolator validation is now extensible. New `InterpolatorCheck` SPI trait (`interpolatorName`, `check(parts: List[String]): List[Diagnostic]`) in `runtime/backend/spi`. New `InterpolatorCheckRegistry` in `lang/core/compiler/plugin`: TrieMap-backed `register`/`checksFor`/`checkAll`/`registerFrom`; pre-registers `XmlInterpolatorCheck` (xml placeholder validation via `PureMarkupCodec`). `MarkupInterpolatorCheck` now dispatches ALL `name"..."` interpolations through `InterpolatorCheckRegistry.checkAll` rather than hard-checking only `xml`. `Backend.interpolatorChecks: List[InterpolatorCheck]` field (default `Nil`); `BackendRegistry.registerDslHooks` registers checks on backend load. 12 tests: MarkupInterpolatorCheck XML regression (10) + registry discovery + custom check via traversal (2).

## 2026-05-29 — arch-ffi-p4 js/glue.js injection + META-INF/services

- **arch-ffi-p4** — JS glue preamble injection and `META-INF/services` Backend discovery from `.ssclib` glue archives. New `GlueJsPreambleRegistry` (TrieMap-backed, `addPreamble`/`contains`/`preambles`/`isEmpty`/`clear`). `ImportResolver.extractSsclib` now registers `js/glue.js` content in `GlueJsPreambleRegistry` when `manifest.glueJs` is declared; `JsGen.generateRuntime` prepends all registered glue preambles (behind a `// ── glue preambles ──` header) before the standard runtime parts, so library-shipped JS helpers are available to consumer code. `addGlueJarToClasspath` now also calls `BackendRegistry.addPluginJar(jarPath)` after wiring the URLClassLoader, so any `META-INF/services/scalascript.backend.spi.Backend` entries in the glue JAR are picked up by `ServiceLoader` on the next `BackendRegistry.inProcess` scan. 10 tests: `GlueJsPreambleRegistry` unit tests, `ImportResolver` integration (JS preamble populated, no-JS case stays empty, META-INF/services graceful handling), `JsGen.generateRuntime` injection tests (preamble appears before runtime helpers, newline termination, multiple preambles).

## 2026-05-29 — arch-ffi-p3 jvm/glue.jar in .ssclib

- **arch-ffi-p3** — JVM glue JAR support in `.ssclib` archives. `SsclibManifest` gains `glueJvm: Option[String]` and `glueJs: Option[String]`; `parseString` reads them from a `glue: { jvm: ..., js: ... }` YAML map; `toYaml` emits the `glue:` section when non-empty; backward-compatible (manifests without `glue:` parse cleanly). New `GlueClasspathRegistry` (TrieMap-backed): `addJar`/`contains`/`jars`/`clear`. `ImportResolver.extractSsclib` now calls `addGlueJarToClasspath(jarPath)` when `manifest.glueJvm` is defined and the file exists in the extracted archive — the jar is wired into the JVM thread-context `URLClassLoader` (JDK 11 path; degrades gracefully on module-path JDKs) and always tracked in `GlueClasspathRegistry`. `ssc package --lib` gains `--jvm-glue <jar>` and `--js-glue <js>` flags: the external file is packed into the archive at `jvm/glue.jar` / `js/glue.js` and the generated manifest records the path. 8 unit + integration tests.

## 2026-05-29 — arch-dsl-hooks-p3 built-in SourceLanguage plugins

- **arch-dsl-hooks-p3** — Built-in fenced languages now route through SourceLanguage SPI. Added bundled SourceLanguage implementations for `javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware `transaction` alongside existing `scala`, `html`, and `css`; added a backward-compatible attrs-aware `compileBlock` overload for fence attrs such as `@db` and `@side`; Normalize consults `SourceLanguageRegistry` for built-ins and keeps core-only SQL/transaction fallbacks. CLI registry/dispatch tests cover all built-ins plus legacy SQL normalize/capability regressions.

## 2026-05-29 — arch-dsl-hooks-p2 PreprocessorRegistry

- **arch-dsl-hooks-p2** — Extensible preprocessor pipeline for ScalaScript → Scala source transformation. New `Preprocessor` SPI trait in `runtime/backend/spi`: `name`, `priority: Int = 100`, `apply(String): String`. New `PreprocessorRegistry` in `lang/core/parser`: `TrieMap`-backed `register`/`lookup`/`all`/`applyAll`/`registerFrom`; sorted by `(priority, name)` ascending. All 6 built-in preprocessors pre-registered at `PreprocessorRegistry` init time via `private[parser]` method references: `inline-imports` (10), `list-literals` (20), `slash-imports` (30), `remote-defs` (40), `effects` (50), `extern` (60). `Parser.preprocessForScala` replaced with `PreprocessorRegistry.applyAll(code)`. `Backend.preprocessors: List[Preprocessor]` field (default `Nil`) allows backends to contribute custom preprocessors; `BackendRegistry` calls `PreprocessorRegistry.registerFrom(backend)` on load. Plugin preprocessors registered at priority ≥100. 10 tests: built-in registrations, priority ordering, custom preprocessor applyAll, backend `registerFrom`, Parser integration.

## 2026-05-29 — arch-lib-p6 precompiled ssclib interfaces

- **arch-lib-p6** — `ssc package --lib --precompile` now writes `.scim` interface artifacts under `ir/` inside `.ssclib` archives. Added `ssc check-compat old.ssclib new.ssclib`, which compares public symbol shapes from packaged `.scim` interfaces and falls back to deriving interfaces from `src/*.ssc` when needed. `SsclibPackageCliTest` covers the precompiled archive layout and removed-symbol detection.

## 2026-05-29 — arch-dsl-hooks-p1 InterpolatorRegistry

- **arch-dsl-hooks-p1** — `InterpolatorRegistry` extension point for typed string interpolators. New `InterpolatorImpl` SPI trait in `runtime/backend/spi`: `name`, `returnTypeName`, `requiredFeatures`, `jvmEmit(parts, args)`, `jsEmit(parts, args)`. New `InterpolatorRegistry` in `lang/core/compiler/plugin`: `TrieMap`-backed `register`/`lookup`/`all`/`registerFrom`; built-in `HtmlInterpolator` and `CssInterpolator` pre-registered at init. `Backend.interpolators: List[InterpolatorImpl]` field (default `Nil`) allows backends to ship custom interpolators. Integration in all five sites: Typer now falls through to `InterpolatorRegistry.lookup(prefix).map(impl => SType.named0(impl.returnTypeName)).getOrElse(SType.String)`; JvmGen adds `blockContainsRegisteredInterpolator` + `termContainsRegisteredInterpolator` detectors and a `Term.Interpolate` guard-match that calls `impl.jvmEmit`; JsGen dispatches both direct and CPS `Term.Interpolate` arms through `impl.jsEmit`; CapabilityCheck scans source for registered interpolator prefixes and adds `impl.requiredFeatures` to detected capabilities. 18 tests: 11 in `InterpolatorRegistryTest` (registry, typer), 7 in `DslHooksCodegenTest` (JvmGen + JsGen dispatch, fallback).

## 2026-05-29 — arch-lib-p5 transitive deps + lockfile

- **arch-lib-p5** — Transitive dependency resolution and lock file for `.ssclib`-based dependencies. New `SemVer` object with numeric segment comparison (`compare`, `max`). New `SscLibLock` case class (`ssc-lock.yaml`): `locked: Map[org/name → version]`; `parseString`/`toYaml`/`read`/`write`/`withResolved` API; alphabetically-sorted YAML output. `ImportResolver.resolveAll(depUris, strictDeps)` performs BFS using internal `ResolutionState` (mutable `resolved` LinkedHashMap + `visiting` HashSet): cycle detection throws `"Dependency cycle detected"`, version conflicts are resolved by latest-wins (or hard-error under `--strict-deps`); `prefetchTransitiveDeps` reads `dependencies:` from each fetched manifest and recurses. New `ssc update` CLI command reads all `dep:` imports from source files, calls `resolveAll`, and writes `ssc-lock.yaml`. 19 unit + integration tests: 14 in `SscLibLockTest` (lock YAML, SemVer), 5 in `TransitiveResolutionTest` (mock HTTP server verifying single dep, transitive pull-in, latest-wins, strict-deps error, cycle error).

## 2026-05-29 — arch-stable-spi-p2 plugin capability bridge

- **arch-stable-spi-p2** — `scalascript-plugin-api` now exposes `HttpCap`, `WsCap`, `DbCap`, `StorageCap`, `ValidateCap`, `MountCap`, `PluginContext.fromNative`, `LegacyNativeContext`, and `PluginNative.eval` for typed native intrinsic implementations. Representative intrinsics in `json-plugin`, `http-plugin`, and `auth-plugin` now use the typed bridge, and `auth-plugin` `verifyPassword` now delegates to `Password.verify`.

## 2026-05-29 — arch-lib-p4 ssclib format + ssc package --lib

- **arch-lib-p4** — `.ssclib` ZIP archive format for ScalaScript libraries. New `SsclibManifest` case class in `lang/core/.../imports/` with `parseString(yaml)` / `toYaml(m)` methods; fields: `name`, `version`, `entry`, `scala-script-version`, `dependencies`, `description`, `author`. `ImportResolver.resolveDep` extended: checks new `~/.cache/scalascript/libs/<org>/<name>/<version>/` extracted-lib cache alongside the existing `.ssc` cache; when fetching from dep-sources tries `.ssclib` before `.ssc`; `extractSsclib` unpacks the ZIP and returns the manifest entry-point path. New `ssc package --lib` CLI command (in `packageLib`): reads or auto-generates `ssclib-manifest.yaml`, walks `src/`, packs manifest + sources into a `.ssclib` ZIP; flags: `--manifest`, `-o`/`--output`. `PluginSpec` moved from bare `build.sbt` to `project/PluginSpec.scala` so it is visible in all sbt build segments (worktree compilation fix); `backendInterpreterPluginTests.dependsOn` uses `ClasspathDependency(p.project, None)` for correct type. 11 manifest unit tests in `SsclibManifestTest`.

## 2026-05-29 — arch-build-registry-p2 runtime PluginRegistry facade

- **arch-build-registry-p2** — Added `PluginRegistry`, `PluginMeta`, and `PluginSource` to backend SPI; made `BackendRegistry` implement the facade while preserving existing APIs; added `RemotePluginInstaller` for path/URL/registry `.sscpkg` installs; routed CLI plugin install and `pkg:` auto-install through the shared installer; extended `BackendRegistryTest` for facade/classpath install coverage.

## 2026-05-29 — arch-ssc-new-p3 standalone install docs

- **arch-ssc-new-p3** — Added `docs/getting-started-standalone.md`, updated user-guide installation and community plugin docs, and changed root `install.sh` to require `--dev` for monorepo staging. Plain `./install.sh` now prints standalone install options (`cs`, Homebrew, curl) instead of starting a local sbt build.

## 2026-05-29 — arch-ssc-new-p2 extra templates and standalone install inputs

- **arch-ssc-new-p2** — Added bundled `dsl`, `web-app`, and `wasm-app` templates for `ssc new` (`plugin` was already present), a repo-local Homebrew formula source at `releases/homebrew/ssc.rb`, and a lightweight `releases/install.sh` curl/wget installer for GitHub Release `ssc.jar` downloads. Updated scaffolding docs and expanded `NewProjectTest` coverage for the new templates.

## 2026-05-29 — arch-build-registry-p1 PluginSpec registry in build.sbt

- **arch-build-registry-p1** — `case class PluginSpec(id, project, jarPrefix)` + `lazy val allPlugins: Seq[PluginSpec]` registry introduced in `build.sbt`. Three of the five scattered plugin lists are now derived from `allPlugins`: `pluginJarPrefixes` Set in `installBin`, `backendInterpreterPluginTests.dependsOn`, and the root aggregate (via a separate `.aggregate(allPlugins.map(_.project: ProjectReference): _*)` call). `pluginPkgs` inside `installBin` stays explicit (sbt task-macro constraint) with a comment. Also fixes missing `deployPlugin`, `paymentRequestPlugin`, and `paymentsPlugin` from `installBin` pluginPkgs. The registry has 19 entries covering all std plugins.

## 2026-05-29 — arch-ssc-new-p1 app/lib scaffolds and Coursier channel

- **arch-ssc-new-p1** — `ssc new` now defaults to the `app` template while preserving explicit `--template plugin`. Added bundled `app` and `lib` templates under CLI resources, `releases/coursier.json` as the repository-side Coursier channel descriptor, documentation for the existing `sbt cli/assembly` fat JAR path, and `NewProjectTest` coverage for app/lib/plugin scaffolds. Also fixed freshly landed `JsonCodec` and `PluginSpec` build compatibility blockers found while verifying the CLI module.

## 2026-05-29 — arch-stable-spi-p1 scalascript-plugin-api module

- **arch-stable-spi-p1** — New `runtime/scalascript-plugin-api/` sbt subproject (`scalascript-plugin-api`). Stable plugin surface: `PluginValue` (opaque `Any`), `PluginError` (opaque `Throwable`), `PluginComputation` (opaque `Any`), `JsonCodec` (wraps `ujson.Value`), and `type PluginContext = NativeContext`. All 18 std plugin projects (`jsonPlugin`, `frontendPlugin`, `swingPlugin`, `requestPlugin`, `authPlugin`, `oauthPlugin`, `fetchPlugin`, `graphPlugin`, `sqlPlugin`, `httpPlugin`, `wsPlugin`, `mcpPlugin`, `remotePlugin`, `pwaPlugin`, `streamsPlugin`, `dstreamsPlugin`, `deployPlugin`, `paymentRequestPlugin`, `paymentsPlugin`) and the root aggregate gain `pluginApi` as a dependency. 11 tests in `PluginApiTest`.

## 2026-05-29 — arch-sbt-plugin-p4 developer tools and BSP setup

- **arch-sbt-plugin-p4** — Added interactive `SscRunner` support plus `sscRepl`, `sscRun`, `sscWatch`, and `sscBspSetup` tasks to the sbt plugin. `BspIntegration` emits `.bsp/scalascript.json` pointing editors at `ssc lsp --project <project>`. Added scripted `dev-tools` coverage for REPL/run/watch command wiring and BSP file emission.

## 2026-05-29 — arch-ffi-p2: @interpreterUnsupported + cross-backend parity

- **arch-ffi-p2** — Added `@interpreterUnsupported` annotation support in `StatRuntime`: extern defs annotated with `@interpreterUnsupported` register a `NativeFnV` that throws an `InterpretError` with a descriptive message when called from the interpreter. Custom message: `@interpreterUnsupported("msg")`. Default message includes the def name. Error is raised at call site, not at definition. Combined `@jvm`+`@interpreterUnsupported` works: the annotation wins in interpreter mode. Cross-backend parity tests verify the same `.ssc` source with `@jvm`+`@js` produces correct output on both JVM and JS backends including `$N` argument substitution. 9 tests.

## 2026-05-29 — arch-sbt-plugin-p3 sbt test integration

- **arch-sbt-plugin-p3** — Added `sscTestResultsDir`, `Test / sscTest`, and `SscTestFramework` JUnit XML parsing to the sbt plugin. `sscTest` scans `src/test/scalascript`, runs `ssc test <dir> --backend <id> --output-format junit-xml --output <dir>`, maps failures/errors to sbt `TestResult`, and is wired into `Test / test`. Added scripted `test-integration` coverage for `sbt test`.

## 2026-05-29 — arch-sbt-plugin-p2 sscLink and packageBin

- **arch-sbt-plugin-p2** — Added `sscLinkedJar` and `Compile / sscLink` to the sbt plugin. `sscLink` depends on `sscCompile`, runs `ssc link --backend <id> --output <jar> <artifact-dir>` through `SscRunner`, skips cleanly when there are no ScalaScript artifacts, and is wired into `Compile / packageBin`. Added scripted `package-link` coverage where `sbt package` produces the configured linked JAR.

## 2026-05-29 — arch-lib-p2 @internal access control

- **arch-lib-p2** — `@internal` cross-package access control. `ExportedSymbol.isInternal: Boolean = false` field added (backward-compatible, derives ReadWriter). `InterfaceExtractor` detects `@internal` annotations via `Mod.Annot` on top-level `Defn.Def/Val/Var/Class/Object/Trait` and sets `isInternal = true` in the emitted interface. Typer builds `internalImportedNames: Set[String]` from all `importedInterfaces` entries where `isInternal = true`; at `Term.Name` call sites, if the name is in `internalImportedNames`, a hard `TypeError` is emitted with a message naming the `@internal` symbol. 8 tests in `TyperInternalAccessTest`.

## 2026-05-29 — arch-ffi-p1 @jvm / @js inline FFI annotations

- **arch-ffi-p1** — Tier-1 inline FFI annotations for `extern def`. `@jvm("expr")` on an extern def causes `JvmGen` to emit the expression as the method body (instead of skipping); `$0`/`$1`/… placeholders are substituted with the parameter names. `@js("expr")` causes `JsGen` to emit the expression as a JS function body. `@jvm`-only extern defs (no `@js`) get an error-throwing JS stub so the failure is explicit rather than silent. `Diagnostic.JvmOnlyExternDef` added to the `Diagnostic` enum; `CapabilityCheck.jvmOnlyExternDefs` detects `@jvm`-without-`@js` extern defs in modules compiled for the JS family and emits the diagnostic. 13 tests in `FfiAnnotationTest`.

## 2026-05-29 — arch-lib-p3 namespace collision detection

- **arch-lib-p3** — Import namespace collision detection. When two imported modules export the same top-level name the Typer emits a warning; `--strict-namespaces` flag on `ssc check` turns warnings into hard errors. `NamespaceCollision(name, aliasA, aliasB)` case class with a human-readable `.message` suggesting the qualified import form. `InterfaceScope.detectCollisions` accepts a `suppressed: Set[(String, String)]` to silence known collisions. Qualified import syntax `[Name from Module](path)` parses into `ImportBinding(fromModule = Some("Module"))` and suppresses the collision for that name. `Typer` gains `strictNamespaces` and `suppressedCollisions` parameters; `typeCheckWithCollisionWarnings` and `typeCheckStrictNamespaces` companion factories added. 12 tests in `NamespaceCollisionTest`.

## 2026-05-29 — arch-sbt-plugin-p1 source convention and sscCompile

- **arch-sbt-plugin-p1** — Extended the existing `ScalascriptInteropPlugin` with `SscRunner`, `sscSourceDirectories`, `sscBackend`, `sscExtraArgs`, config-scoped `sscArtifactDir`, `Compile / sscCompile`, and `Compile / compile` wiring. `sscCompile` discovers `.ssc` files under `src/main/scalascript`, runs `ssc build --incremental <src-dir> --artifact-dir <target>/ssc-artifacts --backend <id>`, and returns generated artifact files. Added scripted `compile-sources` coverage while preserving existing facade scripted tests.

## 2026-05-29 — arch-lib-p1 @deprecated / @experimental annotation warnings

- **arch-lib-p1** — Added `@deprecated` and `@experimental` call-site warnings to the Typer. `fatalWarnings: Boolean` parameter on `Typer`; `TypeError.isWarning` flag; `TypedModule.hasErrors` ignores warnings, `TypedModule.warnings` returns the warning-only subset. `Typer.typeCheckFatalWarnings` factory promotes all warnings to errors (`--fatal-warnings` semantics). Annotation extraction from `Mod.Annot` mods on `Defn.Def`: `@deprecated("msg", since = "v")` populates `deprecatedDefs`; `@experimental("notice")` populates `experimentalDefs`; both emit warnings at every `Term.Name` call site. 11 tests in `TyperAnnotationWarningsTest`.

## 2026-05-29 — arch-distribution-p4 community plugin starter template

- **arch-distribution-p4** — Added bundled `templates/plugin` resources, `NewProject` scaffolding, `ssc new <name> --template plugin`, a GitHub Actions release workflow template, and `docs/specs/community-plugins.md`. The template emits Backend SPI skeleton code, ServiceLoader registration, `.sscpkg` manifest/source files, and release packaging steps.

## 2026-05-29 — arch-distribution-p2 Coursier and JitPack dependency resolver

- **arch-distribution-p2** — Added `MavenDepResolver` for Maven-shaped `dep:group:artifact:version` / `dep:group::artifact:version` imports via Coursier command wiring, preserved legacy `dep:org/name:version` dep-sources behavior, and added `JitpackResolver` as a thin Coursier repository wrapper. Added deterministic fake-Coursier tests over a local Maven-layout fixture.

## 2026-05-29 — arch-distribution-p1 GitHub release dependency resolver

- **arch-distribution-p1** — Added `DepResolver`/`DepSpec` SPI, content-addressed `DepCache`, built-in `GithubReleaseResolver` for `github:owner/repo@tag[#asset]`, and `ImportResolver` dispatch for `github:` imports with `sha256:` suffix pins. Added mock GitHub API coverage for release lookup, asset download, cache reuse, and pin verification.

## 2026-05-29 — v1.63.8 dynamic code ops hardening

- **v1.63.8-dynamic-code-ops-hardening** — `WorkerBundle` with `verify(zipBytes, hmacSecret, knownDeps)` — SHA-256 hash check + HMAC-SHA256 signature verification + dep set check — and `sign(zipBytes, hmacSecret, keyId)` that injects signature into `manifest.json`; `BundleManifest`/`BundleVerificationError`/`VerifiedBundle` types; `parseManifest` regex-based JSON extractor. `ArtifactCache` — content-addressed LRU store (ConcurrentHashMap + access-ordered LinkedHashMap with `removeEldestEntry` eviction from both maps); global singleton `ArtifactCache.global(128)`. `AuditLog` — concurrent ring-buffer (ConcurrentLinkedDeque, capacity-bounded) with `record`/`recent`/`toJson`; `AuditEntry` case class; `AuditEvents` string constants; global singleton `AuditLog.global(1000)`. `CircuitBreaker` — per-workerId state (failures, openedAt, open); opens at `threshold` consecutive failures; auto-resets after `resetAfterMs`; `allOpen`/`failureCount` query; global singleton. `ResourcePolicy` — `maxCpuMs`/`maxMemoryMb`/`maxThreads`/`maxQueueDepth` (0 = unlimited); `parse(Map[String,Any])`; `LoadTracker` — per-workerId `AtomicLong` counter with `acquire`/`release`/`activeCount` for load-shedding gates; global singletons. Interpreter integration: `shipWorker(workerId, zipBase64)`, `unloadWorker(workerId)`, `rollbackWorker(workerId)`, `workerStatus(workerId)`, `workerList()` actor globals in `ActorGlobals`/`ActorInterp`; wire messages `bundle_load`/`bundle_unload`/`bundle_rollback` recorded to per-node ring buffer; `GET /_ssc-cluster/audit` and `GET /_ssc-cluster/workers` routes registered on `startNode`. 24 new tests (148 total in `deployPlugin`).

## 2026-05-29 — v1.62.8 Wire binary compatibility and evolution

- **v1.62.8-wire-compatibility** — `WireSchemaId.hash` (SHA-256 truncated to 16 hex, `sha256:` prefix); `CompatibilityResult` enum (Identical/Compatible/Unknown/Incompatible); `WireSchemaRegistry` (directional `registerEvolution` + `check`); `WireCompatibilityGuard.check` (envelope schema-id guard with `requireSchemaId`/`allowUnknown` flags); `WireGoldenVectorRegistry` (Base64-stored cross-version decode test vectors + `byFormat`). Evolution policy: additive field additions are automatically forward-compatible because field-by-field decoders ignore unknown keys. 21 tests.

## 2026-05-29 — v1.62.7 Wire security and operations

- **v1.62.7-wire-security-ops** — Five modules in `backend/wire/.../security/`: `WireIntegrity` (HMAC-SHA256 sign/verify via `javax.crypto.Mac`, constant-time compare, `attachHmac`/`verifyEnvelope`); `WireCompression` (gzip compress/decompress via `java.util.zip.*`, ratio utility, stub for unsupported algorithms); `WireSession` (per-connection sequence counter + `stamp(env)`) + `WireReplayWindow` (sliding BitSet, configurable window size, `checkAndRecord`/`checkEnvelope`); `WireTlsConfig` (keystore/truststore/ciphers/protocols data type + `fromMap` front-matter parser); `WireMetrics` (`LongAdder` counters for frames/bytes/errors/hmac/replay/chunked/compressed + immutable snapshot + reset) + `WireDebug` (`summary()` one-liner, `dump()` multi-line pretty-print). 37 tests.

## 2026-05-29 — v1.62.4c Dataset binary actor frames

- **v1.62.4c-dataset-actor-binary-frames** — `runDistributedWire`, `runDistributedShuffleWire`, and `DistributedDataset.run/runShuffle` now accept non-JSON `wireFormat` as direct actor-frame selection: MsgPack/CBOR paths send `DatasetWire` envelope bytes for partition, shuffle-bucket, and key-result messages; JSON keeps the existing object-message fallback. Updated dataset wire examples to exercise CBOR.

## 2026-05-29 — v1.62.6 ObjectStore sync binary wire protocol

- **v1.62.6-object-sync-binary** — `ObjectSyncMsg` sealed trait (4 kinds: `PullRequest`, `PullResponse`, `PushRequest`, `PushResponse`) + value types (`SyncChange`, `SyncMutation`, `SyncResult`, `SyncConflict`) with full `WireCodec` instances and `ObjectSyncEnvelope` helpers. Mirrors the generated `/__ssc/sync/<store>/changes` GET and `/__ssc/sync/<store>/push` POST routes. `correlationId` passed through for request/response pairing. JSON/MsgPack/CBOR round-trips, 31 tests.

## 2026-05-29 — v1.62.5 DStream native wire protocol

- **v1.62.5-dstream-native-wire** — `DStreamMsg` sealed trait (7 kinds: `ElementBatch`, `Watermark`, `Trigger`, `SideInput`, `SideOutput`, `CheckpointMetadata`, `DStreamError`) with full `WireCodec[DStreamMsg]` instances and `DStreamEnvelope` helpers for building/decoding `WireEnvelope(protocol="dstream")`. `TriggerKind` enum (EventTime/ProcessingTime/CountBased/AfterWatermark) with `WireCodec[TriggerKind]`. All 7 message kinds round-trip through JSON, MsgPack, and CBOR (58 tests). External Spark/Kafka/Flink/Beam protocols untouched.

## 2026-05-29 — v1.62.4b Dataset runner wire-format boundary

- **v1.62.4b-dataset-runner-binary-wire** — `DistributedDataset.run` and `DistributedDataset.runShuffle` now accept `wireFormat`; non-JSON formats round-trip input and output `DatasetWirePartition` values through `DatasetWire` so runner-facing map/shuffle boundaries are checked under MsgPack/CBOR while preserving the current actor messages. Updated `examples/distributed-dataset-typed-helpers.ssc` to use `wireFormat = "cbor"`. Direct binary actor frames remain tracked in `v1.62.4c`.

## 2026-05-29 — v1.62.4 Dataset binary partition envelopes

- **v1.62.4-dataset-binary-partitions** — Added `DatasetWire`, a typed-data bridge that wraps `DatasetWirePartition` in shared `WireEnvelope(protocol = "dataset")` frames and encodes/decodes partitions as JSON, MsgPack, or CBOR. JSON numbers are preserved exactly via tagged string representation. Large partitions can now be chunked at element boundaries and reassembled using `chunk-id`, `chunk-index`, and `chunk-count` envelope headers. Added focused `DatasetCodecTest` coverage and updated wire/mapreduce/user docs. Runner transport selection remains tracked in `v1.62.4b`.

## 2026-05-29 — v1.63.7 cluster-aware deploy operations

- **v1.63.7-cluster-aware-deploy-ops** — `ClusterTarget` trait extending `DeployTarget` with `seedUrlsFor`, `injectAuthToken`, `emitWorkloadManifest`, `emitHeadlessService`, `emitAutoscaler`; `WorkloadMode` enum (Deployment/StatefulSet/DaemonSet) and `ScalePolicy`; `K8sTarget` now implements `ClusterTarget` — cluster mode emits StatefulSet + headless Service + token Secret bundle via new `K8sManifestGenerator` methods (`statefulSet`, `headlessService`, `tokenSecret`, `hpa`, `clusterBundle`); `HpaConfig`/`AutoscaleTarget.Cpu`/`AutoscaleTarget.Custom` types with YAML parser and HPA YAML emitter using `autoscaling/v2`; `ComposeTarget` generates docker-compose.yml with cluster mode token injection; `TargetFactory` wired for `compose`/`docker-compose` kinds; `RollingStrategy` enum (Rolling/BlueGreen/Canary); `Deploy.rollingCluster` orchestrates drain → deploy → health-check per node; `Deploy.multiRegion` runs regions sequentially checking quorum; `rotateClusterToken(newToken, overlapMs)` broadcasts `{"t":"token_rotate"}` to peers and schedules local commit after overlap window; incoming `token_rotate` wire message accepted and applied; `ClusterRoutesRuntime` auth check extended to accept pending new token during overlap; `clusterConfigSet/Get` persists to `.ssc-cluster-config-<nodeId>.json` JSON file (loaded on `startNode`); `DeployEnvironment` gains `autoscale: Option[AutoscalePolicy]` field parsed from manifest `autoscale:` block. 19 new tests (124 total in `deployPlugin`).

## 2026-05-29 — v1.63.6 stream/actor placement adapters

- **v1.63.6-stream-actor-placement-adapters** — `Source[A].remote(name, policy)` registers a named remote source and SSE route, returns a `RemoteSource[A]`; `remoteSourceLocal(rs, buffer)` retrieves in-process source; `RemoteSource.local(buffer)` / `RemoteSource.distributed` extension methods via `DispatchRuntime` bridges. `DStream[A].remote(name)` runs the dag, collects to local source, and registers SSE route. `ActorGroup.router/sharded/role`, `actorGroupAdd/Remove/Members/Tell`, `proxyActor` — actor groups use `nativeFeatureSet` for mutable member state so successive `actorGroupAdd` calls are cumulative; `proxyActor` implements drain-on-step semantics (proxyFlush) instead of a virtual thread, staying within the cooperative scheduler. `RemoteStreamPolicy`, `SseOverflowPolicy`, `RoutingPolicy` companion constants assembled in `BuiltinsRuntime`; actor group globals wired in `ActorGlobals`. 10 tests: 5 stream (RemoteSourceTest) + 5 actor (ActorGroupTest).

## 2026-05-29 — v1.63.5 cluster runner, worker bundles, handlers route

- **v1.63.5-cluster-runner-worker-bundles** — `ssc cluster run` delegates to `ssc run` with `SSC_CLUSTER_ROLE`/`SSC_NODE_ID`/`SSC_BIND`/`SSC_JOIN_SEEDS`/`SSC_CLUSTER_TOKEN` env vars; `ssc cluster package` creates a zip containing the source file plus `manifest.json` with SHA-256 code identity and registry metadata (remoteHandlers, exportedBehaviors, exportedSources); `ssc cluster handlers` GETs `/_ssc-cluster/handlers` and displays the operation list; `ssc cluster stop` POSTs to drain then step-down. `GET /_ssc-cluster/handlers` is registered automatically on `startNode` in both the interpreter and JVM codegen. Also fixes a pre-existing DAP exhaustivity warning (`Value.OptionV(None)` case).

## 2026-05-29 — v1.63.4f remoteStub API type syntax

- **v1.63.4f-remote-trait-stubs-wire** — `remoteStub[Api](baseUrl)` and `Remote.stub[Api](baseUrl)` now accept a forward-compatible API type argument while returning the path-based `RemoteStub` facade. This lets source code move toward the planned trait-shaped call site without requiring runtime type-argument reflection in the interpreter. Generated trait methods, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4g`.

## 2026-05-29 — v1.63.4e RemoteStub HTTP facade

- **v1.63.4e-remote-trait-stubs-wire** — Added `Remote.stub(baseUrl)` / `RemoteStub` as a lightweight path-based HTTP JSON fallback facade with `function`, `call`, and `tryCall`, all reusing the existing `Remote.http` transport and typed `RemoteCallError` mapping. Added interpreter plugin coverage with an embedded JDK HTTP server and updated docs. Trait-shaped compile-time `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4f`.

## 2026-05-29 — v1.61.7 MessagePack binary artifact format

- **v1.61.7-memory** — Switch `.scim`, `.scir`, and `.scjvm`/`.scjvm-runtime` artifact files to MessagePack binary format (via `upickle.default.writeBinary`/`readBinary`), yielding 5–10× smaller on-disk artifacts and faster serialization. All `*File` write methods now write binary; all `*File` read methods auto-detect format (first byte `{` = legacy JSON, otherwise binary) for backward compatibility with existing artifacts. String-returning write methods (`writeInterface`, `writeJvm`, etc.) are unchanged — still emit pretty-printed JSON for terminal display and test string-manipulation. No behavior change; 247/248 tests pass (1 pre-existing `CrossPlatformSmokeTest` hash-normalization failure unrelated to this change).
  - **Bench delta (v1.61 artifact I/O)**: `.scim` file write/read: ~8× smaller binary vs pretty JSON; `.scjvm` with embedded Scala source: ~6× smaller. Round-trip parse time: ~3× faster for large artifacts (MessagePack vs JSON string parsing).

## 2026-05-29 — v1.61.6 JS preamble sub-capabilities

- **v1.61.6-preamble-split** — Split the ~185 KB monolithic `JsRuntime` preamble into conditional sub-capability blocks. `JsRuntimePart2` refactored into `JsRuntimePart2a` (_show/List/Map/_copy), `JsRuntimeOptics` (Lens/Optional/Traversal/Prism), `JsRuntimePart2b` (_tupleConcat/_dispatch/JSON/Free Monad/fs), and `JsRuntimeSignals` (reactive signals). Six new `Capability` cases added: `HtmlDsl` (Part1b — serve/route/sessions/metrics/TOTP/password), `Jwt` (Part1c — JWT/OAuth2/CSRF), `WsServer` (Part1d — WebSocket/SSE/CORS), `Optics`, `Signals`, `IndexedDb`. `generateRuntime` now assembles from parts based on detected capabilities; `detectCapabilities` extended with text-scan rules for each new capability. `JsRuntime` val retained as full preamble for backward compat (WebServer, existing tests). Pre-existing `JsRuntimeBrowserPatch` test corrected (`mergedInit` vs stale `init`). Hello World bundle: ~50 KB Core-only vs ~185 KB full; HTTP-serve apps get ~127 KB (Core+HtmlDsl+Jwt); optics/signals programs only pay for those sections when used.

## 2026-05-29 — v1.61.5 JS codegen inlining

- **v1.61.5-js-inlining** — Three targeted JS codegen quality improvements: (1) Tuple literals now emit `Object.assign([...], {_isTuple: true})` instead of a three-step IIFE — saves ~20 chars per tuple, one fewer closure allocation per creation (4 emission sites); (2) `Term.While` in statement context emits a direct `while (cond) { body; }` statement without IIFE wrapper — saves ~28 chars per while loop in statement position; (3) Integer `*` skips the `typeof === 'string'` guard when both operands are known integers via `isIntExpr` — saves ~52 chars and one typeof check per int multiply. `PatternRuntime.scala`: remove stale `import Computation.Pure`. No behavior change; 183/184 tests pass (1 pre-existing Choose failure).

## 2026-05-28 — v1.63.4d RemoteRpc typed client bridge

- **v1.63.4d-remote-stubs-async-wire** — Added `RemoteClientDeriver`, which derives generated `RemoteRpc` typed HTTP client metadata from `remoteHandlers:` entries that declare `path:`. This reuses the existing JS/JVM typed-route client codegen for the first remote-stub bridge while preserving explicit `apiClients:`. Updated distributed runtime docs, user guide, README, example notes, and parser coverage. Trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4e`.

## 2026-05-28 — v1.63.4c Remote HTTP JSON client

- **v1.63.4c-remote-stubs-async-wire** — Added explicit `Remote.http[A, B](url)` / `remoteHttpFunction` client calls for remote handler POST HTTP JSON fallback routes. The client posts ScalaScript value JSON, decodes the response, and maps non-2xx/network/decode failures into typed `RemoteCallError` values through `tryCall`. Added embedded JDK HTTP server coverage in `RemotePluginInterpreterTest`; typed `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` are tracked in `v1.63.4d`.

## 2026-05-28 — v1.63.4b Remote source sugar

- **v1.63.4b-remote-sugar-stubs-wire** — Parser now lowers source `@remote(name = ..., path = ...) def` and simple `remote def echo(...)` declarations into `remoteHandlers:` metadata, so annotated/sugared handlers reuse the same interpreter `RemoteHandlerRegistry`, validation, and HTTP JSON fallback from v1.63.4. Updated `examples/remote-registry-rpc.ssc`, docs, and parser coverage. Remaining RPC pieces are tracked as `v1.63.4c-remote-stubs-async-wire`.

## 2026-05-28 — v1.63.4 Remote registries and async RPC base

- **v1.63.4-remote-registries-async-rpc** — Added backend SPI `RemoteHandlerRegistry`, `RemoteHandlerInfo`, and `RemoteCallError`; interpreter now lowers manifest `remoteHandlers:` entries into a local registry and exposes POST HTTP JSON fallback routes for handlers with `path:`. Added `runtime/std/remote.ssc` plus `remote-plugin` intrinsics for `Remote.function`, `remoteCall`, `remoteTryCall`, and `remoteHandlers()`, with typed `Left(RemoteCallError)` results for unavailable handlers. Added `examples/remote-registry-rpc.ssc` and targeted interpreter-plugin tests. Remaining planned pieces are `@remote` / `remote def`, `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec[A]` negotiation.

## 2026-05-28 — v1.63.3 Cluster capability base

- **v1.63.3-cluster-capability-seed-code-identity** — Added backend SPI `Cluster`, `SeedResolver`, and `CodeIdentity`; exposed ScalaScript `ClusterCapability`, `SeedResolver.staticList`, `clusterOf`, `resolveSeeds`, `codeIdentity`, and `assertCodeIdentity`; interpreter now returns cluster snapshots, resolves static/DNS/K8s seed descriptors, computes deterministic SHA-256 code identity, and reports explicit diagnostics for the still-planned Consul resolver. Added typed `cluster:` / `remoteHandlers:` / `remoteSources:` / `remoteBehaviors:` front-matter metadata in AST/IR/`.sscc`, parser validation for missing registry target definitions and missing registry types, and source-level `cluster Demo:` lowering.

## 2026-05-28 — v1.63.2 Typed actors and remote spawn

- **v1.63.2-typed-actors-remote-spawn** — Added typed ScalaScript `ActorRef[M]` / `LocalActorRef[M]` aliases over `Pid`, `ref.tell`, `ref.address`, `ref.isLocal`, `ref.tryLocal`, `ref.publishAs`, `registerBehavior`, and `spawnRemote`. Interpreter actor runtime now handles named behavior spawn via JSON `cluster_spawn` / `cluster_spawn_ack`; JVM lowering now supports bare `setClusterAuthToken(...)`; JVM codegen always emits the effect runtime needed by the inlined Logger facade. Added interpreter coverage, jar-gated two-node CLI remote-spawn smoke, docs, and `examples/actors-typed-remote-spawn.ssc`.

## 2026-05-28 — v1.63.1 Source↔DStream stream bridge

- **v1.63.1-stream-bridge-basic-ops** — Bidirectional bridge between local `Source[A]` and distributed `DStream[A]`: `Source[A].distributed` wraps a local source into a `_dag_source_local` DAG node (dispatched via `DispatchRuntime.dispatchInstanceFallback` → `interp.globals("Source.distributed")` to avoid circular plugin dependency); `DStream[A].local()` materialises the full DAG through DirectRunner and returns a `Source` InstanceV with all BasicStreamOps (map/filter/merge/runForeach/runFold/runToList) wired as NativeFnV fields; `DStream[A].localBounded(maxBytes)` raises `InterpretError` when the approximate byte count exceeds the limit. `runtime/std/streams-bridge.ssc` declares the bridge API and `BasicStreamOps[F[_]]` shared trait. 9 new tests; round-trip `Source(1,2,3).distributed.map(_*2).local.runToList == List(2,4,6)` verified. Also fixes `ActorGlobals` pattern-extractor bug from upstream `b806ef2a` commit (smart constructors `Value.intV`/`Value.doubleV` used in `case` patterns replaced with case-class extractors `Value.IntV`/`Value.DoubleV`).

## 2026-05-28 — Architecture roadmap queue

- **queue-architecture-themes** — Added Architecture & Extensibility roadmap items to `WORK_QUEUE.md` so agents can claim them directly. Official centralized publishing to Maven Central / sbt Plugin Portal remains deferred in `BACKLOG.md`; ScalaScript's own package registry tasks are queued.

## 2026-05-28 — v1.61.4 Pattern-match compilation

- **v1.61.4-pattern-compile** — Compile each `Term.Match` into a `CompiledMatch` handler array cached by AST identity (`IdentityHashMap`). Each handler is `(Value, Env) => Computation | Null`, avoiding `Option` allocation in the hot dispatch path. Fast-path cases: `Pat.Wildcard`, `Pat.Var`, `Lit`, `Pat.Extract` with simple `Var`/`Wildcard` subpatterns (field order lazily cached per type on first match; `FrameMap.one/two/of` for 0–N bindings), `Pat.Alternative`. Complex patterns fall back to `matchPat`. Guard evaluation extracted to `evalGuard` helper. **Benchmarks (median 3 runs):** pattern-match-heavy 6069ms (baseline) → 3960ms (**1.53× vs baseline**, 8% over v1.61.3); arith-loop unchanged at ~4500ms. No behavior change; 115/116 tests pass (pre-existing Choose multi-shot failure).

## 2026-05-28 — v1.61.3 Env overhaul

- **v1.61.3-env-overhaul** — Two targeted hot-path fixes eliminating O(N_globals) overhead per while-loop iteration: (1) While-loop frame now only copies env entries that differ from `interp.globals` (locally-declared vars), shrinking from O(N_globals) to O(N_local_vars) — 2-5 entries instead of 300+; (2) `evalBlock` intercepts ALL `Term.Assign(Name)` to write both `local` and `interp.globals` simultaneously, making the per-statement global refresh a cheap no-op for direct-assignment blocks. **Benchmarks (median 3 runs):** arith-loop 15600ms → 4480ms (**3.5×**); pattern-match-heavy 6070ms → 4300ms (**1.4×**); recursion-tco/fib/tuple-monoid unchanged. No behavior change; 115/116 tests pass (pre-existing Choose multi-shot failure).

## 2026-05-28 — v1.61.2 Computation pure-path elimination

- **v1.61.2-pure-path** — Smart `Computation.map` constructor (skips FlatMap allocation when sub is Pure); all-Pure fast path in `Computation.sequence` (skips N-deep FlatMap chain for pure list operations); `Term.Select` pure-path in `EvalRuntime` (skips FlatMap for field access when receiver is Pure); `Term.Assign` pure-path (skips FlatMap for global-var assignment with pure RHS); `BlockRuntime.evalBlock` pure-paths for local-var assignment and compound assignment. Reduces FlatMap allocations on hot interpreter paths. No behavior change.

## 2026-05-28 — v1.61.1 Interpreter dispatch table

- **v1.61.1-dispatch-table** — Replace flat 300-case `(recv, name, args)` triple-match in `DispatchRuntime` with two-level dispatch: `recv match` selects the per-type handler (one `instanceof` check, O(1)); each handler uses `name match` which Scala 3 compiles as a hashCode-based switch (O(1) average). Extensions early-exit: when `interp.extensions` is empty (the common case), the 7-way `HashMap` probe is skipped on every `dispatch` call. Also fixes `dispatchInstanceFallback` field-access ordering: no-arg field access checked before enum-companion call. No behavior change.

## 2026-05-28 — v1.61.0 Benchmark infrastructure

- **v1.61.0-bench** — Performance measurement framework for v1.61 optimization pass. 8-workload corpus in `bench/corpus/` covering interpreter hot paths (arith-loop, recursion-fib, recursion-tco, pattern-match-heavy, effect-pure, effect-stream, tuple-monoid, hello-world). `bench/run.sc` scala-cli timing harness (median of 7 runs, 2 warmup; invokes `ssc` CLI; `--baseline` flag writes `bench/BASELINE.md`). `runtime/backend/interpreter-bench` sbt submodule with `sbt-jmh` for microbenchmarks (`InterpreterBench.scala`: 6 JMH benchmarks covering all hot-path workloads). `scripts/bundle-size.sh` for tracking JS+JVM generated bundle sizes (gzip-aware; appends date-stamped rows to `bench/BUNDLE_SIZES.md`). `bench/BASELINE.md` placeholder with capture instructions. `bench/BUNDLE_SIZES.md` log. `WORK_QUEUE.md` / `BACKLOG.md` updated with v1.61.0–7 roadmap.

## 2026-05-28 — Distributed runtime spec

- **v1.63.0-distributed-runtime-spec** — New canonical `docs/specs/distributed-runtime.md` merges the placement/remoting plan with the local/distributed cluster lifecycle architecture. Keeps operation names such as `users.get`, code identity, handler/source/behavior registries, worker bundles, `ssc cluster` UX, remote streams, actor remote spawn/proxies/groups, dynamic-code-shipping roadmap, and cluster operations (token rotation, persistent state, rolling upgrades, multi-region, autoscaling), while adopting `! Async`, `BasicStreamOps`, typed `ActorRef[M]`, `Cluster`, `SeedResolver`, cluster-aware deployment, and backlog phases v1.63.1-v1.63.8. Follow-up sync incorporated `docs/cluster-operations.md` details directly into the canonical spec: `rotateClusterToken`, `token_rotate` / `token_rotate_ack`, `clusterConfigSet/Get`, `Deploy.rollingCluster`, `FaultToleranceConfig` lowering, and HPA `HpaConfig`. The older specs now redirect to the canonical document.

## 2026-05-28 — Distributed wire protocol spec

- **v1.62.0-distributed-wire-spec** — New `docs/specs/distributed-wire-protocol.md` planning an opt-in internal wire layer for distributed actors, cluster control, Dataset/MapReduce, native DStream, typed route clients/RPC, WebSocket subscriptions, and object sync. The spec includes JSON fallback, MsgPack and CBOR binary profiles, JS/browser support, same-version-only initial compatibility, negotiation, security, compression, limits, observability, and backlog phases v1.62.1-v1.62.8.

## 2026-05-28 — Coinbase Prime MPC adapter

- **wallet-vault-mpc-coinbase** — `CoinbaseRemoteSigningClient` extending `HttpRemoteSigningClient`; EC P-256 ECDSA request signing (`X-CB-ACCESS-KEY` / `X-CB-ACCESS-TIMESTAMP` / `X-CB-ACCESS-SIGNATURE`); `CoinbaseAuth` (SHA256withECDSA over `timestamp+method+path+body`); `CoinbaseWire` (signing request JSON, hex payload, SECP256K1/ED25519/P256 algorithm names, poll status decoding); `CoinbaseVault` named constructor + `CoinbasePlugin` ServiceLoader; `docs/specs/wallet-vault-mpc.md §Coinbase`; 17 tests including ECDSA signature verification. sbt: `walletVaultMpcCoinbase`.

## 2026-05-28 — Fireblocks MPC wallet vault

- **wallet-vault-mpc-fireblocks** — Fireblocks provider adapter for the shared MPC vault SPI: dedicated sbt subproject, `FireblocksRemoteSigningClient` with RS256 JWT auth + `X-API-Key`, RAW transaction signing request generation, `/v1/transactions/{id}` polling, `FireblocksVault`, `FireblocksPlugin` ServiceLoader entry, `docs/specs/wallet-vault-mpc.md`, `examples/wallet-mpc-fireblocks.ssc`, and 16 mock-HTTP/JWT/wire tests.

## 2026-05-28 — v1.60 Tuple Monoid

- **v1.60.1-tuple-monoid-types** — Type system: `SType.Unit = Tuple(Nil)` (0-tuple as canonical unit); `SType.tupleConcat(t1, t2)` smart constructor (eager flattening, 1-element collapse); `++` infix type operator in `InterfaceScope` parser + `Typer.typeAnnotToSType`; `(A,)` trailing-comma syntax for 1-element tuples; unifier handles 0-tuple identity and 1-tuple transparency. 49 tests in `ParseSTypeTest` (6 new `++` tests, 1-tuple test).

- **v1.60.2-tuple-monoid-values** — Value level + backends: `TupleV ++ TupleV` in `DispatchRuntime` (concat `as ++ bs`, `UnitV` as identity on both sides); JS: `_tupleConcat(a, b)` runtime helper in Core (spreads arrays, sets `_isTuple = true` when both operands are tuples — preserves list semantics for non-tuple arrays); JVM: `_tupleConcat` with `scala.Tuple.fromArray` for tuple operands, `List ++ List` fallback; both `++` codegen paths now route through `_tupleConcat`. 4 interpreter tests + 3 JsGen codegen tests.

- **v1.60.3-tuple-monoid-docs** — Docs: `algebraic-effects.md` §8.3 "Unified runner signature" with `Out(E) ++ (R,)` table covering all 8 built-in effects + the `Out(E) ++ (R,)` derivation formula; `streams.ssc` "Tuple monoid" section explaining `runStream`'s `(Source[A], R)` return as `Out(Stream[A]) ++ (R,)`; `BACKLOG.md` v1.60 section marked complete.

- **v1.60.4-tuple-bareconcat** — 1-tuple ≅ element equivalence at value level: bare (non-tuple) operands treated as 1-tuples in `++`. New dispatch cases in `DispatchRuntime`: `TupleV(as) ++ v = TupleV(as :+ v)`, `v ++ TupleV(bs) = TupleV(v :: bs)`, `bare ++ bare = TupleV(List(v, w))`, and identity `() ++ v = v` / `v ++ () = v` for bare values. JS `_tupleConcat` updated to use `Array.isArray` guard (non-array = bare scalar, wrapped to `[x]` before spread). JVM `_tupleConcat` extended with `Tuple ++ bare`, `bare ++ Tuple`, `bare ++ bare`, and bare-identity cases. 5 new `InterpreterTest` cases + 2 new `JsGenStreamsTest` cases. Docs: `tuple-monoid.md` §2 (1-tuple equivalence subsection), `user-guide.md` Tuples section, `algebraic-effects.md` §8.3, `streams.ssc` tuple monoid section, `streams.md` unified runner subsection.

## 2026-05-28 — Wallet Trezor vault adapter

- **wallet-vault-trezor** — `payments/wallet/vault-trezor/` sbt subproject: `TrezorEthVault` (implements `Vault` SPI; `unlock/lock/getSigner`; `ButtonRequest` auto-ack loop up to 10 retries); `TrezorBridge` trait + `HttpTrezorBridge` (java.net.http, `Origin: https://bridge.trezor.io`); `TrezorSession` (acquire/release with guaranteed release via `transformWith`); `TrezorMessages` (`TrezorDeviceInfo`, `TrezorResponse`, `Bip32.parse`, `TrezorMessageType` constants, `TrezorDeviceFailure`); `MockTrezorBridge` (per-messageType response queues, recorded calls); `enqueueFeatures/PublicKey/EthSignature/Failure` helpers. 29 tests (TrezorBridgeTest 11, TrezorSessionTest 4, TrezorEthVaultTest 14).

## 2026-05-28 — Ledger WebBLE transport (Scala.js)

- **wallet-vault-ledger-bluetooth-js** — `WebBleTransport` implementing `LedgerTransport` for Ledger Nano X / Stax via Web Bluetooth GATT; `BleFraming` with configurable MTU (default 23 bytes); `BrowserBluetoothDevice` live impl; `MockBluetoothDevice` for tests; 12 tests; `docs/specs/wallet-vault-ledger.md §bluetooth-transport` created.

## 2026-05-28 — x402 Cardano Scalus thin-glue wiring

- **x402-cardano-scalus-wire** — `CardanoScalusFacilitator.preprod/mainnet` factory in `x402-facilitator-cardano-scalus` wires `ScalusSettler.asConfigHook` into `CardanoFacilitatorConfig.scalusSettle`; removes the "not yet implemented" stub; 8 new tests total (5 in `CardanoScalusFacilitatorTest` + 3 in `CardanoFacilitatorTest`). Closes last open backlog checkbox in x402 Phase 6.

## 2026-05-28 — v1.59 Bureau (Government Interaction Framework)

- **v1.59.9-bureau-mock** — `gov/bureau-mock/` module: `MockFiscalProvider`/`MockSocialProvider`/`MockRegistryProvider` (in-memory, `succeed` flag, `recorded*` call inspection, `reset()`); `MockBureauProvider` named constructors — `poland()` (PL + all 3 domains), `vat()` (EU/VIES fiscal+registry), `all()` (all domains); `examples/bureau-demo.ssc`. 32 tests.

- **v1.59.8-bureau-scheduler** — `gov/bureau-scheduler/` module: `BureauCalendar` (Polish business day calendar, Meeus/Jones/Butcher Easter algorithm, Corpus Christi, Epiphany); `JobSpec` ADT (OneTime/Recurring/PeriodJob); `SimpleScheduler` (ScheduledExecutorService-backed; runNow/disable/enable; onJobComplete/onJobFailed callbacks). 28 tests.

- **v1.59.7-bureau-eu** — `gov/bureau-eu/` module: `EuViesAdapter` (SOAP checkVat call to EC VIES service; injectable `postSoap`; SOAP fault + HTTP 503/429 handling); `EuRegistryProvider` (RegistryProvider for EU-level VatEU lookups; UnsupportedOperation for non-VatEU ids). 25 tests.

- **v1.59.6-bureau-pl-social** — `gov/bureau-pl-social/` module: `ZusNrbGenerator` (ISO 7064 MOD-97 NRB/IBAN generation; `98 - (BigInt(bban+"252100") % 97)` check digit formula); `ZusContributionCalculator` (2024 ZUS rates; HALF_UP rounding); `PlZusAdapter` (SocialProvider for ZUS PUE REST; KEDU XML ZUA/ZWUA/ZIUA/DRA). 37 tests.

- **v1.59.5-bureau-pl-fiscal-declarations** — `PlDeclarationAdapter` (e-Deklaracje SOAP; JPK_VAT7M/JPK_FA/CIT-8/PIT-36). 19 tests.

- **v1.59.4-bureau-pl-fiscal-ksef** — `PlKsefAdapter` (QES session auth; FA_VAT invoke/poll/fetch/query); `KsefXmlBuilder`; `KsefSessionStore`. 32 tests.

- **v1.59.3-bureau-pl-registry** — 4 adapters (CEIDG/REGON/Biała Lista/KRS); `PlRegistryProvider` orchestrator; injectable HTTP. 58 tests.

- **v1.59.2-bureau-signing** — `gov/bureau-signing/` module: `SigningProvider` SPI (`sign/verify/certificateInfo`); `SignatureFormat` enum (XAdES, PAdES, CAdES, JWS); `SignedDocument`/`VerificationResult`/`CertificateInfo`; `SigningError` sealed hierarchy (KeystoreError, CertificateExpired, UnsupportedFormat, VerificationFailed); `PfxSigningProvider` (PKCS#12 via `java.security.KeyStore`; SHA256withRSA; password-copy-then-zero pattern); `MockSigningProvider` (SHA-256 digest as fake signature, configurable cert info); `SelfSignedCertHelper` (keytool subprocess for test cert generation). `sbt bureauSigning`. 12 tests across PfxSigningProviderTest + MockSigningProviderTest.

- **v1.59.1-bureau-core** — `gov/bureau-core/` SPI module: `CountryCode` opaque type (PL/DE/FR/UA/EU constants + `apply` validator); `LegalForm` enum (13 cases incl. `Other(name)`); `TaxIdentifier`/`TaxIdType`/`TaxId` (NIP, REGON, KRS, PESEL, VatEU, EIN, SIREN, HRB, `Other(country, name)`); `Address` + `BusinessEntity` (with `taxId`/`requireTaxId`); `GovDomain` enum (7 cases); `SubmissionStatus`/`SubmissionResult`/`GovError`/`GovWarning`; `BureauError` sealed hierarchy (9 cases: ApiError, AuthenticationError, SignatureError, ValidationError, MissingTaxId, UnsupportedOperation, RateLimitError, ServiceUnavailable, SubmissionRejected); domain provider traits `CountryProvider`/`FiscalProvider`/`SocialProvider`/`RegistryProvider`/`CustomsProvider`/`StatisticsProvider`/`EnvProvider`; shared fiscal types (`FiscalInvoice` with `Currency`+`ExchangeRate`, `InvoiceLine`, `TaxSummaryLine`, `VatRate`, `TaxDeclaration`, `AuditFile`, `InvoiceFilter`, `InvoiceRef`, `InvoiceSubmissionResult`, `VatVerificationResult`); social types (`ContributionDeclaration`, `EmployeeRecord`, `ContractType`, `DeregistrationReason`, `PaymentReference`, `ContributionParams`, `ContributionBase`, `ContributionCalculation`); registry types (`BusinessRecord`, `RegistrationStatus`, `RegistrationDetails`, `VatPayerStatus`); customs/stats types (`IntrastatReport`, `IntrastatLine`, `TradeFlow`, `StatisticsReport`, `EnvironmentReport`). `sbt bureauCore`. 24 tests in `BureauCoreTest`.

- **v1.51.6-streams-typed** — Type-safe algebraic-effect integration for streams. **Track 1 — type system:** `EffectOp(name: String, args: List[SType])` replaces the plain `Set[String]` in `EffectRow`; all existing effects (`Logger`, `Clock`, etc.) migrate to `EffectOp(name, Nil)` (no behavior change); `Stream[A]` uses 1 type arg; `solveEffectRow` rewritten for element-wise name+args unification; `parseDeclReturnType` now handles `Type.Apply` in effect rows so `! Stream[Int]` parses correctly. **Track 2 — Stream feature completion:** 4 typed ops (`Stream.emit[A]`, `Stream.complete[A]`, `Stream.error[A]`, `Stream.request[A]`); `runStream[A, R](body): (Source[A], R)` canonical algebraic-effects form; interpreter returns `TupleV(List(source, bodyResult))`; JS backend returns `[_makeAsyncStream(...), bodyResult]` with error-path generator; JVM backend returns `(emitted.toList, bodyResult)` tuple; `detectCapabilities` updated in both JsGen and JvmGen to detect all 4 Stream ops. `streams.ssc` externals refreshed. 87 interpreter tests + 24 JS-codegen tests, all passing.

- **v1.51.3-streams-flow-sink** — 10 new `Flow` companion constructors with interpreter intrinsics, `BuiltinsRuntime` companion wiring, and `JsGen` codegen: `Flow.fromFunction(f)`, `Flow.take(n)`, `Flow.drop(n)`, `Flow.flatMap(f)`, `Flow.scan(z)(f)` (curried), `Flow.mapAsync(n)(f)` (curried), `Flow.recover(h)`, `Flow.throttle(rate)`, `Flow.debounce(ms)`. `streams.ssc` extended with complete extern declarations for `Source.tick/unfold/fromCallback`, `Source.scan/onError/cancellable` instance methods, and the full `Sink` + `Flow` companion API. 11 new Flow tests; 564/564 total pass.

- **v1.51.2-streams-js** — JS codegen for the full backpressured streams API. `_makeAsyncStream` in `JsRuntimeAsyncB` extended with 17 new methods: combining (`merge/zipWith/broadcast/balance/groupBy/mergeSubstreams`), advanced (`scan/onError/cancellable/buffer/throttle/debounce/mapAsync/recover/mapError`), routing (`async to(sink)/via(flow)`). New `genExpr` cases: `Source.tick(ms)` → infinite `while(true)/setTimeout` async iterator; `Source.unfold(seed)(f)` → curried nested-apply lowering with `_None/_Some/_t[0]/_t[1]`; `Source.fromCallback(register)` → push-via-array pattern; `Sink.foreach(f)/fold(z)(f)/ignore/toList` → run-object literals; `Flow.map(f)/filter(p)` → apply-object literals. `detectCapabilities` now adds `Async` when any stream API is referenced so `_makeAsyncStream` is always emitted with stream-using modules. 20 `JsGenStreamsTest` code-shape tests (no node execution required), all passing.

- **v1.51.1-streams-source-core** — 6 new Source operators for the interpreter. Instance methods: `scan(z)(f)` (running aggregate, no initial-value emission); `onError(f)` (side-effect on error, elements pass through on success); `cancellable()` (returns `(Source, cancelFn: () => Unit)` via an `AtomicBoolean`-guarded forwarding VT). Companion factory methods wired in both `StreamsIntrinsics.table` and `BuiltinsRuntime` Source companion assembly: `Source.tick(ms)` (infinite Unit source with configurable delay); `Source.unfold(seed)(f)` where `f :: s → None | Some((nextState, emitValue))`; `Source.fromCallback(register)` (push-based; `register` receives a callback that puts into a bounded queue). 12 new tests, 68/68 total pass.

- **v2.0-cross-platform-smoke** — Cross-platform portability for the v2.0 artifact pipeline. `InterfaceExtractor.normalizeLineEndings(bytes)`: strips `\r\n` and bare `\r` to `\n` (fast-path: no allocation when no CR present). `InterfaceExtractor.sourceFileHash(bytes)`: normalizes then SHA-256 — used for all `.ssc` source-file hash computations. `ModuleGraph.isStale/isJvmStale/isJsStale`: switched from `sha256` to `sourceFileHash`; `extract()` likewise. A `.ssc` file checked out with CRLF on Windows now hashes identically to the LF variant, making `.scim`/`.scjvm`/`.scjs` artifacts fully cross-platform-portable. `CrossPlatformSmokeTest`: 13 tests covering normalization edge-cases, `extract()` CRLF/LF stability, `os-lib` path-separator portability (2 cases), concurrent writes to distinct dirs, concurrent same-path last-write-wins. `docs/specs/v2.0-scale-benchmark.md §Cross-platform smoke` updated.

- **wallets-metamask-js** — Added `x402ClientJs` Scala.js browser wallet helper: `Wallets.metaMask(network)` connects through `window.ethereum`, validates EIP-155 chain id, signs EIP-712 via `eth_signTypedData_v4`, and has 7 Node-backed tests with stubbed MetaMask provider.

- **oauth-par** — PAR (RFC 9126 Pushed Authorization Requests). `OAuthRoutes.handlePar` — new `POST /par` endpoint: validates client + redirect_uri, stores `PushedAuthRequest` (TTL = `parRequestTtlSeconds`, default 90s), returns 201 with `request_uri` (urn:ietf:params:oauth:request_uri:<nonce>) + `expires_in`. `OAuthRoutes.handleAuthorize` extended: resolves `request_uri` query param via `AuthServer.parRequests.consume` (single-use, expiry-checked), overlays stored params as effective query; rejects direct params when `AuthServerConfig.parRequired = true`. New types: `PushedAuthRequest`, `PushOutcome` (Pushed/Error), `PushedAuthRequestStore` trait, `InMemoryPushedAuthRequestStore`. `AuthServer.pushAuthorizationRequest` validates client + redirect_uri, generates URN, saves record. `metadataJson` always includes `pushed_authorization_request_endpoint`; adds `require_pushed_authorization_requests: true` when `parRequired`. 27 tests in `OAuthPARTest`.

- **oauth-dpop** — DPoP (RFC 9449) sender-constrained tokens. New `DPoP` object: `verifyProof(proofJwt, htm, htu, ...)` validates DPoP proof JWTs (RS256 + ES256; `typ=dpop+jwt`, alg check, JWK extraction + signature verify, `htm`/`htu` binding, `iat` freshness, `jti` single-use via `InMemoryJtiStore`, optional `nonce` + `ath`); `jwkThumbprint(jwk)` (RFC 7638 SHA-256); `accessTokenHash(token)` (SHA-256 `ath`). `AuthServer.issueToken` gains `dpopJwkThumbprint: Option[String]` — injects `cnf.jkt` + sets `token_type=DPoP`. `OAuthRoutes.handleToken` extracts `DPoP` request header, validates via `as.dpopJtiStore`, returns 400 `invalid_dpop_proof` on failure. `OAuthGuard.check` gains `requestMethod`/`requestUrl`/`dpopJtiStore` params — validates `cnf.jkt`-bound tokens against the DPoP proof, backward-compatible when params absent. `AuthServerConfig.dpopNonceLifetimeSeconds`; script API `dpopNonce` field wired. 36 tests in `OAuthDPoPTest`.

## 2026-05-27

- **spark-lakehouse-l4-hudi** — Apache Hudi lakehouse support (L.4). `SparkGen.DefaultHudiVersion = "0.15.0"`; `lakehouseConfigs` extended with Hudi branch: `spark.serializer=KryoSerializer`, `spark.sql.extensions=HoodieSparkSessionExtension` (merged comma-separated with Delta/Iceberg values), `spark.sql.catalog.spark_catalog=HoodieCatalog`; `genModule` emits `//> using dep "org.apache.hudi:hudi-spark3.5-bundle_2.13:0.15.0"` dep; `examples/spark-lakehouse-hudi.ssc` (write/read/upsert round-trip); `docs/specs/spark-lakehouse.md §L.4` updated; 9 new `SparkGenTest` tests; duplicate Iceberg test block removed from test file.

- **spark-lakehouse-l3-iceberg** — Apache Iceberg lakehouse support (L.3). `SparkGen.DefaultIcebergVersion = "1.5.2"`; `IcebergFormatPattern` (case-insensitive `.format("iceberg")`); `lakehouseConfigs` extended to emit 5 Iceberg config pairs (`spark.sql.extensions=IcebergSparkSessionExtensions`, `spark.sql.catalog.spark_catalog=SparkSessionCatalog`, `spark_catalog.type=hive`, `spark.sql.catalog.local=SparkCatalog`, `local.type=hadoop`); `genModule` emits `//> using dep "org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:1.5.2"` dep; Delta+Iceberg `spark.sql.extensions` merged comma-separated by existing `lakehouseConfigs` groupBy logic; `examples/spark-lakehouse-iceberg.ssc` (write/read, time-travel `snapshot-id`, `MERGE INTO` via SQL extension); `docs/specs/spark-lakehouse.md §L.3` updated; 11 new `SparkGenTest` tests.

- **v1.58-compliance-provider** — AML/KYC/sanctions compliance provider SPI + adapters. `payments/compliance/` SPI: `ComplianceProvider` trait (`screenAml/verifyKyc/checkSanctions/getStatus/fullReport`); `BlockchainComplianceProvider` extends it with `screenTransfer`; `ComplianceEntity/BlockchainAddress/TransferDirection/RiskLevel/ComplianceStatus/AmlResult/KycResult/SanctionsResult/TransferRiskResult/ComplianceReport` model; `ComplianceError` sealed hierarchy (`CheckFailed/EntityRejected/UnsupportedCheck/RateLimitExceeded/ProviderError`); 24 SPI tests. `payments/compliance-complyadvantage/`: POST `/searches`, `Token` auth, fuzz search, risk_level mapping (low→Approved/medium→ManualReview/high|very_high→Rejected); 20+ tests. `payments/compliance-chainalysis/`: POST `/api/kyt/v2/transfers` + GET `/api/risk/v2/entities/<addr>`, `Token` auth, 0–100 riskScore, no-address fallback, verifyKyc always Approved; 19 tests. `payments/compliance-mock/`: configurable status per check type, named constructors (`allApproved/allRejected/manualReview/sanctionsHit/highRiskTransfer`); 21+ tests. 4 sbt subprojects in root aggregate. Spec: `docs/specs/compliance-provider.md`.

- **v1.57.1-payment-rails-australia-npp** — Australia NPP (New Payments Platform / PayID) adapter: `runtime/std/payments-au-npp/` subproject (`AuNppProvider`, `AuNppApi`, `AuNppWebhookReceiver`, `AuNppPlugin`); PayID proxy resolution (mobile/email/ABN → BSB+account via aggregator REST); ISO 20022 pacs.008 JSON envelope to aggregator; AUD-only enforcement (`UnsupportedCurrency` error); BSB+account fallback when PayID not present; `BankAccount.bsbNumber` additive field; `BankRailsEvent.AuNppCredited/AuNppReturned`; `BankRailsError.NppPayIdNotFound/UnsupportedCurrency`; HMAC-SHA256 `X-NPP-Signature` webhook; irrevocable cancel guard; `docs/specs/payment-rails-apac.md §AU_NPP`; 35+ tests.

- **v1.58-tax-provider** — Tax calculation SPI + three adapters. `payments/tax/` SPI module: `TaxProvider` trait (`calculateTax/validateTaxId/getSupportedJurisdictions`); `TaxRequest/TaxQuote/TaxedLineItem/TaxAddress/TaxLineItem/JurisdictionTax/TaxIdValidation/Jurisdiction` model; `TaxError` sealed hierarchy (`TaxCalculationFailed/TaxIdValidationFailed/UnsupportedJurisdiction/TaxProviderError`); `TaxMoneyConverter` utility (`totalTax/totalWithTax/effectiveTaxRate`); 20 SPI tests. `payments/tax-stripe/` — Stripe Tax Calculations API v1: form-encoded POST `/v1/tax/calculations`, Basic auth (sk_... as username), idempotency key header, format-only `validateTaxId`, 19 supported jurisdictions; 18 tests. `payments/tax-avalara/` — Avalara AvaTax REST v2: JSON POST `/api/v2/transactions/create`, Basic `accountNumber:licenseKey` auth, `X-Avalara-Client` header, GET `/api/v2/taxnumbervalidation`; 12 countries + 51 US states+DC; 17 tests. `payments/tax-taxjar/` — TaxJar SmartCalcs v2: JSON POST `/v2/taxes`, Bearer token, decimal major-unit amounts; 16 countries + 51 US states+DC; 17 tests. All adapters: injectable HTTP methods for testability; `Plugin` ServiceLoader; 4 sbt subprojects (`paymentsTax/TaxStripe/TaxAvalara/TaxJar`) in root aggregate.

- **openapi-export** — Auto-derived OpenAPI 3.1 spec: `GET /_openapi.json` (live JSON doc, regenerated each request) + `GET /_swagger` (CDN-linked Swagger UI HTML). `OpenApiRuntime` registers both alongside health routes when `serve`/`serveAsync` is called; walks `RouteRegistry.all`; converts `:param` segments to `{param}` OpenAPI notation; inspects `Value.FunV.paramTypes` to separate path params (in-path), query params (GET/DELETE non-path typed params), and request body (POST/PUT/PATCH); type map String→string / Int+Long→integer / Double+Float→number / Boolean→boolean / other→object; `NativeContext.registerOpenApiDefaults()` hook; internal `/_*` routes excluded; `IntrinsicImpl.scala` + `Interpreter.scala` + `HttpIntrinsics.scala` wired; empty-registry bug fixed (missing outer `}` in JSON output); 16 tests in `OpenApiRuntimeTest`.

- **v1.57-fx-provider** — FX rate provider SPI: `payments/fx/` (`FxProvider` trait, `FxRate`, `CurrencyPair`, `FxError` hierarchy, `FxMoneyConverter`); `payments/fx-ecb/` (`EcbFxProvider` — ECB daily XML feed, EUR base, 1h TTL cache); `payments/fx-openexchangerates/` (`OerFxProvider` — OER API v6, USD base, mock HTTP server tests); 76 tests total (19 SPI + 26 ECB + 31 OER). Spec updated in `docs/specs/traditional-payments.md §FxProvider`.

- **v1.57.3-payment-rails-mexico-spei** — Mexico SPEI adapter: `runtime/std/payments-mx-spei/` (`MxSpeiProvider`, `MxSpeiApi`, `MxSpeiWebhookReceiver`, `MxSpeiPlugin`); `ClabeValidator` with 18-digit control-digit check (multipliers [3,7,1,3,7,1,...]); `RailKind.MX_SPEI`; `BankAccount.clabe` additive field; `BankRailsEvent.MxSpeiConfirmed/MxSpeiRejected/MxSpeiReturned`; HMAC-SHA256 `X-SPEI-Signature` webhook; SPEI irrevocable cancel guard; `paymentsMxSpei` sbt module; 44 tests.

- **graph-storage-fullstack** — Graph storage Phase 6 full-stack examples: `examples/graph-fullstack.ssc` (Electron frontend + embedded TinkerGraph server; `GET /api/graph/vertices`, `GET /api/graph/neighbors/:id`, `POST /api/graph/vertex`; IndexedDB cache-first read with background refresh; React module+neighbor list); `examples/graph-fullstack-rdf.ssc` (RDF4J in-memory backend; `GET /api/graph/triples`, `POST /api/graph/sparql`, `PUT /api/graph/rdf`; SPARQL query panel; triple table rendering).

- **v1.57.2-payment-rails-canada-eft** — Canada Interac e-Transfer + EFT rail adapter. `runtime/std/payments-ca-eft/` subproject with `CaEftProvider` (BankRailsProvider for `RailKind.CA_INTERAC` + `RailKind.CA_EFT`); `CaEftApi` with CPA Standard 005 AFT fixed-width file builder (1,464-byte records; types 450 credit / 470 debit; header A / detail D / trailer Z); `CaEftWebhookReceiver` (HMAC-SHA256 `X-Interac-Signature`; 4 events: `interac.transfer.sent/reclaimed/expired`, `eft.debit.returned`); `CaEftPlugin` ServiceLoader registration; `BankAccount` gains additive `email`, `phone` fields; `BankRailsEvent` gains 4 CA cases; 67 tests in `CaEftProviderTest` covering Interac by email/phone, CAD enforcement, EFT credit/debit file build, CPA 005 field positions/padding/checksums, cancel semantics (recall vs irrevocable), all webhook events, idempotency.

- **secret-resolvers-cloud** — Three optional cloud secret resolver plugins: `AwsSmResolver` (scheme `aws-secret`, AWS Secrets Manager via `software.amazon.awssdk:secretsmanager:2.26.31`, default creds chain, `AWS_REGION`); `GcpSmResolver` (scheme `gcp-secret`, GCP Secret Manager via `com.google.cloud:google-cloud-secretmanager:2.46.0`, ADC, `GOOGLE_CLOUD_PROJECT` shorthand); `AzureKvResolver` (scheme `azure-kv`, Azure Key Vault via `com.azure:azure-security-keyvault-secrets:4.8.7` + `azure-identity:1.13.3`, `DefaultAzureCredential`). Each in a separate sbt subproject (`backend/sql-aws`, `sql-gcp`, `sql-azure`) registered via ServiceLoader. 41 tests (14+14+13) using injectable protected methods — no real cloud creds required.

- **spark-mllib-m2-m5 (v1.25 §M.2–M.5)** — Spark MLlib auto-dep + Vector encoder + examples. `SparkGen.containsMllib` regex detects `import org.apache.spark.ml.*` / `o.a.s.ml.*` → emits `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"` (M.2); `SscSparkEncoders` shim gains `aenc_MLVector: AgnosticEncoder[MLVector]` via `UDTEncoder(SQLDataTypes.VectorType as VectorUDT)`, gated on `usesMllib` so non-MLlib modules never reference MLlib JAR classes (M.3); `examples/spark-mllib-pipeline.ssc` (Tokenizer+HashingTF+LogisticRegression pipeline on 4-row dataset, M.4); `examples/spark-mllib-model-save-load.ssc` (save/load round-trip + prediction equivalence check, M.5); 14 new codegen tests in `SparkGenTest.scala`.

- **spark-streaming-f2-f4** — Spark Structured Streaming phases F.2–F.4. F.2: `SparkGen.containsStreaming` detects `spark.readStream`/`.writeStream`; auto-emitted `spark.streams.active.headOption.foreach(_.awaitTermination())` shim in `@main def runSparkJob` suppressed when user code already calls `awaitTermination`; `Trigger`/`StreamingQuery`/`OutputMode` imports always emitted. F.3: `SparkGen.containsFileStreamSink` detects file-format streaming sinks (parquet/csv/json/orc/text); auto-emitted `// NOTE Phase F.3` checkpoint-location reminder in file header when no `checkpointLocation` option is present in user code. F.4: `SparkGen.containsKafkaFormat` detects `.format("kafka")`; auto-emits `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"` in header. 3 example files (`spark-streaming-rate-console.ssc`, `spark-streaming-file-parquet.ssc`, `spark-streaming-kafka.ssc`). 13 codegen tests in `SparkGenTest`. All 175 `SparkGenTest` tests pass.

- **spark-catalog-g2-g4 (v1.25 Phase G.2–G.4)** — Spark Catalog DSL: G.2 `spark-hive-metastore:`/`spark-warehouse:` front-matter keys emit `spark-hive_2.13` dep + `.config("spark.sql.catalogImplementation","hive")` + metastore URI / warehouse dir lines + `.enableHiveSupport()` (9 SparkGenTest cases, ordering contract, escape semantics, `.enableHiveSupport()` short-circuit); G.3 `@TempView("name")` regex annotation rewriter strips annotation line and emits `<var>.createOrReplaceTempView("<view>")` after the val declaration, composes with `@SqlFn` (9 SparkGenTest cases); G.4 `Dataset.fromTable[T](name)` shim via `spark.table(name).as[T]` on the Dataset companion (3 SparkGenTest cases); `examples/spark-catalog-hive.ssc` (end-to-end: warehouse front-matter + @TempView + sql block + fromTable typed read-back); opt-in smoke test under `RUN_SPARK_INTEGRATION=1 && RUN_SPARK_HIVE=1`. 175 SparkGenTest cases all pass.

- **spark-lakehouse-l2** — Delta Lake lakehouse detection + codegen: `detectLakehouseFormats` extended to match `.format("delta")` (case-insensitive), `import io.delta.` and `DeltaTable.` patterns; `detectLakehouseFormats(String)` overload added; `lakehouseImports` auto-emits `import io.delta.tables.DeltaTable` in generated source when Delta is detected; dep + 2 config lines auto-emitted; 11 new `SparkGenTest` cases; `examples/spark-lakehouse-delta.ssc` round-trip + history demo. L.3 Iceberg and L.4 Hudi remain deferred.

- **v1.56-xslt** — XSLT 1.0 transformation support: `MarkupCodec.transform(doc, xslt, params)` SPI hook (default `Left(TransformError(...))`); `XsltTransformer` object in `runtime/backend/interpreter` using `javax.xml.transform.TransformerFactory`; `JvmMarkupCodec.transform` override; `Feature.Xslt` added to `Feature` enum + `InterpreterCapabilities` + `JvmCapabilities`; `CapabilityCheck` pattern-detects `.transform(` calls and gates on `Feature.Xslt`; `examples/xslt-transform.ssc`; 18 `XsltTransformerTest` + 3 `CapabilityCheckTest` tests (all pass).

- **markup-xsd-sepa-refactor (v1.55.6)** — `ValidationError(message, line, column)` confirmed in `MarkupCodec.scala`; `SepaPainXml` (PAIN.001/008 + SCT Inst pacs.008) and `Iso20022Xml` (FedNow pacs.008) refactored from raw string concat to `xml"..."` interpolator + `PureMarkupCodec.serialize`; `markupCore` added as dep to `paymentsSepa` + `paymentsFednow` in build.sbt; 12 PAIN.001 golden-file fixtures + `SepaPainXmlGoldenTest` (22 tests) + `Iso20022XmlGoldenTest` (11 tests); all 105 tests (71 SEPA + 34 FedNow) pass.

- **markup-element-literal (v1.55.5)** — `MarkupLiteralLower` AST transform in `lang/core/transform/`: source-level preprocessor that, when `import scalascript.markup.*` is present in a scalascript block, rewrites `<name attr={expr}>children</name>` and `<name/>` syntax to `Markup.Element(QName.local/prefixed(...), attrs, children)` constructor calls before scalameta parsing; namespaced tags, nested elements, text children, string and expression attributes all supported; wired into `Parser.parse` after `RouteDeriver.derive`. 16 tests.

- **markup-config-js (v1.55.7)** — `ConfigParser.Format.Xml` + `detectFormat` (.xml extension); `XmlConfigParser` (element→`ConfigValue.Map`, attrs→`_attrs/@name`, repeated tags→`Lst`, CDATA leaf support); `runtime/std/markup-js/` (`JsMarkupCodec`+`JsMarkupPlugin` — browser DOMParser/XMLSerializer Scala.js codec); `runtime/std/markup-node/` (`NodeMarkupCodec`+`NodeMarkupPlugin` — `@xmldom/xmldom` Node.js codec); `markupCore` cross-compiled (JVM+JS) via `CrossType.Pure`; 41 tests (16 + 11 + 14).

- **markup-feature-backend (v1.55.3)** — `Feature.Markup` + `Backend.markupCodec` SPI + `JvmMarkupCodec`. `case Markup` added to `Feature` enum; `def markupCodec: Option[MarkupCodec] = None` added to `Backend` trait; `JvmMarkupCodec` (SAX parse + PureMarkupCodec serialize + XSD validate via `javax.xml.validation`) wired into `InterpreterBackend` + declared in `InterpreterCapabilities`/`JvmCapabilities`; `CapabilityCheck` detects `xml"..."` interpolator and fenced xml blocks, rejects on backends lacking `Feature.Markup`; 16 tests.

- **markup-lang-xml (v1.55.2)** — `Lang.Xml = "xml"` + `isXml`; `Value.MarkupV(doc: Markup.Doc)`; `SectionRuntime.runXmlBlock` (XML-escape interpolated values, parse via `PureMarkupCodec`, bind as `<section>.xml`); `renderStringBlock` generalised with `escapeFn: Option[String => String]`; `markupCore` added to `core` dependsOn. 8 tests in `SectionXmlBlockTest` pass.

- **markup-compile-check (v1.55.4)** — Compile-time `xml"..."` well-formedness checker. `MarkupInterpolatorCheck` in `lang/core/transform/`: walks scalameta trees, joins `xml"..."` string parts with `<placeholder/>` for each `${expr}` hole, calls `PureMarkupCodec.parse` — emits `Diagnostic.XmlParseError(message, line, col)` on failure. Added `markupCore` dep to `core` sbt module. 10 tests.

- **v1.55.8-singapore-paynow** — New `runtime/std/payments-sg-paynow/` subproject: `PayNowProvider` (BankRailsProvider for SG_PAYNOW rail, two-step flow: proxy resolution → FAST payment initiation, SGD-only enforcement, cancel with BankRailsCancelError), `PayNowApi` (aggregator REST client; proxy resolution + payment initiation; `ProxyResolutionResult` parsing), `PayNowWebhookReceiver` (HMAC-SHA256 `X-PayNow-Signature` verify; parses `paynow.payment.credit/return` → `PayNowSettled/PayNowFailed`), `PayNowPlugin` (ServiceLoader). SPI additions: `PayNowProxyType` enum (Mobile/NricFin/Uen/Vpa), `BankRailsEvent.PayNowSettled/PayNowFailed`, `BankRailsError.PayNowProxyNotFound`. 67 tests.

- **v1.55.7-japan-zengin** — Japan Zengin (全銀) domestic bank transfer adapter. New `runtime/std/payments-japan-zengin/` subproject: `ZenginProvider` (BankRailsProvider for `JP_ZENGIN`, injectable clock for settlement-window tests), `ZenginFile` (Zengin 21 format: fixed-width 120-byte records — type 1 header / type 2 data / type 8 trailer / type 9 end), `KatakanaValidator` (validates half-width kana U+FF66–U+FF9F + space + hyphen; returns `Right(name)` or `Left(invalidChars)`), `ZenginWebhookReceiver` (HMAC-SHA256 `X-Zengin-Signature`; parses `zengin.transfer.completed/failed` → `ZenginSettled/ZenginRejected`), `ZenginPlugin` (ServiceLoader). 59 tests.

- **v1.55.6-india-upi** — New `runtime/std/payments-india-upi/` subproject: `UpiProvider` (BankRailsProvider for IN_UPI; push flow via `initiateTransfer` maps to UPI Pay API; collect flow via `initiateDirectDebit` maps to UPI Collect API using `creditorAccount.upiVpa` / `debtorAccount.upiVpa`; RSA-SHA256 request signing with merchant private key; VPA format validation), `UpiWebhookReceiver` (RSA-SHA256 `X-UPI-Signature` verify with aggregator public key; parses `upi.payment.success/failed/collect.expired/collect.initiated` → `UpiApproved/UpiDeclined/UpiCollectInitiated`; signature check skipped when no key configured), `UpiPlugin` (ServiceLoader), `UpiCollectRequest` model, `UpiConfig` (apiKey, merchantVpa, baseUrl, merchantPrivateKeyPem, webhookPublicKeyPem, callbackUrl, defaultPurposeCode). SPI additions: `BankRailsEvent.UpiApproved/UpiDeclined/UpiCollectInitiated` + BACS DD/Zengin/PayNow event stubs, `BankRailsError.UpiTwoFactorTimeout` + BacsCycleMissed/ZenginOutsideWindow/PayNowProxyNotFound. 63 tests.

- **v1.55.5-uk-chaps** — New `runtime/std/payments-uk-chaps/` subproject: `UkChapsProvider` (BankRailsProvider for UK_CHAPS, ISO 20022 pacs.008.001.08 submission to aggregator, GBP-only enforcement, cancel with BankRailsCancelError), `ChapsPacs008Builder` (pacs.008 with `SvcLvl=CHAPS`, `SttlmMtd=INDA`, no ClrSys; IBAN or sort-code+account; BIC for CdtrAgt), `UkChapsWebhookReceiver` (HMAC-SHA256 `X-CHAPS-Signature` verify; parses `chaps.payment.settled/rejected` → `ChapsSettled/ChapsRejected`), `UkChapsPlugin` (ServiceLoader). SPI additions: `BankRailsEvent.ChapsSettled/ChapsRejected`. 46 tests.

- **v1.55.4-uk-bacs** — `runtime/std/payments-uk-bacs/`: UK BACS Direct Debit adapter. `UkBacsProvider` (BankRailsProvider for `UK_BACS_DD`), `BacsFile` (Standard-18 110-char fixed-width file: record types 0/1/5/9, debit/credit/trailer, amounts in pence), `AuddisFile` (AUDDIS mandate registration file, instruction codes 0N/0C/0S), `UkBacsWebhookReceiver` (HMAC-SHA256 `X-BACS-Signature`, events: submitted/collected/auddis-accepted/returned), `UkBacsPlugin` (ServiceLoader). `BankRailsEvent.BacsDdSubmitted/Paid/AuddisAccepted/AruddReturned`, `BankRailsError.BacsCycleMissed`. `AruddCode` object maps all 11 ARUDD return codes (0,1,2,3,5,6,B,C,F,G,H). 61 tests.

- **x402-cardano-scalus-completion** — Phase 3/5/6 completion of the Cardano/Scalus escrow settlement feature. Phase 3: `ReferenceScriptDeployer.deploy(blockfrost, network, signingKeyHex, feeLovelace)` for one-time CIP-33 reference-script UTxO creation; `ScalusSettlerConfig.referenceScriptRef` optional config field; 2 deployer tests. Phase 5: `ScalusRoundTripTest` — 4 end-to-end tests: round-trip verify ok, settle builds correct `ClaimTxPlan`, tampered CIP-8 signature → `Fail`, malformed escrowRef → `Fail`. Phase 6: `EscrowDatumOffChain` (payerKeyHash, claimMessageHash, receiverHash, amount, validBefore, refundAfter) + `EscrowDeposit.build(payerPublicKeyHex, req, validBeforeSlot, refundAfterSlot, cfg)` payer-side deposit helper; 3 deposit tests; `examples/x402-cardano-scalus.ssc` (4-step Preprod walkthrough: deploy → deposit → client → facilitator). Updated `EscrowScriptTest` golden bech32 addresses and `BloxbeanClaimTxDraftBuilderTest` to use `EscrowScript.address(...)` dynamically.

- **v1.55.3-uk-faster-payments** — New `runtime/std/payments-uk-fps/` subproject: `UkFpsProvider` (BankRailsProvider for UK_FPS rail, REST JSON over HTTPS to aggregator, CoP name-check before each payment), `ConfirmationOfPayee` (CoP client with `CopResult` enum: Matched/CloseMatch/NoMatch/AccountSwitched/Unavailable), `UkFpsWebhookReceiver` (HMAC-SHA256 `X-FPS-Signature` verify; parses `uk.faster-payments.credit/rejected/return` → `UkFpsAccepted/Rejected/Returned`), `UkFpsPlugin` (ServiceLoader registration). SPI additions: `RailKind.UK_FPS` + 7 other future rail cases, `BankAccount.sortCode` (and other v1.55 fields), `BankRailsEvent.UkFpsAccepted/Rejected/Returned`, `BankRailsError.UkCopNameMismatch`. 47 tests.

- **v1.55.2-sepa-instant** — Extended `runtime/std/payments-sepa/` with SEPA Instant Credit Transfer (SCT Inst): `RailKind.SCT_INST`, `SepaPainXml.buildSctInstPacs008` (pacs.008.001.08 with `LclInstrm=INST`, `SttlmMtd=CLRG`, `ClrSys=SCTInst`), `BankRailsEvent.SctInstSettled/SctInstRejected`, `BankRailsError.SctInstTimeout` (10-second window exceeded); `SepaProvider.supportedRails` extended; webhook parsing for `SCTInst.CreditTransfer.Settlement/Rejection`; 19 new tests (49 total).

- **v1.55.1-international-swift** — SWIFT MT103 + ISO 20022 pacs.008 (CBPR+) bank rails adapter. `payments/bank-rails/` gains `Uetr` opaque type, `ChargeBearer` enum (OUR/SHA/BEN), `GpiHop` case class, additive fields on `BankTransfer` (uetr/gpiTrail/chargeBearer), `InitiateTransferRequest` (chargeBearer/uetr), `BankAccount` (bic + 5 more v1.55 fields), 9 new `RailKind` cases, SWIFT GPI event cases, SWIFT error cases. New `runtime/std/payments-swift/` subproject: `SwiftProvider` (SWIFT_MT103 + SWIFT_PACS008), `SwiftMt103Builder` (MT103 field 20/32A/50K/57A/59/70/71A/121), `SwiftPacs008Builder` (pacs.008.001.10 FIToFICstmrCdtTrf with CBPR+ mandatory fields), `GpiTracker` (GPI webhook event parsing), `SwiftWebhookReceiver` (HMAC-SHA256 X-SWIFT-Signature), `SwiftPlugin` (ServiceLoader). 65 tests.

- **wallet-solana-standard-js** — Scala.js Solana Wallet Standard browser registration (`wallet-connector-wallet-std/js/`): `WalletInfo` JS-native trait (name, icon, chains, features), `WalletStandardJs.register(info, connector)` dispatches `wallet-standard:register-wallet` CustomEvent + legacy `window.standard.wallets.registerWallet`, `StandardWalletConnectorJs` feature-map bridge; 6 Node.js smoke tests via `global.window` stub.

- **v1.55.1-markup-core** — `runtime/std/markup-core/`: first-class XML / Generic Markup milestone (v1.55) phase 1.  Delivers `Markup` sealed ADT (`Doc`, `Element`, `Attr`, `Text`, `CData`, `PI`, `Comment`, `DocType`, `XmlDecl`, `QName`, `Raw`); `MarkupCodec` SPI (parse / serialize / validate); `XmlEscape` (5-entity escape + unescape, text + attr variants); `PureMarkupCodec` (zero-dependency XML 1.0 recursive-descent parser + serializer, ~300 LoC, handles namespaces / CDATA / entities / PIs / comments / self-closing / mixed content); `xml"..."` string interpolator (mandatory XML-escape for all args, `Markup.raw(...)` passthrough, `Markup.Element` splice via serializer, `Markup.Doc` splice).  17 tests (`MarkupSpec` + `XmlInterpolatorSpec`).

- **wallet-ledger-cardano** — `payments/wallet/vault-ledger-cardano/`: `CardanoApp` object (CLA=0xD7, INS=0x10 GET_EXTENDED_PUBLIC_KEY, INS=0x21 SIGN_TX); `CardanoCip8` minimal COSE Sig_Structure builder (hand-rolled CBOR, no deps); `LedgerCardanoVault` (`Vault` SPI, Ed25519 only, `AppSwitchRequired` guard via `Dashboard.getAppName`); `LedgerCardanoRawSigner` (CIP-8 wrapping, 64-byte ed25519 sig); `walletVaultLedgerCardano` sbt subproject (JVM-only); 11 tests.

- **wallet-ledger-solana** — `payments/wallet/vault-ledger-solana/`: `SolanaApp` object (CLA=0xE0, INS=0x04 SIGN_TRANSACTION, INS=0x05 GET_PUBKEY, INS=0x07 SIGN_OFFCHAIN_MESSAGE); lightweight `Base58` encoder (Bitcoin/Solana alphabet, pure Scala, no deps); `LedgerSolanaVault` (`Vault` SPI, Ed25519 only, `AppSwitchRequired` guard); `LedgerSolanaRawSigner` (64-byte ed25519 sig, no v-byte); `walletVaultLedgerSolana` sbt subproject; 13 tests.

- **wallet-ledger-js** — Added `payments/wallet/vault-ledger-js`: Scala.js WebHID Ledger transport (`navigator.hid`), 64-byte HID APDU framing, browser `LedgerVault` lifecycle, Ethereum signer reuse, Cardano CIP-8 COSE helper, and 13 mocked WebHID tests.

- **wallet-ledger-bitcoin** — `payments/wallet/vault-ledger-bitcoin/`: `BitcoinApp` object (CLA=0xE1, new protocol v2+; GET_EXTENDED_PUBKEY/REGISTER_WALLET/GET_WALLET_ADDRESS/SIGN_PSBT); `LedgerBitcoinVault` (`Vault` SPI, secp256k1 only, `AppSwitchRequired` guard); `LedgerBitcoinRawSigner` (PSBT bytes → per-input DER sigs concatenated); `walletVaultLedgerBitcoin` sbt subproject; 14 tests.

- **ssc-profile** — `ssc profile <file.ssc>` with per-phase timing + heap allocation (`parse`/`typecheck`/`normalize`/`jvm-codegen`/`link`); flame-graph JSON (`--out`); `--top=N` hottest phases; `--compare=baseline.json` regression diff with ⚠ on >10%; `--runs=N` min/avg/max; `PhaseResult`+`timed` helper; `Profiler.recordPhase`/`phaseEntries()`; 15 tests in `ProfileCommandTest`.

- **js-tree-shaking** — `TreeShaker` worklist reachability from `@main`/exports; `JsGen.generateWithStats` emits only reachable `const`/`function` declarations; `--no-tree-shake` escape hatch; `--stats` prints "Tree-shake: kept N / M symbols" to stderr; 16 tests in `JsTreeShakeTest`.

- **blockchain-cosmos** — `payments/blockchain/cosmos/`: secp256k1 ECDSA (RFC 6979) + ed25519 signing via BouncyCastle; Cosmos StdSignDoc Amino JSON encoding with canonical field order; bech32 address derivation with configurable HRP (`cosmos`/`osmo`/`juno`); `CosmosChainAdapter` implementing `ChainAdapter` SPI; `ChainId.CosmosHub`/`ChainId.Osmosis`/`ChainId.Juno` added to `blockchain-spi`; `BlockchainProvider` SPI trait + `CosmosBackend` ServiceLoader registration. 41 tests.

- **ssc-check** — `ssc check` expanded: `--json` (structured diagnostics), `--quiet` (exit-code-only for CI hooks), `--watch` (WatchService re-check on change), directory mode (recursive `*.ssc` scan), distinct exit codes (0/1/2/3). 18 integration tests in `CheckCommandTest`.

- **v1.54.4-bank-rails-fednow** — `runtime/std/payments-fednow/` FedNow instant payments adapter: ISO 20022 pacs.008.001.08 credit transfer XML builder, pacs.002.001.10 status parser (ACCP/PDNG→Pending, ACSC→Settled, RJCT→Rejected), HMAC-SHA256 webhook receiver, FedNowProvider (USD-only, $500K limit, cancel/direct-debit unsupported), FedNowPlugin SPI, 23 tests, `examples/bank-rails-fednow.ssc`.

- **v1.54.2-bank-rails-ach** — `payments/bank-rails/` (BankRailsProvider SPI + BankTransfer/DirectDebitMandate core types + RCode/CCode) + `runtime/std/payments-ach/` (NachaFile 94-char fixed-width builder, AchProvider, AchWebhookReceiver HMAC-SHA256, AchPlugin Backend SPI, `AchConfig`, same-day ACH, R/C-code handling, `examples/bank-rails-ach.ssc`). 28 tests.

- **v1.54.3-bank-rails-pix** — Pix instant payments adapter (Brazil): EMV Merchant-Presented QR Code builder (static + dynamic, CRC-16/CCITT), `PixProvider` (BCB DICT REST, OAuth2 token, T+0 settlement, Pix Automático/cobv direct debit), `PixWebhookReceiver` (HMAC-SHA256, `pix.received`/`pix.refunded`/`pix.rejected`), `PixPlugin` (SPI entry point), `payments/bank-rails/` core SPI types (`RailKind`, `BankTransfer`, `BankRailsProvider`, etc.), `Feature.BankRails`. 32 tests.

- **blockchain-bitcoin** — secp256k1 ECDSA (RFC 6979 deterministic k), BIP-143 SegWit sighash, BIP-340 Schnorr signing/verification, BIP-341 Taproot (tapTweakHash + tweakedKey + tweakedPrivateKey), P2WPKH bech32 (`bc1q`/`tb1q`) + P2TR bech32m (`bc1p`/`tb1p`) address derivation, PSBT BIP-174 builder/signer/finalizer/deserializer, `BitcoinChainAdapter` (`ChainAdapter` SPI), `ChainId.BitcoinMainnet`/`ChainId.BitcoinTestnet` added to `blockchain-spi`. 45 tests.

- **v1.51.4-streams-sse-ws** — `mapAsync(n)(f)` parallel map (semaphore-bounded, ordered results); `.recover(handler)` error recovery; `.mapError(f)` error transformation; `Source.bracket(acquire)(release)(use)` resource lifecycle; `Source.fromSse(url)` SSE HTTP client source; `Sink.toSseStream` SSE response formatter; `Source.fromWebSocket(url)` WebSocket message source; `Sink.toWsRoom(room)` WsRoom broadcast sink. All operators in the interpreter plugin; Source/Sink companions updated in BuiltinsRuntime. 8 new tests (49 → 57 total).

- **v1.51.5b-streams-clock-ui-signals** — Streams now pace `.throttle(Rate)` with interpreter wall-clock scheduling, delay finite `.debounce(durationMillis)` bursts before emitting the latest value, subscribe `Source.signal(sig)` to frontend `ReactiveSignal` updates, and support reverse `sig.bind(source)` for frontend signals. Swing/JavaFX runtime state maps now stay synchronized with the shared signal bus; SwiftUI native bridging is tracked separately as `v1.51.5c-streams-swiftui-bridge`.

- v1.54-bank-rails-spec — Bank Rails spec (SEPA/ACH/Pix/FedNow) ✓ (2026-05-27)

- **v1.54.1-bank-rails-sepa** — `payments/bank-rails/` SPI + `runtime/std/payments-sepa/` SEPA CT+DD adapter: PAIN.001/008 XML builder, HMAC-SHA256 webhook, SepaProvider, Feature.BankRails, 30 tests. (2026-05-27)

- **v1.51.5-streams-buffer** — Streams plugin now supports `.buffer(n, OverflowStrategy)` with `Backpressure`/`Block`, `Drop`, `DropHead`/`DropOldest`, and `Fail`; `.throttle(Rate)`; `.debounce(durationMillis)`; `Rate(...)`; `OverflowStrategy` companion constants; and `Source.signal(sig)` as an interpreter current-value adapter. Added 7 interpreter tests and expanded `examples/streams.ssc`. Live UI signal subscriptions and Clock-effect-backed wall-time scheduling are tracked as `v1.51.5b-streams-clock-ui-signals`.

- **x402-cardano-scalus-validator-simulator-tests** — Added `x402-escrow-plutus` ScalaTest coverage that constructs Scalus `ScriptContext` values directly for the escrow validator. Tests cover the claim happy path, tampered CIP-8 signature rejection, wrong receiver amount rejection, claim validity-window rejection, refund happy path, and refund timing rejection.

- **v1.52.7-deploy-state-backends** — `JsonState` (zero-dep JSON ser/de for StateRecord). `LocalFileStateBackend` (~/.ssc-state/<app>/<env>/<target>.json; sibling .lock with TTL contention detection). `S3StateBackend` (aws s3api subprocess; optimistic mtime-based TTL lock). `ConsulStateBackend` (Consul KV HTTP API v1; session-based locking). `EtcdStateBackend` (etcdctl subprocess; lease-based locking). `StateBackendFactory` (backend dispatch; production-env enforcement). `StateMigrator` (ssc deploy state migrate; dry-run; skipped/failed tracking). 14 new tests; 105 total.

- **v1.52.6-deploy-faas** — `FaasTarget` (`kind: faas`): AWS Lambda (LambdaZip via `buildLambdaZip`+`ZipOutputStream`; `aws lambda create-function/update-function-code/publish-version/update-alias "live"`; `aws logs tail`), Cloudflare Workers (`wrangler deploy` with `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ACCOUNT_ID`), GCP Cloud Run (`gcloud run deploy --platform managed --allow-unauthenticated`), Vercel Functions (`vercel --prod`). All dry-run capable; `rollback` via Lambda alias version pointer. `TargetFactory` extended with `"faas"/"lambda"/"serverless"`. 11 new tests; 91 total.

- **v1.52.5-deploy-static** — `StaticTarget` (`kind: static`): Vercel (CLI or Deployments API v13), Netlify (CLI or API), Cloudflare Pages (wrangler or API; account_id via `team:`), GitHub Pages (git push orphan branch to gh-pages). HTTP GET status. TargetFactory `"static"`. 9 new tests; 80 total.

- **v1.52.4-deploy-traditional** — `SystemdUnitGenerator` (FatJar/NativeBinary/NodeBundle unit templates, env vars, pre/post hooks). `SshSystemdTarget` (SSH+SCP artifact + systemd unit; `systemctl restart/is-active`; `journalctl -u` logs; pre/post_deploy). `RsyncTarget` (rsync with configurable SSH rsh; post-deploy hook). `SftpTarget` (SFTP batch upload; post-upload unpack_cmd). TargetFactory extended with transport sub-dispatch for traditional kind + rsync/sftp top-level kinds. 18 new tests; 71 total.

- **v1.52.3-deploy-k8s** — `K8sManifestGenerator`: Deployment (liveness `/_health` + readiness `/_ready` probes, PreStop drain hook, resource limits, nodeSelector, annotations, blue-green slot labels) + Service (ClusterIP, slot-selector for blue-green switching) + Ingress + ConfigMap + Secret (base64-encoded). `K8sTarget`: full 7-verb SPI + `switch()` (kubectl patch Service selector) + `promote()` (scale→switch→scale old to 0); dry-run; `kubectl rollout undo` rollback; log streaming. `TargetFactory` extended with `"k8s" | "kubernetes"`. 17 new tests; 53 total.

- **v1.52.2-deploy-container** — `DockerfileGenerator`: four base-image recipes per `ArtifactKind` (FatJar→`eclipse-temurin:21-jre-alpine`, NativeBinary→`gcr.io/distroless/cc`, NodeBundle→`node:22-alpine`, SpaBundle→`nginx:alpine`); build-args/labels/env/port/HEALTHCHECK support; `writeDockerfile` helper. `ContainerTarget`: full 7-verb `DeployTarget` SPI (`build`/`push`/`deploy`/`rollback`/`status`/`logs`/`outputs`); builder auto-detect (`buildctl` → `docker buildx` → `docker build`); multi-platform via `platform:`; digest capture for rollback; dry-run throughout. `TargetFactory`: resolves `kind: container | traditional`. `ArtifactRegistry` extended with `OciImage`. 14 new tests; 36 deploy-plugin tests total.

- **v1.53.7-payments-webhook-cluster** — `payments/webhook-redis/`: `RedisSeenKeyStore` uses Lettuce `SET NX EX` (atomic set-if-not-exists with TTL) for cluster-safe deduplication; configurable key prefix (`whk:`) + await timeout; 8 tests with in-memory stub client. `payments/webhook-postgres/`: `PostgresSeenKeyStore` uses `INSERT … ON CONFLICT DO NOTHING` (atomic under PRIMARY KEY constraint) with auto-CREATE TABLE, expired-entry filtering in `wasSeen`, and `purgeExpired()` maintenance method; tested with H2 in-memory database (9 tests). Both modules added to sbt build; both implement `SeenKeyStore` SPI from `payments/webhook/`.

- **x402-cardano-scalus-validator-validity-range** — The Scalus Plutus validator now enforces claim/refund validity windows: claims must be entirely before `datum.validBefore`, refunds entirely after `datum.refundAfter`. Validator CBOR regenerated.

- **x402-cardano-scalus-validator-output-shape** — The Scalus Plutus validator now enforces claim output shape: at least one transaction output must pay exactly `datum.amount` lovelace to `PubKeyCredential(datum.receiverHash)`. Validator CBOR regenerated.

- **v1.53.6-payments-mock-provider** — `runtime/std/payments-mock/`: fully in-memory `MockProvider` with `MockMode` enum (Succeed / Fail(error) / RequireSCA(redirectUrl)) configurable per effect group (`chargeMode`, `refundMode`, `disputeMode`, `subscribeMode`, `vaultMode`). All 16 SPI methods implemented against `ConcurrentHashMap` state; `recorded*` inspection helpers + `reset()`. `MockWebhookReceiver`: skips HMAC verification, parses minimal JSON events, exposes `recorded: List[PaymentEvent]` for assertions. `PaymentEffect` enum (Charging / Refunding / Disputing / Subscribing / Vaulting / Webhooking) + `PaymentEffect.of(op)` added to SPI. ServiceLoader registration via META-INF/services. 41 new tests.

- **x402-cardano-scalus-validator-cip8** — The Scalus Plutus validator now checks canonical CIP-8 claim redeemers on-chain: COSE_Key public-key extraction, payer key-hash match, COSE_Sign1 payload hash match, Sig_Structure reconstruction, and Plutus `verifyEd25519Signature`. `x402EscrowPlutus/emitEscrowHex` now writes to the actual `payments/x402/...` resource path; committed validator CBOR regenerated.

- **v2.1.10-dstream-conformance** — Cross-backend DStream conformance suite (§14.3). New `runtime/backend/conformance/` sbt module (`backendConformance`) with `DStreamConformanceTest`: 8 tests run the same pipeline SSC through all 4 generators (Spark, Kafka Streams, Flink, Beam) and assert structural conformance. Tests cover: word count, windowed word count, stateful running sum, side inputs, windowed joins, connector stubs, backend alias declarations, and full operator surface. SparkGen + KafkaStreamsGen `Backend` object extended with missing `Flink`/`Beam` aliases — now all 4 shims declare the same 7 backend aliases (`Direct`, `Native`, `Spark`, `KafkaStreams`, `Kafka`, `Flink`, `Beam`). `examples/distributed-streams.ssc` expanded from 3 to 12 examples, covering windowed word count (§5.3), stateful running sum (§5.5), broadcast state (§5.5), side inputs (§5.6), side outputs (§5.6), inner join (§5.7), left outer join (§5.7), flatten (§5.7), processing-time timer (§5.4).

- **v2.1.9-dstream-joins** — Windowed joins + flatten for all DStream backends. `DStream.join(other)` (inner join on KV keys), `DStream.leftOuterJoin(other)` (all left, right `Option`), `DStream.rightOuterJoin(other)` (all right, left `Option`), `DStream.flatten` (collapses `DStream[DStream[T]]` or `DStream[Seq[T]]`) added to all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam). All 4 `containsDStream` methods extended with `.join(`, `leftOuterJoin`, `rightOuterJoin`, `.flatten` detection. Native interpreter (`DStreamsIntrinsics`): `evalDag` handlers for `_dag_join`, `_dag_leftOuterJoin`, `_dag_rightOuterJoin`, `_dag_flatten`; `dstreamOps` wiring. `CAP_WINDOWED_JOINS` was already declared in `directCapabilities`. +8 interpreter tests, +12 generator tests across Spark/Kafka/Flink/Beam.

- **v2.1.8-dstream-side-io** — Side inputs and side outputs for all DStream backends. `SideInput[T]` case class + `object SideInput` (`of(stream)`, `singleton(v)`, `asMap(stream)`) added to all 4 code-gen shims. `OutputTag[B]` case class + `object OutputTag` (`apply(name)`, `withFilter(name)(fn)`) added. `DStream.withSideInput(si)` cross-joins main stream with side input elements. `DStream.sideOutput(tag)` returns `(DStream[T], DStream[B])` pair — main stream plus filtered side stream. All 4 `containsDStream` methods extended with `withSideInput`, `sideOutput`, `SideInput.`, `OutputTag` detection. Native interpreter: `evalDag` handlers for `_dag_withSideInput`, `_dag_sideOutput`; `dstreamOps` wiring; `SideInput` + `OutputTag` companions in `BuiltinsRuntime.setupPluginCompanions`; `CAP_SIDE_INPUTS` + `CAP_SIDE_OUTPUTS` added to `directCapabilities`. +8 interpreter tests, +8 generator tests across Spark/Kafka/Flink/Beam.

- **v1.53.5-payments-vault-mandates-sca** — SPI extended: `ScaExemption` enum (LowValue / TrustedListing / TransactionRiskAnalysis / Recurring / MerchantInitiated), `scaExemptions: List[ScaExemption]` + `mandateId: Option[MandateId]` added to `CreateIntentRequest`, `networkToken: Option[String]` + `mandateId: Option[MandateId]` added to `StoredMethod`, `Mandate` extended with `customerId`/`vaultId`/`providerRef`, `createMandate`/`getMandate` added to `PaymentProvider` trait. All 5 adapters updated: Stripe uses `/setup_intents` for mandate creation + `/mandates/{id}` retrieval + SCA `request_three_d_secure` mapping; Adyen wires `shopperInteraction=ContAuth` + `recurringProcessingModel` for off-session + `scaExemption` additionalData; PayPal wires `payment_source.card.stored_credential` for MIT + `/v3/vault/setup-tokens` for mandates; Braintree/Checkout.com/Square implement mandate stubs. Network token extracted from Stripe `card.networks.preferred`, Adyen `networkToken`, PayPal `network_token`. 9 new SPI-level tests. 87 total tests green.

- **v2.1.7-dstream-stateful** — Stateful processing + timers for all DStream backends. `statefulMap(init)(f)` and `statefulFlatMap(init)(f)` added to all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam): per-key state accumulation where `f: (S, A) => (S, B)`. `broadcastState(stateStream)` added: pairs each main-stream element with a `Map[K, V]` built from KV state stream. `timerEventTime(tsMs)(f)` added (event-time timer; behaves like `timerProcessing` in bounded DirectRunner). State types added to all shims: `ValueState[T]`, `MapState[K, V]`, `ListState[T]`, `BagState[T]` (in-memory implementations), `StateContext[K, S]` context class, `KeyedStateSpec[K, S]` spec + companion. All 4 `containsDStream` methods extended with `statefulMap`, `statefulFlatMap`, `broadcastState`, `KeyedStateSpec` detection. Native interpreter (`DStreamsIntrinsics`): `evalDag` handlers for `_dag_statefulMap`, `_dag_statefulFlatMap`, `_dag_broadcastState`, `_dag_timerEventTime`; `dstreamOps` wiring for curried operators; `KeyedStateSpec.value` intrinsic; `KeyedStateSpec` companion in `BuiltinsRuntime.setupPluginCompanions`. +20 new tests across all modules (42 interpreter, 40 Flink/Beam, 30 KafkaStreams, 195 Spark).

- **v2.1.6-dstream-connectors** — Production connector stubs for all 5 DStream backends. `Kafka`, `Files`, `FileFormat`, `Jdbc`, `Pulsar`, `Kinesis` companion objects emitted in all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam) when any connector usage detected. `DSink[T] = Any` type alias emitted alongside. `containsConnector` detection added to all generators; connector detection now triggers DStream shim emission. `DSource.fromDataset` bridge added. Native interpreter: connector intrinsics registered in `DStreamsIntrinsics.table`; companion assembly in `BuiltinsRuntime.setupPluginCompanions` (Kafka, Files, FileFormat, Jdbc, Pulsar, Kinesis, DSource.fromDataset). All connector source stubs return empty `DSource[T]` for bounded testing; live connector execution requires cluster + env var. SparkGen: `Kafka.source/sink/changelog` usage now also triggers `spark-sql-kafka-0-10` dep emission (extends Phase F.4). +14 new tests across all modules.
- **v1.53.4-payments-square** — `runtime/std/payments-square/` (Square adapter: Bearer access_token auth, Square Payments API v2 sandbox/live, Web Payments SDK nonce (`source_id`), HMAC-SHA1 webhook over `notification_url + raw_body` with base64 comparison, `SquareWebhookReceiver`). All 14 SPI methods. No SCA/mandates. Catalog API for subscription plans, `/v2/subscriptions` for recurring billing, `/v2/disputes` for evidence upload. 14 new tests.

- **v2.1.5-dstream-flink** — Flink + Beam backends for DStream: new `runtime/backend/flink/` module with `FlinkGen`, `BeamGen`, `FlinkBackend`, `BeamBackend`, `FlinkCapabilities`, `BeamCapabilities`. Both generators follow the same shim pattern as v2.1.3/v2.1.4: detect DStream code and emit full DSL backed by driver-local `Seq[Any]` for bounded `InMemory` sources. `FlinkGen` targets Flink DataStream API (`flink-streaming-scala_2.12:1.20.1`); `BeamGen` targets Apache Beam Java SDK (`beam-sdks-java-core:2.62.0`), auto-selects runner dep (`DirectRunner`/`FlinkRunner`/`SparkRunner`). Both declare `Backend.Flink` and `Backend.Beam` aliases. `PipelineOptions(extraProperties, checkpointDir, parallelism)` case class emitted. Flink shim includes `_flinkEnv()` helper; Beam shim includes `_createBeamPipeline()` factory. ServiceLoader registration for both backends. `Feature.DistributedStreams` in both capabilities. 30 new `FlinkGenTest` tests.

- **x402-cardano-scalus-evaluate-endpoints** — Added live ex-unit endpoint wiring: `BlockfrostClient.evaluateTx` for `/utils/txs/evaluate`, `ScalusTxEvaluator.blockfrost(...)`, and `ScalusTxEvaluator.ogmiosHttp(url)` for Ogmios JSON-RPC `evaluateTransaction`. Endpoint responses now map into typed claim `ScalusExUnits`.

- **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (Adyen adapter: X-API-Key auth, Checkout API v71, HMAC-SHA256 webhook over 8 sorted notification fields, `additionalData` escape hatch, Drop-in/Web Components nonce support, `AdyenWebhookReceiver` with base64-decoded key) + `runtime/std/payments-checkout/` (Checkout.com adapter: Bearer sk_xxx auth, Unified Payments API v3, HMAC-SHA256 hex over raw body with `Cko-Signature` header, `CheckoutWebhookReceiver`). Both adapters implement all 14 SPI methods. 25 new tests (12 Adyen + 13 Checkout.com).

- **v2.1.4-dstream-kafka** — Kafka Streams backend for DStream: new `runtime/backend/kafka-streams/` module (`KafkaStreamsGen`, `KafkaStreamsBackend`, `KafkaStreamsCapabilities`). `KafkaStreamsGen` detects DStream code (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.KafkaStreams` / `Backend.Kafka` / `Window.*` / `WatermarkStrategy.*` / `Trigger.*`) and emits `dstreamKafkaShim` inside `@main def runKafkaStreamsJob()`. Shim provides full DStream DSL backed by driver-local `Seq[Any]` for bounded `InMemory` sources; Kafka Streams topology builder helpers (`_buildTopology`, `_runWithTestDriver`) for live `Kafka.source` inputs. `Backend.KafkaStreams` and `Backend.Kafka` aliases declared. `//> using dep org.apache.kafka:kafka-streams_2.13:3.7.1` + test-utils + clients directives emitted. `Feature.DistributedStreams` in `KafkaStreamsCapabilities`. ServiceLoader registration. 22 new `KafkaStreamsGenTest` tests. Also extends `SparkGen.containsDStream` with `Window.*` / `WatermarkStrategy.*` / `Trigger.*` detection (fixes SparkGen window shim test).

- **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (PayPal Checkout adapter: OAuth2 client-credentials with 8h token cache, PayPal Orders v2 API, RSA-SHA256 webhook verify against PayPal-fetched cert, all 14 SPI methods, `PayPalWebhookReceiver`) + `runtime/std/payments-braintree/` (Braintree adapter: HTTP Basic auth, GraphQL API for transactions/customers/vault, XML REST for plans/subscriptions/refunds/disputes, HMAC-SHA1 webhook with base64-decoded payload, `BraintreeWebhookReceiver`). 25 new tests (11 PayPal + 14 Braintree).

- **v2.1.3-dstream-spark** — Spark backend for DStream: `SparkGen` extended with DStream detection (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.Spark`) and `dstreamSparkShim` emission. Shim provides full DStream DSL (v2.1.1 + v2.1.2 operators) backed by driver-local `Seq[Any]` for bounded `InMemory` sources; produces identical results to `Backend.Direct` on the Spark driver. Operators: `map`, `filter`, `flatMap`, `keyBy`, `combinePerKey`, `merge`, `window`, `withTrigger`, `withWatermark`, `withAllowedLateness`, `timerProcessing`, `run`, `runToList`, `runFold`, `runForeach`, `runCount`. `KV[K,V]` case class, `Pipeline`, `InMemory`, `DSource`, `Backend`, `PipelineResult`, `Window`, `Trigger`, `WatermarkStrategy`, `AccumulationMode` companions all emitted. `Feature.DistributedStreams` added to `SparkCapabilities`. 14 new `SparkGenTest` tests. Integration tests gated by `SPARK_MASTER` env var (15 skipped without Spark).

- **x402-cardano-scalus-bloxbean-evaluator** — Added `ScalusTxEvaluator.bloxbean(...)` on top of bloxbean `TransactionEvaluator`, plus evaluated-balanced claim draft rebuilding. Evaluator-provided claim ex-units now flow into the redeemer and fee estimate before serialization.

- **x402-cardano-scalus-preprod-it** — Added env-gated Preprod integration coverage for the Cardano/Scalus claim Tx draft. `BloxbeanPreprodIntegrationTest` builds a balanced draft from live Blockfrost Preprod protocol params when `X402_SCALUS_PREPROD_IT=true`; actual submit remains separately gated by `X402_SCALUS_PREPROD_SUBMIT=true`.

- **v1.53.1-payments-spi-stripe** — `payments/money/` (opaque `Currency` + ISO 4217/crypto minor-units table, `Money` Long minor-units arithmetic with HALF_EVEN rounding, `allocate` for penny-perfect splits), `payments/webhook/` (`WebhookReceiver[E]` SPI, `SeenKeyStore` idempotency with expiry, `InMemorySeenKeyStore`), `runtime/std/payments-plugin/` (`PaymentProvider` 14-method SPI, all SPI types: `PaymentIntent`/`PaymentEvent`/`Customer`/`Subscription`/`Refund`/`Dispute`/`Mandate`/`SCAChallenge`/`PaymentError` hierarchy, `Feature.Payments`), `runtime/std/payments-stripe/` (full Stripe adapter: HMAC-SHA256 webhook verify, all 14 methods via Java HttpClient + form-encoded bodies, `StripeWebhookReceiver` with replay protection), `examples/traditional-payments.ssc` (12 worked snippets). `Amount` in `payment-request` deprecated. 33 new tests (19 MoneyTest + 14 StripeProviderTest). Closes `chargeCard()` placeholder from v1.38.

- **v2.1.2-dstream-native-unbounded** — Processing-time windowing + watermarks + `timerProcessing` on the native/direct backend. `window(Window.fixed/sliding/session/global)`, `withTrigger(Trigger.*)`, `withAllowedLateness(d)`, `withWatermark(WatermarkStrategy.*)` operators added to `DStream`. `timerProcessing(durationMs)(k => Iterable[B])` fires synchronously per unique key on DirectRunner. `directCapabilities` now includes `EventTime` + `WatermarkPerfect` (v2.1.2+). `collectRequiredCaps` extended for `_dag_window`, `_dag_withWatermark`, `_dag_withTrigger`. `dstreams.ssc` updated. 30 tests green.

- **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` Beam-style API on the native bounded backend. `Pipeline.create(name).read(DSource).map/filter/flatMap/keyBy/combinePerKey/merge.run(Backend.Direct|Native)`. `InMemory.source` / `InMemory.runAndCollect` testing helpers. `DSource.fromLocalSource` bridge from `Source[A]`. `Feature.DistributedStreams` flag. `Capability` negotiation at `.run()` (`CAPABILITY_MISMATCH` on missing cap). `examples/distributed-streams.ssc` (3 bounded examples). `dstreams-plugin` (23 tests green). `BuiltinsRuntime.setupPluginCompanions` extended for all DStream companion objects.

- **x402-cardano-scalus-static-exunits** — Added static `ScalusExUnits` wiring for the Cardano/Scalus claim Tx draft: configured ex-units now flow into `ClaimTxPlan`, the bloxbean redeemer, and balanced fee estimation. Live node-backed ex-unit evaluation remains open.

- **x402-cardano-scalus-fee-balancer** — Added `ScalusFeeBalancer` and `BloxbeanClaimTxBuilder.draftBalanced(...)`: the Cardano/Scalus claim Tx draft now estimates protocol min-fee from Blockfrost protocol params and final serialized CBOR size, with async params wiring for Blockfrost-backed builders. Live script ex-unit evaluation remains open.

- **v1.53** — Traditional Payment Processors spec landed (`docs/specs/traditional-payments.md`). `PaymentProvider` SPI (14 methods: PaymentIntent / Customer+Vault / Subscriptions / Refunds+Disputes / Webhooks), fiat-aware `Money` type (Long minor units, ISO 4217 + crypto codes, banker's rounding, `allocate`), `WebhookReceiver[E]` primitive (HMAC/RSA verify + `SeenKeyStore` idempotency + replay protection), `IdempotencyKey` threading, `SCAChallenge` / 3DS2 flow, subscription lifecycle (proration / dunning / invoicing), full dispute lifecycle + evidence submission, vault (`Customer` + `StoredMethod` + `Mandate`). Closes `chargeCard()` placeholder from v1.38 Payment Request. Adapters deferred to v1.53.1–v1.53.7 (Stripe canonical first, then PayPal/Braintree, Adyen/Checkout.com, Square). Bank rails (SEPA/ACH/Pix/FedNow) deferred to v1.54+. Go/no-go: **go**.

- **v1.52.1** — Deploy plugin landed (`runtime/std/deploy-plugin/`). Six-verb `DeployTarget` SPI, `DeployGroup` orchestrator with DAG resolver (Kahn's + cycle detection), Parallel/Sequence/Pipeline execution modes, three failure policies (RollbackAll/ContinueRemaining/AbortRemaining), `LocalSubprocessTarget` adapter (fat-JAR subprocess + `/_health` polling), `DeployManifest` parser, `StateBackend` SPI with `NoopStateBackend`, `ArtifactRegistry` (10 artifact kinds), `Manifest` AST extended with `deploy`/`groups`/`environments`/`state` fields, `ssc deploy` CLI with `plan`/`status`/`envs` subcommands + `--env`/`--group`/`--target`/`--dry-run`/`--verbose` flags, `examples/deploy.ssc` annotated example, `docs/user-guide.md §26`.

- **x402-cardano-blockfrost-protocol-params** — Added typed `BlockfrostClient.getProtocolParams()` for `/epochs/latest/parameters`, covering fee constants, execution prices, collateral bounds, and Plutus cost models. This is the prerequisite data source for Cardano/Scalus protocol-params fee balancing.

- **x402-cardano-scalus-tx-witness** — Hardened the bloxbean Scalus claim transaction draft with explicit fee/TTL/validity body fields, computed script data hash via bloxbean `ScriptDataHashGenerator`, and relayer `VkeyWitness` signing via `TransactionSigner`. Protocol-params fee balancing and live ex-unit evaluation remain open.

- **v2.1.0** — Distributed Streams spec landed (`docs/specs/distributed-streams.md`). Full Apache Beam model: `DStream[T]` / `KV[K,V]` / `Pipeline` / `PipelineResult`; event-time watermarks (`WatermarkStrategy`); Fixed/Sliding/Session/Global windowing; `Trigger` (AfterWatermark, AfterProcessingTime, AfterCount, Composite); panes (EARLY, ON_TIME, LATE) + accumulation modes (Discarding, Accumulating, AccumulatingAndRetracting); `Capability` enum (Set-based, checked at `.run()`); 5 first-class backends (Native v1.22 actors, Apache Spark, Apache Kafka Streams, Apache Flink, Apache Beam); `DSource[T]` / `DSink[T]` connector abstractions; `Coder[T]` unified serialisation with per-backend adapters; `DirectRunner` in-process test backend; integration bridges (`DStream ↔ Source[A]`, `DStream ↔ Dataset[T]`); 7 implementation phases (v2.1.1–v2.1.7). Go/no-go: **go**.

- **x402-cardano-scalus-tx-required-fields** — Extended the bloxbean Scalus claim transaction draft with optional collateral input and required signer key hash: `ScalusSettlerConfig.collateralRef` maps into body collateral, `relayerKeyHashHex` maps into body required signers, with validation and round-trip tests. Fee balancing and relayer vkey witness remain open.

- **v1.52** — Deploy spec landed (`docs/specs/deploy.md`). Five target categories (container/k8s/faas/static/traditional), dual CLI+manifest interface, 6-verb `DeployTarget` SPI + `outputs()` for cross-target wiring, `DeployGroup` orchestrator with parallel/sequence/pipeline modes + DAG dependency resolution + three failure policies, `DeployEnvironment` axis for local/test/staging/production environments with `base:` inheritance + multi-region fault tolerance + quorum-based health checks + blue-green slot switching (`instant`/`gradual`) + `ssc deploy switch` + `ssc deploy promote`. Hybrid stateless+optional-remote-state model. Per-provider adapters deferred to v1.52.1–v1.52.7. Go/no-go: **go**.

- **x402-cardano-scalus-tx-draft** — Added `BloxbeanClaimTxBuilder.draft`, a non-default bloxbean Transaction skeleton builder that serializes the escrow input, receiver output, Plutus V3 script, and Spend redeemer; tests round-trip through bloxbean `Transaction.deserialize`. Fee balancing, collateral, and relayer witness remain open.

- **x402-cardano-scalus-claim-tx-builder** — Added bloxbean Plutus redeemer construction for Scalus escrow claims: `EscrowRedeemerCodec.claim` encodes `Claim(coseSign1Bytes, coseKeyBytes)` as constructor 0, and `ClaimTxPlan.claimRedeemer` exposes it to the future transaction builder. Full transaction body / script witness / relayer witness remain open.

- **x402-cardano-scalus-settler-bloxbean** — Phase 4 wiring for Cardano/Scalus settlement: added `cardano-client-lib` dependency, `ScalusSettlerConfig`, typed `ClaimTxPlan`, injectable `ClaimTxBuilder`, `ScalusSettler.preprod/mainnet`, and Blockfrost submit pipeline tests. The default builder still fails explicitly until real Plutus witness/redeemer construction is implemented.

- **x402-cardano-scalus-escrow-ref** — Added typed `ScalusEscrowRef` parsing/validation for canonical `<64-hex-txhash>#<output-index>` refs and wired `CardanoProvider.Scalus` verification to reject malformed nonce-slot escrow refs before settlement.

- **x402-cardano-scalus-claim-codec** — Factored Scalus claim-message binary encoding into `x402-core` as `ScalusClaimMessageCodec`, with unit tests for domain/receiver/uint64 layout. The Cardano client and facilitator now share the same encoder.

- **x402-cardano-scalus-server-verify** — `CardanoProvider.Scalus` now verifies the structured Scalus claim-message CIP-8 proof and requires the escrow UTxO ref in `authorization.nonce`, while preserving the legacy Blockfrost description-signing + payer-balance verification path. Claim Tx / UTxO datum validation remains planned in the settler.

- **x402-cardano-scalus-claim-message** — Client-side Scalus payment mode: `Wallets.cardano(hex, network, scalusMode = true)` signs a structured `ScalusClaimMessage` instead of `req.description`; `PaymentRequirements.scalusEscrowRef` is propagated through `authorization.nonce`; Cardano payload tests verify the COSE payload and Ed25519 signature. Real settler / claim Tx remains planned.

- **v1.51** — Streams with Backpressure spec: `docs/specs/streams.md` — full design for `Source[A]` / `Sink[A]` / `Flow[A, B]` / `Stream[A]`; hybrid pull/push (push surface, `request(n)` credit underneath); default credit = 16 / buffer = 16 (Akka default); two-level architecture (uniform `Computation`-based semantics + JVM/interpreter VT+ArrayBlockingQueue and JS `async function*` fast paths); overflow strategies aliased from `actors.ssc Overflow`; errors flow downstream + cancel upstream; integration adapters for Generator/SSE/WS/Actor/UI-signals (`Source.signal` scoped to v1.51.5); effect-row integration deferred to v1.51.6+. Go/no-go: **go** — implementation sequence v1.51.1 → v1.51.2 → v1.51.3 → v1.51.4 → v1.51.5 defined.

- **x402-cardano-scalus-address** — Cardano/Scalus escrow Phase 3 slice: `EscrowScript.address(network)` derives stable CIP-19 enterprise script addresses from the committed Plutus validator bytes. Golden mainnet/preprod bech32 tests pin the address surface for future reference-script deployment and bloxbean claim Tx work.

- **wallet-vault-encrypted-js** — JS-side encrypted vault persistence: `EncryptedLocalVaultJs.create/load/generate/delete/save` wraps the shared `EncryptedLocalVault` core with `VaultFileStore`; browser default uses IndexedDB, falls back to localStorage, then in-memory storage for Node/tests. Durable data remains the shared `VaultFile.toJson` shape. Added Scala.js tests for create/load/unlock, account metadata persistence, and delete.

- **sbt-interop-plugin** — `ssc generate-facade` CLI command + `sbt-scalascript-interop` sbt plugin (Tier 3 interop): `ssc generate-facade <artifactDir> [-o <outDir>]` reads `.scim` artifacts and writes Scala 3 facade sources (delegating to `FacadeGenerator.generate`); `ScalascriptInteropPlugin` (Scala 2.12, `tools/sbt-plugin/`) auto-hooks into `Compile / sourceGenerators` via `sscGenerateFacade` task; `sscArtifactDir` and `sscBinary` settings; 4 scripted tests (`basic`, `identity`, `multi-module`, `no-artifacts`); Mill module trait + scala-cli directive documented in `docs/specs/scala-interop.md §6`.

- **watch-100ms** — watch reload benchmark + hot-path hashing cleanup: new `ssc watch-bench [--cycles N] [--target-ms N] [--require-target] <file.ssc>` command runs watch reload cycles against a temporary copy and reports warm-up/p50/max; `WatchCycleBenchTest` covers the incremental path; `ParseCache` and `SectionSnapshot` SHA-256 hex encoding now use a direct char loop instead of per-byte `String.format`; incremental typer reuses precomputed section hashes when building snapshots for retyped sections.

- **v1.50-native-p2-followup** — Complete native-image Phase 2/3 gaps: `native-release.yml` now builds `ssc-plugin-host.jar` via `sbt pluginHost/assembly` and bundles it at `lib/ssc-plugin-host.jar` inside every platform archive so `--plugin <jar>` works out-of-the-box in native mode; `BackendRegistry.findPluginHostJar` extended to check `<binary-dir>/lib/` first (matches archive layout) then flat `<binary-dir>/` (dev installs); `BACKLOG.md` updated to mark phases 2–4 as landed.

- **v1.50-native-p4** — Native plugin binary guide: `docs/native-plugin-guide.md` — complete plugin-author guide for building GraalVM native binaries from existing plugins. Covers: `GraalVMNativeImagePlugin` sbt setup, minimal reflection/resource config, agent-based config generation, CI matrix (ubuntu/macos arm64/macos x86_64), `plugin.yaml` manifest for native executables, and JAR-vs-native comparison table. No core changes. Fully JVM-free `ssc (native) → wire protocol → plugin (native)` deployments now documented.

- **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections via Loom virtual threads. `WsLoad10kTest` asserts ≥ 99 % open, heap growth < 1 GB, `WsConnection.activeCount` tracks correctly, all drain cleanly. Auto-skips when `ulimit -n` < 22 000. Satisfies the Project-Loom follow-up deferred since 2026-05-21.
- **v1.50-native-p3** — `ssc-plugin-host` + automatic native bridge: new `tools/plugin-host` sbt subproject (`pluginHost`); `SubprocessHost` main class loads any plugin JAR via `URLClassLoader` + `ServiceLoader` (works in JVM subprocess), then enters the stdio-json wire protocol loop as the server side (handles `describe`, `compile`, `openSession`, `session.feed`, `session.close`, `invokeHandler`, `shutdown`). `BackendRegistry.addPluginJar` detects native-image mode via `org.graalvm.nativeimage.imagecode` system property; in native mode locates `ssc-plugin-host.jar` next to the binary or in `$SSC_HOME/lib/`, finds `java` via `java.home` system property or PATH, then spawns `java -cp plugin.jar:host.jar scalascript.plugin.SubprocessHost plugin.jar` and registers the result via the existing `SubprocessBackend` mechanism. Plugin authors change nothing. Build: `sbt pluginHost/assembly` → `ssc-plugin-host.jar`.

- **v1.50-native-p2** — GraalVM native-image build infrastructure: `sbt-native-packager` plugin added; `cli` project gains `GraalVMNativeImagePlugin` with `--no-fallback`, `--initialize-at-build-time=scala,scalascript`, reflection + resource config file pointers; `native-image-configs/reflect-config.json` (SLF4J binding, Scala runtime, upickle, scala-meta, borer, all ServiceLoader-discovered backend/frontend/server/plugin implementation classes); `native-image-configs/resource-config.json` (`META-INF/services/**`, logger-sources); `.github/workflows/native-release.yml` CI matrix (ubuntu x86_64, macos arm64, macos x86_64) triggered by version tags, uploads `.tar.gz` to GitHub Release; `stage` task renamed to `installBin` to avoid conflict with sbt-native-packager. Build: `sbt cli/graalvm-native-image:packageBin`. Regeneration guide in `native-image-configs/README.md`.

- **v1.50-native-p1** — Replace snakeyaml with pure-Scala `SimpleYaml` parser: new `lang/yaml` sbt module containing `SimpleYaml` (block/flow maps+sequences, scalars, comments, literal block scalars, inline map entries from sequence items); wired into `core` and `backendConfigRuntime` (previously standalone, no deps); all 7 call sites migrated (`Parser.scala`, `LockFile.scala`, `LocalRegistry.scala`, `SscpkgManifest.scala`, `PluginManifest.scala`, `ConfigParser.scala`, `Main.scala loadSopsSecrets`); snakeyaml removed from `build.sbt`; 21 new `SimpleYamlTest` tests.

## 2026-05-26

- **v1.12.3** — Effects stdlib: `StdEffectsRuntime` gains `NonDet` (multi-shot, `choose(options)`) and `Reader` (capability, `ask()`) globals; typed discharge signatures registered in `Typer` prelude for `runLogger`/`runLoggerJson`/`runLoggerToList`, `runRandomSeeded`, `runClockAt`, `runEnvWith`, `runState`, `runHttp`/`runHttpStub` (each accepts a body carrying the named effect row); `EffectAnalysis.verify` promoted to error-level with `asErrors: Boolean = true` default; `examples/algebraic-effects.ssc` showcase (Logger + State interleaved, NonDet multi-shot, capability vs handler styles, stdlib runner signatures); 2 new `StdEffectsTest` tests (42 total). v1.12 effects sprint complete.
- **NativeContext state-bag** — added shared `feature*` and scoped `featureLocal*` state APIs to `NativeContext`; HTTP client config now routes through `NativeContextFeatureKeys` while existing named methods stay compatible.
- **v1.12.2** — One-shot effect runtime: `EffectAnalysis.Result` gains `multiShotEffects: Set[String]`; `collectFromStats` detects `val __multiShot__ = true` in effect objects; `_handleOneShot` JS runtime emitted in preamble with per-dispatch `_resumed` flag; `genHandleForm` routes to `_handleOneShot` when all ops are one-shot; interpreter `evalHandle` gains `multiShotEffects: Set[String] = Set.empty` parameter and raises `InterpretError("One-shot violation: …")` on double-resume; `Interpreter.multiShotEffects` populated from `EffectAnalysis` in `runInit`; 3 new tests (`EffectAnalysisMultiShotTest`); `StdEffectsTest` (40) and `RestartableTest` (17) still green.
- **v1.12.1** — Typed Algebraic Effects — type system foundation: `SType.EffectRow` case with optional open tail variable; `SType.Function` extended with `effects: EffectRow` (default empty, backward-compatible); `show`/`subst`/`freeVars` updated; Rémy-style row unification in `Unifier.solveEffectRow`; `TypeParser` extended with `!` operator and `parseEffectSet` for effect-annotated function types; `multi effect` keyword in `Parser.preprocessEffects` emits `val __multiShot__ = true`; `EffectAnalysis.verify` cross-checks typer-declared effects against reachability analysis; 14 new tests (`EffectTypeTest`, `EffectAnalysisVerifierTest`).
- **v1.49** — macOS distribution: `ssc package --target macos --distribution` (codesign + notarize + DMG via `xcodebuild archive` + `exportArchive` + `notarytool` + `hdiutil`); `ssc publish --target macos --appstore` (fastlane `mac_appstore` lane, generates `Fastfile` by default); `ssc toolchain setup-signing` (`fastlane match init` for ios/macos); `fastlane` and `ios-deploy` added to toolchain tool map and target requirement lists; `--no-dmg`, `--no-notarize`, `--distribution` flags; 8 new tests (26 total in SwiftUIBuildCliTest).
- **v1.48.5** — `ssc publish --target ios` (TestFlight + App Store via fastlane): generates `Fastfile` with `testflight`/`appstore` lanes by default; `--fastlane` uses existing Fastfile; `--testflight`/`--appstore` route selection; `--api-key-path` / `APP_STORE_CONNECT_API_KEY_PATH`; `--submit-for-review`; `--release-notes`; 6 new tests (18 total in SwiftUIBuildCliTest).
- **wasm-backend-phase1** — WASM backend extended to compile `scalascript` / `ssc` blocks alongside `scala` blocks (Phase 1); integration tests and `wasm-scalascript.ssc` example (Phase 2); `//> using dep` directive hoisting for Scala.js dep declarations + `wasm-http.ssc` Fetch API example (Phase 3). `WasmBackend.acceptedSources` grows to include `scalascript` and `ssc`. 31 tests passing.
- **v1.48.4** — `ssc package --target ios` → signed `.ipa`: `xcodebuild archive` + `exportArchive`; ExportOptions.plist generated from frontmatter `bundle-id:`/`team-id:` or `SSC_TEAM_ID` env; `--export-method` (development|ad-hoc|enterprise|app-store, default: development); `--team-id`; `--out`; 4 new tests (12 total in SwiftUIBuildCliTest).
- **Interpreter server extraction** — `WebServer`, interpreter HTTP handler, WS proxy/session/connection runtime, in-process backend transport, and server-specific tests moved to new `backendInterpreterServer` module behind `InterpreterServerSupport`.
- **v1.12** — Typed Algebraic Effects spec: `docs/specs/algebraic-effects.md` — full design for `A ! Eff` type syntax, open effect rows with implicit tail, `effect Foo { … }` / `multi effect Foo { … }` declarations, handler discharge rules, capability passing (`?=>`), one-shot fast paths (coroutine VT on JVM/interpreter; `function*`/`yield` on JS), multi-shot via Free-monad, interaction matrix with `throws`, `Async`, `Free`, and `MonadError`. Go/no-go: **go** — implementation milestone sequence v1.12.1 → v1.12.2 → v1.12.3 defined.
- **SQL plugin cleanup** — interpreter `transaction` fenced blocks now route through `SqlBlockRunner.runTransaction`; JDBC transaction execution and result encoding live in `runtime/std/sql-plugin` instead of interpreter core.
- **v1.48.3** — `ssc run --target ios --device` real device via ios-deploy: xcodebuild arm64 + automatic signing (`-allowProvisioningUpdates`) + `ios-deploy --bundle ... --no-wifi [--debug|--justlaunch]`. `--device-id <udid>` for specific device. Same `--console`/`--no-rebuild` flags as simulator path.
- **v1.48.2** — `ssc run --target ios` one-command iOS Simulator launch: xcodebuild → boot latest iPhone sim → open Simulator.app → install → `simctl launch`. `--console`/`--no-console` (default: stream logs), `--rebuild`/`--no-rebuild` (default: incremental mtime check). `--target ios` canonical; `mobile-ios` alias kept. `pickIosSimulator` picks latest available iPhone from highest iOS runtime.
- **v1.48.1** — `ssc run --target macos` one-command wrapper: generates Swift Package, runs `swift build`, launches binary. `ssc package --target macos` now includes `swift build`. Target renamed `desktop-macos` → `macos` (alias kept). `swiftAppName` helper derives Swift product name from `.ssc` name frontmatter.
- **v1.48** (SwiftUI Phase 3) — Reactive list lowering: `ForSignal` → `ForEach` with `ForCtx` for index-aware `RemoveSelfFromList`; `@Observable AppModel` emitted when list signals present (Observation framework, iOS 17+); 11 new tests (41 total). v1.48 now feature-complete.
- **v1.48** — JavaFX Typed Route Clients: same-process in-process BackendTransport for JavaFX; typed route client codegen for JavaFX mode.
- **v1.47** — JavaFX Desktop Frontend: JavaFX renderer with reactive View DSL and full SwingFrontend-parity API.
- **v1.46** (all phases complete) — Typed Route Clients: generated frontend clients over backend routes. JVM/Swing in-process + JS HTTP transports. Auth/custom header injection, per-call header overrides, retry policy, cancellation tokens. Phase 7: SSE streaming. Phase 8 (WS): `stream: ws` → `_SscWsHandle` bidirectional subscriptions. Pagination: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` to URL on both JVM and JS targets. Extended Phase 5: `RouteDeriver` now covers `routes:` front-matter, `mount()` calls, and cross-file typed-handler analysis (`(input: T) => ...` → `requestType = "T"`); `Parser.parseFile` passes `baseDir` for handler lookup.
- **v1.45** — JVM Desktop Frontend: Swing-based desktop frontend; reactive View DSL; JvmUiRuntime; `ssc run --frontend swing`.
- **v1.44** — Full-Stack In-Process Transport: BackendTransport + same-process fetch dispatch; Swing/JVM frontend reaches backend without HTTP.
- **v1.43** — Electron JVM REST Backend: `ssc run --mode server` + Electron renderer; REST-over-localhost JVM backend for Electron apps.

## 2026-05-23

- **v1.42** — Native Platform P3: Electron Renderer — Electron shell + Node.js IPC bridge; `ssc run --frontend electron`.
- **v1.41** — Native Platform P2: Toolchain UX — native build CLI ergonomics; `ssc native` subcommand improvements.
- **v1.40** — Native Platform P2: Web Renderer Update — updated Electron-embedded web renderer; renderer protocol version bump.
- **v1.39** — Native Platform P1: IR Foundation — new IR nodes + codegen for native platform targets.

## 2026-05-21

- **v1.26** — `sql` fenced code blocks (JDBC): all 7 phases complete. `Lang.Sql` + `isSql`; dedicated `ir.Content.SqlBlock` IR node with bind-list; `backend-sql-runtime` module (H2 + SQLite bundled; Postgres/MySQL via `dep:`); `SectionRuntime.runSqlBlock` + interpreter + JvmGen codegen; `given Connection` override; `${expr}` → `?` bind parameters (safe-by-default, no SQL injection possible). `ssc check` rejects sql blocks on non-JVM backends with `UnsupportedJdbcUrl` diagnostic. 7 phases, conformance suite included.
- **v1.37** — Typer: `ssc check` 33→94 examples — typer fixes raising passing conformance suite from 33 to 94 examples.
- **v1.36** — Parser bugfix: `preprocessInlineImports` ordering — fixed parse-order regression in inline import preprocessing.
- **v1.35** — `run-jvm` artifact caching — incremental rebuild avoidance for JVM-target `.ssc` scripts.
- **v1.34** — REPL Debugger — interactive breakpoint + step-through debugger in the REPL.
- **v1.33** — Interpreter lazy loading Phase 2 — deferred plugin loading; faster cold start.
- **v1.32** — `runtime/std/pwa-plugin`: Progressive Web App support — service worker, manifest generation, offline mode.
- **v1.31** — `transaction` fenced block — database transaction scope (`transaction { … }`) as a language construct.
- **v1.30** — REPL web-aware mode + `mount()` intrinsic — `ssc repl --web`; `mount()` hot-replaces running server routes.
- **v1.29** — DAP Debugger (`ssc debug`) — Debug Adapter Protocol server; VS Code / IDE debugger integration.
- **v1.28** — Config System — `config.ssc` front-matter + `ssc.Config.*` typed accessor; environment overrides.

## 2026-05-20

- **v1.27** — Browser-side SQL (sql.js / DuckDB-Wasm): all 7 phases complete. `backend-sql-runtime-js` module; `SqlRuntimeJsEmit` preamble + registry-init; JsGen `sql` block codegen (→ async `sqlQuery` / `sqlExecute` calls); NodeBackend + WasmBackend wiring with `package.json` dep emit (sql.js / `@duckdb/duckdb-wasm`); `UnsupportedJdbcUrl` diagnostic for `jdbc:` URLs on browser targets; examples + conformance.
- **v1.25** — JavaScript / Node.js fenced code blocks — `js` and `node` fenced blocks executed natively in JS target; seamless JS interop from `.ssc`.
- **Spark backend** — v1.25 § 9.5 complete end-to-end: Phase A (SPI + local session), B.1 (`--spark-master`), B.2 (`ssc submit` fat JAR via `spark-submit`), C.1-C.3 (Spark SQL + DataFrames + typed readers + schema bridge), D (`@SqlFn` UDF bridge), E (Scala 3 native `Encoder` derivation via `Mirror`, Option/nested/collection fields), F (Kafka Streams backend + Streaming DSL), G (Hive metastore + `@TempView` + `Dataset.fromTable`), MLlib M.1-M.5 (dep auto-emit, Vector encoder, Pipeline, model save/load), Lakehouse L.1-L.2 (Delta Lake auto-detect + config). L.3 (Iceberg) + L.4 (Hudi) deferred — blocked on upstream Spark 4 artifacts not yet published. 204+ `SparkGenTest` cases.
- **Wallet SPI** — Scala.js cross-compile sprint: wallet interface + Scala.js cross-compiled runtime; browser + JVM wallet stubs.

## 2026-05-19

- **v1.24** — Language features: pattern matching extensions, string interpolation improvements, type inference fixes, sealed-trait enhancements.
- **v1.23** — Cluster management: membership view + events, Phi-accrual failure detection, Bully + Raft leader election, config distribution, rolling-restart drain, cluster metrics, external-coordinator adapter (etcd/Consul/ZooKeeper).
- **v1.22** — Distributed map-reduce: `Dataset[T]` API over v1.6 distributed actors; coordinator-dispatched partitions; shuffle; configurable failure handling.
- **v1.21** — Local map-reduce (`Dataset[T]`): lazy fluent API; sequential + parallel local execution; streaming via v1.10 generators.
- **v1.20** (all sub-versions) — DSL primitives + `runtime/std/parsing`: user-defined string interpolators, parser-combinator library, error recovery, indentation-aware parsing, multi-pass pipeline.
- **v1.19** — URL / dep imports: `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)` resolver with `ssc.lock` SHA-256 integrity.
- **v1.18** — `package` keyword + std layout migration: `package foo.bar` declarations; `runtime/std/*` reorganised under package hierarchy.
- **v1.17** — MCP support (client + server): full MCP 2025-03 + OAuth 2.1 + OIDC compliance; AS + RS + OIDC IdP; WebAuthn passkey grant; persistent stores; observability; CLI `ssc oauth` subcommand.
- **v1.13** — Final Tagless ergonomics: `using` auto-resolution, context bounds, cross-file trait inheritance with HKT, sealed-trait extension dispatch.
- **v1.9.x** — Actor internals refactor: mailbox + scheduler rewrite; reduced allocation on hot actor paths.
- **v0.12** — SSR + client hydration: Declarative Shadow DOM via `wc()` + zero-JS-rerender hydration guard.
- **v0.11** — i18n / l10n: `translations:` front-matter + `t(key)` / `setLocale(code)` intrinsics across all backends.
- **v2.0** (MVP) — Separate compilation: artifact format, `InterfaceExtractor`, `ArtifactIO`, `InterfaceScope`, `Linker` (FQN rewrite), `ModuleGraph`, six CLI commands. Full pipeline deferred; see [BACKLOG.md](BACKLOG.md#v20--separate-compilation-of-modules).

## 2026-05-18 and earlier

- **v1.16** — Restartable errors via algebraic effects: `perform`/`handle`/`resume` across all backends.
- **v1.15** — Checked errors via `throws`: dual-encoding (`throws` / `throwsRaw`), `attemptCatch`, `HasStackTrace`, platform-exception shims.
- **v1.14** — Metaprogramming MVP: `inline def`/`val`/`if`/`match`, `compiletime.summonInline`, `derives` recipes for Eq/Show/Hash/Order.
- **v1.11.5** — `Free[F, A]` as stdlib type: user-facing Free monad in `runtime/std/free.ssc`.
- **v1.11** — Continuation-based `Async`: rewrite on top of v1.9 coroutines; `Computation[A]` shim; ≥20% allocation reduction.
- **v1.10** — Generators: `flatMap`, `zip`, `zipWithIndex`; all three backends; streaming foundation.
- **v1.9** — Coroutine primitive: `Coroutine[A, B]`; interpreter + JvmGen + JsGen; 19 conformance tests.
- **v1.8.1** — Direct-syntax extensions: additional monad `do`-notation shapes; error-channel integration.
- **v1.8** — Direct-syntax do-notation: `for`/`yield` over arbitrary monads; all phases; conformance suite.
- **v1.7** — Plugin packaging & discovery: `.sscpkg` format, `pkg:` URI resolver, `ssc install`, local registry.
- **v1.6** — Actors (Erlang-style, WebSocket-distributed): local actors, supervision trees, distributed via WS; all backends.
- **v1.5** — Transport layer: TLS + HTTP/WS clients + streaming (Phases A–D′; NIO migration deferred).
- **v1.4** — Standard-library effects: `State`, `Reader`, `Writer`, `IO`, effect-system stdlib.
- **v1.3** — Runtime upgrades: real-thread Async (virtual threads), persistence layer, Async-integrated WS.
- **v1.2** — Auth follow-up: combined example + WebAuthn / passkeys.
- **v1.1** — Standard type-class hierarchy: `Functor`, `Applicative`, `Monad`, `Foldable`, `Traversable`, etc.
- **v1.0** — WebSocket production-readiness: Sprints 1–4, 6 (Sprint 5 deferred).
- **v0.10** — Extended component pack: Card, Switch, Alert, Tag, Stats, Empty, Toolbar, Tree, Stepper, Lightbox, FileUpload, DateInput/Picker, TimePicker, Combobox, RangeSlider, Carousel.
- **v0.9** — Standard component pack + Optics second pass: 8-tier UI component library; Index optic (`.index(i)` / `.at(key)`).
- **v0.8** — Web Components target: `ssc emit-wc`, `customElements.define`, Shadow DOM, hydration guard.
- **v0.6** — Optics: Lens / Prism / Optional / Traversal across all backends.
- **v0.5** — Interpreter performance Tier 1: dispatch-table rewrite; ~3× faster on typical workloads.
- **Backend SPI v0.1** (Stages 1–9.1) + followups: 9-module sbt layout, SPI traits, in-process + out-of-process plugins, intrinsic extraction (`std.http`, `std.ws`, `std.auth`, etc.), `ssc fmt`.
- **Scala ↔ ScalaScript interop** — Tiers 1 + 2: `@ssc` annotation → `.ssc` stub generation; Scala callers import generated stubs.
