# Aggregation Algebra — `Monoid`, `Group`, and `Aggregator[In, Acc, Out]`

Status: **design / planning**. No implementation yet; every code sample in this document has been
run for real against this checkout's `bin/ssc-tools` to confirm it actually compiles and produces
the stated result — see §11 for what that verification found (including one interpreter bug it
surfaced).

Companion documents:
- [`std/semigroup-monoid.ssc`](../std/semigroup-monoid.ssc) — `Semigroup`/`Monoid` already ship
  here with canonical instances (`Int` sum, `String` concat, `List` concat). This document adds
  `Group` and builds `Aggregator` on top; it does not redefine `Semigroup`/`Monoid`.
- [`std/foldable-traversable.ssc`](../std/foldable-traversable.ssc) — documents the same
  typeclass-resolution limits this spec designs around (§11.1).
- [`specs/distributed-streams.md`](distributed-streams.md) — `DStream[T]`/`Pipeline`, with real
  backends (native actor cluster, Spark, Kafka Streams, Flink, Beam) already implemented. Its
  `Status:` line ("design sign-off required before implementation starts") is stale — the backend
  codegen is landed (`v1/runtime/backend/{spark,flink,kafka-streams}/`,
  `v1/runtime/plugins/dstreams-plugin/`). §9 of this document is the bridge: `Aggregator` values
  become the `zero`/`seqOp`/`combOp` triple `aggregatePerKey`/`runFold` already accept.
- [`specs/mapreduce.md`](mapreduce.md) — `Dataset[T]` batch map-reduce; the same bridge applies to
  its `fold`/`reduce` operators.

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

| Aggregator | `Acc` | Commutative | Group? | Notes |
|---|---|---|---|---|
| `sum` | the numeric type | yes | yes | `Group` — subtraction inverts it |
| `count` | `Int` | yes | yes | a `Monoid[Unit]`-shaped `prepare` composed with `intSum` |
| `mean` | `(sum, count)` | yes | no | `Group` on the pair would need division to be safe at `count = 0`; present-time only |
| `min` / `max` | the type, with a `Top`/`Bottom` sentinel for `empty` | yes | **no** | no inverse: cannot "un-see" the max once other values remain |
| `variance` / `stddev` | `(n, mean, M2)` triple (Welford/Chan) | yes | no | see §5.1 for the merge formula — this is the one exact statistic that is NOT simply "zip two simpler monoids" |
| `first` / `last` | the type, paired with a sequence number | **no** (`first`/`last` need arrival order or an explicit timestamp) | no | only meaningful with an ordering key; do not merge unordered partitions naively |

### 5.1 Variance as a monoid (Chan et al.'s parallel merge)

The naive two-pass variance formula (`mean((x - mean)^2)`) is not incrementally mergeable — it
needs the mean before it can start. The one-pass, mergeable form carries a triple `(n, mean, M2)`
where `M2` is the running sum of squared deviations from the running mean (Welford's algorithm for
`combine` with a single new value, generalized by Chan, Golub & LeVeque (1979) to merge two
**already-aggregated** partitions):

```scalascript
class VarianceAcc(n: Int, mean: Double, m2: Double)

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
left to the caller, not baked into the monoid.

## 6. Approximate aggregators — sketches as monoids

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
each verified example threads a field through a local `val` before indexing it — see §11.3 for the
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
merge, accounts for the gap) and `quantile(0.99)` ≈ `195.5` (true p99 ≈ `198`).

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

The language's job is to make this a **type-level fact, not a runtime surprise**: a sliding-window
operator should only accept a `Group`-backed `Aggregator`; requesting one over a `min`/`max` or a
§6 sketch is a compile-time rejection with a clear reason ("no inverse — use a tumbling window, or
a bucketed approximation"), not a silently wrong number or an unbounded-memory fallback chosen for
you.

## 8. `groupBy` needs no new concept — it's `Map[K, Acc]`, pointwise

If `Acc` is a `Monoid`, then `Map[K, Acc]` is a `Monoid` too — combine two maps key-by-key, using
`Acc`'s `combine` on keys present in both, and passing through keys present in only one:

```scalascript
class MapMonoid[K, Acc](inner: Monoid[Acc]) extends Monoid[Map[K, Acc]]:
  def empty: Map[K, Acc] = Map.empty
  def combine(a: Map[K, Acc], b: Map[K, Acc]): Map[K, Acc] =
    b.foldLeft(a) { (acc, kv) =>
      val (k, v) = kv
      acc.get(k) match
        case Some(existing) => acc.updated(k, inner.combine(existing, v))
        case None           => acc.updated(k, v)
    }
