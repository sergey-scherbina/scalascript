# Interpreter perf findings — 2026-06-02 JFR survey

A JFR-profile pass over the heaviest `InterpreterBench` benchmarks after
the Phase C bytecode JIT scorecard landed, intended to surface the *next*
optimization wave's targets before any code is written.

Profiling commands (all from `scripts/bench profile <pat>`):
- `recursiveEval` (12.93 ms/op, 383 KB/op)
- `recursiveEvalMixed` (13.43 ms/op, 476 KB/op)
- `patternMatchHeavy` (115.66 ms/op, 4.46 MB/op)
- `recursionFibMul` (6.10 ms/op, 56.9 KB/op)
- `recursionFibMulD` (6.29 ms/op, 57.8 KB/op)
- `instanceFieldAccess` (2602 ms/op, 131 MB/op — 1M-iter hot loop)
- `mapForeach` (533 ms/op, 43.6 MB/op — 500K callEntries)

Each `:gc.alloc.rate.norm` figure is bytes per op (the whole script run);
JFR allocation samples were aggregated by `objectClass`, weight is
sampler-weighted KB (not actual bytes — proportional, not absolute).

## Findings ranked by lever

### 1. `BytecodeJit.withInterp` leaked `ThreadLocalMap.Entry` per call

**Top sampled allocator on `recursiveEval`** before the fix: 17 samples,
weight ~10 MB/op (sampler-weighted). Stack:

```
ThreadLocal.set
BytecodeJit.withInterp
JitRuntime.invokeBytecode1
```

Cause: `withInterp` used `interpTls.remove()` when `prev == null`, which
deletes the per-thread `ThreadLocalMap.Entry`; the next outer call's
`interpTls.set(interp)` then re-allocates a fresh Entry. Net: one Entry
allocation per outer bytecode-JIT invocation, ×millions per script.

**Fix shipped this session** (commit deferred until accompanying report
lands): replace the `if prev == null then remove() else set(prev)` finally
with unconditional `interpTls.set(prev)`. Setting the slot to `null`
keeps the Entry intact; `readGlobalLong/Double` already guards on
`interp == null`. Per-thread memory cost: ~32 bytes that never shrink —
negligible.

**Result**: `ThreadLocalMap$Entry` drops out of the top-10 allocators on
`recursiveEval` (verified by re-profile after fix). Wall-clock unchanged
within JMH noise — the alloc pressure was modest relative to total
allocator bandwidth (~25 MB/sec on `recursiveEval`). Cleanup, not a
speedup.

### 2. `MethodHandle.invoke` auto-boxes the `Long` return on every JIT call

**Second top allocator on `recursiveEval`** (post-fix top java.lang.Long
samples remain at ~6, pre-fix were 14). Stack:

```
sun.invoke.util.ValueConversions.boxLong
java.lang.Long.valueOf
JitRuntime$.$anonfun$2(BytecodeJit$Result, Object)
BytecodeJit$.withInterp
JitRuntime$.invokeBytecode1
```

Cause: `JitRuntime.invokeBytecode1/2` uses `r.mh.invoke(arg)` rather than
`r.mh.invokeExact(arg)`. `invoke` performs signature adaptation including
primitive `long` → `java.lang.Long` autoboxing on the return path.
`invokeExact` would emit no box but requires the call-site type ascription
to *exactly* match the MH's `MethodType`.

**Why it's deferred**: BytecodeJit's `Result` has 4–5 distinct MH
signatures by `(paramIsRef, resultIsDouble)`:
1. `(long) long` — int param, int result
2. `(Object) long` — ref param, int result
3. `(double) double` — Double-typed body
4. `(Object) double` — ref param, double-typed body
5. `(long, long) long`, `(long, Object) long`, etc. — 2-arg variants

To use `invokeExact`, `invokeBytecode1/2` must dispatch on these cases
(branching is cheap; JIT compiles each branch as a typed call site). Risk:
a wrong type ascription throws `WrongMethodTypeException` at runtime — the
test suite catches this, but a single missed combination would land a
regression.

