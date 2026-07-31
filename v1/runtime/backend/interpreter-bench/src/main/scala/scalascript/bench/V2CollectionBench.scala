package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import ssc.{Done, Runtime, Value}
import ssc.Value.{ClosV, IntV}

/** Where `lazylist-take`'s 631× actually goes — decomposed, not profiled.
 *
 *  WHY. C-0 measured the row honestly (`bench/history.tsv`, sha 315dbca16): `lazylist-take` is
 *  **631× the v1 interpreter on the bytecode lane**, and — the part that redirected this work —
 *  compiling to bytecode removes almost NOTHING from it (93.4→75.0, 29.2→29.6, 41.6→38.5, inside
 *  that row's own spread) while it removes 6.3× from `list-fold`. So the cost is not in the code
 *  the bytecode lane compiles. It is inside the collection machinery the VM calls out to.
 *
 *  C-0's second finding aims this: v2's numbers for that row swing 3.2× across rounds while the
 *  reference lane holds ±8%, and the swing runs OPPOSITE to host load — worst at load 9.35, best at
 *  15.76. Contention does not do that; heap state and GC timing do. Hence `-prof gc` below is not a
 *  nice-to-have, it is the measurement.
 *
 *  WHAT v2 ACTUALLY DOES (`Runtime.scala` ~2456): `LazyList` is **Scala's own**
 *  `scala.collection.immutable.LazyList`, wrapped in `ForeignV`. `LazyList.from(n)` builds an
 *  infinite lazy list and immediately `.map`s every element into a boxed `IntV`; a user `.map` then
 *  runs the mapping function as a v2 CLOSURE through `callClos` — one full trampoline entry per
 *  element. Three separable costs on one line of user code.
 *
 *  THE LAYERS mirror the corpus body `LazyList.from(start).map(_*2).take(8).sum` exactly, so each
 *  difference names one of those three:
 *
 *    floorLoop        a plain `while` over 8 Longs. No collection, no Value, no closure.
 *    scalaLazyList    the same pipeline as pure Scala over `Int`. Adds Scala's LazyList machinery:
 *                     cons thunks, memoisation, deferred forcing.
 *    boxedLazyList    same, elements are `Value.IntV`. Adds v2's boxing.
 *    vmClosureLazyList same, and the map function is a real `ClosV` invoked the way `Runtime`
 *                     invokes it. Adds the VM closure call per element. THIS is what v2 runs.
 *
 *  Read as differences: scalaLazyList − floorLoop = the LazyList machinery, which is what v1
 *  eliminated by fusing the bounded prefix (`specs/jit-collection-ops.md`, 190 → 0.058 ms);
 *  boxedLazyList − scalaLazyList = boxing; vmClosureLazyList − boxedLazyList = the closure call.
 *  Whichever difference dominates is the one worth attacking, and the other two are then declared
 *  NOT the problem — which is the part a profile share cannot give you.
 *
 *  `strictFoldStep` covers `list-fold` for contrast. C-0 showed that row is a different animal
 *  (bytecode wins 6.3× there), so it is here only to keep the comparison in one place.
 *
 *  DISCIPLINE (`specs/v2-vs-v1-backend-matrix.md`): three times on this codebase a frame holding a
 *  large share of profile samples did not hold a large share of the time — `dataFields` 28%→20%,
 *  kind-dispatch 25%→nothing, `Value[]` 43%→nothing. *"Treat a hot frame as a place to look, never
 *  as a size of prize."* So nothing here reports a percentage of samples. Each layer is a wall-clock
 *  difference against the layer below, and `gc.alloc.rate.norm` is bytes per operation — both are
 *  sizes of prize, not places to look.
 *
 *  Run:
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 .*V2CollectionBench.*"
 *  The allocation question C-0 raised — answer it with:
 *    sbt "interpreterBench/Jmh/run -prof gc -wi 5 -i 10 -f 1 .*V2CollectionBench.*"
 */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class V2CollectionBench:

  /** `take(8)` — the corpus body's prefix length. Not a knob: the whole reason v1's fusion worked is
   *  that the prefix is BOUNDED, so the length is part of the shape being measured. */
  private final val N = 8

  /** Varied per invocation so nothing folds to a constant and the pipeline is genuinely rebuilt —
   *  the corpus row builds a fresh pipeline per iteration, and a benchmark that hoists it would be
   *  measuring a program nobody runs. */
  private var start: Int = 0

  /** A real v2 closure `x => x * 2`, invoked exactly as `Runtime` invokes one. */
  private var doubler: ClosV = null

  @Setup(Level.Trial)
  def setup(): Unit =
    start = 1
    doubler = ClosV(
      Array.empty[Value],
      1,
      (env: Array[Value]) => Done(IntV(env(env.length - 1).asInstanceOf[IntV].n * 2)))

  /** `callClos` is private in Runtime; this is its body verbatim (Runtime.scala:3412), so the
   *  benchmark cannot drift from the thing it claims to measure without this line changing. */
  private def callClos(fn: ClosV, arg: Value): Value =
    Runtime.run(fn.code, Runtime.extend(fn.env, Array(arg)))

  @Benchmark
  def floorLoop: Long =
    var i = 0; var acc = 0L
    val s = start
    while i < N do { acc += (s + i) * 2; i += 1 }
    acc

  @Benchmark
  def scalaLazyList: Int =
    LazyList.from(start).map(_ * 2).take(N).sum

  @Benchmark
  def boxedLazyList: Long =
    LazyList
      .from(start)
      .map(i => IntV(i.toLong * 2): Value)
      .take(N)
      .foldLeft(0L)((a, v) => a + v.asInstanceOf[IntV].n)

  /** What v2 actually executes for `LazyList.from(s).map(_*2).take(8).sum`. */
  @Benchmark
  def vmClosureLazyList: Long =
    LazyList
      .from(start)
      .map(i => IntV(i.toLong): Value)
      .map(v => callClos(doubler, v))
      .take(N)
      .foldLeft(0L)((a, v) => a + v.asInstanceOf[IntV].n)

  // ── C-1 headroom probe: is the 64% actually REMOVABLE? ────────────────────────────────────────
  //
  // C-2 attributed the cost. Attribution is not a promise that the cost can be removed, and the
  // shared kernel is the wrong place to discover the difference. These two layers bracket the win a
  // runtime-level fusion could earn, WITHOUT touching `Runtime.scala`:
  //
  //   fusedIterator  the same pipeline on `Iterator`, which does not memoise and allocates one
  //                  chain instead of three. Realistic upper-middle: a conservative fusion that
  //                  still goes through a lazy abstraction.
  //   fusedManual    a plain `while` over the bounded prefix, applying the VM closure per element.
  //                  The FLOOR of any fusion: no lazy structure at all, closure call still paid.
  //
  // `vmClosureLazyList − fusedManual` is the prize. `fusedManual − floorLoop` is what remains
  // afterwards and is the P-5/closure-call lever, not this one. If these come out near
  // `vmClosureLazyList`, the 64% is inherent to doing the work at all and C-1 should be declined —
  // which is a legitimate outcome and cheaper to learn here than in the kernel.
  //
  // v2 currently builds THREE chains for `from(s).map(f).take(n)`: `from`'s Int LazyList, the
  // `IntV`-boxing map inside the `from` arm, and the user's map (`Runtime.scala` ~2456-2462).

  @Benchmark
  def fusedIterator: Long =
    Iterator
      .from(start)
      .map(i => callClos(doubler, IntV(i.toLong)))
      .take(N)
      .foldLeft(0L)((a, v) => a + v.asInstanceOf[IntV].n)

  @Benchmark
  def fusedManual: Long =
    var i = 0; var acc = 0L
    val s = start
    while i < N do
      acc += callClos(doubler, IntV((s + i).toLong)).asInstanceOf[IntV].n
      i += 1
    acc

  /** The SEMANTICS-PRESERVING option, and the one that decides the design.
   *
   *  `fusedIterator`/`fusedManual` get their win partly by dropping MEMOISATION — `Iterator` does
   *  not memoise, `LazyList` does. JS and Rust already accept that trade (`specs/lazylist-all-
   *  backends.md`: iterator adapters, with "a LazyList stored in a `val` and reused across
   *  statements" deferred and documented). The v2 VM is different: it is the reference semantics
   *  the other lanes are compared against, so a side-effecting `map` firing twice would be a
   *  user-visible divergence, not an optimisation.
   *
   *  This layer keeps memoisation and removes only the REDUNDANT CHAINS. v2 builds three
   *  (`Runtime.scala` ~2456: `from`'s Int LazyList, the `IntV` boxing map, the user's map); this
   *  builds ONE whose elements are already the composed result. Same laziness, same memoisation,
   *  same number of user-function calls, same order — one cons cell per element instead of three.
   *
   *  If this lands close to `fusedManual`, the design is settled and cheap: compose pending maps
   *  into a single memoising chain, change nothing about semantics. If it lands close to
   *  `vmClosureLazyList`, then memoisation itself is the cost and C-1 becomes a product decision
   *  rather than a perf fix. */
  @Benchmark
  def fusedOneMemoisingChain: Long =
    def gen(i: Int): LazyList[Value] = callClos(doubler, IntV(i.toLong)) #:: gen(i + 1)
    gen(start).take(N).foldLeft(0L)((a, v) => a + v.asInstanceOf[IntV].n)

  /** `list-fold`'s shape: a strict fold, no thunks, no memoisation. Present for contrast — C-0
   *  established this row behaves differently (the bytecode lane wins 6.3× on it). */
  @Benchmark
  def strictFoldStep: Long =
    var acc: Value = IntV(0L)
    var xs = List(IntV(1L), IntV(2L), IntV(3L), IntV(4L), IntV(5L), IntV(6L), IntV(7L), IntV(8L))
    while xs.nonEmpty do
      acc = IntV(acc.asInstanceOf[IntV].n + callClos(doubler, xs.head).asInstanceOf[IntV].n)
      xs = xs.tail
    acc.asInstanceOf[IntV].n
