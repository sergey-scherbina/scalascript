package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import ssc.{Emit, Value}
import ssc.Value.{ClosV, IntV}

/** What a cross-unit call costs the v2 JIT, against the shape the AOT lane emits.
 *
 *  WHY THIS EXISTS. After the wide-JIT programme, steady-state measurement leaves exactly ONE row
 *  where the JIT is behind its own emitter's ahead-of-time result: `pattern-match-heavy`, 1.31×,
 *  disjoint. It is also the only one of the four with a hot cross-def call — `area(s)`, 500 000
 *  times. The JIT reaches a linked callee through `INVOKEINTERFACE LamFn.call` plus `Emit.unroll`;
 *  the AOT lane, which registers every def in `defMethods`, emits a direct call. So the hypothesis
 *  is that the residual lives in that seam.
 *
 *  A whole-workload bench cannot settle it — the difference is nanoseconds against a host that
 *  swings 2.3× on identical code — which is why this is JMH and why it prices the LADDER rather
 *  than one total:
 *
 *    directCall        a plain monomorphic call to the callee's method — the AOT shape.
 *    interfaceCall     through `Emit.LamFn`, which is what a linked JIT unit emits.
 *    interfaceUnrolled + `Emit.unroll`, which a linked call must do because the callee may hand
 *                      back a bounce. This is the JIT's actual per-call cost.
 *    genericGlobalApp  `Emit.global(name)` + `Emit.app` — the UNLINKED path, i.e. what every
 *                      cross-def call cost before linking landed. Present so the ladder shows what
 *                      linking already bought as well as what it still owes.
 *
 *  READ IT AS DIFFERENCES. `interfaceUnrolled − directCall` is what the JIT pays over AOT per
 *  cross-def call; `genericGlobalApp − interfaceUnrolled` is what linking already removed. If the
 *  first difference is small, the `pattern-match-heavy` residual is NOT this seam and the next probe
 *  belongs elsewhere — a negative result here is as useful as a positive one, and cheaper than
 *  rewriting the emitter on a hunch.
 *
 *  Run:
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 .*V2LinkBench.*"
 */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class V2LinkBench:

  /** Stands in for a compiled unit: the same `LamFn` shape `JvmByteGen.emitUnit` produces, doing
   *  the cheapest work that still returns a fresh `Value` — the callee's own body must not dominate
   *  the call it is there to measure. */
  final class Callee extends Emit.LamFn:
    def call(env: Array[Value]): Value = env(env.length - 1) match
      case IntV(n) => IntV(n + 1)
      case other   => other

  private val calleeImpl = new Callee
  private var callee: Emit.LamFn = null       // through the interface, as a linked unit sees it
  private var args: Array[Value] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    callee = calleeImpl
    // Outside IntV's -128..4096 intern table: an interned result measures a program where the
    // callee's own allocation is free, which is not the program anyone runs.
    args = Array[Value](IntV(900_001L))
    Emit.globalsRef = collection.mutable.HashMap[String, Value](
      "f" -> ClosV(Array.empty[Value], 1, env => ssc.Done(calleeImpl.call(env))))

  /** The AOT shape: a direct, monomorphic call. */
  @Benchmark
  def directCall: Value = calleeImpl.call(args)

  /** What a linked JIT unit emits, without the unroll. */
  @Benchmark
  def interfaceCall: Value = callee.call(args)

  /** What a linked JIT unit actually pays per cross-def call. */
  @Benchmark
  def interfaceUnrolled: Value = Emit.unroll(callee.call(args))

  /** The unlinked path — a globals lookup, a `ClosV` and `Emit.app` — i.e. what every cross-def
   *  call cost before cross-unit linking landed. */
  @Benchmark
  def genericGlobalApp: Value = Emit.app(Emit.global("f"), args)
