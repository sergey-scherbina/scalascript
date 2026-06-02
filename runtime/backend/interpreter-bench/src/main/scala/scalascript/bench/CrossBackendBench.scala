package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.parser.Parser
import scalascript.ast.Module
import scalascript.codegen.{JvmGen, JsGen}

/** JMH codegen benchmarks: how long each backend takes to generate code from
 *  a pre-parsed module. Execution time of each backend lives elsewhere —
 *  interpreter in `InterpreterBench`, cross-backend execution in `RuntimeBench`.
 *
 *  Run all codegen benchmarks:
 *    sbt "interpreterBench/Jmh/run .*CrossBackend.*"
 *
 *  Save results:
 *    sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json .*CrossBackend.*"
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class CrossBackendBench:

  private def src(code: String): Module =
    Parser.parse(s"# Bench\n\n```scalascript\n$code\n```\n")

  private val modArithLoop: Module = src(
    """var i = 0
      |var sum = 0
      |while i < 1000000 do
      |  sum = sum + i
      |  i = i + 1
      |sum""".stripMargin
  )

  private val modFib: Module = src(
    """def fib(n: Int): Int =
      |  if n <= 1 then n else fib(n - 1) + fib(n - 2)
      |fib(30)""".stripMargin
  )

  private val modTco: Module = src(
    """def sumTco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else sumTco(n - 1, acc + n)
      |sumTco(100000, 0)""".stripMargin
  )

  private val modPatternMatch: Module = src(
    """sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |def area(s: Shape): Double = s match
      |  case Circle(r)      => 3.14159 * r * r
      |  case Rect(w, h)     => w * h
      |  case Triangle(b, h) => 0.5 * b * h
      |val shapes = List(Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0))
      |var total = 0.0
      |var i = 0
      |while i < 100000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin
  )

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  // ── JvmGen codegen benchmarks ────────────────────────────────────

  @Benchmark
  def jvmGen_arithLoop(): Unit =
    JvmGen.generate(modArithLoop, None)

  @Benchmark
  def jvmGen_recursionFib(): Unit =
    JvmGen.generate(modFib, None)

  @Benchmark
  def jvmGen_recursionTco(): Unit =
    JvmGen.generate(modTco, None)

  @Benchmark
  def jvmGen_patternMatch(): Unit =
    JvmGen.generate(modPatternMatch, None)

  // ── JsGen codegen benchmarks ─────────────────────────────────────

  @Benchmark
  def jsGen_arithLoop(): Unit =
    JsGen.generateUserOnly(modArithLoop, None)

  @Benchmark
  def jsGen_recursionFib(): Unit =
    JsGen.generateUserOnly(modFib, None)

  @Benchmark
  def jsGen_recursionTco(): Unit =
    JsGen.generateUserOnly(modTco, None)

  @Benchmark
  def jsGen_patternMatch(): Unit =
    JsGen.generateUserOnly(modPatternMatch, None)
