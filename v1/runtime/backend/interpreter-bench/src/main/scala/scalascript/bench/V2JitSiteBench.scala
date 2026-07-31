package scalascript.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import ssc.{Compiler, Const, Def, Env, Program, Runtime, Step, Term, Value}
import ssc.Value.{ClosV, IntV}

/** What the v2 wide JIT's tier-0 counter costs, measured BEFORE the kernel is changed.
 *
 *  WHY THIS EXISTS. `specs/v2-wide-jit.md` J-1 wraps every `Lam` body and every `While` body in a
 *  `JitSite` — the counting node that decides when a site is hot. That wrapper is on the entry path
 *  of every call in every program, including programs that never JIT, so it is the one change in the
 *  whole programme that can make things slower for everybody. It is also, by construction, a
 *  sub-nanosecond effect: the whole-workload harness on this host swings 2.5× on identical code and
 *  cannot see it at all. Hence JMH, and hence this file landing BEFORE the kernel edit rather than
 *  after — if the shape is too expensive, J-1 should be designed differently, and that is cheaper to
 *  learn now.
 *
 *  WHAT IT MEASURES — the real v2 VM, not a model of it. Each benchmark compiles an actual Core IR
 *  program through `ssc.Compiler` and calls the resulting `ClosV` through `ssc.Runtime.run`, exactly
 *  as the VM lane does. The only thing that varies is what sits between the call and the body:
 *
 *    vmCallDirect        today's path: `ClosV.code` IS the compiled body.
 *    vmCallCounting      J-1's tier-0 state: `code` is a `JitSite` whose `fast` field is still null,
 *                        so every entry reads the field, fails the null test, bumps a counter and
 *                        forwards. This is the number that decides whether J-1 is free.
 *    vmCallInstalled     J-1's steady state after a unit is installed: `fast` is set, so the entry
 *                        reads the field and calls through it. What a JIT'd site pays FOREVER, on
 *                        top of the compiled code itself.
 *    vmCallPlainField    the same as vmCallCounting with a NON-volatile field, which prices the
 *                        `@volatile` read on its own. If the two are indistinguishable, publication
 *                        safety is free and the kernel should keep `@volatile`; if it is not, that
 *                        is a real fork in the design, not a micro-optimisation.
 *
 *  READ IT AS DIFFERENCES. `vmCallCounting − vmCallDirect` is J-1's price. `vmCallInstalled −
 *  vmCallDirect` is the permanent dispatch tax on a compiled site. `vmCallCounting −
 *  vmCallPlainField` is what memory publication costs.
 *
 *  WHAT IT DOES NOT MEASURE, stated so nobody over-reads it: a microbenchmark calls ONE site, so the
 *  JVM sees a monomorphic call and inlines it. A real program has many sites and a
 *  polymorphic/megamorphic `Code.apply`, where an extra indirection costs more than it does here. So
 *  a green result here is necessary, not sufficient — J-1's own gate re-measures with the kernel
 *  actually wired, on the four-row corpus. Treat these numbers as a floor on the cost, never a
 *  ceiling.
 *
 *  Run:
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 .*V2JitSiteBench.*"
 *  Save JSON (for bench/history.tsv):
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 -rff /tmp/v2-jitsite.json -rf json .*V2JitSiteBench.*"
 */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class V2JitSiteBench:

  /** The J-1 node, written exactly as `specs/v2-wide-jit.md` §3.3 specifies it, so the number
   *  measured here is the number the kernel will pay. `Code = Env => Step` is a SAM, which is what
   *  lets a class BE the compiled body and makes the trampoline, `ClosV` and every call site
   *  oblivious to the wrapper's existence. */
  final class JitSite(slow: Env => Step) extends (Env => Step):
    @volatile var fast: (Env => Step) | Null = null
    var hits: Int = 0
    def apply(env: Env): Step =
      val f = fast
      // `.asInstanceOf` rather than flow typing, mirroring the kernel's own `| Null` call sites
      // (`Runtime.dispatchHandler1`): this module is not compiled with -Yexplicit-nulls, so the
      // union does not narrow on the null test.
      if f != null then f.asInstanceOf[Env => Step](env)
      else
        hits += 1          // benign race by design: a lost increment delays tier-up by one call
        slow(env)

  /** The same shape with a plain field, to price `@volatile` alone. Never installed in the kernel —
   *  this exists only so the volatile read is a measured decision rather than an assumed cost. */
  final class PlainSite(slow: Env => Step) extends (Env => Step):
    var fast: (Env => Step) | Null = null
    var hits: Int = 0
    def apply(env: Env): Step =
      val f = fast
      if f != null then f.asInstanceOf[Env => Step](env)
      else
        hits += 1
        slow(env)

  private var direct:    ClosV = null
  private var counting:  ClosV = null
  private var installed: ClosV = null
  private var plain:     ClosV = null
  private var arg:       Value = null

  /** `def f(n) = n + 1` in Core IR, compiled by the real `ssc.Compiler`.
   *
   *  Deliberately the smallest body that still allocates a result: a bigger body would bury the
   *  wrapper under its own cost and report a reassuring number that means nothing. The wrapper's
   *  price is worst — and therefore honest — against the cheapest possible callee. */
  @Setup(Level.Trial)
  def setup(): Unit =
    val body = Term.Prim("__arith__", List(
      Term.Lit(Const.CStr("+")), Term.Local(0), Term.Lit(Const.CInt(1L))))
    val program = Program(List(Def("f", Term.Lam(1, body))), Term.Lit(Const.CUnit))
    val globals = Compiler.compileWithGlobals(program)._2
    val compiled = globals("f").asInstanceOf[ClosV]

    // Outside Value.IntV's -128..4096 intern table: an interned argument measures a program in
    // which boxing is free, which is not the program anyone runs (V2DispatchBench's `cached`
    // parameter is the same point).
    arg       = IntV(900_001L)
    direct    = ClosV(compiled.env, 1, compiled.code)
    counting  = ClosV(compiled.env, 1, new JitSite(compiled.code))
    plain     = ClosV(compiled.env, 1, new PlainSite(compiled.code))
    val site  = new JitSite(compiled.code)
    site.fast = compiled.code
    installed = ClosV(compiled.env, 1, site)

  private def call(c: ClosV): Value = Runtime.run(c.code, Array(arg))

  /** Today's VM call path — the baseline every other case is read against. */
  @Benchmark
  def vmCallDirect: Value = call(direct)

  /** J-1's tier-0 state: field read + null test + counter bump on every entry. */
  @Benchmark
  def vmCallCounting: Value = call(counting)

  /** J-1's steady state once a unit is installed: field read + call through it. */
  @Benchmark
  def vmCallInstalled: Value = call(installed)

  /** The counting state with a non-volatile field — the price of publication safety, alone. */
  @Benchmark
  def vmCallPlainField: Value = call(plain)

  /** A loop, so the seam is measured the way a compiled `while` drives it rather than as one
   *  isolated call. Mirrors `V2DispatchBench.arithFastLoop`, and exists for the same reason: an
   *  inlining cliff shows up here and nowhere else. */
  @Benchmark
  @OperationsPerInvocation(100)
  def vmCallDirectLoop(bh: Blackhole): Unit =
    var i = 0
    while i < 100 do
      bh.consume(call(direct))
      i += 1

  @Benchmark
  @OperationsPerInvocation(100)
  def vmCallCountingLoop(bh: Blackhole): Unit =
    var i = 0
    while i < 100 do
      bh.consume(call(counting))
      i += 1
