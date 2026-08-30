# Aggregation Algebra — `Monoid`, `Group`, and `Aggregator[In, Acc, Out]`

Status: **every section landed 2026-08-29–30** except what §11.4 deliberately defers, as
[`std/aggregator.ssc`](../std/aggregator.ssc) — `Group`, `Aggregator[In, Acc, Out]`, `zip`/`map`
composition, `mean`-from-`sum`-and-`count`, `min`/`max` (any `Order[A]`), `variance`/`stddev`
(Chan/Golub/LeVeque), `first`/`last` (`MinByAgg`/`MaxByAgg` generalized to a projected key, keyed
on `.zipWithIndex`), HyperLogLog/Count-Min Sketch/T-Digest (§6 — see its own note on three more
front/interpreter defects this surfaced), a `Group`-backed `SlidingWindow` (§7 — see its own note
on a type-checking gap this surfaced), `groupByAgg` (`Map[K, Acc]` as a `Monoid`), the
`DStream`/`Dataset` bridge (`aggregatorSeqOp`/`aggregatorCombOp`), the rendering bridge
(`mapToRows`/`renderTableHtml`), and §11's base effect shape (`EffAggregator`, `runEffAggregator`,
`ContramapAgg`/`dimapAgg` — `LiftAgg` and `ZipEffAgg` are NOT shipped, see §11's own note on a
serious front defect this surfaced). Runnable at
[`examples/std-aggregator.ssc`](../examples/std-aggregator.ssc) +
[`examples/std-aggregator-approx.ssc`](../examples/std-aggregator-approx.ssc) and gated by
`tests/conformance/std-aggregator.ssc` + `tests/conformance/std-aggregator-approx.ssc` +
`tests/conformance/std-order.ssc`. `std-aggregator`: INT green, JS and JVM known-red against two
filed codegen bugs. `std-aggregator-approx`: INT green (its two interpreter bugs — missing
`math.log`, lexicographic `sortBy` on a `Double` key — are fixed), JS and JVM still known-red
against two remaining, separate codegen bugs; every sketch is verified correct on the reference
front. Every code sample in this document has been run for real against this checkout's
`bin/ssc-tools` to confirm it actually compiles and produces the stated result — see §12 for what
that verification found (including one interpreter bug it surfaced, since fixed).

Companion documents:
- [`std/semigroup-monoid.ssc`](../std/semigroup-monoid.ssc) — `Semigroup`/`Monoid` already ship
  here with canonical instances (`Int` sum, `String` concat, `List` concat). This document adds
  `Group` and builds `Aggregator` on top; it does not redefine `Semigroup`/`Monoid`.
- [`std/foldable-traversable.ssc`](../std/foldable-traversable.ssc) — documents the same
  typeclass-resolution limits this spec designs around (§12.1).
- [`specs/distributed-streams.md`](distributed-streams.md) — `DStream[T]`/`Pipeline`, with real
  backends (native actor cluster, Spark, Kafka Streams, Flink, Beam) already implemented. Its
  `Status:` line ("design sign-off required before implementation starts") is stale — the backend
  codegen is landed (`v1/runtime/backend/{spark,flink,kafka-streams}/`,
  `v1/runtime/plugins/dstreams-plugin/`). §9 of this document is the bridge: `Aggregator` values
  become the `zero`/`seqOp`/`combOp` triple `aggregatePerKey`/`runFold` already accept.
- [`specs/mapreduce.md`](mapreduce.md) — `Dataset[T]` batch map-reduce; the same bridge applies to
  its `fold`/`reduce` operators.
- [`std/functor-applicative-monad.ssc`](../std/functor-applicative-monad.ssc) — `Functor`/
  `Applicative`/`Monad` already ship here (§11 builds `EffAggregator` on top, reusing them as-is).
- [`specs/algebraic-effects.md`](algebraic-effects.md) — asynchrony is a `! Async` effect here, not
  a `Future`/`Task` value type; §11 designs around that rather than introducing a second one.

## 1. Motivation

ScalaScript's ambition (see the originating discussion) is to be a language for data analysis
where: data arrives from any source, in unbounded volume; every analysis is expressed as a
composable, parallelizable computation; the same definition compiles to any execution target
(local interpreter, Spark, a future Python/R backend, a real-time streaming dashboard); and
results render as tables, charts, or any other required shape.

Sources, backends, and rendering are tracked as separate, future specs. **This document is
narrowly scoped to the one piece all of them depend on**: the language for expressing "a
computation over an unbounded sequence of records" itself — the piece the project's own framing
called the hard part, with everything else "a matter of engineering" once it exists.

ScalaScript already has half of this: `Semigroup[A]`/`Monoid[A]` (algebraic typeclasses) and
`aggregatePerKey(zero)(seqOp)(combOp)` (a raw three-lambda aggregation primitive on `DStream`).
What's missing:

1. **A named, typed, reusable unit of aggregation** — today every aggregation is three
   hand-written lambdas at the call site. There is no `mean`, no `variance`, no `p99` as a value
   you import and reuse; there is only "write the fold yourself, again."
2. **Composability** — computing two statistics from one pass over the data (e.g. sum AND count,
   to get a mean) requires hand-fusing two folds into one by hand. It should be free: combine two
   aggregators, get one that computes both.
3. **A first-class answer for "unlimited volume."** Sum, count, min, max are exact monoids in
   O(1) space. Distinct count, quantiles, and top-K are NOT — not in any bounded amount of memory,
   for genuinely unbounded data. The honest answer, already proven at industry scale (Algebird,
   DataSketches, the T-Digest and HyperLogLog papers), is: approximate versions of these ARE
   monoids, at the cost of a bounded, known error. The language needs to make "exact" and
   "approximate, with these error bounds" two distinguishable, equally first-class kinds of
   aggregator — not blur them.
4. **The distinction between "can merge" and "can also un-merge."** A sliding window ("the last 5
   minutes, updated every second") wants to drop data that aged out without recomputing the whole
   window from scratch. That needs an inverse, not just a combine — an Abelian **group**, not
   merely a monoid. Sum and count are groups (subtraction exists); min/max, HyperLogLog, and
   T-Digest are not (there is no way to "un-see" a value from a running max). The language must
   say which is which, so a sliding-window request on a non-invertible aggregator is a clear
   compile-time-visible limitation, not a silent full-recompute or a wrong answer.

## 2. Core algebra

### 2.1 `Semigroup` and `Monoid` — already defined, reused as-is

```scalascript
trait Semigroup[A]:
  def combine(a: A, b: A): A

trait Monoid[A] extends Semigroup[A]:
  def empty: A
```

`combine` MUST be associative: `combine(combine(a, b), c) == combine(a, combine(b, c))`. This is
what licenses parallel evaluation — a monoid's `combine` may be applied in ANY grouping (tree
reduction across cores, across machines, across time in a streaming window) and the answer is
identical. Associativity does **not** by itself license reordering: `String` concatenation is an
associative monoid but not commutative, and a `List`-building monoid needs its inputs to arrive
(or be sorted back) in a defined order. An aggregator whose `combine` is also *commutative*
additionally tolerates out-of-order arrival — relevant for real distributed sources, where two
partitions may finish in either order. §5 marks which canonical aggregators are commutative.

### 2.2 `Group` — the new primitive: invertible monoids

```scalascript
trait Group[A] extends Monoid[A]:
  def inverse(a: A): A
```

A `Group` additionally guarantees an inverse: `combine(a, inverse(a)) == empty`. This is exactly
what makes **retraction** possible — removing a value from an accumulator without recomputing from
the values that remain. `Int` addition is a group (`inverse(a) = -a`); string concatenation,
`min`/`max`, and every approximate sketch in §6 are NOT groups — there is no way to "un-concat" a
suffix, "un-see" a value from a running max, or remove one observation's contribution to a
HyperLogLog register. §7 works through why this distinction is the one that decides whether a
sliding window is cheap or requires a full window recompute.

```scalascript
given intSumGroup: Group[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b
  def inverse(a: Int): Int = -a
```

## 3. `Aggregator[In, Acc, Out]` — the central primitive

A `Monoid[Acc]` alone says how to *merge* accumulated state. An aggregation also needs: how to
turn one raw input record into that state, and how to read a final answer back out of it once
merging is done. Splitting these three concerns is what makes aggregators composable (§4) — this
shape is deliberately the same one Twitter's Algebird calls `Aggregator` and the "Fold" pattern
used elsewhere: `prepare`, then `Monoid`, then `present`.

```scalascript
trait Aggregator[In, Acc, Out]:
  def monoid: Monoid[Acc]
  def prepare(in: In): Acc
  def present(acc: Acc): Out
```

Running one over a finite, in-memory sequence (the reference semantics every backend must agree
with — see §9):

```scalascript
def runAggregator[In, Acc, Out](xs: List[In], agg: Aggregator[In, Acc, Out]): Out =
  val acc = xs.foldLeft(agg.monoid.empty)((a, in) => agg.monoid.combine(a, agg.prepare(in)))
  agg.present(acc)
```