**Recommended next-session work** (`phase-c-bytecode-invokeExact`):
specialize `invokeBytecode1/2` into branches by `(isRef, isDouble)`,
calling `mh.invokeExact(...)` per signature. Skip the `marshalBytecode`
boxing for the primitive cases (read `Value.IntV.x: Long` or
`Value.DoubleV.x: Double` directly and pass primitively). The
`wrapBytecodeResult` side becomes `Computation.pureIntV(out)` /
`Value.doubleV(out)` with `out` typed as `Long` / `Double`. Expected win:
modest wall-clock (autoboxing is HotSpot-optimized for hot integers via
the long cache; expect 1–5% on `recursiveEval`-class shapes) but
meaningful alloc-rate reduction. JFR-profile after to verify
`java.lang.Long` drops out of the top samples.

### 3. `patternMatchHeavy` confirms `phase-d-patternmatch-double-slot`

Top-2 samples on `patternMatchHeavy`: **`Computation$Pure` (125 samples)
+ `Value$DoubleV` (121 samples)**. These two account for ~95% of the
4.56 MB/op allocation. Other classes are noise (< 1% combined).

This is the per-iter `interp.globals(accName) = Value.doubleV(acc)`
writeback inside `FastTier.tryDoubleAccumForeach` — exactly what
`WORK_QUEUE.md`'s `phase-d-patternmatch-double-slot` spec predicted (3.2
MB / op for the 100K outer iters). The DoubleV+Pure pair is the writeback
allocation plus the surrounding `Computation.Pure` wrapper from the
foreach driver. Removing the per-iter writeback (the slot-Tls trick in
the spec) closes that 3.2 MB.

**Recommendation**: do `phase-d-patternmatch-double-slot` per its
3-commit-split protocol in a fresh agent session. The lever is alloc-rate,
not wall-clock — JFR-profile after to verify the DoubleV+Pure pair drops
proportionally. Wall-clock impact estimated 1–5% (per the WORK_QUEUE
note); if the JFR drop is real and wall-clock is unchanged, the writeback
allocations are GC-bandwidth absorbing rather than cycle-burning.

### 4. `recursiveEval` confirms `phase-d-instancev-array-repr`

`Map$Map1` + `Map$Map2` together: ~9.5 MB sampled weight on
`recursiveEval` (out of ~78 MB total — ~12%). These are the
`InstanceV.fields: Map[String, Value]` HashMaps lookup-allocated by every
`inst.fields().apply("name")` in the generated Java match arms.

The `phase-d-instancev-array-repr` work currently running in the parallel
agent's worktree targets exactly this — switching to positional
`Array[Value]` storage eliminates the per-lookup HashMap walk and (on
construction) the Map allocations themselves. JFR-profile after that
lands should show `Map$Map1`/`Map$Map2` drop out of `recursiveEval`'s
top-10, and `instanceFieldAccess`'s 131 MB/op should fall sharply (this
bench is the dedicated floor for that work — currently ~2.6 µs/iter for
two HashMap field reads + a Term.Match dispatch).

### 5. `recursiveEval`'s `$colon$colon` (List cons cells) — 17 MB sampled

Top sampled allocator post-fix on `recursiveEval`. Likely arg-list packing
in `CallRuntime`/`tcoTrampoline` (every self-recursive call builds a
`List[Value]` for the arg snapshot). Profiling didn't pinpoint a single
hot caller — distributed across the runtime.

**Possible lever** (`recursive-arg-list-arraywise`): switch the
`tcoTrampoline` arg snapshot from `List[Value]` to `Array[Value]`. Not
trivial — the trampoline shape is load-bearing across multiple runtimes
(`TcoRuntime`, `CallRuntime`, `JitRuntime.tryBytecodeList`). Defer to a
multi-day project with its own JFR-baseline.

### 6. `mapForeach`: 43.6 MB/op on `(k,v) =>` 2-arg closure path

Confirms the `mapForeach` bench's stated purpose — `DispatchRuntime`'s
`Map.foreach` callEntry path is not covered by `FastTier`. Each iteration
allocates an entry tuple and dispatches through a generic 2-arg closure.

