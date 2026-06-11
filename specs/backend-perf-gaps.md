# Backend performance gaps (Tier 2)

## Overview

Three diagnosed-but-open performance/measurement items, in priority order. Each
is a real sub-project; this spec captures scope, the known traps, and acceptance
so the work can be claimed independently.

## Items

### T2.1 — bench-honesty (measurement integrity) — `bench-honesty-varying-data` ✓ SUBSTANTIALLY DONE (2026-06-11)

**Problem.** Many sub-µs cells in the cross-backend bench table are HotSpot/JIT
folds of loop-invariant work, not real throughput (verified: jvm instance-field
0.0003 → 0.0079 ms with a per-iter barrier). Perf decisions rest on these
numbers, so the table must be trustworthy before further tuning.

**Known trap (do not repeat).** A naïve "de-fold via `Bench.opaque`" makes the
**interpreter's JIT bail to tree-walk** (interp arith-loop 0.287 → 3043 ms;
instance-field 0.0068 → 31.7 ms). A `VmCompiler.compileInto` identity case is not
enough — the while-loop / FastTier / bytecode matchers bail first.

**Two viable directions (pick one per workload):**
- (a) make `opaque` JIT-transparent across *all* interp matchers (while-loop,
  FastTier, bytecode), or
- (b) redesign folded workloads to consume loop-varying data so no backend can
  fold them.

**Acceptance:** every corpus workload either consumes varying data or uses a
JIT-transparent opacity barrier; re-published table has no fold-artifact cells;
interp JIT does not regress on the redesigned workloads (spot-check 3 cases).

- [x] Workloads audited; fold-artifact cells identified. Complete across three
      docs: `cross-backend-gap-analysis.md` §3 (compiled fold cells: instance-field,
      tuple-monoid, bool-predicate, either-chain, option-chain, literal-match;
      honest cells: arith-loop, nested-loop, pattern-match-heavy, list-fold,
      typeclass-monoid), `js-honesty-audit.md` (JS column clean), and the new
      `interp-honesty-audit.md` (interp column clean).
- [x] **`off`-baseline honesty defect found + fixed (2026-06-11).** The algebraic
      loop eliminators (`tryFoldInvariantAccumLoop` + `tryClosedFormPolyLoop`, the
      T2.3 const-prop folds) ran *unconditionally* — `tryFastWhileAssign` was gated
      only by `debugHooks`, so `scripts/bench off` silently kept folding
      (`pureCallSum` 0.003 ms "on" **and** "off"). Gated them behind
      `FastTier.enabled`; now `off` reports the honest un-folded 11.748 ms (~3900×
      fold), default unchanged. This is direction (a) for the *interp* baseline:
      the no-JIT switch now actually disables the fold, making the de-folded cost
      measurable. 1605 tests green.
- [x] Interp JIT non-regression spot-checked: `pureCallSum` 0.003 (unchanged),
      `arithLoop` 0.248, `instanceFieldAccess` 0.035 — all default-on numbers held.
- [x] **Direction (b) — `tuple_monoid` de-folded (2026-06-11).** The one fold cell
      with an automated compiled cross-backend measurement. Was loop-invariant on
      all backends (`jvm_tupleMonoid` 0.011 µs — HotSpot hoisted `last = k`;
      `js_tupleMonoid` ref-copied a frozen const; interp `(1,2)++(3,4)` hoisted by
      `tryHoistedPureWhile`). Rebuilt the tuple from the loop counter each iteration
      and accumulate all components → no backend folds. After: `jvm_tupleMonoid`
      **205 µs** (~18000×), `js_tupleMonoid` **1688 µs**, interp **~14 ms** (1000
      iters). Remaining jvm/js gap is honest codegen difference. Details:
      `docs/bench/interp-honesty-audit.md`.
- [x] **Interp column verified honest (2026-06-11).** A/B (`interp` vs `off`) on the
      remaining audit-flagged cells that exist as interp benches: `eitherChain`
      0.002↔0.017 ms (honest, JIT speeds real work), `optionChain` 0.002 ms ON
      (honest by analogy — `off` un-measurable due to the bench-harness
      `initBuiltins`-skip gotcha `Undefined: None`, a harness artifact not a fold),
      `instanceFieldAccess` 0.035↔8.277, `arithLoop` 0.248↔2.746. `bool-predicate` /
      `literal-match` are **not** interp benches (JS-only / ad-hoc). So no interp cell
      is a measurement artifact.

**T2.1 status: substantially complete.** The *automated* benchmark harness is now
honest — `off` baseline fixed, the one automated compiled fold (`tuple_monoid`)
de-folded, interp column verified clean, JS column audited clean, Rust/JVM
anti-fold documented. The cross-backend-gap doc's other compiled fold cells
(`instance-field` etc.) were **ad-hoc one-off JVM probes with no standing
automated cell**, so nothing dishonest is published by the harness.

