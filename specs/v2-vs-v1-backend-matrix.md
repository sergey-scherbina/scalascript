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
./bench.sh --backends ssc,ssc-asm,jvm,js,rust,v2,v2-bytecode --reps 20     # 2026-07-29, REBUILT
```

macOS arm64, 14 cores, JDK 21.0.7, node v26.5.0, scala-cli 1.15.0, rustc 1.92.0.
**Load average 1.8** — a quiet machine, unlike the first build of this table (5.7-10.2).

⚠️ **Do not diff this table against its previous revision row by row.** The old one was taken under
load, which inflated BOTH columns of every row by different amounts. A ratio that changed between
the two revisions is not evidence that anything was fixed. The only trustworthy before/after
numbers in this document are the ones taken with the alternating protocol
(`specs/v2-runtime-perf-vs-v1.md` §7), and they are labelled as such.

**A worked example of getting that wrong — mine.** I reported `effect-stream` as improving from
271× to 115× after the handler fix. It did not. I compared a fresh v2 number (4.70 ms) against the
STALE, load-inflated v1 number from the old table (0.041 ms). On this quiet machine v1 is 0.017 and
v2 is 4.57: **269×, essentially unchanged.** Both columns had fallen ~2.4× because the machine was
quieter, and I attributed one column's fall to my change. The alternating protocol exists precisely
to prevent this, and I skipped it because a single number was already in front of me.

## The matrix (ms/iter, lower is better)

| Workload | ssc | ssc-asm | jvm | js | rust | v2 | **v2-bytecode** | **ratio** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `lazylist-take` | 0.060 | 0.060 | 0.054 | 5.22 | 0.088 | 27.4 | **23.7** | **395×** |
| `pattern-match-heavy` | 0.057 | 0.058 | 0.050 | 0.052 | 1.45 | 79.2 | **21.7** | **381×** |
| `float-fold` | 0.010 | 0.010 | 0.0047 | 0.0025 | 0.267 | 4.65 | **3.15** | **315×** |
| `effect-stream` | 0.017 | 0.017 | n/a | 0.021 | 0.020 | 5.74 | **4.57** | **269×** |
| `list-fold` | 0.0059 | 0.0060 | 0.000336 | n/a | 0.050 | 4.82 | **0.861** | **146×** |
| `range-sum` | 0.0037 | 0.0038 | 0.013 | 0.032 | 0.0012 | 0.698 | **0.418** | **113×** |
| `float-loop` | 1.15 | 1.14 | 1.14 | 0.577 | 2.05 | 64.6 | **62.2** | **54×** |
| `vector-index` | 0.803 | 0.902 | 0.489 | 9.84 | 0.615 | 52.9 | **37.3** | **46×** |
| `map-ops` | 0.025 | 0.026 | 0.020 | 0.205 | 0.022 | 0.713 | **0.693** | **28×** |
| `hof-pipeline` | 0.0046 | 0.0047 | 0.0050 | 0.012 | 0.0029 | 0.180 | **0.122** | **27×** |
| `bool-predicate` | 0.0093 | 0.010 | 0.000681 | 6.64 | 0.0022 | 0.294 | **0.235** | **25×** |
| `literal-match` | 0.010 | 0.010 | 0.0078 | 4.37 | 0.0016 | 0.328 | **0.179** | **18×** |
| `string-concat` | 0.094 | 0.195 | 0.083 | 0.739 | 1.000 | 2.79 | **1.10** | **12×** |
| `tuple-monoid` | 2.02 | 1.94 | 0.085 | 39405 | 0.112 | 43.6 | **18.3** | 9.1× |
| `type-lambda-placeholder` | 0.0073 | 0.0072 | 0.0011 | 0.393 | 0.000519 | 0.105 | **0.064** | 8.8× |
| `instance-field` | 0.270 | 0.520 | 0.0055 | 362.9 | 0.011 | 5.38 | **2.03** | 7.5× |
| `either-chain` | 0.013 | 0.012 | 0.0015 | 0.421 | 0.0018 | 0.194 | **0.090** | 6.9× |
| `option-chain` | 0.014 | 0.012 | 0.000547 | 0.432 | 0.000671 | 0.183 | **0.095** | 6.8× |
| `effect-pure` | 0.0066 | 0.0066 | 0.0033 | 0.0033 | 0.0095 | 0.711 | **0.045** | 6.8× |
| `mutual-recursion` | 1.57 | 1.42 | 0.513 | 7.07 | 2.77 | 45.6 | **9.89** | 6.3× |
| `string-split` | 0.190 | 0.144 | 0.079 | 0.372 | 0.301 | 1.57 | **0.983** | 5.2× |
| `nested-loop` | 0.258 | 0.260 | 0.251 | 16.7 | 0.977 | 92.5 | **1.32** | 5.1× |
| `arith-loop` | 0.243 | 0.241 | 0.238 | 16.9 | 0.835 | 69.6 | **0.573** | 2.4× |
| `recursion-fib` | 1.21 | 1.26 | 1.29 | 6.45 | 1.80 | 134.0 | **1.20** | 1.0× |
| `recursion-tco` | 0.030 | 0.030 | 0.026 | 0.524 | 0.025 | 5.40 | **0.026** | 0.87× |
| `streams-pipeline` | 0.012 | 0.012 | 0.000046 | 0.000488 | 0.000031 | 0.0036 | **0.0022** | 0.18× |
| `effect-multishot` | 5.53 | 5.08 | 0.083 | 0.437 | 0.0095 | 0.520 | **0.495** | 0.09× |
| `typeclass-fold` | 1.41 | 1.29 | 0.0028 | 0.076 | n/a | 0.170 | **0.104** | 0.07× |
| `hello-world` | 0.0028 | 0.0028 | 0.000477 | 0.000023 | 0.000237 | 0.000256 | **0.000148** | 0.05× |
| `typeclass-monoid` | 0.0090 | 0.0091 | 0.000005 | 0.000268 | 0.000002 | 0.000596 | **0.000320** | 0.04× |
| `type-lambda-native` | 0.0044 | 0.0042 | 0.000008 | 0.000115 | 0.000002 | 0.000409 | **0.000131** | 0.03× |
| `effect-oneshot` | 0.027 | 0.028 | 0.198 | 4.79 | 0.0018 | 0.791 | **0.000532** | 0.02× |
| `array-update` | 0.649 | 0.648 | 0.476 | 12.4 | 0.695 | ✗ | **✗** | — |

## Category 1 — v2 cannot run this at all

| Item | Failure | Scope |
|---|---|---|
| `array-update` | the indexed store `a(i) = v` is parsed away | **DEFAULT front only** — fixed on `SSC_FRONT=legacy`, still open on F |

Everything else in this category is closed. `v2-jvm` and `v2-rust` — which could not run a single
program (`__autoOutput__` unimplemented in both source generators) — now work: `v2-jvm` reaches
0.276 on `arith-loop`, parity with the v1 interpreter, and `v2-rust` 0.000047. The three float
workloads listed here originally were never a v2 defect at all; that was the bench wrapper's `0d`.

Tracked: `BUGS.md v2-array-indexed-store-silently-dropped` (half fixed, F open),
`v2-front-drops-float-literal-suffix`.

## Category 2 — an order of magnitude worse than v1 (≥10×)

13 rows, and the shapes are unchanged from the first analysis: **lazy/collection iteration**
(`lazylist-take`, `effect-stream`, `list-fold`, `range-sum`, `hof-pipeline`), **structural access**
(`vector-index`, `map-ops`, `bool-predicate`, `literal-match`, `pattern-match-heavy`), **floats**
(`float-fold`, `float-loop`), **strings** (`string-concat`).

`float-fold` at **315×** and `float-loop` at **54×** are new to this list — they were `✗` before,
so this is the first time they have ever been measured. Against `arith-loop` at 2.4×, the Long twin,
they say plainly: **the numeric fast paths cover Long and not Double.** That is the most concrete
open lead in the category.

## Category 3 — worse, but under 10×

`tuple-monoid` 9.1×, `type-lambda-placeholder` 8.8×, `instance-field` 7.5×, `either-chain` 6.9×,
`option-chain` 6.8×, `effect-pure` 6.8×, `mutual-recursion` 6.3×, `string-split` 5.2×,
`nested-loop` 5.1×, `arith-loop` 2.4×, `recursion-fib` 1.0× (parity).

Now measurable, which it was not before: on a quiet machine this band is real rather than noise.
Note `nested-loop` reads 5.1× here against 2.8× in the loaded table — **worse, not better**. Load
compressed the ratios, so some of category 3 was flattered by it.

## Category 0 — where v2 is FASTER than v1

`effect-oneshot` **51×**, `type-lambda-native` **34×**, `typeclass-monoid` **28×**, `hello-world`
**19×**, `typeclass-fold` **13.5×**, `effect-multishot` **11×**, `streams-pipeline` 5.5×,
`recursion-tco` 1.15×.

## The score

Of the 32 workloads v2 can run: **8 faster, 1 at parity, 23 slower** — 13 of those by an order of
magnitude. Unchanged in shape from the first reading: effects, typeclasses, type-lambdas and
startup are v2's strengths; anything that walks a collection, a string or an object's fields
element-by-element through the generic runtime is where it loses.

## Category 2 findings — a cause located, and a fix that was tried and refuted

### Every 1-arg matching lambda pays effect-handler bookkeeping

`bench/corpus/range-sum` is `(0 until 50).map(i => i * i).foldLeft(0)((a, b) => a + b)` — no
effects, no handlers anywhere in the program. Its profile nevertheless shows
`Runtime.withHandlerDispatchInvocation` at **36 samples**, which per call does a ThreadLocal get,
allocates a `HandlerDispatchInvocation` **and** a fresh `Object`, conses onto a list, and runs the
body inside a `try/finally` that restores the ThreadLocal.

Located in `HandlerDispatchShape.isRoot` (`v2/src/CoreIR.scala`):

```scala
def isRoot(arity: Int, body: Term): Boolean =
  arity == 1 && (body match
    case Term.Match(Term.Local(0), _, _) => true          // ← any `x => x match { … }`
    case other                           => containsDecisionMarker(other))
