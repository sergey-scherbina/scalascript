# Performance & Memory — v1.61

Status: **spec landed 2026-05-28. All v1.61 themes shipped (under different
milestone names) by 2026-06-02.** See §3 below for the original roadmap; see
§3b for the what-shipped summary and current benchmark numbers.

Companion docs:
- [`docs/specs/vm-jit-spec.md`](vm-jit-spec.md) — register VM + BytecodeJIT spec and end-to-end numbers
- [`docs/specs/vm-jit-next.md`](vm-jit-next.md) — next optimization phases (Directions A–C backlog)
- [`docs/specs/instancev-array-repr-spec.md`](instancev-array-repr-spec.md) — Direction B (InstanceV array repr)
- [`docs/interpreter-perf-findings-2026-06.md`](interpreter-perf-findings-2026-06.md) — JFR profiling 2026-06-02
- [`docs/benchmarks.md`](benchmarks.md) — how to run benchmarks

---

## 1. Goals

| Goal | Metric | Target |
|---|---|---|
| Interpreter throughput | wall-clock on `arith-loop` workload | 3–10× vs baseline |
| JS bundle size (trivial programs) | ungzipped Hello World | < 10 KB (from ~100 KB) |
| JS bundle size (non-trivial programs) | median corpus | ≥ 50% smaller |
| JVM arithmetic (effectful code) | `_binOp` dispatches / op | 0 (direct) |
| Allocation rate | allocations / eval of `arith-loop` | ≥ 50% lower |
| Artifact size | `.scim` / `.scjvm` files | ≥ 5× smaller |

No optimization PR is merged without before/after benchmark numbers from the v1.61.0 harness.

---

## 2. Benchmark infrastructure (`bench/`)

### Running

```bash
sbt cli/stage          # build the ssc CLI first
./bench.sh             # wall-clock all workloads (2 warmup + 7 reps)
./bench.sh --baseline  # same, writes bench/BASELINE.md
./bench.sh arith-loop recursion-fib  # specific workloads

# Quick non-blocking smoke checks
ssc bench --smoke
ssc bench --smoke --target-ms 250 --require-target
scripts/perf-smoke.sh --jmh

# JMH microbenchmarks (interpreter internals)
sbt "interpreterBench/Jmh/run"
sbt "interpreterBench/Jmh/run -i 5 -wi 3 -f 1 .*arithLoop.*"
sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"

# Bundle size tracking
./scripts/bundle-size.sh
```

### Corpus workloads (`bench/corpus/`)

Workflow manifest and baseline policy: [`bench/perf-manifest.yaml`](../bench/perf-manifest.yaml)
and [`bench/README.md`](../bench/README.md). Default benchmark runs are
informational. They become blocking only when a caller passes an explicit target
gate such as `--require-target --target-ms N`.

| File | Tests |
|---|---|
| `arith-loop.ssc` | `while`-loop summing 1..1M — `IntV` boxing, env lookup, while overhead |
| `recursion-fib.ssc` | naive fib(30) — call overhead, FrameMap construction |
| `recursion-tco.ssc` | accumulator sum to 100k — TCO path |
| `pattern-match-heavy.ssc` | sealed ADT × 5 cases × 100k matches — `matchPat` allocation |
| `effect-pure.ssc` | `runLogger { while loop }` — `Computation`/`FlatMap` wrapping for pure-body |
| `effect-stream.ssc` | `runStream { emit × 10k }` — effect dispatch + Source allocation |
| `tuple-monoid.ssc` | `(1,2) ++ (3,4)` × 100k — `_tupleConcat` hot path |
| `hello-world.ssc` | `println("hello")` — cold-start + bundle-size baseline |

---

## 3. Optimization roadmap

### v1.61.0 — Benchmark infrastructure ✓

Files: `bench/`, `runtime/backend/interpreter-bench/`, `scripts/bundle-size.sh`.

### v1.61.1 — Interpreter dispatch table

**Problem.** `DispatchRuntime.scala:24-468` is a 440-line linear pattern-match on `(recv, name, args)`. Every method call walks through up to ~300 cases with string compares. Additionally, 7 sequential `extensions.get` lookups happen per call even with no extensions registered.

**Fix.** Precompute `HashMap[(ReceiverTag, InternedName), (List[Value]) => Computation]`. Intern method names + identifier names at parse time (`String.intern()`) so hot-path comparisons reduce to reference equality.