- [x] **OPTIONAL follow-up DONE (2026-06-11) — corpus wall-table de-folded
      (`bench-honest-corpus-seed`).** The cross-language wall table
      (`bench/run.sc` → `ssc bench --machine`) still folded six `bench/corpus`
      cells to sub-nanosecond compiled numbers. Converted them to a shared
      **carried 64-bit LCG** anti-fold idiom (`def workload(seed: Long)`):
      `instance-field`, `tuple-monoid`, `bool-predicate`, `either-chain`,
      `option-chain`, `literal-match`. The harness (`BenchCmd.generateWrapper`)
      is now arity-aware and feeds an **opaque** seed (JVM `_ssc_sink.get()`,
      atomic load); `bench/run.sc` passes the opaque rust seed `_s`. After
      (M1): jvm `tuple-monoid` 2 ps → 0.087 ms, `instance-field` 0.32 µs →
      6.2 µs, `bool-predicate` 19 ns → 0.81 µs; rust column restored from `n/a`
      (see the `.toInt` fix below). Idiom doc:
      `docs/bench/corpus-antifold.md`. The honest workloads keep their no-arg
      signature and numbers.
- [x] **emit-rust `.toInt` correctness fix (2026-06-11).** Exposed by the above:
      ScalaScript `Int` maps to rust `i64`, but `.toInt` emitted `as i32`, so an
      `i32` result couldn't be passed to an `i64` (`Int`) parameter (E0308) —
      every seed workload's rust build failed. `RustCodeWalk` now emits
      `as i32 as i64` (32-bit truncation semantics, i64 type). Broader than the
      bench: any `someLong.toInt` flowing into an `Int` context was affected.

### T2.2 — JS persistent map (HAMT) — `js-persistent-map-hamt` ✓ DONE (2026-06-11)

> **Landed (2026-06-11):** design + implementation in
> [`specs/js-persistent-map-hamt.md`](js-persistent-map-hamt.md). Duck-typed
> `_HAMT` (native-Map read interface) + `_isMap()` helper replacing the 71
> `instanceof Map` sites; `_Map`/`updated`/`removed`/`filter` route to `_HAMT`.
> p2 sweep `2d0b780d6`, p1+p3 activation `a653cd331`. Full suite 1609 green;
> micro-bench O(n²)→O(n log n), ~100× at N=4000.

**Problem.** JS `map-ops` is ~40× slower than JVM because the runtime models
immutable `Map` as a native `Map` copied on every update (O(n) `new Map(obj)`).

**Constraints (verified).** A persistent (HAMT) structure either (a) needs a new
runtime type, but **70 `instanceof Map` sites** across the JS runtime couple to
native `Map` (completeness risk), or (b) an in-place/CoW mutation hack
(silent-corruption risk via aliasing). Neither is a safe quick landing.

**Acceptance:** `map-ops` JS closes most of the gap to JVM with no correctness
regression; all 70 native-`Map` couplings either migrated or proven safe; JS
conformance + map tests green.

- [x] HAMT (persistent) `Map` runtime type added (`_HAMT`, p1+p3 `a653cd331`).
- [x] All 71 native-`Map` coupling sites migrated via `_isMap` (p2 `2d0b780d6`); grep
      `instanceof Map` = 0; native↔HAMT interop verified by the green suite.
- [x] `map-ops` O(n²) copy eliminated → O(n log n) (~100× at N=4000); full suite
      1609 green, no conformance regression. (Cross-backend `map-ops` table
      re-measure is an optional p4 follow-up.)

### T2.3 — JIT const-propagation — `ssc-jit-const-propagation` ✓ DONE (2026-06-11)

Generalises invariant-call folding in the interpreter JIT.

- [x] Stage 2: pure-function calls with literal/invariant args — memoise once.
      Implemented as `EvalRuntime.tryFoldInvariantAccumLoop` (landed `3174c0b4c`,
      "fold invariant recursive eval loops"): folds `while i<N do { acc = acc +
      f(stableArg); i += step }` by evaluating the bytecode-JIT-direct call once
      and multiplying by the iteration count.
- [x] Stage 3: scalar-evolution-style range folding for counter loops.
      Implemented as `EvalRuntime.tryClosedFormPolyLoop` (landed `abe7e4d02`,
      "Gauss closed-form recognizer"): recognises a 1-/2-param FunV whose body is
      degree-1 in its parameter(s) (`walkLinearPoly`) and replaces
      `while i<N do { acc = acc + f(i); i += step }` with the algebraic Gauss sum
      `a*step*K*(K-1)/2 + (a*s+b)*K`, bypassing bytecode JIT entirely.
