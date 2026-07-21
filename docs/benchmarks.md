# Benchmarks

Single source of truth for every benchmark in this repo: what each one
measures, when to use it, and the one-line command that runs it.

Everything goes through `scripts/bench`. Run `scripts/bench help` for the
full command list; `scripts/bench list` queries both the interpreter and
compiler benchmark projects and enumerates every available `@Benchmark`
method without duplicates.

## One command per case

| You want to … | Command |
| --- | --- |
| Run all interpreter benches (Javac JIT) | `scripts/bench interp` |
| Run one interpreter bench | `scripts/bench interp recursionFib` |
| Run all benches with AsmJitBackend | `scripts/bench asm` |
| Run one bench with AsmJitBackend | `scripts/bench asm recursionFib` |
| Wall-clock all backends (ssc, ssc-asm, jvm, js) | `./bench.sh` |
| Wall-clock ASM backend only | `./bench.sh --backend ssc-asm` |
| Wall-clock v2 VM/source backends | `scripts/bench v2-backends [workload]` |
| Wall-clock v2 VM/JVM-bytecode lane | `scripts/bench v2-bytecode [workload]` |
| Compare interp vs JS vs JVM | `scripts/bench cross` |
| Measure codegen time | `scripts/bench gen` |
| Measure compile pipeline | `scripts/bench compile` |
| Measure one compiler phase | `scripts/bench compile parseActors` |
| Prove the off-mode still works | `scripts/bench off recursionFib` |
| Profile (alloc + GC) one bench | `scripts/bench profile recursionFib` |
| Wall-clock vs Scala/Node | `scripts/bench wall` |
| Verify bench infra is alive | `scripts/bench smoke` |
| List every available bench | `scripts/bench list` |

Default JMH config is `-wi 3 -i 5 -f 1` (~25 s per bench). Override with
`BENCH_WI`, `BENCH_MI`, `BENCH_F` env vars when you want tighter A/B:

```bash
BENCH_F=2 scripts/bench interp recursionFib   # two forks for a stable A/B
BENCH_WI=1 BENCH_MI=1 scripts/bench interp    # quick smoke across all benches
```

## What lives where

### JMH (the sbt-driven microbenchmarks)

| File | Class | Purpose |
| --- | --- | --- |
| `runtime/backend/interpreter-bench/.../InterpreterBench.scala` | `InterpreterBench` | Interpreter hot-path microbenchmarks (arith, recursion, pattern match, foreach, tuple, effects). |
| `runtime/backend/interpreter-bench/.../RuntimeBench.scala` | `RuntimeBench` | Cross-backend EXECUTION speed: `interp_X` vs `js_X` (Node subprocess) vs `jvm_X` (standalone JAR). |
| `runtime/backend/interpreter-bench/.../CrossBackendBench.scala` | `CrossBackendBench` | Cross-backend CODEGEN time: `jvmGen_X` / `jsGen_X` (no subprocess; measures the backend itself, not the output). |
| `lang/core-bench/.../CompilerBench.scala` | `ParserBench`, `TyperBench`, `UnifyBench`, `SsccFormatCompilerBench` | Parser, typer, unifier, and `.sscc` format benches — orthogonal to interpreter perf. |

If you are micro-optimising the interpreter, almost everything you need is
in `InterpreterBench`. The cross-backend benches are for periodic "how far
are we from native?" checkpoints.

### Wall-clock (subprocess-based)

| Path | Purpose | How to run |
| --- | --- | --- |
| `bench/corpus/*.ssc` + `bench/run.sc` | Workload sweep through the `ssc` CLI; checked-in summaries land in `bench/BASELINE.md`. | `scripts/runtime-bench.sh --baseline` |
| `bench/corpus/*.ssc` + `bench/run.sc --v2-backends` | Same corpus shape, limited to v2 VM, v2 JVM source backend, and v2 Rust source backend. Use this for Phase-3 separate-backend measurement baselines; unsupported rows remain `n/a`. | `scripts/bench v2-backends [workload]` or `./bench.sh --v2-backends ...` |
| `bench/corpus/*.ssc` + `bench/run.sc --v2-bytecode` | Same corpus shape, limited to the v2 VM and the in-process v2 JVM bytecode lane. Use this for Phase-4 bytecode-lane A/B work. | `scripts/bench v2-bytecode [workload]` |
| `tests/bench/{fib,sum,list-ops}.{ssc,scala,js}` | Cross-language wall-clock: same workload in ScalaScript / Scala-direct / Node. | `scripts/bench wall` (alias for `scala-cli tests/bench/run.sc`) |

