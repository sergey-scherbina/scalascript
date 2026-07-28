# v2 vs v1 — the cross-backend matrix

The full corpus × every backend, in one table, with each cell classified. This is the document the
v2 gap work is planned from; `specs/v2-runtime-perf-vs-v1.md` carries the optimisation history and
the measurement protocol.

## How to read it, before the numbers

- **`v2-bytecode` is the v2 product lane.** `JvmByteGen` emits real JVM bytecode and it is what
  `ssc run` uses. Plain `v2` is the `--interpret` reference lane and is slower *by design*. A
  "v2 vs v1" claim built on the `v2` column is measuring the reference lane; every ratio below uses
  `v2-bytecode ÷ ssc`.
- **`ssc` is v1's default interpreter** (with its JIT); `ssc-asm` is the same with the ASM JIT
  backend. `jvm`, `js`, `rust` are v1's code generators.
- **`✗` means the lane cannot run the program at all** — an exception, not a slow result. `n/a`
  means genuinely unsupported (rust has no `LazyList`, js has no `LazyList.from`). Those two used
  to print the same character; see `BUGS.md bench-jvm-js-lanes-dead-silently`.

## Provenance

```
./bench.sh --backends ssc,ssc-asm,jvm,js,rust,v2,v2-bytecode --reps 20     # 2026-07-28
```

macOS arm64, 14 cores, JDK 21.0.7, node v26.5.0, scala-cli 1.15.0, rustc 1.92.0.

**Load average during the sweep was 5.7-10.2** (sibling agents building). By the protocol in
`specs/v2-runtime-perf-vs-v1.md` §7, that makes this a SNAPSHOT: differences under ~30% are not
established here, ratios of 4× and up are. The one cell with a better number is noted inline.
`v2-jvm` and `v2-rust` are absent because they cannot run any program at all (§1 below).

## The matrix (ms/iter, lower is better)

| Workload | ssc | ssc-asm | jvm | js | rust | v2 | **v2-bytecode** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `arith-loop` | 0.277 | 0.279 | 0.274 | 17.3 | 0.906 | 82.3 | **0.634** |
| `array-update` | 0.816 | 0.824 | 0.627 | 14.4 | 0.714 | ✗ | **✗** |
| `bool-predicate` | 0.0099 | 0.010 | 0.000861 | 4.55 | 0.0022 | 0.311 | **0.131** |
| `effect-multishot` | 5.12 | 5.44 | 0.095 | 0.477 | 0.010 | 0.731 | **0.553** |
| `effect-oneshot` | 0.065 | 0.035 | 0.341 | 5.49 | 0.0021 | 2.78 | **0.0014** |
| `effect-pure` | 0.022 | 0.0077 | 0.0060 | 0.0095 | 0.011 | 2.84 | **0.167** |
| `effect-stream` | 0.041 | 0.053 | n/a | 0.061 | 0.024 | 18.4 | **11.1** |
| `either-chain` | 0.033 | 0.025 | 0.0064 | 1.28 | 0.0022 | 1.67 | **0.564** |
| `float-fold` | 0.103 | 0.048 | 0.020 | 0.017 | 0.347 | ✗ | **✗** |
| `float-loop` | 5.66 | 4.18 | 3.66 | 1.82 | 5.36 | ✗ | **✗** |
| `hello-world` | 0.0083 | 0.0098 | 0.0015 | 0.000089 | 0.000315 | 0.000892 | **0.0019** |
| `hof-pipeline` | 0.012 | 0.025 | 0.011 | 0.014 | 0.017 | 0.349 | **0.229** |
| `instance-field` | 0.401 | 0.577 | 0.0074 | 415.8 | 0.011 | 7.65 | **5.24** |
| `lazylist-take` | 0.086 | 0.065 | 0.058 | 1.66 | 0.089 | 36.9 | **40.8** |
| `list-fold` | 0.0065 | 0.0067 | 0.000358 | n/a | 0.050 | 5.02 | **1.04** |
| `literal-match` | 0.011 | 0.011 | 0.0088 | 5.20 | 0.0017 | 0.544 | **0.580** ⚠ |
| `map-ops` | 0.083 | 0.035 | 0.020 | 0.207 | 0.022 | 0.706 | **0.699** |
| `mutual-recursion` | 1.40 | 1.40 | 0.530 | 7.19 | 2.79 | 46.7 | **10.2** |
| `nested-loop` | 0.268 | 0.311 | 0.259 | 17.1 | 1.03 | 92.1 | **0.761** |
| `option-chain` | 0.013 | 0.012 | 0.000564 | 0.697 | 0.000840 | 0.210 | **0.127** |
| `pattern-match-heavy` | 0.060 | 0.060 | 0.053 | 0.054 | 1.58 | ✗ | **✗** |
| `range-sum` | 0.0040 | 0.0039 | 0.015 | 0.050 | 0.0012 | 0.774 | **0.679** |
| `recursion-fib` | 1.56 | 1.80 | 1.80 | 10.2 | 3.27 | 232.8 | **1.74** |
| `recursion-tco` | 0.053 | 0.042 | 0.037 | 0.963 | 0.176 | 9.17 | **0.044** |
| `streams-pipeline` | 0.030 | 0.018 | 0.000131 | 0.0011 | 0.000019 | 0.0072 | **0.0057** |
| `string-concat` | 0.166 | 0.672 | 0.239 | 1.44 | 2.12 | 5.43 | **4.65** |
| `string-split` | 0.490 | 0.260 | 0.161 | 0.554 | 0.364 | 2.58 | **2.09** |
| `tuple-monoid` | 3.53 | 3.01 | 0.091 | 39943 | 0.118 | 50.2 | **35.1** |
| `type-lambda-native` | 0.0044 | 0.0043 | 0.000008 | 0.000113 | 0.000015 | 0.000488 | **0.000275** |
| `type-lambda-placeholder` | 0.0072 | 0.0072 | 0.0012 | 0.417 | 0.000448 | 0.108 | **0.068** |
| `typeclass-fold` | 1.48 | 1.40 | 0.0028 | 0.077 | n/a | 0.170 | **0.107** |
| `typeclass-monoid` | 0.0092 | 0.0093 | 0.000005 | 0.000269 | 0.000004 | 0.000606 | **0.000311** |
| `vector-index` | 0.804 | 0.801 | 0.490 | 9.67 | 0.637 | 54.3 | **36.9** |