- Both fire from the FastTier loop dispatch (`EvalRuntime.scala:2726-2729`).
- Gate (verified 2026-06-11): `JitLintTest` + `SscVmTest` (closed-form 1-param /
  2-param / block-wrapped / val-bound-global + invariant-fold cases) +
  `ConstFoldJsGenTest` = **277 tests green**. Bench A/B (`scripts/bench interp
  'pureCallSum$'`): **0.003 ms/op** (was ~0.25 ms pre-fold per `abe7e4d02`; ~83×;
  native JVM floor for this shape is 0.247 ms, so the closed form eliminates the
  loop). `pureCallSum2` 0.29→0.003 (97×), `pureCallSumBlock` 0.28→0.003 (93×);
  `pureCallSumIf` unchanged (conditional body rejected by the linear grammar — by
  design).
- **Closure note:** both stages landed under their own perf commits before this
  spec item was tracked; this entry records the discovery + verification, not new
  code. Coverage is the 2-assign (counter+accumulator) Int loop shape; broadening
  (product accumulators, Double, non-counter shapes) is out of scope — no bench
  currently demonstrates a gap there.

### T3 — Interpreter JIT object-construction coverage — `interp-jit-object-construct` ◑ PARTIAL (case-class landed 2026-06-11)

- [x] **Case-class / ADT construction (`instance-field`) — landed 2026-06-11.**
      `JitRefDispatch.newInstanceRef` + `walkRef`/`isRefValRhs` in both
      JavacJitBackend and AsmJitBackend now compile `val v = Vec(x, y)` in a hot
      loop. Builtin ADTs (Some/None/Right/Left/collections) are excluded so they
      keep their dedicated dispatch. **instance-field: 57 ms → 0.267 ms javac
      (213×) / 0.767 ms asm (74×)**, result identical to tree-walk, suite 1633
      green. Lints JIT-OK on both backends.
- [ ] **Tuple `++` (`tuple-monoid`, ~960 ms) — remaining; needs the
      WhileGenCtx path.** Investigated 2026-06-11 (reverted, not shipped). A
      prototype added `JitRefDispatch.newTupleRef`/`tupleIntElem` +
      `collectionConcat` TupleV + `walkRef` `Term.Tuple` + `walkLong` `t._n` +
      `isRefValRhs` tuple/`++` cases. **Tuple construction + element access JIT
      cleanly that way** (isolated micro-loops: construct+`._1` 633×, 4-tuple+`._4`
      563×, both correct). **But tuple-monoid still bailed**: its
      `def workload(seed):Long` var+while+return shape is compiled by the
      *optimised* WhileGenCtx / WhileJitEntry path (`walkLocalSlotCtx` /
      `walkRefArgCtx`, ~line 3826+/3990+), which **never calls `walkRef` /
      `isRefValRhs`** (verified by instrumentation: neither fired for the `++`
      case). So the real fix lives in the WhileGenCtx path — add ref-val bindings
      (`val t = … ++ …`), tuple construction, `++`, and `t._n` to its local-slot
      walkers, *or* make it fall back to `walkWhileAsStmt` (which does route
      through `walkRef`, where the prototype worked) on an unsupported val RHS.
      Narrow value (artificial `_tupleConcat` bench) vs. real WhileGenCtx work —
      deprioritised. Case-class went through `walkRef` because its body shape
      didn't match the WhileGenCtx fast path.


Surfaced by `bench-honest-corpus-seed`: once the folds were removed, two corpus
workloads showed pathological interpreter times because the bytecode JIT **bails
the whole loop to slow tree-walk** when the body constructs an object. Diagnosed
with `ssc lint-jit` + on/off A/B (the JIT contributes ~nothing — on≈off):

| Workload | interp (honest) | jvm | Lint bail | Root cause |
|---|---|---|---|---|
| `instance-field` | ~57 ms | 6 µs | `workload` NOT JIT | `Vec(x, y)` case-class **constructor** in the loop body unsupported → tree-walk |
| `tuple-monoid` | ~960 ms | 0.09 ms | `workload` NOT JIT | tuple literal `(a, b)` construction + `++` on non-primitive → tree-walk |

**Not a bail (do NOT "fix"):** `bool-predicate` (0.8 µs), `either-chain`,
`option-chain`, `literal-match` all lint **JIT OK**; their 9–12 µs interp cost is
*honest* per-iteration work (monadic `Either`/`Option` allocation + closures), not
a tree-walk fallback. The AOT backends are faster only because escape analysis
removes those allocations — a real codegen-quality gap, not a measurement issue.