```

The first arm classifies **any one-argument lambda whose body matches on its own parameter** as a
handler-dispatch root. That is one of the most ordinary shapes in ScalaScript — every `case` lambda,
every partial function, every `xs.map(x => x match …)` — and each one is then built with
`Emit.handlerClos` instead of `Emit.clos` and pays the bookkeeping above on every invocation.

### The obvious narrowing is REFUTED — do not repeat it

Tried: drop the shape arm and keep only `containsDecisionMarker`, which tests for the actual
handler-dispatch primitives.

```
tests/conformance/contract.sc --lanes int,v2 --only 'effect*,handler*,control*,coroutine*,resume*'
  → 2 REGRESSIONS: effects v2 FAIL, effects-handler v2 FAIL
```

Reverted; effects are 12/12 again. **The shape arm is load-bearing**: a genuine handler does not
always carry the decision markers, and the shape is what catches those. So the over-classification
cannot be fixed by making the *predicate* smarter about the same information.

### What a real fix would need

The information that distinguishes "this lambda is a handler arm" from "this lambda happens to
match on its argument" exists in the FRONT, which knows it is lowering a `handle` construct — and
is thrown away before the emitter sees the term. The fix is to carry that fact in the IR (a flag on
the `Lam`, or a distinct node) rather than re-deriving it from shape in the backend. That is an IR
change and needs a spec and cross-front agreement, so it is queued rather than attempted:
`SPRINT.md v2m-2g`.

### MEASURED NEGATIVE: skipping the two single-kind dispatch parts

A profile of `lazylist-take` (the worst ratio in the matrix) put **665 of ~2,500 samples ≈ 25%**
inside `methodDispatch2..7` — the linear fall-through of the `__method__` split. Its receiver is a
`ForeignV` served by part 9, so it walks seven parts to get there.

A mechanical census (script over the arm patterns, not by eye) established that exactly two parts
are single-kind and therefore provably skippable: part 2 is 41 arms = 40 `StrV` + one `case _`,
part 7 is 41 = 40 `DataV` + one `case _`. Every other part is mixed, and parts 5/6/10 carry
bare-variable receivers with guards that can match anything.

Both were given a kind guard that bails to the next part when the receiver cannot match — exactly
what running the part would have done, so semantics are preserved by construction.

**Result: nothing.** Three alternating rounds, medians normalised against the unchanged v1 column:

| workload | ratio before | ratio after | |
|---|---:|---:|---|
| `lazylist-take` | 421 | 410 | 1.03× |
| `map-ops` | — | — | 1.05× |
| `string-split` | 5.80 | 5.64 | 1.03× |

All inside the noise band. **Reverted** — a change that removes work but not time, while adding a
maintenance hazard ("keep the part single-kind or the guard silently skips arms"), is not worth
carrying.

**What this teaches about the profile, and it is the second time today:** a frame holding 25% of
samples did not hold 25% of the time. Failed type tests inside those parts are evidently near-free
once JIT-compiled — the samples land there because that is where the *thread* is, not because that
is where the *cost* is. The `dataFields` slice earlier showed the same discount (28% of profile →
20% gain). Treat a hot frame as a place to look, never as a size of prize.

The census itself is kept in `SPRINT.md v2m-2f` — it is the prerequisite for any future
kind-indexed dispatch, and it cost more to produce than the guards did.

### Marginal, kept: `ArraySeq` instead of `Vector` for effect-Op fields

Every performed effect reifies as `DataV("Op", <3 fields>)`, and all 16 sites built those fields
with `Vector(l, a, k)` — `IterableFactory.apply(Seq)` → `Vector$.from`, i.e. a varargs `Object[]`,
an intermediate Seq and the Vector: three allocations per operation. A profile of `effect-stream`
put `Vector$.from` + `IterableFactory.apply` at 109 samples and `Object[]` at 186 allocation
samples.

Alternating medians, v2-bytecode ms/iter:

| workload | before | after | |
|---|---:|---:|---|
| `effect-stream` | 4.93 | 4.58 | 1.08× (ranges partly overlap) |
| `effect-multishot` | 0.494 | 0.480 | 1.03× |
| `effect-oneshot` | 0.000545 | 0.000533 | 1.02× |

**This is the same magnitude at which the dispatch kind-guards were REVERTED, so the difference has
to be stated rather than assumed.** The guards carried a correctness footgun — a future non-`StrV`
arm added to part 2 would have been silently skipped, and only a comment stood between that and a
wrong answer. This change cannot acquire an invariant to violate: `ArraySeq` and `Vector` are both
`IndexedSeq`, Seq equality is element-wise across implementations, and the file already spells
constructor fields this way in 29 other places. It is strictly less allocation with nothing to
maintain, so it stays — but it is recorded as **marginal, not as a win**.

If a future reader wants a rule out of the pair: revert a no-gain change when carrying it costs
future attention; keep it when it costs none.

### LOCATED: the entire Double numeric fast-path tier is built and never wired up

`float-fold` **315×** and `float-loop` **54×**, against `arith-loop` — their Long twin — at
**2.4×**. The cause is one branch in the SHARED lowering, `v2/lib/ssc1-lower.ssc0` (`var` case,
~:4038):

```
if isIntLitExpr(expr) then
  -- Integer literal init: use long cell (no IntV boxing per store)
  IrPrim("lcell.new", …)      scope marker "@@name"