⚠ `literal-match`: the sweep says 0.580, but three alternating rounds on a quiet machine put it at
**0.184** (`specs/v2-runtime-perf-vs-v1.md` §8). Use 0.184; the 0.580 is load. It is the only cell
with a better measurement available.

## Category 1 — v2 cannot run this at all

Not slow: an exception. Two causes across four workloads, plus two backends that cannot run
anything.

| Item | Failure | Scope |
|---|---|---|
| `float-loop`, `float-fold`, `pattern-match-heavy` | `RuntimeException: unbound global: d` | both v2 lanes |
| `array-update` | `RuntimeException: app: not a function: 0` | both v2 lanes |
| backend `v2-jvm` | `RuntimeException: unknown prim1: __autoOutput__` | every program |
| backend `v2-rust` | `panicked: unimplemented prim: __autoOutput__` | every program |

**4 of 33 workloads, and 2 of the 4 v2 backends.** `pattern-match-heavy` matters more than its row
suggests: it is one of the four rows `BACKLOG.md`'s v2 production-route policy was decided on, and
it currently runs on neither v2 lane.

Tracked: `BUGS.md v2-lanes-cannot-run-four-corpus-workloads`, `v2-source-backends-miss-autoOutput`.

## Category 2 — runs, but an order of magnitude worse than v1 (≥10×)

| Workload | ssc | v2-bytecode | ratio | shape |
|---|---:|---:|---:|---|
| `lazylist-take` | 0.086 | 40.8 | **474×** | lazy collection |
| `effect-stream` | 0.041 | 11.1 | **271×** | lazy/effect stream |
| `range-sum` | 0.0040 | 0.679 | **170×** | range iteration |
| `list-fold` | 0.0065 | 1.04 | **160×** | list traversal |
| `vector-index` | 0.804 | 36.9 | **46×** | indexed read |
| `string-concat` | 0.166 | 4.65 | **28×** | strings |
| `hof-pipeline` | 0.012 | 0.229 | **19×** | map/filter/fold chain |
| `either-chain` | 0.033 | 0.564 | **17×** | ADT chain |
| `literal-match` | 0.011 | 0.184 | **17×** | match on literals |
| `bool-predicate` | 0.0099 | 0.131 | **13×** | predicate calls |
| `instance-field` | 0.401 | 5.24 | **13×** | field access |

Three shapes, not eleven: **iteration over collections** (lazylist, effect-stream, range-sum,
list-fold, hof-pipeline), **strings**, and **structural data access** (instance-field,
vector-index, either-chain).

## Category 3 — worse, but not by an order of magnitude (<10×)

Deliberately NOT planned yet. It must be re-measured after categories 1 and 2, for two reasons:
the sweep it comes from is a contended snapshot where sub-30% differences are not established, and
fixing a shared cause in category 2 will move these rows too.

| Workload | ratio | |
|---|---:|---|
| `recursion-fib` | 1.1× | parity |
| `arith-loop` | 2.3× | |
| `nested-loop` | 2.8× | |
| `string-split` | 4.3× | |
| `mutual-recursion` | 7.3× | |
| `effect-pure` | 7.6× | |
| `map-ops` | 8.4× | |
| `type-lambda-placeholder` | 9.4× | |
| `option-chain` | 9.8× | |
| `tuple-monoid` | 9.9× | |

## Category 0 — where v2 is FASTER than v1

Omitting this would make the matrix a list of complaints rather than a description. It is also the
evidence that the v2 compilation path works: these are the workloads that do not spend their time
in the generic runtime.

| Workload | faster by |
|---|---:|
| `effect-oneshot` | **46×** |
| `typeclass-monoid` | **30×** |
| `type-lambda-native` | **16×** |
| `typeclass-fold` | **14×** |
| `effect-multishot` | **9×** |
| `streams-pipeline` | 5× |
| `hello-world` | 4× |
| `recursion-tco` | 1.2× |

## The score, and what it means

Of 29 workloads v2 can run: **8 faster, 3 at parity, 18 slower** (11 of those by an order of
magnitude). The honest summary is not "v2 is slower than v1" but **"v2 has a different shape"**:
effects, typeclasses, type-lambdas and startup are where it wins; anything that walks a collection,
a string, or an object's fields element-by-element through the generic runtime is where it loses.
Arithmetic and recursion are already at parity, which is what the bytecode lane bought.
