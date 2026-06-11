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

- [ ] Workloads audited; fold-artifact cells identified.
- [ ] Each fixed via (a) or (b); rationale recorded per workload.
- [ ] Interp JIT non-regression spot-checked.

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

### T2.3 — JIT const-propagation — `ssc-jit-const-propagation`

Generalises invariant-call folding in the interpreter JIT.

- [ ] Stage 2: pure-function calls with literal args — memoise once.
- [ ] Stage 3: scalar-evolution-style range folding for counter loops.
- Gate: `JitLintTest` + interp bench A/B (`scripts/bench interp`), record numbers.

## Out of scope

- AOT codegen passes (`aot-hoist`, `aot-mutual-tco`) — already largely landed.
- Any change that trades correctness for a bench number.

## Decisions

- **Honesty before more tuning** — T2.1 first: untrustworthy baselines make every
  later perf change a guess.
- **HAMT is a dedicated sub-project, not a quick fix** — the 70 coupling sites
  make a contained landing impossible; scope it explicitly.

## Results

<!-- at verify -->