Use the wall-clock benches when you need cold-JVM, fresh-process numbers
(JMH only measures the warmed-up steady state).

#### Anti-fold strategy (keeping the corpus honest across backends)

A constant-folding compiler can replace a whole benchmark loop with a single
constant load, reporting a dishonest ~0 ms. The corpus defends against this
**uniformly but minimally**:

- **Primary (all backends):** each folded workload carries a non-linear 64-bit
  LCG seed and consumes every result, so no backend can derive a closed form
  (see `docs/bench/corpus-antifold.md`).
- **Rust only — one extra barrier:** LLVM's scalar evolution is far more
  aggressive than HotSpot/V8 (it solves affine/polynomial recurrences
  symbolically — even opaque *inputs* don't stop it). So `bench/run.sc` adds a
  single `std::hint::black_box(...)` on the **first loop-carried reassignment**
  of each emitted `pub fn`. One barrier is necessary *and* sufficient: measured on
  `sumTco(100000,0)` at `-O3`, 0 barriers folds to 0.000001 ms, one barrier on the
  carried accumulator gives an honest 0.10 ms, and the loop's time scales linearly
  with the trip count. The earlier harness wrapped *every* assignment (3–4
  barriers/iter), which inflated rust loop cells 3–4× and made the column look
  slower than codegen-equal jvm; that redundant tax is gone (`recursion-tco`
  0.34 → 0.025 ms, now ≈ jvm). A single irreducible barrier still taxes rust on
  loops jvm folds for free — that asymmetry is real, not a harness defect.

#### Adding a new workload to the dashboard

The wall-clock dashboard (`bench/BASELINE.md`) is fed by `bench/corpus/*.ssc`,
**auto-discovered** by `bench/run.sc` — there is no registry to edit. To add one:

1. **Create `bench/corpus/<name>.ssc`.** It is a normal `.ssc` markdown doc: a
   title, a short paragraph saying what the workload measures and on which
   backends it is supported, then **one ` ```scalascript ` fence** defining the
   entry point. The harness (`ssc bench --machine`) calls one of:
   - `def workload(seed: Long): Long` — preferred. The harness feeds an opaque
     `seed`; carry it through a generator so nothing constant-folds (see below).
   - `def workload(): Long` — only when the body is already non-foldable.
   Return a `Long` and make the loop **consume every result** into it, or a
   backend may delete the loop. Put any fixtures (a top-level `val`, a
   `case class`) above the `def`, inside the same fence.

2. **Keep it honest (anti-fold).** Carry a per-iteration generator and feed its
   output into the work:
   - Default: the 64-bit LCG `s = s * 2862933555777941757L + 3037000493L`
     (relies on 64-bit wrap; fine for interp/JVM/Rust).
   - **If the value feeds an array index** (or anything where overflow must not
     escape a range): use **MINSTD** `s = (s * 48271L) % 2147483647L` (start
     `(seed % 2147483646L) + 1L`). Its product stays below 2⁵³, so it is *exact*
     in JS's f64 `Number`; a 64-bit-wrapping LCG overflows to `NaN` on JS and an
     `arr(NaN)` read returns `undefined` → crash → a misleading `n/a`. See
     `vector-index.ssc` / `array-update.ssc` for the pattern.

3. **Verify on one backend (fast):** `./bench.sh --backend ssc <name>`. A backend
   that can't run the workload (e.g. Rust has no `Vector`/`Array`/`LazyList`, JS
   has no `LazyList.from`) reports `n/a` — that is the honest support signal, not
   a failure. Run the other backends with `--backend jvm|js|rust`.

4. **Publish numbers.** `./bench.sh --baseline` regenerates the **whole** corpus
   table into `bench/BASELINE.md` (needs `bin/ssc` staged via `sbt installBin`,
   plus `node` / `scala-cli` / a Rust toolchain for those columns). To add just
   your rows without a full multi-backend sweep, run the single-backend commands
   above and paste a focused table into `bench/BASELINE.md` (as the
   *Collection-type microbenchmarks* section does).

The matching JMH micro (`InterpreterBench`/`RuntimeBench`) is **optional** and
separate — add a method there only if you want a warmed-up steady-state number;
note the scale caveat below.

### ⚠️ JMH and the corpus measure different *scales* under the same name

Several JMH methods in `InterpreterBench` share a name with a
`bench/corpus/*.ssc` workload but run a **different amount of work**, so
their absolute numbers are not directly comparable:

| Name | JMH `InterpreterBench` | `bench/corpus/*.ssc` |
| --- | --- | --- |
| `typeclassFold` | `combineAll(List(1,2,3,4))` **once** (a micro-call) | `300 × combineAll(List(1..10))` (a macro loop) |
| `stringSplit` | the full 300-iteration parse-and-sum loop | same 300-iteration loop |

So a reader comparing JMH `typeclassFold` ≈ 0.009 ms to the cross-backend
table's `typeclass-fold` ≈ 1.8 ms is comparing one call to three thousand —
both are honest, they just measure different scales. When a JMH method is a
deliberate micro-call, prefer a `…Macro` sibling that mirrors the corpus loop
for A/B work (e.g. `typeclassFoldMacro` was added for exactly this reason). If
you add a JMH method that diverges from its corpus namesake, either give it a
`Macro`/`Micro` suffix or note the divergence in its source comment.

## What each interpreter bench is for

Listed in `InterpreterBench.scala` order. The `name pattern` is what you
pass to `scripts/bench interp <pattern>` (regex; matched against the full
method name).

| Bench | What it stresses |
| --- | --- |
| `arithLoop` | Top-level `while` + arithmetic; no function calls. The simplest "is the loop interpreter alive?" target. |
| `recursionFib` | Classic `fib(30)` — recursive int arithmetic. Today's main BytecodeJit target. |
| `recursionFibD` | Same shape, `Double` params/return — exercises the BytecodeJit double subset. |
| `recursionFibMul` | `fib` whose base case multiplies by a top-level `val mul = 7` — exercises the free-name (global) read path. |
| `recursionTco` | Tail-recursive `sumTco` — exercises the BytecodeJit `while`-loop emission for self-tail calls. |
| `recursiveEval` | Recursive `Expr` evaluator over `Add`/`Mul`/`Num` — the canonical ADT-match workload. BytecodeJit ADT-match target. |
| `recursiveEvalMixed` | Same evaluator with a mixed `(scale: Int, e: Expr)` signature — tests per-param Object/long marshalling. |
| `patternMatchHeavy` | 3-arm `Shape` match over a `List`. Foreach + match + arithmetic. |
| `patternMatchWide` | 12-arm pure-int match — exercises the wide-arm dispatch table without `Double` noise. |
| `patternMatchSet` | Same as `Heavy` but over a `Set` receiver — exercises `dispatchSet.foreach`. |
| `pureCallSum` | 1-param pure `f(x) = x + 1` in a tight 1M loop — exercises the Tier-2b pure-call path. |
| `pureCallSum2` | 2-param parallel: `g(x, y) = x + y` — exercises `LApply2` raw-Long inlining. |
| `tupleMonoid` | `(1, 2) ++ (3, 4)` in a loop — tuple-concat intrinsic. |
| `effectPure` | `runLogger { compute(10000) }` — a PURE arithmetic loop inside an effect context. Baseline for "effect-typed but effect-free" code. Measured ~0.006 ms — already near-free (the body optimises), which is itself the evidence that pure-in-effect overhead is small (cf. direct-style-eval §10). |
| `effectOneShot` | A custom `effect Bump` performed N times, handler resumes exactly once (`resume(1)`). Isolates the **per-`perform` dispatch cost** of the one-shot regime (~µs/perform — the Perform→handle→resume→continuation trampoline, NOT `Pure`-wrapping). This is the regime that *could* in principle use the direct-style `EffectPerform` fast-path. |
| `effectMultiShot` | `multi effect NonDet` with 4 choose-points of 4 (256 branches); handler `resume`s once per option. Isolates the **multi-shot trampoline** — which REQUIRES the re-callable monadic continuation and therefore *cannot* use the exception model (direct-style-eval §10.1). Watch this stay correct + bounded as the effect runtime evolves. Both effect benches use `interp.run` (not `runSections`) so `multiShotEffects` is populated by EffectAnalysis. |
| `instanceFieldAccess` | Inline `while: total += p match { case Pair(a,b) => a+b }`. Post-LMatch (2026-06-02): whole loop in Long-slot array, ~16.6 ms/op (1M iters, 162× vs baseline 2690 ms). Remaining cost: HashMap reads inside `CompiledMatch.runValueLong`. |
| `mapForeach` | `Map(...).foreach((k, v) => …)` — 2-arg callEntry path; not yet FastTier-covered. |
| `option-chain` / `either-chain` / `hof-pipeline` / `range-sum` | Warmed HOF method-chain call targets. Kebab names are `scripts/bench` aliases for JMH methods `optionChain`, `eitherChain`, `hofPipeline`, and `rangeSum`. |
| `typeclass-fold` | Warmed context-bound typeclass fold target. Alias for JMH method `typeclassFold`; classified separately from the monomorphic standard-library HOF receiver path. |

## Type-level lambdas — capability tracker (not a perf bench)

Type lambdas are surface-only (types are erased at runtime in this
interpreter-first language), so there is nothing to micro-benchmark. The
equivalent "see what works / where the progress is" artifact is
`lang/core/.../typer/TypeLambdaProgressTest.scala`: `[now]` tests pin the current
parser/SType behaviour; `[target]` tests are `pending` and flip to passing as
`type-lambda-p2` lands. The passing-vs-pending split is the progress dashboard
(run `sbt "core/testOnly scalascript.typer.TypeLambdaProgressTest"`). A real
parse/typecheck-throughput bench is only worth adding once the surface parses.

## JIT backend selector

The bytecode JIT has two implementations of the `JitBackend` SPI:

| `SSC_JIT_BACKEND=` | Backend | Notes |
| --- | --- | --- |
| `javac` (default) | `JavacJitBackend` | AST → Java source → `javax.tools.JavaCompiler` → bytecode. Requires JDK (not JRE). ~50–100 ms cold-start per function. |
| `asm` | `AsmJitBackend` | AST → JVM bytecode directly via ASM 9.7. ~1–3 ms cold-start; no `javax.tools` dep. Steady-state performance identical. |

To A/B the two backends:

```bash
scripts/bench interp recursionFib   # Javac (default)
scripts/bench asm    recursionFib   # ASM
scripts/bench asm                   # all benches with ASM backend
```

Expected: numbers within ±5% at steady state. Cold-start (first iter, low
warmup) should show ASM 30–100 ms faster per function.

## Off-mode A/B (proving fall-backs work)

The interpreter has two opt-out flags:

| Flag | Effect |
| --- | --- |
| `SSC_JIT_BYTECODE=off` / `-Dssc.jit.bytecode=off` | Disables the bytecode JIT (both backends). Hot recursion falls back to `SscVm.exec`. |
| `SSC_FASTTIER=off` / `-Dssc.fasttier=off` | Disables `FastTier` (foreach-accumulator / pure-call shortcuts) **and the algebraic loop eliminators** (invariant-call memoise + Gauss closed-form). Falls back to the general dispatcher. Use this to get an honest **un-folded** baseline for closed-form-able workloads (e.g. `pureCallSum` 0.003 ms on ↔ ~12 ms off). |
| `SSC_JIT=off` / `-Dssc.jit=off` | Disables `SscVm.exec` as well — pure tree-walker. |

```bash
scripts/bench off recursionFib    # both BYTECODE + FASTTIER off
```

Expected result today: `recursionFib` 1.2 ms (on) ↔ ~28 ms (off). A roughly
**24×** gap; anything closer means the bytecode JIT isn't actually firing.

For pure-tree-walk numbers (no SscVm.exec either):

```bash
SSC_JIT=off scripts/bench off recursionFib
```

## Profiling

```bash
scripts/bench profile recursionFib
```

Adds both `-prof gc` (deterministic alloc rate / norm) and
`-prof jfr:configName=profile` (sampled allocation events + CPU). JFR
output lands under `runtime/backend/interpreter-bench/jfr-cpu-*` and
`*.jfr` files can be opened with `jfr print` or JDK Mission Control.

For interpreting alloc samples: cross-check `jdk.ObjectAllocationSample`
counts against `gc.alloc.rate.norm` — a sampler can over-attribute to a
hot leaf, so deterministic numbers are the tie-breaker.

## Smoke + manifest

- `scripts/bench smoke` runs **one** iteration of one bench
  (`InterpreterBench.arithLoop`) and writes raw JSON to
  `bench/jmh-smoke.json`. The point is "did the JMH plumbing break?", not
  any perf claim.
- `ssc bench --smoke` runs `bench/corpus/hello-world.ssc` through the
  interpreter — same intent, but exercises the CLI's `bench` subcommand.
- `bench/perf-manifest.yaml` is the machine-readable manifest the CI/gate
  policy reads from. Update it if you add a smoke target.

Default gate policy: **informational**. Numbers only become a non-zero
exit code if the caller explicitly passes `--require-target --target-ms N`.

## Adding a new benchmark

1. Add a `private val mod<Name>: Module = src("""…""")` block at the top
   of `InterpreterBench.scala` with a one-line comment explaining WHAT
   the workload is meant to stress (the WHY, not the WHAT of the code).
2. Add a `@Benchmark def <name>(): Unit = Interpreter(devNull).runSections(mod<Name>)`.
3. Smoke-run it with `BENCH_WI=1 BENCH_MI=1 scripts/bench interp <name>`
   and confirm the result looks sensible.
4. Add a one-line row to the table above.
5. If the new bench is going to appear in the smoke path, update
   `bench/perf-manifest.yaml`.

Avoid:
- Modules that depend on `BuiltinsRuntime.initBuiltins` (e.g. raw `Set(...)`
  constructors) — the bench harness skips that init, so use `.toSet`
  instead. `Map(...)` is fine because it routes through `intrinsics/Core`.
- Workloads under ~100 µs/op — too small to separate from JMH noise.
- Workloads over ~10 s/op — pushes total run time past the patience
  budget for routine A/B work.

### Gotcha: stale incremental state → `NoClassDefFoundError` at bench init

If every interp bench suddenly fails at `_jmh_tryInit` with
`java.lang.NoClassDefFoundError: org/commonmark/ext/gfm/tables/TableCell` (or a
similar transitive class), it is **stale incremental build state**, not a missing
dependency — `lang/core` correctly declares `commonmark-ext-gfm-tables` (the
parser registers the GFM-tables extension on every parse). It shows up after
heavy parallel-branch churn / interleaved `cli/assembly` builds leave the
`interpreterBench` JMH-fork classpath inconsistent. Fix:

```bash
sbt "interpreterBench/clean" "interpreterBench/Jmh/compile"
```

then re-run. No source/dependency change is needed.

## Related docs

- `bench/README.md` — gate policy and full-run recipes (durable summaries:
  `bench/BASELINE.md`, `bench/BUNDLE_SIZES.md`).
- `bench/perf-manifest.yaml` — manifest of smoke and full-run commands.
- `specs/vm-jit-spec.md` — Phase-B SscVm spec (historical `VmJitBench`
  numbers; the bench was removed 2026-06-02 as it was superseded by
  `InterpreterBench` + the off-mode flags).
- `specs/vm-jit-next.md` — current perf roadmap (Phase C BytecodeJit +
  Phase D FastTier).
