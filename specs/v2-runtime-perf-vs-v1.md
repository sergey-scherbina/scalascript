# v2 runtime performance vs the v1 interpreter

Status: **live** — slice 1 (`__method__` dispatch never JITs) landed; the remaining gaps are
listed at the bottom with their measured sizes.

This file is the durable answer to "what do the benchmarks say about the v2 runtime, and what
is still worse than v1". It carries the apparatus (what was measured, with which command), the
baseline, and every A/B that changed a number.

## 0. The apparatus came first, because it was lying

`ssc bench --backend v2` and `--backend v2-bytecode` printed nothing and exited **0**. The
generated bench wrapper calls `System.nanoTime()`; on the v2 native lane `Runtime.methodOp`
could not resolve `DataV("System").nanoTime`, fell through to
`PortableEffects.perform("System.nanoTime")` → unhandled runtime effect → the program died
before printing `BENCH_MS`, and `result.foreach(…)` in `BenchCmd` prints nothing on `None`. So
`bench/run.sc` recorded a polite `n/a`.

Every v2 column in every sweep since the bench lane moved to the native ssc1 front was
therefore **blank, not slow**. Nobody had measured the v2 runtime at all.

Fixed in two commits before any optimisation work:

- `e9946f3ee` — `System.nanoTime` / `System.currentTimeMillis` resolve on the v2 native lane
  (tag-qualified natives in `v2/runtime/std/os-plugin`, the `Random.uuid` pattern). This is a
  real v1→v2 parity gap, not only a bench problem: v1's interpreter has both as core builtins.
- `49f99d0db` — `bench/run.sc` gained `--backends a,b,c` (so v1-interp / v2-VM / v2-bytecode
  land in ONE table under one machine state) **and** a dead-lane detector: a per-workload blank
  and a whole-column blank are structurally different claims, so they no longer print the same
  character. A backend that measured nothing on every workload now exits non-zero and prints
  the command to reproduce one case.

## 1. Which lane is the product

v2 has two execution lanes and they are not interchangeable:

| Lane | bench backend | what it is |
|---|---|---|
| **JVM bytecode** | `v2-bytecode` | the DEFAULT product lane — `JvmByteGen` emits real JVM bytecode |
| VM (closure tree-walker) | `v2` | the `--interpret` reference lane; accepted as slower by design |

Both go through `ssc.Prims` for every method call, so a `Prims` defect shows up in both — which
is exactly what slice 1 turned out to be. Read the `v2-bytecode` column when asking "is the
product slow"; read `v2` when asking "is the reference lane slow".

## 2. Slice 1 — the `__method__` dispatch was never JIT-compiled (LANDED)

### The defect

HotSpot ships `-XX:+DontCompileHugeMethods` **on** by default: a method whose bytecode exceeds
`-XX:HugeMethodLimit` (8000) is never compiled by C1 or C2 and runs in the bytecode interpreter
for the life of the process. No warning, no log line, no correctness signal.

A `javap` census of the v2 kernel found **exactly one** method over the limit:

```
   49384  ssc.Prims$ :: resolveBuiltinRaw$$anonfun$150(scala.collection.immutable.List)
```

That is the `__method__` arm — the single dispatch point for **every non-arithmetic operation
in every ScalaScript program** (`xs.map`, `s.split`, `n.toInt`, `opt.getOrElse`, …), at 6.2× the
limit. Next largest in the kernel was `arithRest` at 5,235, so nothing else was close.

The shape of the benchmark data is what identified it: workloads that never reach `__method__`
(`arith-loop`, `nested-loop`, `recursion-fib`) were at or near v1 parity, while everything that
does was 20-400× slower. A uniform "the VM is slow" would not split that way.

### The fix

`ssc.Prims.methodDispatch1..10` — a **pure sequential decomposition**: part N tries its cases in
the original order and falls through to part N+1, so the first matching case is exactly the one
the single match would have chosen. Nothing reordered, nothing duplicated. Largest part is now
5,509 bytecodes.

Parts 9 and 10 rebuild the raw builtin arg list (`StrV(name) :: recv :: margs`) because the
plugin `__method__.<name>` handlers take it undestructured; it is the same list the caller
built, and it is allocated only on that cold path.

### The measurement

```
./bench.sh --backends ssc,v2,v2-bytecode --reps 30 <workloads>     # warmup 2000 ms (default)
```

macOS arm64, 14 cores, JDK 21.0.7, both sides built with `sbt installBin` in the same worktree,
BEFORE and AFTER differing only in `v2/src/Runtime.scala`. Verified per side with
`scripts/bytecode-size-census bin/lib/jars/scalascript-v2-core_3-*.jar 8000`: BEFORE reports the
49,384-byte method, AFTER reports nothing.

ms/iter, lower is better. `ssc` = the v1 interpreter, unchanged by this work, and therefore
**the noise control**: its two columns bracket the run-to-run drift (worst: `option-chain`
0.021 → 0.014, `map-ops` 0.035 → 0.028). Read anything under ~1.3× as noise.

