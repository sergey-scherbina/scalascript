package scalascript.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import ssc.{Prims, Value}
import ssc.Value.{IntV, StrV}

/** What one v2 primitive dispatch costs, decomposed into the layers it is made of.
 *
 *  WHY THIS EXISTS. Every v2 perf result so far was measured by running a whole workload through
 *  `bin/ssc` and comparing wall-clock. That apparatus cannot see anything under about 2×: identical
 *  code measured 0.263 / 0.211 / 0.429 / 0.171 s on this host at load 5.5 — a 2.5× spread from
 *  contention alone. So a change that removes, say, 30% of dispatch cost is indistinguishable from
 *  noise, and the honest verdict for such work has been "unresolved" rather than "no effect". This
 *  benchmark measures the seam directly, in-process, with JMH doing the statistics.
 *
 *  WHAT IT MEASURES. Five layers, each one step further out than the last, so the DIFFERENCES name
 *  the cost of each seam rather than one opaque total:
 *
 *    jvmAdd          `x + y` on two Longs — the floor. Everything else is overhead over this.
 *    boxedAdd        unwrap two IntV, add, re-box. Isolates Value boxing from dispatch.
 *    arithFast       Prims.arithFast("+", l, r) — the compact typed path the bytecode lane emits.
 *    preResolvedFn   a Fn resolved ONCE, then called with List(StrV(op), l, r) per call. Adds the
 *                    List[Value] allocation and the closure call, without the lookup.
 *    resolvePerCall  Prims.resolve("__arith__")(...) per call — adds the string-keyed lookup.
 *
 *  READ IT AS DIFFERENCES: preResolvedFn − arithFast is what the `List[Value] => Value` calling
 *  convention costs; resolvePerCall − preResolvedFn is what the lookup costs. Either can be
 *  attacked independently, and this says which is worth attacking.
 *
 *  THE `cached` PARAMETER IS NOT A DETAIL. `Value.IntV` interns −128..4096, so a benchmark written
 *  only with small integers measures a program in which boxing is free — which is not the program
 *  anyone runs. Both settings are reported: `true` uses interned values, `false` allocates on every
 *  result. If the two differ sharply, allocation, not dispatch, is the thing to fix.
 *
 *  Run:
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 .*V2DispatchBench.*"
 *  Save JSON (for bench/history.tsv):
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 -rff /tmp/v2-dispatch.json -rf json .*V2DispatchBench.*"
 *
 *  A NOTE ON TRUSTING IT. JMH removes the host-noise problem, not every measurement problem: it
 *  still measures whatever the code under it actually does. Before believing a delta here, check
 *  that the layer you changed is the layer that moved — that is the point of having five of them.
 */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class V2DispatchBench:

  /** Interned range vs allocating range — see the class comment; this is a real fork in behaviour,
   *  not a sensitivity knob. */
  @Param(Array("true", "false"))
  var cached: Boolean = true

  private var x: Long        = 0L
  private var y: Long        = 0L
  private var lv: Value      = null
  private var rv: Value      = null
  private var opv: Value     = null
  private var arithFn: List[Value] => Value = null

  @Setup(Level.Trial)
  def setup(): Unit =
    // Inside Value.IntV's -128..4096 intern table, or well outside it.
    x = if cached then 3L else 900_001L
    y = if cached then 7L else 900_007L
    lv = IntV(x)
    rv = IntV(y)
    opv = StrV("+")
    arithFn = Prims.resolve("__arith__")

  /** The floor: no Value, no dispatch. */
  @Benchmark
  def jvmAdd: Long = x + y

  /** Boxing alone — unwrap, add, re-box — with dispatch removed. */
  @Benchmark
  def boxedAdd: Value = (lv, rv) match
    case (IntV(a), IntV(b)) => IntV(a + b)
    case _                  => sys.error("bench setup: not IntV")

  /** The compact typed arith path (`Prims.arithFast`), as the bytecode lane emits it. */
  @Benchmark
  def arithFast: Value = Prims.arithFast("+", lv, rv)

  /** Resolved once, called per invocation: adds List[Value] allocation + the closure call. */
  @Benchmark
  def preResolvedFn: Value = arithFn(List(opv, lv, rv))

  /** The full generic seam, lookup included, as an uncached call site pays it. */
  @Benchmark
  def resolvePerCall: Value = Prims.resolve("__arith__")(List(opv, lv, rv))

  /** A loop, so the JIT sees the seam the way a compiled `while` does rather than as a single
   *  isolated call. Reported per whole loop; divide by 100 for per-op, or compare against
   *  `arithFast` × 100. Exists because an inlining cliff shows up here and nowhere else — a method
   *  over 8000 bytecodes is never JIT-compiled at all, which is how `Prims.__method__` came to be
   *  10× slower than it looked. */
  @Benchmark
  @OperationsPerInvocation(100)
  def arithFastLoop(bh: Blackhole): Unit =
    var i = 0
    while i < 100 do
      bh.consume(Prims.arithFast("+", lv, rv))
      i += 1
