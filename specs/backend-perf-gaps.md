# Backend performance gaps (Tier 2)

## Overview

Three diagnosed-but-open performance/measurement items, in priority order. Each
is a real sub-project; this spec captures scope, the known traps, and acceptance
so the work can be claimed independently.

## Items

### T2.1 — bench-honesty (measurement integrity) — `bench-honesty-varying-data`

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
- [ ] **REMAINING (open continuation):** fix the *compiled* (jvm/js/rust) fold
      cells via direction (b) — redesign those workloads to consume loop-varying
      data. Per-workload benchmark-design project that changes what each cell
      measures; not landed here. The interp side + `off` baseline are now honest.

### T2.2 — JS persistent map (HAMT) — `js-persistent-map-hamt`

**Problem.** JS `map-ops` is ~40× slower than JVM because the runtime models
immutable `Map` as a native `Map` copied on every update (O(n) `new Map(obj)`).

**Constraints (verified).** A persistent (HAMT) structure either (a) needs a new
runtime type, but **70 `instanceof Map` sites** across the JS runtime couple to
native `Map` (completeness risk), or (b) an in-place/CoW mutation hack
(silent-corruption risk via aliasing). Neither is a safe quick landing.

**Acceptance:** `map-ops` JS closes most of the gap to JVM with no correctness
regression; all 70 native-`Map` couplings either migrated or proven safe; JS
conformance + map tests green.

- [ ] HAMT (or persistent-CoW) `Map` runtime type added.
- [ ] All native-`Map` coupling sites migrated/audited.
- [ ] `map-ops` JS gap closed; no conformance regression.

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