else
  IrPrim("cell.new", …)       scope marker "@name"     ← everything else, incl. a Double literal
```

There is no Double branch, so `var sum: Double = 0.0` gets a GENERIC cell and every read and write
boxes. Measured on `float-loop`: **841 `FloatV` allocation samples** and 256 `ForeignV`, with
`Emit.prim1` + `Emit$.s1` + `CHM.computeIfAbsent` at ~600 CPU samples — the generic
string-keyed `cell.get`/`cell.set` path, once per iteration. The Long twin never enters it.

**The Double tier already exists everywhere else.** `Prims` implements `dcell.new`, `dcell.get`,
`dcell.set`; `Emit` has `dcellAccum` (the unboxed fused accumulator, written as the deliberate twin
of `lcellAccum`); `JvmByteGen` has `canDouble`/`genDouble`/`DArithB`. And **`grep -c dcell
v2/lib/ssc1-lower.ssc0` returns 0** — nothing ever emits any of it. An entire numeric tier was
built, tested against its Long sibling, and left unreachable.

**What wiring it needs** (not a one-liner, and the reason it is queued rather than rushed):
an `isFloatLitExpr` predicate; a `dcell.new` branch with its own scope marker; and a Double twin at
each site that currently special-cases the `@@` (lcell) marker on read/write — `ssc1-lower.ssc0`
:2575, :2870, :4094. Verify with the effects/collections conformance slices plus an ALTERNATING A/B
on `float-loop` and `float-fold`, and expect the Long ratio (2.4×) as the target, not zero.

Queued as `SPRINT.md v2m-2h`. It is the largest single lead the rebuilt matrix produced.