The simplest aggregator wraps a `Monoid[A]` directly with `In = Acc = Out = A` (`prepare`/`present`
both the identity) — every existing `Monoid` instance is already a trivial `Aggregator`. The
interesting cases have `Acc` carry more than `Out` needs (§5's `mean` accumulates a `(sum, count)`
pair but presents only their ratio), or `In` differ from `Acc` (a text aggregator whose `Acc` is a
word-count `Map`, prepared from raw `In = String` lines).

## 4. Composition

### 4.1 Product (`zip`) — two aggregators, one pass, one answer

Given a `Monoid[A]` and a `Monoid[B]`, pairs form a `Monoid[(A, B)]` by combining componentwise:

```scalascript
class PairMonoid[A, B](ma: Monoid[A], mb: Monoid[B]) extends Monoid[(A, B)]:
  def empty: (A, B) = (ma.empty, mb.empty)
  def combine(x: (A, B), y: (A, B)): (A, B) =
    (ma.combine(x._1, y._1), mb.combine(x._2, y._2))

def zipMonoid[A, B](ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)] = PairMonoid(ma, mb)
```

Lifted to `Aggregator`, this is the "compute two statistics in one pass" combinator:

```scalascript
class ZipAgg[In, AccA, AccB, OutA, OutB](
    aggA: Aggregator[In, AccA, OutA],
    aggB: Aggregator[In, AccB, OutB]
) extends Aggregator[In, (AccA, AccB), (OutA, OutB)]:
  def monoid: Monoid[(AccA, AccB)] = zipMonoid(aggA.monoid, aggB.monoid)
  def prepare(in: In): (AccA, AccB) = (aggA.prepare(in), aggB.prepare(in))
  def present(acc: (AccA, AccB)): (OutA, OutB) = (aggA.present(acc._1), aggB.present(acc._2))
```

`ZipAgg` generalizes to any fixed N by nesting — `zip(zip(a, b), c)` — or a variadic helper that
builds the nested pairs mechanically; there is no need for it to be a language primitive beyond
this one class.

### 4.2 `map` over the output

```scalascript
class MapAgg[In, Acc, Out, Out2](agg: Aggregator[In, Acc, Out], f: Out => Out2)
    extends Aggregator[In, Acc, Out2]:
  def monoid: Monoid[Acc] = agg.monoid
  def prepare(in: In): Acc = agg.prepare(in)
  def present(acc: Acc): Out2 = f(agg.present(acc))
```

### 4.3 Worked example: `mean` from `sum` and `count`, for free

```scalascript
given doubleSum: Monoid[Double] with
  def empty: Double = 0.0
  def combine(a: Double, b: Double): Double = a + b

given intSum: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

class SumAgg() extends Aggregator[Double, Double, Double]:
  def monoid: Monoid[Double] = doubleSum
  def prepare(in: Double): Double = in
  def present(acc: Double): Double = acc

class CountAgg[In]() extends Aggregator[In, Int, Int]:
  def monoid: Monoid[Int] = intSum
  def prepare(in: In): Int = 1
  def present(acc: Int): Int = acc

// mean = (sum, count) computed in ONE pass, then divided on present
val sumAndCount = ZipAgg(SumAgg(), CountAgg[Double]())
val mean        = MapAgg(sumAndCount, (p: (Double, Int)) => p._1 / p._2.toDouble)

runAggregator(List(1.0, 2.0, 3.0, 4.0, 5.0), mean)   // => 3.0
```

Verified: this exact program runs and prints `3` (ScalaScript's `Double` formatting drops a
trailing `.0`) against a freshly built `bin/ssc-tools` from this checkout.

No new aggregation logic was written for `mean` — it is `sum` and `count`, zipped, then divided.
This is the payoff §1 promised: statistics compose by combining smaller aggregators, not by
writing a new fold by hand each time.

## 5. Canonical exact aggregators

**All of §5 landed 2026-08-29** as `std/aggregator.ssc` — `sum`/`count`/`mean` (§4.3), `min`/`max`,
`variance`/`stddev` (§5.1), and `first`/`last` (§5.2).

| Aggregator | `Acc` | Commutative | Group? | Notes |
|---|---|---|---|---|
| `sum` | the numeric type | yes | yes | `Group` — subtraction inverts it |
| `count` | `Int` | yes | yes | a `Monoid[Unit]`-shaped `prepare` composed with `intSum` |
| `mean` | `(sum, count)` | yes | no | `Group` on the pair would need division to be safe at `count = 0`; present-time only |
| `min` / `max` | `Option[A]`, `None` as the `Top`/`Bottom` sentinel for `empty` | yes | **no** | no inverse: cannot "un-see" the max once other values remain; works over any `Order[A]`, not just numbers |
| `variance` / `stddev` | `(n, mean, M2)` triple (Welford/Chan) | yes | no | see §5.1 for the merge formula — this is the one exact statistic that is NOT simply "zip two simpler monoids" |
| `first` / `last` | `Option[A]`, keyed by a caller-attached position | **no** (needs the ordering key `.zipWithIndex` attaches — see §5.2) | no | `MinByAgg`/`MaxByAgg` generalize `min`/`max` to compare by a projected key instead of the value itself |

### 5.1 Variance as a monoid (Chan et al.'s parallel merge)

The naive two-pass variance formula (`mean((x - mean)^2)`) is not incrementally mergeable — it
needs the mean before it can start. The one-pass, mergeable form carries a triple `(n, mean, M2)`
where `M2` is the running sum of squared deviations from the running mean (Welford's algorithm for
`combine` with a single new value, generalized by Chan, Golub & LeVeque (1979) to merge two
**already-aggregated** partitions):

```scalascript
class VarianceAcc(val n: Int, val mean: Double, val m2: Double)

given varianceMonoid: Monoid[VarianceAcc] with
  def empty: VarianceAcc = VarianceAcc(0, 0.0, 0.0)
  def combine(a: VarianceAcc, b: VarianceAcc): VarianceAcc =
    if a.n == 0 then b
    else if b.n == 0 then a
    else
      val n      = a.n + b.n
      val delta  = b.mean - a.mean
      val mean   = a.mean + delta * b.n.toDouble / n.toDouble
      val m2     = a.m2 + b.m2 + delta * delta * a.n.toDouble * b.n.toDouble / n.toDouble
      VarianceAcc(n, mean, m2)
```

`present` divides `m2` by `n` (population variance) or `n - 1` (sample variance) — a policy choice
left to the caller, not baked into the monoid; `std/aggregator.ssc` implements this as two small
presenters (`populationVariance`, `sampleVariance`) composed onto a shared `VarianceAgg` with
`MapAgg`, so there is one monoid and two `Aggregator`s, not two monoids.

