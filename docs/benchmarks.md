# Benchmarks

Single source of truth for every benchmark in this repo: what each one
measures, when to use it, and the one-line command that runs it.

Everything goes through `scripts/bench`. Run `scripts/bench help` for the
full command list; `scripts/bench list` enumerates every available
`@Benchmark` method.

## One command per case

| You want to … | Command |
| --- | --- |
| Run all interpreter benches (Javac JIT) | `scripts/bench interp` |
| Run one interpreter bench | `scripts/bench interp recursionFib` |
| Run all benches with AsmJitBackend | `scripts/bench asm` |
| Run one bench with AsmJitBackend | `scripts/bench asm recursionFib` |
| Wall-clock with ASM column added | `./bench.sh --asm` |
| Wall-clock ASM backend only | `./bench.sh --backend interp-asm` |
| Compare interp vs JS vs JVM | `scripts/bench cross` |
| Measure codegen time | `scripts/bench gen` |
| Measure compile pipeline | `scripts/bench compile` |
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
| `lang/core-bench/.../CompilerBench.scala` | `CompilerBench` | Parser, typer, unifier benches — orthogonal to interpreter perf. |

If you are micro-optimising the interpreter, almost everything you need is
in `InterpreterBench`. The cross-backend benches are for periodic "how far
are we from native?" checkpoints.

### Wall-clock (subprocess-based)

| Path | Purpose | How to run |
| --- | --- | --- |
| `bench/corpus/*.ssc` + `bench/run.sc` | Workload sweep through the `ssc` CLI; checked-in summaries land in `bench/BASELINE.md`. | `scripts/runtime-bench.sh --baseline` |
| `tests/bench/{fib,sum,list-ops}.{ssc,scala,js}` | Cross-language wall-clock: same workload in ScalaScript / Scala-direct / Node. | `scripts/bench wall` (alias for `scala-cli tests/bench/run.sc`) |

Use the wall-clock benches when you need cold-JVM, fresh-process numbers
(JMH only measures the warmed-up steady state).

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
| `effectPure` | `runLogger { compute(10000) }` — algebraic-effects baseline. |
| `instanceFieldAccess` | Inline `while: total += p match { case Pair(a,b) => a+b }`. Post-LMatch (2026-06-02): whole loop in Long-slot array, ~16.6 ms/op (1M iters, 162× vs baseline 2690 ms). Remaining cost: HashMap reads inside `CompiledMatch.runValueLong`. |
| `mapForeach` | `Map(...).foreach((k, v) => …)` — 2-arg callEntry path; not yet FastTier-covered. |

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
| `SSC_FASTTIER=off` / `-Dssc.fasttier=off` | Disables `FastTier` (foreach-accumulator / pure-call shortcuts). Falls back to the general dispatcher. |
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

## Related docs

- `bench/README.md` — gate policy and full-run recipes (durable summaries:
  `bench/BASELINE.md`, `bench/BUNDLE_SIZES.md`).
- `bench/perf-manifest.yaml` — manifest of smoke and full-run commands.
- `specs/vm-jit-spec.md` — Phase-B SscVm spec (historical `VmJitBench`
  numbers; the bench was removed 2026-06-02 as it was superseded by
  `InterpreterBench` + the off-mode flags).
- `specs/vm-jit-next.md` — current perf roadmap (Phase C BytecodeJit +
  Phase D FastTier).
