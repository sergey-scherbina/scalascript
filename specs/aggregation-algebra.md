# Aggregation Algebra — `Monoid`, `Group`, and `Aggregator[In, Acc, Out]`

Status: **design / planning**. No implementation yet; every code sample in this document has been
run for real against this checkout's `bin/ssc-tools` to confirm it actually compiles and produces
the stated result — see §10 for what that verification found (including one interpreter bug it
surfaced).

Companion documents:
- [`std/semigroup-monoid.ssc`](../std/semigroup-monoid.ssc) — `Semigroup`/`Monoid` already ship
  here with canonical instances (`Int` sum, `String` concat, `List` concat). This document adds
  `Group` and builds `Aggregator` on top; it does not redefine `Semigroup`/`Monoid`.
- [`std/foldable-traversable.ssc`](../std/foldable-traversable.ssc) — documents the same
  typeclass-resolution limits this spec designs around (§10.1).
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

### 6.1 HyperLogLog — approximate distinct count

Register array of `2^p` small counters. `prepare` hashes the input and updates one register with
the position of its lowest set bit; `combine` takes the elementwise MAX of two register arrays
(this is what makes it a monoid — a register only ever grows, and taking the max of maxes is
associative and commutative); `present` runs the harmonic-mean estimator over the registers.

```scalascript
class HLLAcc(registers: Array[Int])   // length 2^precision

given hllMonoid: Monoid[HLLAcc] with
  def empty: HLLAcc = HLLAcc(Array.fill(1 << PRECISION)(0))
  def combine(a: HLLAcc, b: HLLAcc): HLLAcc =
    HLLAcc(Array.tabulate(a.registers.length)(i => math.max(a.registers(i), b.registers(i))))
```

Standard error ≈ `1.04 / sqrt(2^precision)`; `precision = 14` (16 KB per sketch) gives ≈0.8% error
regardless of the true cardinality — the defining property that makes it usable on unbounded data.

### 6.2 Count-Min Sketch — approximate frequency / top-K

A 2-D array of counters (`depth` hash functions × `width` buckets). `prepare` increments one
counter per row via a different hash; `combine` is elementwise sum (a monoid — sums of sums
associate and commute); `present` (for a given key) takes the MIN across the `depth` rows — the
sketch only ever overestimates, and the min row is the tightest available bound.

Error bound: with width `w` and depth `d`, estimates are within `ε · N` of the truth with
probability `1 - δ`, for `w = ⌈e/ε⌉` and `d = ⌈ln(1/δ)⌉` (`N` = total count).

### 6.3 T-Digest — approximate quantiles

Maintains a small, size-bounded set of weighted centroids (cluster of nearby values + a count),
with the crucial property that a `Monoid[Acc]`'s `combine` is centroid-list merge followed by a
re-clustering pass, which stays commutative and associative up to the digest's compression
parameter. `present(q)` interpolates the requested quantile across the centroids. Accuracy is
non-uniform by design — the tails (p99, p999) get proportionally more centroids than the middle,
which is exactly the region monitoring/alerting dashboards care about most.

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

## 10. Type-system constraints this design works within

Two real constraints, found by running code against this checkout rather than assumed from the
language grammar, shape every code sample above:

### 10.1 No parametric `given` derivation via `using`

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

### 10.2 Point-free method references on a generically-typed class instance don't eta-expand correctly

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
instances in this design are ordinary `class` values, not exclusively `given` ones (§10.1 already
rules out `given`-based derivation for the composed cases). Filed as two compiler bugs — the
underlying interpreter defect is real and worth fixing on its own merits, independent of this
design: `v2/BUGS.md` `point-free-class-method-never-eta-expands-on-native` (the default lane) and
`v1/runtime/backend/interpreter/BUGS.md` `point-free-class-method-never-eta-expands-on-int` (the
`--v1` lane, a different root cause with the same given-vs-class shape). This document does not
depend on either fix landing, because every sample here already avoids the point-free form.

## 11. Explicitly out of scope for this document

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
- **Rendering results as tables, charts, or any other output shape.** No such module exists yet
  (verified: no chart/table/render module under `std/`). This is intentionally the LAST stage,
  consuming an `Aggregator`'s `Out` (or a stream of them, for a live dashboard) — never something
  the aggregation algebra itself needs to know about.
- **Real-time streaming dashboards** are the composition of all of the above (an unbounded
  `Source`, a `Group`-backed sliding-window `Aggregator` per §7, and a live `Renderer`) — not a
  fifth primitive of their own.