Files: `DispatchRuntime.scala`, `Interpreter.scala`, `EvalRuntime.scala`.

### v1.61.2 — Computation pure-path elimination

**Problem.** `Value.scala:266-301` — every `Term.Apply`, `Term.ApplyInfix`, `Term.Select` in a non-effectful block still allocates `FlatMap(sub, k)` + a closure because the interpreter wraps everything in `Computation`. Re-association in `runUntilSuspension` doubles the allocation per step.

**Fix.** Per-AST `IdentityHashMap[Term, Boolean]` purity cache populated at first eval. For known-pure sub-trees, call sites skip `FlatMap` wrapping and return `Pure(v)` directly. The existing fast path at `EvalRuntime.scala:434+` is the template — extend systematically.

Files: `Value.scala`, `EvalRuntime.scala`, `BlockRuntime.scala`.

### v1.61.3 — Env representation overhaul

**Problem.** `BlockRuntime.scala:26,34-36,39,53,71,82` calls `local.toMap` on **every statement** in every block — O(N) copy per statement, quadratic over block length. The per-statement global refresh (`local.keys.foreach { interp.globals.get(k) }`) adds another O(local.size) pass. `while` loops rebuild the env map on every iteration.

**Fix.** Thread a `FrameMap` directly through `evalBlock` instead of converting to/from immutable Map on every statement. Use a single `FrameMap` across `while` iterations; only copy when needed for closures.

Files: `BlockRuntime.scala`, `EvalRuntime.scala`, `CallRuntime.scala`.

### v1.61.4 — Pattern-match compilation

**Problem.** `EvalRuntime.scala:661-674` tries cases linearly via `PatternRuntime.matchPat`, which allocates `Some/None` per attempt and re-looks up `typeFieldOrder` + re-does field extraction on every `Pat.Extract`.

**Fix.** Per `Term.Match`, compile a decision-tree closure `Value => Option[(Env, Body)]` cached by AST identity in `IdentityHashMap[Term.Match, Value => Option[(Env, Body)]]`. First call builds the tree; subsequent calls are direct function invocations.

Files: `EvalRuntime.scala`, `PatternRuntime.scala`.

### v1.61.5 — JS codegen inlining

**Problems.**
- Every tuple literal emits an IIFE `(() => { const t = [a,b]; t._isTuple = true; return t; })()`.
- Every `while`/`match` expression in statement position is wrapped in an IIFE.
- Every method call emits `_dispatch(obj,'method',[args])` even for statically typed receivers.
- Every function call emits `_call(fn, ...args)` even for known `def`s.
- Known accessors (`head`, `_1`, `length`) route through `_dispatch`.

**Fix.** Track statement vs expression context in JsGen. Drop IIFE wrappers in statement position. For known accessors emit `obj[0]`, `obj.length`. Type-aware dispatch skip: if receiver type is known, emit `obj.method(args)` directly.

Files: `runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala`.

### v1.61.6 — Preamble sub-capabilities

**Problem.** JS `Core` preamble bundles HTML DSL, JWT, IndexedDb, optics, JSON, signals, generators (~100 KB+) into every program. `hello-world.ssc` ships them all.

**Fix.** Split `Core` into: `Console`, `HtmlDsl`, `Optics`, `Scope`, `IndexedDb`, `Jwt`, `Json`, `Signal`. Extend `detectCapabilities` to identify each. Ship only what's used. Identical split for JVM `commonRuntime`. Hello World target: < 10 KB ungzipped JS.

Files: `JsGen.scala` (preamble split + `detectCapabilities`), `JvmGen.scala`.

### v1.61.7 — Memory representation

**Problems.**
- `IntV(Long)` pool covers only `[-128..1024]`; outside that every arithmetic result allocates. `DoubleV` has no pool.
- `TupleV(List[Value])` / `ListV(List[Value])` use `scala.List` (2 words/cons + boxed elem).
- `FunV` carries 8 fields incl. 3 `List[String]` + `List[Option[Term]]` usually `Nil` for hot lambdas.
- Every AST node carries `Option[Span]` (~80 B) retained at runtime even in production.
- `.scim`/`.scjvm` artifacts are pretty-printed JSON with double-encoded body — toolkit-demo.scjvm is 336 KB.