| Workload | ssc before | ssc after | v2 before | v2 after | v2 gain | v2-bc before | v2-bc after | v2-bc gain |
| ------------------ | ------ | ------ | ------ | ------ | -------- | ------ | ------ | -------- |
| `string-split`     | 0.223  | 0.196  | 16.1   | 1.51   | **10.7×** | 13.8   | 1.28   | **10.8×** |
| `typeclass-fold`   | 1.43   | 1.41   | 1.41   | 0.181  | **7.8×**  | 1.16   | 0.107  | **10.8×** |
| `literal-match`    | 0.011  | 0.011  | 1.97   | 0.336  | **5.9×**  | 2.22   | 0.263  | **8.4×**  |
| `option-chain`     | 0.021  | 0.014  | 0.726  | 0.214  | **3.4×**  | 0.862  | 0.120  | **7.2×**  |
| `vector-index`     | 0.821  | 0.894  | 385.7  | 67.3   | **5.7×**  | 382.6  | 58.3   | **6.6×**  |
| `either-chain`     | 0.014  | 0.013  | 0.790  | 0.223  | **3.5×**  | 0.634  | 0.138  | **4.6×**  |
| `map-ops`          | 0.035  | 0.028  | 3.77   | 0.748  | **5.0×**  | 3.43   | 0.778  | **4.4×**  |
| `nested-loop`      | 0.266  | 0.268  | 121.5  | 93.7   | 1.3×      | 0.839  | 0.263  | **3.2×**  |
| `hof-pipeline`     | 0.0053 | 0.0054 | 0.486  | 0.199  | **2.4×**  | 0.498  | 0.173  | **2.9×**  |
| `streams-pipeline` | 0.013  | 0.012  | 0.013  | 0.0046 | **2.8×**  | 0.010  | 0.0037 | **2.7×**  |
| `list-fold`        | 0.0066 | 0.0066 | 21.3   | 5.13   | **4.2×**  | 0.886  | 0.894  | 1.0×      |
| `arith-loop`       | 0.279  | 0.280  | 73.1   | 75.0   | 0.97×     | 0.661  | 0.625  | 1.06×     |
| `recursion-fib`    | 1.28   | 1.24   | 142.8  | 147.3  | 0.97×     | 1.25   | 1.22   | 1.02×     |

**2.4-10.8× on every workload that dispatches methods; unchanged (within noise) on the pure
arithmetic that never reaches `__method__`.** The two null results are the control: they are
exactly the workloads the mechanism predicts should not move.

`list-fold`'s bytecode lane is the interesting non-result — 4.2× on the VM, flat on bytecode.
Its inner `xs.foreach(…)` closure call is dominated by something else on that lane; it is the
first item in §4.

### The gate

Nothing in the test suite could see this defect and nothing would see it come back: a method
that grows past 8000 bytecodes changes no observable behaviour. Two artefacts close that:

- `scripts/bytecode-size-census <classes-dir|jar> [threshold]` — the measurement. Refuses to
  report an empty disassembly as a clean one.
- `tests/e2e/v2-jit-size.sh` — the gate. Fails on any v2 method over 8000 and prints its size;
  also prints everything ≥ 6000 so drift toward the limit is visible before it breaches.
  `--self-test` builds one method that MUST trip it and one that must NOT, because a detector
  only ever observed staying quiet is not a detector.

Current watch item: `ssc.bytecode.JvmByteGen$.gen` at **7,052 / 8000 (88%)** — one `case` away
from silently un-JITing the entire bytecode emitter. Queued as slice 2.

## 3. Baseline (v1 vs v2, after slice 1)

Same command as §2. This is the "what is still worse than v1" answer; ratio is
`v2-bytecode ÷ ssc`, i.e. the product lane against the v1 interpreter.

| Workload | ssc (v1) | v2-bytecode | ratio |
| ------------------ | ------ | ------ | -------- |
| `list-fold`        | 0.0066 | 0.894  | **135×** |
| `vector-index`     | 0.894  | 58.3   | **65×**  |
| `hof-pipeline`     | 0.0054 | 0.173  | **32×**  |
| `map-ops`          | 0.028  | 0.778  | **28×**  |
| `literal-match`    | 0.011  | 0.263  | **24×**  |
| `either-chain`     | 0.013  | 0.138  | 11×      |
| `option-chain`     | 0.014  | 0.120  | 8.6×     |
| `string-split`     | 0.196  | 1.28   | 6.5×     |
| `arith-loop`       | 0.280  | 0.625  | 2.2×     |
| `recursion-fib`    | 1.24   | 1.22   | 1.0×     |
| `nested-loop`      | 0.268  | 0.263  | 0.98×    |
| `streams-pipeline` | 0.012  | 0.0037 | **0.31×** (v2 faster) |
| `typeclass-fold`   | 1.41   | 0.107  | **0.076×** (v2 13× faster) |

## 4. Open slices, largest honest gap first

- **`list-fold` / `hof-pipeline` — closure-call cost on the bytecode lane.** `list-fold` is the
  one workload slice 1 did not move on the product lane (135× v1). Profile the emitted bytecode
  path for `xs.foreach(closure)` before proposing a fix.
- **`vector-index` — 58.3 ms/iter, 65× v1, and 58 ms is an absolute outlier**, not just a ratio.
  Both lanes sit at the same number, so it is not lane-specific.
- **Slice 2: `JvmByteGen.gen` at 88% of HugeMethodLimit.** Split it before it breaches, same
  sequential decomposition, gated by `tests/e2e/v2-jit-size.sh`.
- **F-front compile cost** — `BUGS.md f-front-compile-cost-7x-on-scljet`, 25.72 s vs legacy 1.28 s
  on `examples/scljet-crud.ssc`. **This may no longer be a separate axis.** `specs/v2-f-compile-cost.md`
  records that F is an `.ssc` program *interpreted on the v2 VM*, and that its hot path is "lexing,
  parsing and string concatenation" — i.e. exactly the `__method__`-dispatching shape slice 1 was
  starving of the JIT, and `string-split` is the workload slice 1 moved most (10.7× on the VM lane).
  So slice 1 predicts a large drop in F's compile time as a side effect.

  **Prediction, to be measured with the front-isolation method in `specs/v2-f-compile-cost.md`
  §Method (which measures both fronts in one run, so the ratio self-normalises across machine
  state).** Recording it here BEFORE measuring, so a convenient result cannot be retro-fitted into
  a prediction that was never made.