```

"Group by key, aggregate per group" is this `Map`-monoid wrapping ANY `Aggregator`'s own monoid —
not a separate primitive the language needs to define. This is also exactly what `keyBy` +
`aggregatePerKey` already do on `DStream` (§9); this section just names the algebraic fact that
makes it sound.

## 9. Bridge to `DStream`/`Pipeline` and `Dataset`

`specs/distributed-streams.md` §5.2/§5.8 already defines the raw primitives an `Aggregator` slots
into directly, with real backends (native, Spark, Kafka Streams, Flink, Beam) already implemented:

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

This is a proof of the bridge's MECHANISM, not an end-to-end `DStream` integration test — it does
not exercise `aggregatePerKey` itself, windowing, or any backend.

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

§12 used to list rendering as entirely out of scope, on the grounds that "no chart/table/render
module exists" under `std/`. Checked directly rather than assumed: that is true for **charts**,
but not for **tables** — `std/ui/data.ssc` already ships a live, reactive table component
(`dataTable`/`staticDataTable`, with typed columns via `fcol`/`dcol`/`mcol`/`scol`/`lcol`), and
`examples/std-ui/table.ssc`'s `Table.render(headers, rows, rightAlign)` shows the exact shape a
*static* table renderer needs. Neither is `Aggregator`-aware today, and neither needs to be
rewritten to become useful to one — both already consume the shape an aggregation result already
has: rows of plain values.

### 10.1 The bridge is a shape, not a new primitive

An `Aggregator`'s `Out`, or a `groupBy` result's `Map[K, Out]` (§8), becomes renderable the moment
it is turned into `List[String]` headers and `List[List[String]]` rows — nothing about §2–§9 needs
to know this is coming. Verified standalone:

```scalascript
def renderTableHtml(headers: List[String], rows: List[List[String]]): String =
  val head = headers.map(h => "<th>" + h + "</th>").mkString
  val body = rows.map(row => "<tr>" + row.map(v => "<td>" + v + "</td>").mkString + "</tr>").mkString
  "<table><thead><tr>" + head + "</tr></thead><tbody>" + body + "</tbody></table>"

val counts = Map("apple" -> 3, "banana" -> 2, "cherry" -> 1)   // e.g. a groupBy(count) result
val rows   = counts.toList.map((k, v) => List(k, v.toString))
println(renderTableHtml(List("item", "count"), rows))
// => <table><thead><tr><th>item</th><th>count</th></tr></thead><tbody>
//      <tr><td>apple</td><td>3</td></tr><tr><td>banana</td><td>2</td></tr>
//      <tr><td>cherry</td><td>1</td></tr></tbody></table>
```

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
bridge does. Building that renderer is out of scope here (§12) — the point of this subsection is
that "any format" (§1) does not need a new mechanism invented for charts specifically once it
exists; it needs the same `headers`/`rows`-shaped bridge, or `slots`' existing escape hatch.

## 11. Type-system constraints this design works within

Two real constraints, found by running code against this checkout rather than assumed from the
language grammar, shape every code sample above:

### 11.1 No parametric `given` derivation via `using`

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

### 11.2 Point-free method references on a generically-typed class instance don't eta-expand correctly

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
instances in this design are ordinary `class` values, not exclusively `given` ones (§11.1 already
rules out `given`-based derivation for the composed cases).

**FIXED 2026-08-29, independently on all three lanes** — `v2/BUGS.md`
`point-free-class-method-never-eta-expands-on-native` (`ccd973ba9`),
`v1/runtime/backend/interpreter/BUGS.md` `point-free-class-method-never-eta-expands-on-int`
(`a948d71b8`), and `v3/BUGS.md` `point-free-class-method-reference-never-eta-expands`
(`2cc79a78e` — v3 had never implemented this at all, on either of its own lanes, rather than
regressing an existing one). `xs.foldLeft(m.empty)(m.combine)` now works unmodified on every
lane. This document was written and verified *before* the fix landed and keeps the
explicit-lambda form throughout regardless — not as a workaround left in place out of inertia,
but because §11.1 already commits this design to explicit combinators over implicit `given`
assembly for the same reason (no implicit-resolution ambiguity to reason about, on any backend).
A future revision may adopt the point-free form now that it is safe on every lane; it would be a
style change, not a correctness fix.

### 11.3 Two further front limitations found while writing §6's sketches

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

## 12. Explicitly out of scope for this document

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
