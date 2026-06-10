# Rust vs JVM anti-fold fairness (arith-loop / string-concat)

**Audit date:** 2026-06-10 · **Verdict:** the gap is *genuine but asymmetric* —
inherent to the anti-fold methodology, not a fixable bench bug. Do not
"equalise" it; doing so would make one backend dishonest.

## The question

The cross-backend table shows `arith-loop` rust ≈ **1.27 ms** vs jvm ≈
**0.24 ms** (~5×) and `string-concat` rust ≈ 1.03 ms. Prior work
([rust-honest-disassembly.md](rust-honest-disassembly.md)) established that
rust runs a *real* loop (no closed-form fold). This audit asks: is the gap
real codegen cost, or an asymmetric **anti-fold tax** — i.e. does rust's
barrier do strictly more per-iteration work than the JVM's?

## How each backend stops constant-folding

| | Mechanism | Where applied | Per-iteration cost |
| --- | --- | --- | --- |
| **rust** | `std::hint::black_box(...)` (`bench/run.sc` rewrite) | wraps the RHS of **every assignment** in the workload | for `arith-loop`: **2×/iter** (`sum = black_box(sum+i); i = black_box(i+1);`) |
| **jvm** | `Bench.opaque` — a `@volatile`-guarded identity (`JvmRuntimePreamble`) | **not applied** to the `arith-loop` corpus at all; the source has no `Bench.opaque` | **0×/iter** |

The JVM number is honest *without* a per-iteration barrier because **HotSpot
does not fold `sum += i` to a closed form here** — `arith-loop` jvm at
~0.24 ns/iter is a genuinely-executing loop (a folded version would be ≈0 ms).

LLVM `-O3` is the opposite: it *would* rewrite `sum += i` to `n·(n-1)/2`
(a fake ≈0 ms) unless every assignment is forced opaque. `black_box` is the
only tool available, and it blocks **both** folding **and** the
pipelining/vectorisation the JVM loop enjoys.

## Conclusion

The ~5× gap decomposes into two genuine, non-removable parts:

1. **Real codegen/runtime difference** — different instruction selection and
   loop shaping between HotSpot and LLVM.
2. **An unavoidable asymmetric barrier cost** — rust *must* carry a heavy
   per-assignment `black_box` (because LLVM folds aggressively), and that
   barrier has a pipeline-blocking side-effect; the JVM needs *no* barrier for
   this loop (HotSpot doesn't fold it), so it pays nothing.

This asymmetry is **inherent to honest anti-fold benchmarking**, not a
harness defect. The only ways to "equalise" are both dishonest:

- remove rust's `black_box` → LLVM folds to a fake ≈0 ms, or
- inject `Bench.opaque` per-assignment on the JVM → an artificial barrier the
  workload doesn't need.

So the gap is **documented as genuine** and left as-is. When reading the
table, treat the rust column for tight scalar loops as "honest workload + the
minimum LLVM anti-fold barrier", which is structurally heavier than the JVM's
barrier-free honest loop. The two numbers answer slightly different questions
and a small constant-factor gap between them is expected.