**Recommendation**: `fasttier-2arg-callentry` — extend `FastTier` with a
`tryDoubleAccumForeachMap` / `tryLongAccumForeachMap` that recognizes a
2-arg foreach closure (the LApply2 shape) and bypasses the entry
materialization. Floor bench is `mapForeach` (~1.07 µs/callEntry, 87
bytes/callEntry). Single-module change in `FastTier` + a hookup in
`DispatchRuntime.dispatchMap` "foreach" case.

## Non-findings (i.e. confirmed already-fast paths)

- **`recursionFib` / `recursionTco`**: alloc.rate.norm is small (~10
  KB/op) and stable. The Phase C BytecodeJit + TCO loop emission paths
  are the dominant cost. Don't optimize.
- **`recursionFibMul` / `recursionFibMulD`**: ~57 KB/op alloc. The per-op
  cost is dominated by the `HashMap.get("mul")` lookup chain (one per fib
  base case ×~1.3M calls) and the autoboxing from finding #2. Both
  benches reach `recursionFib`-level perf if globals are pre-cached at
  MH-build time, but that requires a globals-mutation invalidation
  protocol — non-trivial. Defer.
- **`recursionTco`**: 32 µs/op, allocates negligible — Phase C TCO loop
  emission gets us to JVM-codegen parity. Done.

## walkArm coverage on `recursiveEval`

The `phase-c-bytecode-wider-match` work item flags shapes that currently
bail in `walkArm`: guards, literal patterns, `Pat.Bind`, `Pat.Alternative`,
nested matches. `recursiveEval`'s `def eval(e: Expr): Int = e match …` has
three flat arms over `Pat.Extract(ctor, Pat.Var bindings)` only — fully
covered by `walkArm`. No bail on the hot path.

Conclusion: `phase-c-bytecode-wider-match` won't help `recursiveEval`. It
remains valid as cleanup work, to be done when a *specific* bench surfaces
a bailing shape. None currently does.

## Summary — next optimization wave priorities

| Rank | Item | Where | Expected lever |
|------|------|-------|----------------|
| 1 | `phase-d-instancev-array-repr` | parallel agent | Large — `recursiveEval` HashMap field reads |
| 2 | `phase-c-bytecode-invokeExact` | fresh session | Modest cycles + alloc on all `Long`/`Double`-result JIT calls |
| 3 | `phase-d-patternmatch-double-slot` | fresh session | Modest — `patternMatchHeavy` 3.2 MB/op DoubleV writeback |
| 4 | `fasttier-2arg-callentry` | single session | Modest — `mapForeach` 43 MB/op alloc |
| 5 | `recursive-arg-list-arraywise` | multi-day | Modest — recursive call arg snapshots |

Deferred / no-bench: `phase-c-bytecode-wider-match`,
`phase-c-bytecode-mutual`, MH globals precaching.

## Methodology used

Per `feedback_cross_module_commit_safety.md` rule 1 ("JFR-profile-first")
this session ran `scripts/bench profile` against each candidate, then
cross-checked sampled allocator weights against deterministic
`-prof gc alloc.rate.norm`. The two should move together — if a sampled
weight changes and `alloc.rate.norm` doesn't, treat the sample as
mis-attribution.

The `withInterp` fix in this session was the only fix landed: the alloc
samples showed the leak unambiguously, the fix was a single line, and the
post-fix profile confirmed `ThreadLocalMap$Entry` left the top-10.
Everything else surfaced is documented above for a fresh-context session.

---

## Session 2026-06-02 wins (post-original-survey)

Following the survey above, a second session landed 12+ commits on
top of the JFR findings. Each was JFR-validated or A/B-proven.

### Shipped commits

