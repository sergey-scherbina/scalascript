# Corpus anti-fold: the LCG-seed idiom (2026-06-11)

The cross-language wall-clock table (`bench/run.sc` → `ssc bench --machine
--backend <b>`) compares the same `bench/corpus/<name>.ssc` workload across
`ssc`, `ssc-asm`, `jvm`, `js`, `rust`. For a class of workloads the compiled
backends (C2 / TurboFan / LLVM) reported **sub-nanosecond** times — not real
throughput, but the optimiser having **constant-folded** a pure, loop-invariant,
zero-input workload to a compile-time constant. The tree-walking interpreter
cannot fold, so the table compared fake-fast compiled numbers against honest
interpreter numbers (see `cross-backend-gap-analysis.md` §3).

This is the corpus-side counterpart of the JMH harness de-fold
(`bench-honesty-varying-data-p2`, which fixed the one *automated* compiled fold
cell). It closes the optional follow-up flagged in `specs/backend-perf-gaps.md`
§T2.1.

## Why the sink alone is not enough

`ssc bench` already wraps each `workload()` call in a sink (`AtomicLong.getAndAdd`
on JVM) so HotSpot can't hoist the **outer** timing loop via scalar evolution.
But the sink does nothing about **workload-internal** folds: a
`def workload(): T` with no inputs is a deterministic constant, and C2 inlines it
and precomputes the result.

## Why a one-shot seed is not enough

Threading an opaque `seed` that is read **once** before the loop
(`val base = seed % k; … f(base) …`) still leaves the inner loop closed-form in
`base`: C2's scalar evolution computes `Σ f(base)` as a single expression in
`base`, evaluated once per call. Measured: `instance-field` stayed at 2.7 µs
(still folded). A **linear** loop-carried recurrence (`count += 1` while in range)
is also defeated — C2 solves counted-loop inductions. Measured: `bool-predicate`
stayed at 3 ns.

## The idiom: a carried 64-bit LCG

Each de-folded workload advances a **non-linear loop-carried** state — a 64-bit
linear congruential generator — and derives the per-iteration input from it:

```scalascript
def workload(seed: Long): Long =
  var s = seed + 1                       // opaque start (harness feeds the sink)
  var acc = 0L
  var i = 0
  while i < N do
    s = s * 2862933555777941757L + 3037000493L   // Knuth MMIX LCG; wraps mod 2^64
    acc = acc + <real work using (s % k)>          // consume every result
    i = i + 1
  acc
```

Why it works:

- **No closed form.** A 64-bit LCG is a pseudo-random sequence; C2 / LLVM / V8
  cannot express `s` after N steps as a closed form, so they must execute every
  iteration. The interpreter never folds anyway, so its number was already
  honest — the idiom only changes the compiled columns.
- **Opaque seed.** `seed` arrives from the harness sink (`_ssc_sink.get()`, an
  atomic load on JVM) — not a compile-time constant — so the whole call can't be
  precomputed either.
- **Every result consumed.** Accumulating each iteration's result into `acc`
  blocks dead-store elimination of intermediate allocations (e.g. the
  `tuple-monoid` concats), so the allocation cost is actually measured.
- **Negligible overhead.** The LCG is one multiply + one add per iteration; it
  does not distort the measured cost of the real work (monadic dispatch, tuple
  concat, InstanceV allocation, literal match).

The `(s % k)` input can be negative; workloads are written to tolerate the full
range (it just exercises both branches of a predicate / both `Left`/`Right`
arms, which is more representative, not less).

## Harness support (`tools/cli/.../Main.scala`, `BenchCmd`)

`generateWrapper` is **arity-aware**:

- `def workload(): T` — historical no-arg call, unchanged. Workloads that run a
  real data-dependent loop (`arith-loop`, `nested-loop`, `recursion-fib`, …) stay
  as they are.
- `def workload(seed: Long): T` — opts into seed-threading. The harness feeds an
  **opaque** seed: on JVM `_ssc_sink.get()` (atomic load, not constant-foldable);
  on interp/JS a monotonic `_ssc_seed` (they don't scalar-evolve, so a cheap
  varying value suffices). The raw sink value is used verbatim — `(x | 1) & mask`
  would let C2 re-derive a constant, so no normalisation is applied.

## Workloads converted

`instance-field`, `tuple-monoid`, `bool-predicate`, `either-chain`,
`option-chain`, `literal-match`. Each carries an LCG and consumes every result.

## What this does NOT change

- The honest workloads (`arith-loop`, `nested-loop`, `recursion-fib`,
  `mutual-recursion`, `pattern-match-heavy`, `list-fold`, `range-sum`,
  `string-*`, `hof-pipeline`, `streams-pipeline`, `map-ops`, `*-monoid` no-loop,
  `effect-*`, `recursion-tco`, `hello-world`) keep their no-arg signature and
  their numbers.
- The Rust path's `std::hint::black_box` source patcher (`bench/run.sc`) is
  retained as a second line of defence for the no-arg workloads; seed workloads
  additionally receive the LCG defeat. (A follow-up may retire the patcher for
  seed workloads.)
