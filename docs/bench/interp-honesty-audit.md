# Interpreter bench honesty audit

Companion to [`js-honesty-audit.md`](js-honesty-audit.md) and
[`rust-jvm-antifold-fairness.md`](rust-jvm-antifold-fairness.md). This one
covers the **interpreter** column and the `scripts/bench off` baseline that the
other audits rely on as ground truth.

## Method

For an in-process interpreter cell the decisive de-fold A/B is
`scripts/bench interp <cell>` (JIT on) vs `scripts/bench off <cell>`
(`SSC_FASTTIER=off SSC_JIT_BYTECODE=off`). If `off` is dramatically slower the
`on` number reflects genuine JIT optimisation of real work; if `off` is the
*same*, the work is being eliminated by a path the `off` switch doesn't disable.

## Findings (2026-06-11)

| Cell | JIT on | `off` (before fix) | `off` (after fix) | Verdict |
|---|---|---|---|---|
| `arithLoop`           | 0.248 ms | 2.746 ms | 2.746 ms | honest — bytecode JIT speeds a real loop (11×) |
| `instanceFieldAccess` | 0.035 ms | 8.277 ms | 8.277 ms | honest — JIT speeds real work (236×) |
| `eitherChain`         | 0.002 ms | — | 0.017 ms | honest — JIT speeds real work (8.5×) |
| `optionChain`         | 0.002 ms | — | *(n/a)* | honest by analogy to `eitherChain`; `off` cannot be measured — the tree-walk path hits the bench-harness `initBuiltins`-skip gotcha (`Undefined: None`), not a fold |
| `pureCallSum`         | 0.003 ms | **0.003 ms** | **11.748 ms** | was a **baseline artifact** — see below |

`arithLoop`, `instanceFieldAccess`, and `eitherChain` are honest: turning the JIT
off makes the loop tree-walk for real, so the on-numbers are genuine throughput,
not folds (consistent with the cross-backend audit's "interp runs the real loop").

`optionChain` ON is 0.002 ms (same JIT'd Option-chain shape as `eitherChain`),
but its `off` run throws `Undefined: None` — the documented bench-harness
`initBuiltins`-skip gotcha (`docs/benchmarks.md`): the no-JIT tree-walk resolves
`None` as a name, which the harness never initialised. This is a harness artifact,
not a fold and not a product bug (real `ssc run` initialises builtins). So the
interp column is honest across every audited cell.

`pureCallSum` was the outlier. Its 0.003 ms is the **Gauss closed-form fold**
(`tryClosedFormPolyLoop`, the T2.3 const-propagation feature) collapsing
`while i<N do { total = total + f(i); i += 1 }` to a constant. That is a real
interpreter feature and the on-number is legitimate — **but** before this fix the
fold ran *unconditionally* (the dispatcher `tryFastWhileAssign` was gated only by
`debugHooks`, never by `FastTier.enabled` or the bytecode flag). So
`scripts/bench off` — documented as the no-JIT baseline — silently kept folding,
reporting 0.003 ms for "JIT off". The de-folded cost was unmeasurable through the
standard switch.

## Fix

Gate the two algebraic loop eliminators (`tryFoldInvariantAccumLoop` +
`tryClosedFormPolyLoop`) behind `FastTier.enabled` (`EvalRuntime.scala`, the
`Value.BoolV(true)` arm of `tryFastWhileAssign`). Default behaviour is unchanged
(fold on → 0.003 ms); `SSC_FASTTIER=off` now genuinely disables it, so the
honest un-folded baseline (11.748 ms, a ~3900× fold contribution) is visible.
`docs/benchmarks.md` updated to describe the switch accurately.

Verification: `backendInterpreter/test` 1605 green (folds still fire by default,
so the `SscVmTest` closed-form/invariant cases pass unchanged); `interp
pureCallSum` 0.003 ms unchanged; `off pureCallSum` 0.003 → 11.748 ms.

## Relationship to the rest of T2.1

The cross-backend honesty *audit* (which cells are folds) is complete across the
three audit docs:
[`cross-backend-gap-analysis.md`](cross-backend-gap-analysis.md) §3 (jvm/compiled
fold cells: `instance-field`, `tuple-monoid`, `bool-predicate`, `either-chain`,
`option-chain`, `literal-match`), `js-honesty-audit.md` (JS clean), and this doc
(interp clean + the `off`-baseline defect, now fixed).

What remains of T2.1 is the **fix** for the *compiled* (jvm/js/rust) fold cells —
direction (b), redesign those workloads to consume loop-varying data so no
backend folds them. That is a per-workload benchmark-design project that changes
what each cell measures; it is the open continuation. The interp side and the
`off` baseline are now honest.

## Direction-(b) fix landed: `tuple_monoid` (2026-06-11)

The one fold cell with an automated *compiled* cross-backend measurement
(`RuntimeBench.{jvm,js}_tupleMonoid`) was loop-invariant on every backend:

- `jvm_tupleMonoid` **0.011 µs/op** — HotSpot hoisted `last = k` (a 100k-iter
  loop cannot run in 11 ns).
- `js_tupleMonoid` 26.7 µs — V8 didn't fold but only ref-copied a frozen const.
- interp `tupleMonoid` 0.008 ms — `(1,2)++(3,4)` is constant, so
  `tryHoistedPureWhile` hoisted it and the empty counter loop folded (0.008 ms
  on **and** off — a different unconditional fold path than the FastTier folds).

Fix (direction b): build the tuple from the loop counter each iteration and
accumulate all four components, so the result depends on the whole loop and no
backend can fold. The interp variant keeps the `++` monoid op (the workload's
intent: `(i,i+1) ++ (i+2,i+3)`). `modTupleMonoidVal` is left unchanged — it
*deliberately* validates the constant-hoist optimisation.

| Cell | before | after | note |
|---|---|---|---|
| `jvm_tupleMonoid` | 0.011 µs | **205 µs** | was a fold; now real work (~18000×) |
| `js_tupleMonoid`  | 26.7 µs  | **1688 µs** | now real per-iter tuple allocation |
| interp `tupleMonoid` | 0.008 ms | **~14 ms** (1000 iters) | on==off, no fold; tuple-`++` ~20 µs/iter (typeclass dispatch, un-JIT'd) |

The remaining jvm/js gap (205 vs 1688 µs) is now an **honest codegen difference**
(HotSpot scalar-replaces the short-lived tuples; V8 allocates), not a fold.

**Still open:** `instance-field`, `bool-predicate`, `either-chain`,
`option-chain`, `literal-match` are folds **only in the interp-only
`InterpreterBench`** column (no automated compiled cross-backend cell exists for
them); the cross-backend-gap doc measured their compiled folds ad-hoc. De-folding
those is the same per-workload pattern shown here, applied as further slices.