**Fix.** Widen `IntV` pool to `[-2048..16383]`; add `DoubleV` 0.0/1.0 pool. Switch `TupleV` to `Array[Value]`. Split `FunV` into hot `FunVCore` + `FunVMeta` sidecar. Move `Span` to `IdentityHashMap[AstNode, Span]` sidecar. Use `upickle.default.writeBinary` (MessagePack) for artifacts; accept both formats on read.

Files: `Value.scala`, `AST.scala`, `ArtifactIO.scala`.

---

## 3b. What shipped (2026-05-28 → 2026-06-02)

The v1.61 themes all shipped, though often under the milestone names in
`WORK_QUEUE.md` rather than the v1.61.x labels below.  The strategy evolved
from precomputed dispatch tables to a hot-spot JIT — faster to reach and with
far larger gains on the target workloads.

| v1.61 theme | What shipped | Gains |
|---|---|---|
| **v1.61.1** dispatch table | `DispatchRuntime` dispatch table → `LMatch` LExpr in `tryLongWhileAssign`; `SlotTable` replaces `LinkedHashMap` | `instanceFieldAccess` 2690 → 16.6 ms (162×) |
| **v1.61.2** pure-path | `Computation.purify` cached wrappers (−38% Pure allocs); `FastTier` foreach pre-resolve; `LApplyR1`/`LRefConst` dual-bank | `arithLoop` ~2–4× |
| **v1.61.3** Env overhaul | `SlotTable` + `FrameMap` throughout `tryLong`/`MixedLongWhile`; env not rebuilt per iteration | included in SlotTable gains |
| **v1.61.4** pattern-match | `CompiledMatch` + `ctorTagsInt` int-tag dispatch; ADT `match` → Java `switch(int)`; `LMatch` scrutinee caching | `patternMatchHeavy` ~1.3× from int-tag alone |
| **v1.61.7** memory repr | `InstanceV.fieldsArr: Array[Value]` replaces `Map[String, Value]`; direct index reads in `PatternRuntime` arm handlers | `recursiveEval` 12.9 ms (direction B activation) |

Additionally, a register VM + BytecodeJIT layer shipped (v1.62-equivalent work,
not in the original v1.61 spec), with substantially larger gains on integer-heavy
workloads:

| Workload | Baseline (tree-walk) | After JIT (2026-06-02) | Gain |
|---|---|---|---|
| `recursionFib` (fib 30) | ~28.9 ms | 1.21 ms | 24× |
| `recursionTco` (sum 100k) | ~1.08 ms | 32 µs | 34× |
| `arithLoop` (sum 1M) | ~85 ms | ~3.1 ms | 27× |
| `instanceFieldAccess` | ~2690 ms | 16.6 ms | 162× |
| `pureCallSum` | ~13 ms | 0.28 ms | 47× |

Full cross-backend numbers and JFR findings are in
[`docs/specs/vm-jit-next.md`](vm-jit-next.md) and
[`docs/interpreter-perf-findings-2026-06.md`](interpreter-perf-findings-2026-06.md).

**Outstanding from v1.61 spec:**
- v1.61.5 JS codegen inlining — partially done (IIFE removal); full typed-dispatch inlining still open
- v1.61.6 preamble sub-capabilities — tracked as `conf-fix` items in BACKLOG
- v1.61.7 `TupleV`→`Array[Value]`, `FunV` split, artifact binary format — deferred

---

## 4. Risk register

| # | Risk | Mitigation |
|---|---|---|
| R1 | Dispatch table misses a pattern-match arm → silent behavior regression | Comprehensive test pass; derive table from existing match by reflection if feasible |
| R2 | `FrameMap` mutation in concurrent contexts (async effects) | Audit; restrict mutation to single-threaded eval; keep persistent copy for closures |
| R3 | `.scim`/`.scjvm` format change breaks existing artifacts | Write binary; accept both binary and legacy JSON on read until v1.62 |
| R4 | Stripping `Module.sourceText` degrades error messages | Behind `Production` flag; default `Development` keeps source |
| R5 | Sub-capability split breaks programs using undetected features | Extend `detectCapabilities` first; add capability-missing runtime error with clear message |

---

## 5. Open questions (deferred to implementation milestones)

- **Q1** — Dispatch table: lazy (on first access) vs eager (at startup)?  
  Lean: lazy with 256-entry warm cache.
- **Q2** — Purity analysis: parse-time vs first-eval-time?  
  Lean: parse-time, stored in AST sidecar.
- **Q3** — Artifact binary format: MessagePack vs hand-rolled length-prefixed binary?  
  Defer to v1.61.7 design phase.
