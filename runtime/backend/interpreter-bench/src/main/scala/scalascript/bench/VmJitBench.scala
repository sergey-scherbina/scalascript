package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.interpreter.{Interpreter, Value}
import scalascript.interpreter.vm.{SscVm, VmCompiler}
import scalascript.parser.Parser

/** Tree-walking interpreter vs the proof-of-concept bytecode VM
 *  (docs/vm-jit-spec.md) on the integer hot paths.
 *
 *  `treeWalk*` run the whole module through the interpreter (same as
 *  InterpreterBench). `vm*` compile the parsed closure once (mirroring what a
 *  run-time JIT would do after the hot threshold) and time only VM execution.
 *
 *    sbt "interpreterBench/Jmh/run -wi 5 -i 10 -f 1 .*VmJitBench.*"
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class VmJitBench:

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  private def module(code: String) =
    Parser.parse(s"# Bench\n\n```scala\n$code\n```\n")

  // Full modules (def + driving call) for the tree-walker.
  private val fibModule = module(
    """def fib(n: Int): Int =
      |  if n <= 1 then n else fib(n - 1) + fib(n - 2)
      |fib(30)""".stripMargin)

  private val tcoModule = module(
    """def sumTco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else sumTco(n - 1, acc + n)
      |sumTco(1000000, 0)""".stripMargin)

  // Double-domain hot loop (exercises FADD + the I2D-free double fast path).
  private val dsumModule = module(
    """def dsum(n: Int, acc: Double): Double =
      |  if n <= 0 then acc else dsum(n - 1, acc + 0.5)
      |dsum(1000000, 0.0)""".stripMargin)

  // Compile the closures once, the way a run-time JIT would after the function
  // crosses the hot-call threshold.
  private var fibFn: SscVm.CompiledFn = _
  private var tcoFn: SscVm.CompiledFn = _
  private var dsumFn: SscVm.CompiledFn = _
  private val zeroBits = java.lang.Double.doubleToRawLongBits(0.0)

  @Setup
  def setup(): Unit =
    def closure(name: String, defs: String): Value.FunV =
      val it = Interpreter(devNull)
      it.run(module(defs))
      it.globalsView(name).asInstanceOf[Value.FunV]

    fibFn = VmCompiler.compile(closure("fib",
      "def fib(n: Int): Int =\n  if n <= 1 then n else fib(n - 1) + fib(n - 2)")).get
    tcoFn = VmCompiler.compile(closure("sumTco",
      "def sumTco(n: Int, acc: Int): Int =\n  if n <= 0 then acc else sumTco(n - 1, acc + n)")).get
    dsumFn = VmCompiler.compile(closure("dsum",
      "def dsum(n: Int, acc: Double): Double =\n  if n <= 0 then acc else dsum(n - 1, acc + 0.5)")).get

    // Correctness guard: the VM must agree with the known results, else the
    // speed numbers below are meaningless.
    require(SscVm.run(fibFn, Array(30L)) == 832040L, "fib VM result wrong")
    require(SscVm.run(tcoFn, Array(1000000L, 0L)) == 500000500000L, "sumTco VM result wrong")
    require(java.lang.Double.longBitsToDouble(SscVm.run(dsumFn, Array(1000000L, zeroBits))) == 500000.0,
      "dsum VM result wrong")

  @Benchmark
  def treeWalkFib(): Unit = Interpreter(devNull).runSections(fibModule)

  @Benchmark
  def vmFib(): Long = SscVm.run(fibFn, Array(30L))

  @Benchmark
  def treeWalkTco(): Unit = Interpreter(devNull).runSections(tcoModule)

  @Benchmark
  def vmTco(): Long = SscVm.run(tcoFn, Array(1000000L, 0L))

  @Benchmark
  def treeWalkDsum(): Unit = Interpreter(devNull).runSections(dsumModule)

  @Benchmark
  def vmDsum(): Long = SscVm.run(dsumFn, Array(1000000L, zeroBits))