**The fix (large, two-backend, core-VM).** Teach the bytecode VM to construct
ref-bank objects:
- New construct opcode(s) in `SscVm` (`runtime/backend/interpreter/.../vm/SscVm.scala`):
  allocate a `Value.InstanceV` / `Value.TupleV` with N numeric/ref fields onto the
  ref-bank. The VM already reads them (`GETFI`, `ISTAG`, `RETREF`) and the dual-bank
  LExpr layer already has `LApplyR1/R2` + `LRefFieldGet` — this adds the missing
  *producer* side.
- `VmCompiler` lowering for case-class / tuple constructor calls + the `++` tuple op.
- Parity across both JIT backends (`JavacJitBackend`, `AsmJitBackend`). Safe
  increment: land Javac first; ASM keeps bailing (no regression) until brought up.
- Update `JitBailReason` + `JitLint` so the linter stops reporting the now-supported
  forms; extend `SscVmTest` with construct-in-loop cases as the gate.

**Recommended order:** InstanceV/case-class construction first (case classes in
loops are ubiquitous in real code; tuple `++` is narrower). **Acceptance:**
`instance-field` lints JIT-OK and drops to low single-digit ms; no regression on
the bench sweep or the backendInterpreter suite (Javac + ASM).

## Out of scope

- AOT codegen passes (`aot-hoist`, `aot-mutual-tco`) — already largely landed.
- Any change that trades correctness for a bench number.

## Decisions

- **Honesty before more tuning** — T2.1 first: untrustworthy baselines make every
  later perf change a guess.
- **HAMT is a dedicated sub-project, not a quick fix** — the 70 coupling sites
  make a contained landing impossible; scope it explicitly.

## Results

**T2.3 — ssc-jit-const-propagation — DONE 2026-06-11.** Discovered already
implemented + wired + tested; verified and closed (no new code). Stage 2 =
`tryFoldInvariantAccumLoop` (`3174c0b4c`), Stage 3 = `tryClosedFormPolyLoop` +
`walkLinearPoly` (`abe7e4d02`), both dispatched from the FastTier loop path. Gate
green: JitLintTest + SscVmTest fold cases + ConstFoldJsGenTest = 277 tests.
`scripts/bench interp 'pureCallSum$'` = 0.003 ms/op (≈83× over the ~0.25 ms
pre-fold baseline; native JVM floor 0.247 ms). Full details + closure rationale in
the T2.3 item above.

T2.1 (bench-honesty) and T2.2 (js-persistent-map-hamt) remain open.

**interp-curried-method-dispatch — DONE 2026-06-11.** Follow-up to the
typeclass-fold using-cache (`f1917d2ca`). After given-resolution stopped
dominating, JFR on `typeclassFoldMacro` (the honest `combineAll[A: Monoid]`
bench, 300× over `List(1..10)`) showed the remaining ~84% was plain tree-walk
*dispatch* overhead, not the JITed per-element combine. Three contained
hot-path allocation fixes in `EvalRuntime.evalCore`:

1. **Curried-method fast-path** (the dominant 740 MB/op allocator). A curried
   method call `recv.m(a)(b)` (e.g. `xs.foldLeft(z)(op)`) has a `Term.Apply`
   head whose own head is a `Term.Select`. It previously walked all ~40 curried
   special-form extractors (`withFixedUuid(x){body}`, `runState(s0){body}`, …),
   and their inner `Term.Apply.unapply` allocated a `Tuple2` every call. Every
   curried special form has a `Term.Name` head (verified — none is a `Select`),
   so curried *method* calls are now routed straight to `evalApplyGeneral`.
2. **summon-key cache.** `summon[TC[T]]` rebuilt its lookup strings
   (`typeToString` key `"Monoid[A]"` + synthetic context-bound param name
   `"A$Monoid"`) on every eval. Both are pure functions of the immutable AST
   node → cached per-node in `Interpreter.summonKeyCache` (`Array(key, synth)`);
   only the env/global lookups stay per-call.
3. **`Term.Select` no-arg field access → type-test.** The `Term.Select(qual, sn)`
   extractor allocated `Tuple2 + Some` on every `a.b`; `.name`/`.qual` are
   already typed fields, so a `case sel: Term.Select` type-test removes it.

**Result (`scripts/bench interp typeclassFold`, A/B):** `typeclassFoldMacro`
1.722 → 1.323 ms/op (−23%); alloc 394 → 138 KB/op (−65%). No regression on the
hot-path sweep (recursionFib 1.218 ms = baseline, instanceFieldAccess 0.042 ms,
nestedMatchExpr 0.007 ms, rangeSum/hofPipeline fused sub-µs). Full suite green
(1623 tests). All three fixes are broad (they touch *every* curried method call
and *every* no-arg field access), so they also lift any method-dispatch-heavy
workload, not just typeclass-fold.