**Landing this found one more gap this document's earlier verification missed**: `VarianceAcc`'s
fields need `val` (`class VarianceAcc(val n: Int, ...)`, not the bare-constructor-parameter form
shown in earlier drafts of this section) — without it, the JVM lane's real Scala visibility rules
refuse `a.n`/`b.mean`/`acc.m2` from outside the class with "private value ... can only be accessed
from...", while `int`/`native` silently allow it. Every constructor-parameter case class in this
module now takes `val` for exactly this reason (`std/aggregator.ssc`'s own `VarianceAcc`).

### 5.2 `first` / `last` — `min`/`max` generalized to a projected key

`min`/`max` compare a value against itself; `first`/`last` need to compare values by **when they
arrived**, which the value alone does not carry — the input's fold order is not a substitute,
because `combine` must also be correct when merging two already-aggregated partitions (§9's
whole reason for existing), and partitions can finish in either order. The fix generalizes §5's
`min`/`max` rather than inventing a new mechanism: `MinByAgg[A, K](ord: Order[K], key: A => K)`
compares by a *projected* key instead of the value itself (`min` is the special case `key =
identity`), and the caller attaches the ordering key explicitly with `.zipWithIndex`
(`List[A] => List[(A, Int)]`) before folding, so the key travels with the value into `combine`
instead of being implicit in fold order:

```scalascript
class MinByAgg[A, K](ord: Order[K], key: A => K) extends Aggregator[A, Option[A], Option[A]]:
  def monoid: Monoid[Option[A]] = MinByMonoid(ord, key)   // same shape as MinMonoid, compares key(a) vs key(b)
  def prepare(in: A): Option[A] = Some(in)
  def present(acc: Option[A]): Option[A] = acc

def firstAgg[A](): Aggregator[(A, Int), Option[(A, Int)], Option[A]] =
  MapAgg(MinByAgg[(A, Int), Int](orderInt, (p: (A, Int)) => p._2), (acc: Option[(A, Int)]) => acc.map(p => p._1))

runAggregator(List(10.0, 20.0, 5.0, 40.0).zipWithIndex, firstAgg[Double]())   // => Some(10.0)
```

`lastAgg` is the same shape over `MaxByAgg`. Verified against this checkout's `bin/ssc-tools`;
found one more front limitation landing it: `acc.map((a, i) => a)` — a tuple-destructuring
2-parameter lambda passed to `Option.map` — silently double-wraps the result
(`Some(Some((10.0, 0)))` instead of `Some(10.0)`) on the reference front. `acc.map(p => p._1)`
(explicit tuple-field access, the same style §12.2's `ZipAgg`/`MapAgg` already use) works
correctly; every tuple-destructuring lambda in `std/aggregator.ssc` uses this form for that reason.

## 6. Approximate aggregators — sketches as monoids

**Landed 2026-08-30** as `std/aggregator.ssc`'s `HLLAgg`/`CMSMonoid`/`TDigestMonoid`, runnable at
`examples/std-aggregator-approx.ssc` and gated by `tests/conformance/std-aggregator-approx.ssc` —
**`CMSMonoid` is correct on every lane; `HLLAgg` and `TDigestMonoid` are known-red on `js` and
`jvm`** (their two `int`-lane bugs are now fixed, see below), one distinct, independently filed bug
per remaining lane (all found landing this, none assumed): `HLLAgg`'s hash needed a real bit-mixing
finalizer that isn't in the original design (below); `js`'s `Array.fill`/`List.fill`/`*.tabulate`
reject a `BigInt` count, which `1 << precision`-style `Int` arithmetic produces
(`v1/runtime/backend/js/BUGS.md` `js-codegen-array-fill-and-tabulate-reject-a-bigint-count` — TWO
sibling defects that used to block reaching this one are now fixed:
`js-codegen-does-not-resolve-a-sibling-zero-arg-def-from-another-method` and
`js-codegen-never-computes-or-attaches-a-class-body-val-field`); `jvm`'s transpilation
inherits the pre-existing, already-tracked `int-width` non-conformance (a 64-bit `Long` literal
`fmix64` needs doesn't fit the 32-bit `Int` `run-jvm` emits). **`int`'s two bugs are fixed**: the
`math` object was missing `log` entirely (`v1/runtime/backend/interpreter/BUGS.md`
`math-object-is-missing-log-and-other-transcendental-functions` — `log`/`exp` now added, mirroring
`sqrt`'s exact wiring) and `List.sortBy` on a `Double` key sorted lexicographically instead of
numerically (`sortby-on-a-non-int-key-sorts-lexicographically-not-numerically` — a second numeric
fast path now covers `Double`/mixed `Int`+`Double` keys, alongside the untouched `Int`-only one).
Landing this also found a JS syntax error in `groupByAgg` (§8) that had been silently masking part
of an already-filed bug's own diagnosis since that section landed — `v1/runtime/backend/js/BUGS.md`
`js-val-tuple-destructuring-does-not-escape-a-reserved-word-component-name`, worked around at the
time by renaming `groupByAgg`'s `in` to `item`. **Now fixed for real** (the codegen itself, not just
this one call site): `val (a, b) = tuple`-style destructuring now escapes a JS-reserved-word
component name exactly like a lambda parameter's own destructuring already did. `groupByAgg`'s `in`
reverted to its original name.

Exact distinct-count, exact quantiles, and exact top-K cannot be computed in bounded memory over
unbounded data — there is no monoid that does it. The industry answer (Flajolet et al. for
HyperLogLog 2007; Cormode & Muthukrishnan for Count-Min Sketch 2005; Dunning & Ertl for T-Digest
2019) is a family of **probabilistic sketches that ARE monoids**, trading exactness for a known,
bounded error at fixed memory. `Aggregator` doesn't need a new concept for this — an approximate
sketch is an ordinary `Monoid[Acc]`, just one whose `present` returns an *estimate*. What the
language needs is to make that fact visible rather than hide it behind the same interface as an
exact aggregator:

```scalascript
trait ApproxAggregator[In, Acc, Out] extends Aggregator[In, Acc, Out]:
  def errorBound: Double   // e.g. HLL's standard error ≈ 1.04 / sqrt(2^precision)
```

An `ApproxAggregator` is still an `Aggregator` (usable anywhere one is expected, composes via §4
the same way) — the added `errorBound` is what a renderer or a report is expected to surface next
to the number, so "approximately 2.1M distinct users (±1.6%)" is the honest default rendering, not
an afterthought a careless caller can omit.

Each sketch below is presented as a `precision`/`depth`-parameterized **class**, not a `given`, and
each verified example threads a field through a local `val` before indexing it — see §12.3 for the
two front limitations this sidesteps (a `given ... with` method capturing a free top-level `val`,
and indexing a case-class field directly as `recv.field(i)`). Neither affects the algebra: every
sketch below is still an ordinary `Monoid[Acc]` instance, just constructed with its tuning
parameter passed explicitly rather than closed over.

### 6.1 HyperLogLog — approximate distinct count

Register array of `2^p` small counters. `prepare` hashes the input and updates one register with
the position of its lowest set bit; `combine` takes the elementwise MAX of two register arrays
(this is what makes it a monoid — a register only ever grows, and taking the max of maxes is
associative and commutative); `present` runs the harmonic-mean estimator over the registers.

```scalascript
case class HLLAcc(registers: Array[Int])   // length 2^precision

class HLLMonoid(precision: Int) extends Monoid[HLLAcc]:
  def empty: HLLAcc = HLLAcc(Array.fill(1 << precision)(0))
  def combine(a: HLLAcc, b: HLLAcc): HLLAcc =
    val ar = a.registers
    val br = b.registers
    HLLAcc(Array.tabulate(ar.length)(i => math.max(ar(i), br(i))))
```

Verified: `HLLMonoid(4).empty.registers.length` is `16` (`2^4`); combining `HLLAcc(Array(1,2,3,4))`
with `HLLAcc(Array(4,3,2,1))` gives registers starting `4, 3, …` (elementwise max), as expected.

Standard error ≈ `1.04 / sqrt(2^precision)`; `precision = 14` (16 KB per sketch) gives ≈0.8% error
regardless of the true cardinality — the defining property that makes it usable on unbounded data.

**What landing `prepare`/`present` needed, that this section's original draft only described in
prose:** a real hash function with strong bit-independence. `strHash` (§6.2's polynomial hash) alone
gave a 54% cardinality error on 10,000 known-distinct keys — measured, not assumed — because
`h = h*31 + c` doesn't mix bits enough for HLL's per-bit statistics (register index and rank both
derive from individual bits of the hash). Running MurmurHash3's 64-bit finalizer (`fmix64`) over
`strHash`'s output brought the same test down to 1.2% error. `HLLAgg` in `std/aggregator.ssc` also
found a v1-interpreter-specific defect landing this — a class-body `val` field unreadable from a
sibling method, filed in `v1/runtime/backend/interpreter/BUGS.md`, reproduced with a minimal example
unrelated to hashing or aggregators at all. **Now fixed**
(`class-body-val-field-undefined-in-a-sibling-method`): a class body's own statements were scanned
for methods only, so a `Defn.Val` was silently dropped and its name never reached the per-instance
field array or the type's field-order registry. `hllMonoid`/`m` are ordinary `val`s again, as
originally designed, no `def` workaround needed. A second defect was suspected while landing
`CMSMonoid` — `Array.tabulate`'s lambda losing a sibling top-level `def` cross-module — and worked
around at the time; re-tested afterward with a faithful reconstruction against both the current
interpreter and the exact pre-fix binary, and it does not reproduce on either. Filed `wontfix`
(`array-tabulate-lambda-loses-a-sibling-top-level-def-cross-module`) — most likely the class-body-val
bug above, active in the same file at the same time, was the actual cause and got misattributed.
`CMSMonoid.add` now uses the natural, un-worked-around form.

### 6.2 Count-Min Sketch — approximate frequency / top-K

A 2-D array of counters (`depth` hash functions × `width` buckets). `prepare` increments one
counter per row via a different hash; `combine` is elementwise sum (a monoid — sums of sums
associate and commute); `present` (for a given key) takes the MIN across the `depth` rows — the
sketch only ever overestimates, and the min row is the tightest available bound.

```scalascript
def strHash(seed: Int, s: String): Int =
  var h = seed
  for i <- 0 until s.length do
    h = h * 31 + s.charAt(i).toInt
  math.abs(h)

case class CMSAcc(counts: Array[Array[Int]])

class CMSMonoid(depth: Int, width: Int) extends Monoid[CMSAcc]:
  def empty: CMSAcc = CMSAcc(Array.fill(depth)(Array.fill(width)(0)))
  def combine(a: CMSAcc, b: CMSAcc): CMSAcc =
    val ac = a.counts
    val bc = b.counts
    CMSAcc(Array.tabulate(depth)(r => Array.tabulate(width)(c => ac(r)(c) + bc(r)(c))))
  // `add`/`estimate` are this Aggregator's `prepare`/`present` — kept as plain methods here
  // rather than spelled out as a full Aggregator[String, CMSAcc, Int => Int] wrapper, which
  // would add ceremony without changing what is being shown.
  def add(acc: CMSAcc, key: String): CMSAcc =
    val counts = acc.counts
    CMSAcc(Array.tabulate(depth)(r => Array.tabulate(width)(c =>
      if c == strHash(r * 7919 + 17, key) % width then counts(r)(c) + 1 else counts(r)(c))))
  def estimate(acc: CMSAcc, key: String): Int =
    val counts = acc.counts
    var best = -1
    for r <- 0 until depth do
      val c = strHash(r * 7919 + 17, key) % width
      val v = counts(r)(c)
      if best == -1 || v < best then best = v
    best
```

Verified: adding `apple` three times, `banana` twice, and `cherry` once (width 64, depth 4) gives
`estimate` `3` / `2` / `1` respectively and `0` for a never-added key `durian` — exact at this small
scale, as expected (Count-Min Sketch only ever overestimates, and there is no room for a hash
COLLISION to inflate a count yet at four buckets per row and three keys).

Error bound: with width `w` and depth `d`, estimates are within `ε · N` of the truth with
probability `1 - δ`, for `w = ⌈e/ε⌉` and `d = ⌈ln(1/δ)⌉` (`N` = total count).

### 6.3 T-Digest — approximate quantiles

Maintains a small, size-bounded set of weighted centroids (cluster of nearby values + a count),
with the crucial property that a `Monoid[Acc]`'s `combine` is centroid-list merge followed by a
re-clustering pass, which stays commutative and associative up to the digest's compression
parameter. `present(q)` interpolates the requested quantile across the centroids. Accuracy is
non-uniform by design — the tails (p99, p999) get proportionally more centroids than the middle,
which is exactly the region monitoring/alerting dashboards care about most.

```scalascript
case class Centroid(mean: Double, weight: Double)
case class TDigestAcc(centroids: List[Centroid])

class TDigestMonoid(maxCentroids: Int) extends Monoid[TDigestAcc]:
  def empty: TDigestAcc = TDigestAcc(Nil)
  // The MERGE itself is exact (concatenate); COMPRESSION down to `maxCentroids` is a policy
  // choice, not part of what makes `combine` a monoid. This one groups centroids into
  // equal-size runs and folds each to its weighted mean — simpler than, and less accurate
  // than, the real T-Digest's k-scale function (which allocates more, narrower centroids
  // near the tails on purpose). The monoid law (associativity) holds either way; only the
  // ACCURACY profile differs — swapping in a real scale function changes present()'s error
  // distribution, not combine()'s correctness.
  def combine(a: TDigestAcc, b: TDigestAcc): TDigestAcc =
    val all = (a.centroids ++ b.centroids).sortBy(c => c.mean)
    if all.length <= maxCentroids then TDigestAcc(all)
    else
      val groupSize = (all.length + maxCentroids - 1) / maxCentroids
      val grouped = all.grouped(groupSize).toList
      val merged = grouped.map { g =>
        val totalW = g.foldLeft(0.0)((s, c) => s + c.weight)
        val weightedMean = g.foldLeft(0.0)((s, c) => s + c.mean * c.weight) / totalW
        Centroid(weightedMean, totalW)
      }
      TDigestAcc(merged)
  def addPoint(acc: TDigestAcc, x: Double): TDigestAcc =
    TDigestAcc(acc.centroids ++ List(Centroid(x, 1.0)))
  def quantile(acc: TDigestAcc, q: Double): Double =
    val sorted = acc.centroids.sortBy(c => c.mean)
    val totalWeight = sorted.foldLeft(0.0)((s, c) => s + c.weight)
    val target = q * totalWeight
    var cum = 0.0
    var result = 0.0
    var found = false
    for c <- sorted do
      if !found then
        cum = cum + c.weight
        if cum >= target then
          result = c.mean
          found = true
    result
```

Verified: feeding `1..100` into one digest and `101..200` into another, then `combine`-ing them
(`maxCentroids = 20`) gives exactly `20` centroids after compression, `quantile(0.5)` ≈ `95.5`
(true median of `1..200` is `100.5` — the uniform-grouping compression policy above, not the
merge, accounts for the gap) and `quantile(0.99)` ≈ `195.5` (true p99 ≈ `198`) — on every lane.
The `int` interpreter originally gave a scrambled, non-monotonic centroid list after `combine`,
traced to `List.sortBy` sorting a `Double` key (`c.mean`) lexicographically (as if the keys were
strings) rather than numerically; `v1/runtime/backend/interpreter/BUGS.md`
`sortby-on-a-non-int-key-sorts-lexicographically-not-numerically` named the exact code path
(`Int` keys got a real numeric fast path, every other key type fell back to `Value.show` + string
comparison) and is now **fixed** — a second numeric fast path covers `Double`/mixed `Int`+`Double`
keys, alongside the untouched `Int`-only one. `TDigestMonoid` is green on `int`.

## 7. Group vs. Monoid: why it decides whether a sliding window is cheap

A **tumbling** window ("every 5 minutes, non-overlapping") only ever needs `combine` — each window
closes and reports once, and a fresh accumulator starts for the next. Any `Monoid` handles this,
exact or approximate.

A **sliding** window ("the last 5 minutes, updated every second") is different: as time advances,
data both ENTERS (new elements, handled by `combine` as always) and EXITS (elements older than the
window, which must stop contributing). Two strategies exist:

- **Recompute from the retained raw window contents.** Always correct, for any `Monoid` — but
  requires keeping every raw element (or every sub-bucket) in the window and refolding on every
  slide, which defeats the point of aggregating at all for a long window with frequent updates.
- **Retract via `inverse`.** If `Acc` is a `Group`, `combine(windowAcc, inverse(agingOutValue))`
  removes exactly that contribution in O(1), independent of window length. This is only sound for
  a `Group` — attempting it on `min`/`max` or any sketch in §6 produces a wrong answer, silently,
  because there is genuinely no way to know whether some OTHER retained element was tied for the
  max once the one you're retracting is removed.

The intent is to make this a **type-level fact, not a runtime surprise**: a sliding-window operator
should only accept a `Group`-backed `Aggregator`; requesting one over a `min`/`max` or a §6 sketch
should be a rejection with a clear reason ("no inverse — use a tumbling window, or a bucketed
approximation"), not a silently wrong number or an unbounded-memory fallback chosen for you.

**Landed 2026-08-29** as `std/aggregator.ssc`'s `GroupAggregator`/`SlidingWindow` — **narrower than
that intent, though, found while landing it**: `GroupAggregator extends Aggregator` and adds a
`group: Group[Acc]` member, and `SlidingWindow`'s constructor asks for a `GroupAggregator`, not a
plain `Aggregator` — but passing a `MinAgg`/`MaxAgg` (a real `Aggregator`, never declared to extend
`GroupAggregator`) compiles and runs without error on both self-hosted fronts today. The mismatch
surfaces only the first time `.group` is actually accessed inside `push` (an `unhandled runtime
effect`), not at the call site — a runtime surprise, not the compile-time fact this section wants.
Reproduced with a minimal example unrelated to aggregators at all: `v2/BUGS.md`
`trait-typed-parameter-accepts-a-non-conforming-argument`. `GroupAggregator` is still worth
declaring — it documents the real constraint for a reader — but is not, today, an enforced
guardrail.

## 8. `groupBy` needs no new concept — it's `Map[K, Acc]`, pointwise

**Landed 2026-08-29** as `std/aggregator.ssc`'s `MapMonoid`/`groupByAgg`. If `Acc` is a `Monoid`,
then `Map[K, Acc]` is a `Monoid` too — combine two maps key-by-key, using `Acc`'s `combine` on keys
present in both, and passing through keys present in only one:

```scalascript
class MapMonoid[K, Acc](inner: Monoid[Acc]) extends Monoid[Map[K, Acc]]:
  def empty: Map[K, Acc] = Map[K, Acc]()
  def combine(a: Map[K, Acc], b: Map[K, Acc]): Map[K, Acc] =
    b.toList.foldLeft(a) { (acc, kv) =>
      val (k, v) = kv
      acc.get(k) match
        case Some(existing) => acc.updated(k, inner.combine(existing, v))
        case None           => acc.updated(k, v)
    }

def groupByAgg[K, In, Acc, Out](xs: List[(K, In)], agg: Aggregator[In, Acc, Out]): Map[K, Out] =
  val monoid = MapMonoid[K, Acc](agg.monoid)
  val acc = xs.foldLeft(monoid.empty) { (m, kv) =>
    val (k, in) = kv
    monoid.combine(m, Map(k -> agg.prepare(in)))
  }
  acc.toList.foldLeft(Map[K, Out]()) { (m, kv) =>
    val (k, v) = kv
    m.updated(k, agg.present(v))
  }
```

Two front limitations found landing this: `Map` values have no `.foldLeft` — go through `.toList`
first (`b.toList.foldLeft(...)`, matching the codebase's own precedent in `auth-full.ssc`-style
code), a real, permanent limitation; and `Map.empty` used to throw under the v1 interpreter
(`--v1`) — see `v1/runtime/backend/interpreter/BUGS.md`
`map-dot-empty-reads-empty-as-a-literal-key-not-the-companion-accessor`, now **fixed** (`Map` is
wrapped in a companion object, like `List`/`Vector`/`Set` already were). `MapMonoid.empty` uses
`Map.empty` again, as originally designed.

"Group by key, aggregate per group" is this `Map`-monoid wrapping ANY `Aggregator`'s own monoid —
not a separate primitive the language needs to define. This is also exactly what `keyBy` +
`aggregatePerKey` already do on `DStream` (§9); this section just names the algebraic fact that
makes it sound.

## 9. Bridge to `DStream`/`Pipeline` and `Dataset`

**Landed 2026-08-30** as `std/aggregator.ssc`'s `aggregatorSeqOp`/`aggregatorCombOp`, verified by
the equivalence property below (running for real against this checkout). `specs/distributed-streams.md`
§5.2/§5.8 already defines the raw primitives an `Aggregator` slots into directly, with real backends
(native, Spark, Kafka Streams, Flink, Beam) already implemented:

| `DStream`/`Dataset` operator | Existing signature | `Aggregator` bridge |
|---|---|---|
| `aggregatePerKey(z)(f)(g)` | `B => ((B,A)=>B) => ((B,B)=>B) => DStream[KV[K,B]]` | `z = agg.monoid.empty`, `f = (acc,in) => agg.monoid.combine(acc, agg.prepare(in))`, `g = agg.monoid.combine` |
| `runFold(z)(f)` | `B => ((B,A)=>B) => B` | same `z`/`f` as above, single global accumulator |
| `combinePerKey(f)` | `((A,A)=>A) => DStream[KV[K,A]]` | the degenerate case where `In = Acc` — pass `agg.monoid.combine` directly, no `prepare` step needed |

Two small helper functions make the bridge mechanical rather than something every caller
hand-writes:

```scalascript
def aggregatorSeqOp[In, Acc, Out](agg: Aggregator[In, Acc, Out]): (Acc, In) => Acc =
  (acc, in) => agg.monoid.combine(acc, agg.prepare(in))

def aggregatorCombOp[In, Acc, Out](agg: Aggregator[In, Acc, Out]): (Acc, Acc) => Acc =
  (a, b) => agg.monoid.combine(a, b)

// stream.keyBy(keyFn).aggregatePerKey(agg.monoid.empty)(aggregatorSeqOp(agg))(aggregatorCombOp(agg))
```

Verified standalone (without a live `DStream` backend, which needs plugin infrastructure beyond a
single-file check): `aggregatorSeqOp`/`aggregatorCombOp` are what `aggregatePerKey` calls
internally, so folding two "partitions" with `seqOp` and merging their results with `combOp` must
equal folding the whole input with `seqOp` directly — exactly the property that licenses running
`aggregatePerKey` across any number of real partitions.

```scalascript
val agg    = CountAgg[String]()
val seqOp  = aggregatorSeqOp(agg)
val combOp = aggregatorCombOp(agg)
val part1  = List("a", "b", "c").foldLeft(agg.monoid.empty)(seqOp)
val part2  = List("d", "e").foldLeft(agg.monoid.empty)(seqOp)
println(combOp(part1, part2))   // => 5, same as counting all five elements in one partition
```

This is a proof of the bridge's MECHANISM. The end-to-end integration is now WIRED (2026-08-30,
claim `dstreams-aggregate-per-key`): `aggregatePerKey(zero)(seqOp)(combOp)` is a live operator on
the native `DStream` backend (the `dstreams-plugin` DirectRunner), and `std/dstreams.ssc` exports
`aggregateWith(stream, agg)` — the convenience layer that derives all three from any `Aggregator`
in this document and applies `present` per key. Gated by
`tests/conformance/std-dstreams-aggregator.ssc`, which pins, over REAL pipeline runs: per-key
`SumAgg`/`CountAgg`/`VarianceAgg` accumulators equal to this document's reference fold; `mean`
(zip/map composition) via `aggregateWith` equal to `groupByAgg`; per-key `HLLAgg` distinct-count
equal to per-key `runAggregator`; the two-partition split-and-merge equality computed from real
pipeline outputs (which is what exercises `combOp` — the DirectRunner is one partition, so inside a
single run `combOp` legitimately applies zero times, exactly as a one-partition run of any real
backend would); and the empty-input case. Windowed aggregation and the external backends
(Spark/Kafka/Flink, `specs/distributed-streams.md` v2.1.3+) remain unwired here.

**Fault-tolerance, checked and deferred honestly:** the native DirectRunner has NO retry/redelivery
machinery — `evalDag` is a synchronous single-process walk, and the `AtLeastOnce` capability it
declares is vacuously met by running once. The retry machinery that DOES exist in this repo
(`tests/conformance/distributed-failure-retry.ssc`, coordinator-detects-`Exit`-and-retries) belongs
to the v1.22 mapreduce actor cluster (`std/mapreduce/cluster.ssc`), a separate subsystem not
reachable from the dstreams-plugin's lanes. A fault-injection test for `aggregatePerKey` therefore
needs either that wiring or Spark — slice B work, not silently skipped.

Because §7's `Group` requirement for sliding windows is a property of `Aggregator.monoid` (whether
it happens to be a `Group`, checkable by the caller or a future compiler capability-negotiation
rule alongside `specs/distributed-streams.md`'s existing backend-capability negotiation), a
sliding-window builder can refuse a non-`Group` aggregator using the SAME negotiation mechanism
that spec already uses to refuse an operator a chosen backend cannot execute — no new negotiation
machinery, just one more capability to check.

`runAggregator` (§3) is the single-node, in-memory REFERENCE semantics every backend — native
interpreter, Spark, Flink, a future Python/R target — must agree with. This is the same role
`specs/distributed-streams.md` assigns the native backend already (its correctness oracle); this
document adds no second oracle.

## 10. Rendering results

§13 used to list rendering as entirely out of scope, on the grounds that "no chart/table/render
module exists" under `std/`. Checked directly rather than assumed: that is true for **charts**,
but not for **tables** — `std/ui/data.ssc` already ships a live, reactive table component
(`dataTable`/`staticDataTable`, with typed columns via `fcol`/`dcol`/`mcol`/`scol`/`lcol`), and
`examples/std-ui/table.ssc`'s `Table.render(headers, rows, rightAlign)` shows the exact shape a
*static* table renderer needs. Neither is `Aggregator`-aware today, and neither needs to be
rewritten to become useful to one — both already consume the shape an aggregation result already
has: rows of plain values.

### 10.1 The bridge is a shape, not a new primitive

**Landed 2026-08-30** as `std/aggregator.ssc`'s `mapToRows`/`renderTableHtml`. An `Aggregator`'s
`Out`, or a `groupByAgg` result's `Map[K, Out]` (§8), becomes renderable the moment it is turned
into `List[String]` headers and `List[List[String]]` rows — nothing about §2–§9 needs to know this
is coming:

```scalascript
def mapToRows[K, V](m: Map[K, V]): List[List[String]] =
  m.toList.map { kv =>
    val (k, v) = kv
    List(s"$k", s"$v")
  }

def renderTableHtml(headers: List[String], rows: List[List[String]]): String =
  val head = headers.map(h => "<th>" + h + "</th>").mkString
  val body = rows.map(row => "<tr>" + row.map(v => "<td>" + v + "</td>").mkString + "</tr>").mkString
  "<table><thead><tr>" + head + "</tr></thead><tbody>" + body + "</tbody></table>"

val counts = Map("apple" -> 3, "banana" -> 2, "cherry" -> 1)   // e.g. a groupByAgg(count) result
println(renderTableHtml(List("item", "count"), mapToRows(counts)))
// => <table><thead><tr><th>item</th><th>count</th></tr></thead><tbody>
//      <tr><td>apple</td><td>3</td></tr><tr><td>banana</td><td>2</td></tr>
//      <tr><td>cherry</td><td>1</td></tr></tbody></table>
```

Two front-portability gotchas found landing this, both worked around in the code above: a
tuple-destructuring lambda PARAMETER (`(k, v) => ...`, as an earlier draft of this section had it)
is exactly the shape §5.2 found miscompiling on the reference front — `mapToRows` destructures with
a `val` binding inside the lambda body instead. And `v.toString` on a `Double` is not lane-portable
— `13.0.toString` gives `"13.0"` on `run-jvm` (real Scala's own formatting) while every other lane
renders `"13"`; `s"$v"` string interpolation gives `"13"` consistently everywhere, so `mapToRows`
uses that, not `.toString`, for both `k` and `v`.

The real `Table.render`/`staticDataTable` do more (styling, sorting, live updates from a
`Signal`) but consume the identical `headers` + `rows` shape — this document's job stops at
producing that shape from an `Aggregator`'s output; which existing renderer consumes it is a
choice at the call site, not something §2–§9 needs an opinion about.

### 10.2 JSON already works today, with no new code

`jsonStringify` (`std/json.ssc`) already turns any `Map`/`List`/case-class value into JSON — an
`Aggregator`'s `Out` or a `groupBy` result needs no bridge code at all for this format:

```scalascript
println(jsonStringify(counts))   // => {"apple":3,"banana":2,"cherry":1}
```

### 10.3 Charts remain the genuine gap, with a real extension point already in place

No chart component exists anywhere under `std/` (verified: no `Chart`/`Series` type in `std/`,
`std/ui/`, or `examples/`). This is not a placeholder gap invented for this document —
`std/ui/content.ssc`'s `slots` registry (`ContentToolkitComponent(name, render)`) is ALREADY
described, in its own comments, as "the escape hatch for content the declarative vocabulary can't
express (a custom chart, a bespoke composite)": the extension point exists and is already used for
other custom content; a future chart renderer plugs into it the same way, consuming an
`Aggregator`'s `Out` (or a time series of them, for a live dashboard) the same way §10.1's table
bridge does. Building that renderer is out of scope here (§13) — the point of this subsection is
that "any format" (§1) does not need a new mechanism invented for charts specifically once it
exists; it needs the same `headers`/`rows`-shaped bridge, or `slots`' existing escape hatch.

## 11. Effects and asynchrony

**§11.1's base shape and §11.3's `ContramapAgg`/`dimapAgg` landed 2026-08-30** as
`std/aggregator.ssc`'s `EffAggregator`/`runEffAggregator`/`ContramapAgg`/`dimapAgg`. **§11.1's
`LiftAgg` and §11.2's `ZipEffAgg` are NOT shipped**, despite being fully specified below and
verified correct on the reference front — landing them found a serious, previously unknown front
defect: a class parameterized by a higher-kinded `F[_]`, holding a constructor field whose type
also involves `F` (`Applicative[F]` for `LiftAgg`, an `EffAggregator[F, ...]` field for
`ZipEffAgg`), throws a bogus `arity: N expected, M given` at runtime — but ONLY once `VarianceAcc`
(§5.1's `val`-fielded class, needed for the JVM lane) is anywhere in the same program's import
graph, which after §5.1 landed, `std/aggregator.ssc` itself always is. Isolated to a minimal,
fully standalone repro with no aggregator code at all — see `v2/BUGS.md`
`higher-kinded-generic-field-corrupts-arity-of-an-unrelated-val-class-elsewhere-in-scope`. Every
workaround tried (method-parameter passing, field aliasing, a top-level helper, a local class) either
still failed or defeated the point of the combinator (dropping `F[_]` genericity entirely). This is
filed as real, separate front-end work, not a design mistake in this section — `LiftAgg`/`ZipEffAgg`
stay queued in `BACKLOG.md` until a fix exists or a working design is found that avoids the pattern.

`Aggregator.prepare`/`present` (§3) are total, pure functions. Real records don't always cooperate:
`prepare` may need to validate, parse-and-possibly-fail, or enrich a record with an external
(asynchronous) lookup before it becomes accumulator state. This section asks the question the
opening discussion raised explicitly — monads, applicatives, functors, profunctors, arrows,
categories, delimited continuations, "where it helps and doesn't complicate" — and answers it by
checking what ScalaScript already has before reaching for any of that vocabulary formally.

**What already exists, reused as-is:** `std/functor-applicative-monad.ssc` ships `Functor[F[_]]`,
`Applicative[F[_]]`, `Monad[F[_]]` with working `List`/`Option` instances (extension-method
dispatch via `given ... with` — verified live, part of this repository's own conformance corpus at
`tests/conformance/std-functor-applicative-monad.ssc`). ScalaScript models asynchrony as an
**algebraic effect**, not a `Future`/`Task` value type (`specs/algebraic-effects.md` §7:
`def fetch(url): Response ! Async`), discharged by effect handlers; `direct[M]`/`.!` gives
do-notation over any `Monad[F[_]]`, `Either`/the `A throws E` alias already cover pure failure.
None of this is new — §12.1 already found that ScalaScript doesn't auto-resolve `using` clauses,
so every helper below takes its typeclass instance as an **explicit parameter**, exactly like
every combinator already in §4 and §9.

### 11.1 `EffAggregator[F[_], In, Acc, Out]` — the same three-part shape, prepare/present in `F`

```scalascript
trait EffAggregator[F[_], In, Acc, Out]:
  def monoid: Monoid[Acc]        // merging accumulated state stays PURE — no reason for it not to
  def prepare(in: In): F[Acc]    // turning a record into state may fail, or need an effect
  def present(acc: Acc): F[Out]  // reading the final answer out may also be effectful
```

`Acc`'s `monoid` is deliberately untouched — an accumulator merge (§2) has no reason to become
effectful just because *producing* one accumulator value did, and keeping it pure is what lets
`Aggregator` (§3) and `EffAggregator` share the same `Monoid[Acc]` unchanged.

A pure `Aggregator` lifts into any `Applicative[F]` for free — this is the "adapter" the opening
discussion asked for, built directly on top of §3's `Aggregator` rather than beside it:

```scalascript
class LiftAgg[F[_], In, Acc, Out](agg: Aggregator[In, Acc, Out], app: Applicative[F])
    extends EffAggregator[F, In, Acc, Out]:
  def monoid: Monoid[Acc] = agg.monoid
  def prepare(in: In): F[Acc] = app.pure(agg.prepare(in))
  def present(acc: Acc): F[Out] = app.pure(agg.present(acc))
```

Running one needs a monadic fold — each `prepare` now returns `F[Acc]`, so combining it into the
running total has to happen *inside* `F`:

```scalascript
def runEffAggregator[F[_], In, Acc, Out](
    xs: List[In], agg: EffAggregator[F, In, Acc, Out], m: Monad[F]
): F[Out] =
  val accF: F[Acc] = xs.foldLeft(m.pure(agg.monoid.empty)) { (accF, in) =>
    accF.flatMap(acc => agg.prepare(in).flatMap(prepared => m.pure(agg.monoid.combine(acc, prepared))))
  }
  accF.flatMap(agg.present)
```

Verified with `F = Option`, a `prepare` that fails the whole aggregation on a negative input:

```scalascript
class ValidatingSum() extends EffAggregator[Option, Int, Int, Int]:
  def monoid: Monoid[Int] = intSum
  def prepare(in: Int): Option[Int] = if in >= 0 then Some(in) else None
  def present(acc: Int): Option[Int] = Some(acc)

println(runEffAggregator(List(1, 2, 3), ValidatingSum(), optionMonad))    // => Some(6)
println(runEffAggregator(List(1, -2, 3), ValidatingSum(), optionMonad))   // => None
```

`runEffAggregator` itself found one more, independent codegen defect landing it: `run-jvm`
(transpile to real Scala 3, compile with scala-cli) emits `accF.flatMap(...)` against a completely
unconstrained `F`, which real Scala 3 rejects (`value flatMap is not a member of F[Acc]`) — `int`
and the default native lane both run the identical source correctly. See
`v1/runtime/backend/jvm/BUGS.md` `jvm-gen-emits-flatmap-on-an-unconstrained-generic-type-param`;
`tests/conformance/std-aggregator.ssc` carries `known-red: jvm` against it (alongside the
pre-existing `known-red: js`).

and `LiftAgg` lifting an ordinary §3 `Aggregator` (no changes to it) into the same shape:

```scalascript
val lifted = LiftAgg(CountAgg[String](), optionMonad)   // optionMonad IS an Applicative[Option]
println(runEffAggregator(List("a", "b", "c"), lifted, optionMonad))   // => Some(3)
```

### 11.2 Composing two effectful aggregators — `Applicative.ap`, not a new mechanism

§4.1's `ZipAgg` generalizes the same way `Aggregator` itself did — `prepare` for each side now
returns `F[Acc]`, and combining two independent `F`-values into one is exactly what
`Applicative.ap` is for (no `Monad`/sequencing dependency between the two sides needed, since
neither's `prepare` depends on the other's result):

```scalascript
class ZipEffAgg[F[_], In, AccA, AccB, OutA, OutB](
    aggA: EffAggregator[F, In, AccA, OutA],
    aggB: EffAggregator[F, In, AccB, OutB],
    app: Applicative[F]
) extends EffAggregator[F, In, (AccA, AccB), (OutA, OutB)]:
  def monoid: Monoid[(AccA, AccB)] = PairMonoid(aggA.monoid, aggB.monoid)
  def prepare(in: In): F[(AccA, AccB)] =
    aggA.prepare(in).ap(aggB.prepare(in).map(b => (a: AccA) => (a, b)))
  def present(acc: (AccA, AccB)): F[(OutA, OutB)] =
    aggA.present(acc._1).ap(aggB.present(acc._2).map(b => (a: OutA) => (a, b)))
```

Verified: zipping two validating aggregators over `Option` short-circuits to `None` the moment
either side's `prepare` does, and pairs their results otherwise —
`ZipEffAgg(ValidatingSum(), ValidatingCount(), optionMonad).prepare(5)` gives `Some((5, 1))`;
`.prepare(-1)` gives `None`.

### 11.3 `Aggregator` is already profunctor-shaped — named, not formalized (yet)

`Aggregator[In, Acc, Out]` is contravariant in `In` and covariant in `Out` with `Acc` fixed —
exactly a `Profunctor`'s shape (`dimap: (C => A, B => D) => P[A,B] => P[C,D]`). §4.2's `MapAgg` is
already the covariant half (`rmap`); the contravariant half was missing:

```scalascript
class ContramapAgg[In2, In, Acc, Out](agg: Aggregator[In, Acc, Out], f: In2 => In)
    extends Aggregator[In2, Acc, Out]:
  def monoid: Monoid[Acc] = agg.monoid
  def prepare(in: In2): Acc = agg.prepare(f(in))
  def present(acc: Acc): Out = agg.present(acc)

def dimapAgg[In2, In, Acc, Out, Out2](
    agg: Aggregator[In, Acc, Out], f: In2 => In, g: Out => Out2
): Aggregator[In2, Acc, Out2] =
  MapAgg(ContramapAgg(agg, f), g)
```

Verified: adapting a `SumAgg: Aggregator[Int, Int, Int]` to sum STRING LENGTHS instead —
`ContramapAgg(SumAgg(), (s: String) => s.length)` run over `List("a", "bb", "ccc")` gives `6`;
`dimapAgg` additionally formatting the output gives `"total=6"`.

**Why this is named rather than made a formal `Profunctor[P[_, _]]` instance, checked rather than
assumed:**

```scalascript
trait Profunctor[P[_, _]]:
  extension [A, B](p: P[A, B]) def dimap[C, D](f: C => A, g: B => D): P[C, D]

class DimappedAgg[C, In, Acc, Out, D](p: Aggregator[In, Acc, Out], f: C => In, g: Out => D)
    extends Aggregator[C, Acc, D]:
  def monoid: Monoid[Acc] = p.monoid
  def prepare(in: C): Acc = p.prepare(f(in))
  def present(acc: Acc): D = g(p.present(acc))

given aggProf[Acc]: Profunctor[[In, Out] =>> Aggregator[In, Acc, Out]] with
  extension [A, B](p: Aggregator[A, Acc, B])
    def dimap[C, D](f: C => A, g: B => D): Aggregator[C, Acc, D] = DimappedAgg(p, f, g)

SumAgg().dimap((s: String) => s.length, (n: Int) => "total=" + n.toString)
```

parses, typechecks, and (**FIXED 2026-08-29**, see §12.4) now dispatches correctly — a
`Profunctor[P[_, _]]` trait (mirroring `std/bifunctor.ssc`'s existing `Bifunctor`), a `given`
instance for `Aggregator` fixed at one `Acc` via a type lambda over a partially-applied type
constructor. Verified: `SumAgg().dimap((s: String) => s.length, (n: Int) => "total=" +
n.toString).prepare("abc")` gives `3`. Before the fix this threw `unhandled runtime effect:
SumAgg.dimap` — the extension method `given` was never actually consulted at the call site, the
same failure family as §12.1's `given` derivation gap. `ContramapAgg`/`dimapAgg` still exist as
ordinary functions (exactly like every other combinator in §4) rather than being retired in favor
of the formal instance — both are cheap, neither is now more "correct" than the other.

### 11.4 What stays deliberately deferred

Arrows, `Category`, and inventing a new `Future`/`Task`/`Result` value type are set aside for now,
not rejected: `Either`/`A throws E` (pure failure) and `! Async` (the effect ScalaScript already
uses for asynchrony) already cover what a `Future`/`Task` type would be reached for, so a new value
type would duplicate rather than add; neither `Arrow` nor `Profunctor`-as-`Category` has a second
concrete need in this document beyond §11.3's `dimap`, which needs no category structure to be
useful. §12.4's `given`-composition fix has landed (2026-08-29); revisit `Category` once a second
genuine use surfaces.

## 12. Type-system constraints this design works within

Two real constraints, found by running code against this checkout rather than assumed from the
language grammar, shape every code sample above:

### 12.1 No parametric `given` derivation via `using`

```scalascript
given pairMonoid[A, B](using ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)] with
  def empty: (A, B) = (ma.empty, mb.empty)
  def combine(x: (A, B), y: (A, B)): (A, B) = (ma.combine(x._1, y._1), mb.combine(x._2, y._2))
```

fails to typecheck (`cannot unify tuple: (Dyn, Dyn) vs String` on a call site expecting a
`Monoid[(Int, String)]`) — `std/foldable-traversable.ssc`'s own note that "ScalaScript does not
auto-resolve `using` clauses" turns out to mean nested/derived `given` resolution doesn't chain
reliably, not just that a `using` parameter can't be *omitted* at a call site. This is why every
composition combinator in §4 (`zipMonoid`, `ZipAgg`, `MapAgg`) is an **ordinary function taking
explicit instances**, never a `given` that expects the compiler to assemble one from smaller
`given`s. This is not a workaround to route around a limitation reluctantly — it is a better fit
for the stated goal (§1: "guaranteed to work", explicitly preferred over cleverness): an explicit
combinator call always resolves to exactly the instance the two arguments name, with no
implicit-search ambiguity to reason about at all, on any backend a future compiler targets.

### 12.2 Point-free method references on a generically-typed class instance don't eta-expand correctly

```scalascript
def combineAll[A](xs: List[A], m: Monoid[A]): A =
  xs.foldLeft(m.empty)(m.combine)          // BROKEN when m's runtime value is a plain `class`
```

throws `ConstMonoid.combine: expected 2 argument(s), got 0` at runtime when `m: Monoid[A]` is bound
to a value of a user-defined `class` (not a `given ... with` instance) — the interpreter appears to
invoke the method eagerly with zero arguments instead of eta-expanding it to a two-argument
function value, specifically when the receiver's static type is a generic type parameter. A `given`
instance receiver does not trigger it; neither does replacing the point-free reference with an
explicit lambda:

```scalascript
xs.foldLeft(m.empty)((a, b) => m.combine(a, b))    // works with EITHER kind of instance
```

Every combinator in this document (§3's `runAggregator`, §4's `ZipAgg`/`MapAgg`, §9's bridge
helpers) uses the explicit-lambda form throughout, specifically because `Aggregator`/`Monoid`
instances in this design are ordinary `class` values, not exclusively `given` ones (§12.1 already
rules out `given`-based derivation for the composed cases).

**FIXED 2026-08-29, independently on all three lanes** — `v2/BUGS.md`
`point-free-class-method-never-eta-expands-on-native` (`ccd973ba9`),
`v1/runtime/backend/interpreter/BUGS.md` `point-free-class-method-never-eta-expands-on-int`
(`a948d71b8`), and `v3/BUGS.md` `point-free-class-method-reference-never-eta-expands`
(`2cc79a78e` — v3 had never implemented this at all, on either of its own lanes, rather than
regressing an existing one). `xs.foldLeft(m.empty)(m.combine)` now works unmodified on every
lane. This document was written and verified *before* the fix landed and keeps the
explicit-lambda form throughout regardless — not as a workaround left in place out of inertia,
but because §12.1 already commits this design to explicit combinators over implicit `given`
assembly for the same reason (no implicit-resolution ambiguity to reason about, on any backend).
A future revision may adopt the point-free form now that it is safe on every lane; it would be a
style change, not a correctness fix.

### 12.3 Two further front limitations found while writing §6's sketches

Neither blocks anything in this document — both have a straightforward workaround, already used
throughout §6 — but both are real, reproduced directly against `bin/ssc-tools`, and worth naming
so a future implementer does not lose time rediscovering them.

**A `given ... with` method cannot close over a top-level `val`:**

```scalascript
val precision = 4
given hllMonoid: Monoid[HLLAcc] with
  def empty: HLLAcc = HLLAcc(Array.fill(1 << precision)(0))   // "expected Int, got ()"
```

fails at runtime; the identical body inside an ordinary `class HLLMonoid(precision: Int) extends
Monoid[HLLAcc]` (§6.1) works. Every sketch in §6 is a parameterized `class`, never a `given`, for
exactly this reason — which is also, independently, the more honest design: a sketch's tuning
parameter (precision, depth×width, centroid budget) is a property of *that instance*, not a
program-wide constant, so it belongs on the constructor either way.

**A case-class field cannot be indexed directly — `recv.field(i)` reads as a method call, not an
array index:**

```scalascript
case class Box(arr: Array[Int])
val b = Box(Array(1, 2, 3))
println(b.arr(0))   // `Box.arr` was called but does not exist, and the result reached output.
```

throws; extracting the field to a local first works unconditionally:

```scalascript
val a = b.arr
println(a(0))        // 1
```

Every sketch in §6 threads each array field through a local `val` (`ar`, `br`, `counts`) before
indexing it for this reason.

### 12.4 The `given`-derivation gap, scoped precisely — the next thing this design needs fixed

§12.1's `pairMonoid[A, B](using ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)]` failure is not a
tuple-specific quirk. The SAME shape, minimized to one type parameter and one `using` instance
instead of two:

```scalascript
case class Box[A](value: A)

given boxMonoid[A](using ma: Monoid[A]): Monoid[Box[A]] with
  def empty: Box[A] = Box(ma.empty)
  def combine(x: Box[A], y: Box[A]): Box[A] = Box(ma.combine(x.value, y.value))

val m = summon[Monoid[Box[Int]]]   // "unbound global: __summon_value_Monoid" — never resolved
```

fails too — with a DIFFERENT symptom (`unbound global: __summon_value_Monoid`, a name-resolution
failure, vs. §12.1's `TYPEERR: cannot unify tuple`, a type-checking failure) for what is the same
underlying shape:
**a `given` that is itself generic and takes another `given` as a `using` parameter to build its
result.** §11.3's type-lambda `Profunctor` instance is a third data point in the same family — a
`given` built over a partially-applied type constructor rather than a plain nominal type.

This is the concrete, scoped repro the next phase of this work fixes: not "given resolution is
broken" (ordinary, non-parametric `given`s — `intSum`, `optionMonad`, every instance §2–§11 already
uses — work correctly, including EXTENSION-method dispatch resolved by receiver type, which is
the mechanism §11 relies on throughout), but specifically **a `given` whose own declaration takes
one or more `using`-bound instances to construct its result type**, regardless of whether that
result type is a tuple, a single-parameter wrapper, or a type lambda. Two different failure
symptoms across the three data points suggest more than one thing goes wrong along the way, not
one — worth keeping in view rather than assuming a single-line fix once the real diagnosis starts.

**FIXED 2026-08-29**, on the reference front (`ssc1-front.ssc0` + `ssc1-lower.ssc0`) — `v2/BUGS.md`
`parametric-given-declaration-corrupts-an-unrelated-earlier-given`. Root cause was narrower than
"given resolution is broken": `ssc1-front.ssc0`'s `given` parser had never been taught to
recognize a type-param list or a `(using ...)` clause between a given's name and its `:`, so it
erased the WHOLE declaration and — critically — the erasure's own "skip to the next statement"
did not span the given's multi-line body, letting that body's tokens leak into surrounding code
and corrupt an unrelated EARLIER given (which is why §12.1's own symptom blamed `intM`, nowhere
near the mistake). Fixed with real derivation, not a refusal: `ssc1-lower.ssc0` unifies a derived
given's declared type against the request, recursively resolves each `using` requirement the same
way, and builds the instance directly as CoreIR. Verified: the tuple case answers `(3, ab)`; the
single-param wrapper case answers `7`; a TWO-LEVEL nested case (a tuple instance wrapped in
another parametric given) answers `(3, ab)` too.

**FIXED 2026-08-29, in F too.** F (`specs/v2.2-p6.5-fsub.ssc`) is a SEPARATE self-hosted compiler,
not a type-checking pass over the reference front's output; it initially fell back to its own
designed fallback target (`bin/ssc1-run.ssc0`) for this syntax, an honest gap, not a regression, but
now parses and resolves it directly — `ssc info --front-report` on every repro above now names `F`,
not a fallback. The port mirrors `ssc1-lower.ssc0`'s algorithm exactly (string-structure unification
of a declared pattern against a concrete request, recursive `using` resolution, an `(app (lam n
dict) args...)` closure in place of `IrApp(IrLam(n,...), args)` — F emits pre-rendered IR strings
directly, so the resolved instance is one too) in F's own point-free, no-`let` style. Two defects
surfaced and were fixed along the way, both silent: a paren-count error in the new `cx`-tuple slot
(`polyGivenTabOf`) that broke even ORDINARY, non-parametric givens — found by bisecting a "some
givens now fail" symptom down to the simplest possible case, since F's own self-hosting means a
single stray parenthesis anywhere corrupts everything parsed after it, not just the new code path —
and `parseUsingParam` checking for the numeric token code F uses for the `def` keyword instead of
"any lowercase identifier", which silently failed to consume a `using` clause and produced a
misaligned re-parse (`unbound global: ma`, not a parse error) rather than a clean refusal.

The type-lambda `Profunctor` case in §11.3 is a DIFFERENT mechanism (extension-method dispatch by
receiver type, not `summon[TC[X]]`) and was **also fixed, in the same commit** — see
`v2/BUGS.md`'s `given-extension-typehead-mismatch-silently-returns-receiver`: the dispatcher used
to derive its tag-test type from the enclosing `given`'s own type argument, which is wrong whenever
that argument doesn't match the extension's own declared receiver type (a type lambda, a
no-type-parameter trait, or simply a mismatched type argument all trigger it). Fixed on BOTH
self-hosted compilers that can run ordinary (non-`given_poly`) extension dispatch — the reference
front and F independently, since they are separate implementations of the same mechanism.

## 13. Explicitly out of scope for this document

Tracked as separate future specs, each of which consumes `Aggregator` as its computational core
rather than redefining it:

- **Arbitrary data sources.** `DStream`'s `DSource`/`Source[A]` already cover push/pull streaming
  connectors; a uniform "any format, any transport" registry (databases, files, message queues,
  APIs) is real scope but orthogonal to the aggregation algebra itself.
- **Compilation to Python and R.** No backend exists today for either target (verified: no
  `PythonGen`/`RGen` anywhere in `v1/`, `v2/`, unlike the real `SparkGen`/`FlinkGen`/
  `KafkaStreamsGen`/`BeamGen` that already exist for `DStream`). Once one does, it consumes
  `Aggregator` values the same way `aggregatorSeqOp`/`aggregatorCombOp` (§9) let `DStream` consume
  them today — a `Monoid[Acc]` translates to a target-language reduce/fold; nothing about §2–§8
  is backend-specific by construction.
- **A chart renderer**, and any polish beyond §10's `headers`/`rows` bridge (styling, a shared
  table/chart abstraction spanning both, a canonical `Renderer[A]` typeclass). §10 established
  that the *shape* an `Aggregator`'s output needs to become is already consumable by existing
  code (`std/ui/data.ssc`'s live table, `examples/std-ui/table.ssc`'s static one, `jsonStringify`
  for JSON) and that `std/ui/content.ssc`'s `slots` registry is where a future chart component
  would plug in — building that component is real, separate work.
- **Real-time streaming dashboards** are the composition of all of the above (an unbounded
  `Source`, a `Group`-backed sliding-window `Aggregator` per §7, and a live chart renderer) — not
  a fifth primitive of their own.
- **`Arrow`/`Category`, and a new `Future`/`Task`/`Result` value type** — §11.4 explains why:
  `Either`/`! Async` already cover what the latter would be reached for, and neither of the former
  has a second concrete use here beyond §11.3's `dimap`, which doesn't need one to be useful.

## 14. Production readiness — measured status (2026-08-30)

What "ready for production use" concretely means for this module, and what is MEASURED versus
still designed-but-unintegrated:

**Covered, gated, measured:**

- **Algebraic laws** (the correctness production scaling depends on) are now a conformance case:
  `tests/conformance/std-aggregator-properties.ssc` verifies partition-invariance of the §9
  bridge (fold any partitioning — including empty partitions and a 3-way split — merge with
  `aggregatorCombOp`, equal the whole-list fold), `combine` associativity/commutativity/identity
  for the shipped monoids (`intSum`, `doubleSum`, `MinMonoid`/`MaxMonoid`, the variance monoid,
  CMS merge, the `Group` inverse laws), `SlidingWindow` retraction against recompute-from-scratch
  at every push, and empty/single-element edge cases for every canonical aggregator. Green on
  `int` and `js`; `jvm` is declared known-red against the module-level transpile blockers
  (§6's `fmix64` Long constants under the `int-width` non-conformance, and §11's
  `runEffAggregator` — see the case's own `known-red` text). `HLLMonoid`'s union law stays
  covered by `std-aggregator-approx.ssc` rather than repeated here.
- **A perf baseline shape** exists: `bench/corpus/aggregator-fold.ssc` (the `ZipAgg`-shaped
  tuple-accumulator fold plus a `groupByAgg`-shaped keyed `Map` fold, 1000 elements each) runs
  under `scripts/runtime-bench.sh` alongside the rest of the corpus. Numbers were deliberately
  NOT recorded at landing time — the host was contended (parallel agent builds), and a number
  taken under load is a hypothesis, not a result; run
  `scripts/runtime-bench.sh aggregator-fold` on a quiet host to record the baseline.
- Writing the laws case **found a real `int`-lane defect** the existing cases could not see
  (`v1/runtime/backend/interpreter/BUGS.md`
  `imported-generic-fold-with-a-tuple-accumulator-presents-one-component` — a cross-module
  generic fold+present over a TUPLE accumulator returns one component; `MapAgg`-wrapped uses
  masked it everywhere else). The case works around it with an inline whole-list fold and cites
  the slug at the workaround site.

**Not covered — real, separate work, stated plainly:**

- **Live distributed execution**: the §9 bridge is verified as an algebraic property
  (partition-fold-then-merge ≡ whole-fold), NOT wired into a running `DStream`/`Pipeline`
  backend (`specs/distributed-streams.md`). Scaling claims beyond the law itself — backpressure,
  partition rebalancing, worker failure mid-fold — belong to that spec's runtime and have no
  coverage here.
- **Durability/fault-tolerance of accumulator STATE** (checkpointing a partial `Acc`, replay
  after a crash): nothing in this module persists anything; `specs/durable-execution.md`'s
  machinery is where that would come from, and no integration exists.
- **Per-lane parity**: the `js`/`jvm` backend defects tracked in the conformance cases'
  `known-red` declarations gate how much of the module each compiled lane can run today; the
  laws case above is the honest per-lane scoreboard.