| commit | scope | bench δ |
|---|---|---|
| `e17c048a` | Double-globals reads via `readGlobalDouble` (Phase C extension) | `recursionFibMulD` parity with Int-globals shape (~6 ms) |
| `46e8a2d2` | `BytecodeJit.withInterp` TLS-Entry hygiene + JFR profile survey | alloc cleanup; next-wave roadmap |
| `280ae07f` | FastTier 2-arg foreach Map fast path | **mapForeach 532 → 110 ms (4.8×)** |
| `88f2179f` | Pure-const cache for `Term.Tuple` + `Term.ApplyInfix` | **tupleMonoid 415 → 132 ms (3.2×)** |
| `5f1b4ec1` | Pure-RHS hoist out of long-while loop | **tupleMonoid 132 → 0.2 ms (660×)** |
| `262ac02d` | foreach-hoist Set receiver | **patternMatchSet 117 → 8 ms (14.6×)** |
| `b0846e41` | foreach-hoist hierarchy (List/Set/Map × Long/Double) | **mapForeach 117 → 2.85 ms (41×), patternMatchWide 77 → 21 ms (3.7×)** |
| `4a983de6` | foreach-hoist (parallel agent) | **patternMatchHeavy 113 → 10 ms (11×)** |
| `7e7ebb71` | while-loop BytecodeJIT (parallel agent) | **arithLoop 2.86 → 0.28 ms (10.1×, JVM parity)** |
| `c2986e33` | phase-d-patternmatch-double-slot (parallel agent) | patternMatchHeavy alloc 4.9 MB/op → 1.7 MB/op (−65%) |
| `d48a2259` | Long + Map slot bypass | **patternMatchWide 38 → 29 ms (−24%), mapForeach 4.7 → 3.95 ms (−17%)** |
| `14c23556` | `LongEnvFn1`/`LongEnvFn2` traits — eliminate Long boxing in LApply/LApply2 | **pureCallSum alloc 24 MB/op → 34 KB/op (−99.9%); pureCallSum2 48 MB/op → 115 KB/op (−99.8%); wall-clock pureCallSum −28%, pureCallSum2 −32%** |
| `54a65870` | `Computation.purify` reuses cached `Pure` wrappers | recursiveEval Pure samples 34 → 21 (−38%) |
| `471b38d1` | invokeExact via typed JitInterfaces (parallel agent) | unboxed dispatch for bytecode-jitted fns |

### Final bench state (2026-06-02, ms/op, tight n=30)

| bench | ms/op | status |
|---|---:|---|
| arithLoop | 0.28 | JVM parity (while-JIT) |
| effectPure | 0.04 | floor |
| recursionTco | 0.034 | JVM parity (TCO loop) |
| tupleMonoid | 0.20 | floor (RHS hoist) |
| recursionFib | 1.20 | beats JVM-codegen (Phase C) |
| recursionFibD | 1.45 | floor |
| mapForeach | 3.31 | near floor (foreach-hoist + Map slot) |
| recursionFibMul | 5.85 | bound (Int globals HashMap) |
| recursionFibMulD | 6.0 | bound (Double globals) |
| patternMatchHeavy | 7.82 | foreach-hoist + double-slot |
| patternMatchSet | 8.0 | foreach-hoist Set |
| pureCallSum | 13 | per-iter eval dispatch — A.3 target |
| recursiveEval | 13 | HashMap field reads — **B target** |
| recursiveEvalMixed | 13.6 | same — B target |
| pureCallSum2 | 14 | per-iter eval dispatch — A.3 target |
| instanceFieldAccess | 15.6 | LMatch ceiling (HashMap residual) |
| patternMatchWide | 20.8 | 12-arm dispatch + Long slot |

### Strategic next phase

After this session, the cumulative wins exhausted every micro-opt the
JFR survey suggested. The remaining headroom is in three strategic
directions captured in `~/.discovering-knuth.md` and
`docs/instancev-array-repr-spec.md`:

- **Direction A** — BytecodeJit incremental extensions (5 slices,
  proven pattern). `WORK_QUEUE.md` items `phase-c-bytecode-block-single`,
  `phase-c-bytecode-if-in-while`, `phase-c-bytecode-pure-fn-call`,
  `phase-c-bytecode-foreach-static`, `phase-c-bytecode-block-multistat`.
- **Direction B** — `InstanceV` positional array fields (multi-day,
  5 sub-phases). `WORK_QUEUE.md` items
  `phase-d-instancev-array-repr-infra` through `…-flag-flip`.
- **Direction C** — direct-style eval (architectural, multi-week,
  deferred until A+B stable). Blocked on `direct-style-eval-spec`.

Each direction's verification protocol carries forward from this
survey: JFR-profile target bench → cross-check `gc.alloc.rate.norm`
→ A/B against pre-commit baseline → verify the target allocator class
drops out of the top-10 samples.
