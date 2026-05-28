package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser
import scalascript.ast.Module

/** JMH microbenchmarks for the ScalaScript interpreter hot paths.
 *
 *  Run via sbt:
 *    sbt "interpreterBench/Jmh/run -i 5 -wi 3 -f 1 .*arithLoop.*"
 *
 *  Or run all:
 *    sbt "interpreterBench/Jmh/run"
 *
 *  Results are printed to stdout in JMH table format; save to file with:
 *    sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"
 */

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class InterpreterBench:

  // ── pre-parsed modules ───────────────────────────────────────────

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

  private val modEffectPure: Module = src(
    """def compute(n: Int): Int ! Logger =
      |  var acc = 0
      |  var i = 0
      |  while i < 10000 do
      |    acc = acc + i
      |    i = i + 1
      |  acc
      |runLogger { compute(10000) }""".stripMargin
  )

  private val modTupleMonoid: Module = src(
    """var i = 0
      |var last = (0, 0, 0, 0)
      |while i < 100000 do
      |  last = (1, 2) ++ (3, 4)
      |  i = i + 1
      |last""".stripMargin
  )

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  // ── benchmarks ───────────────────────────────────────────────────

  @Benchmark
  def arithLoop(): Unit =
    Interpreter(devNull).runSections(modArithLoop)

  @Benchmark
  def recursionFib(): Unit =
    Interpreter(devNull).runSections(modFib)

  @Benchmark
  def recursionTco(): Unit =
    Interpreter(devNull).runSections(modTco)

  @Benchmark
  def patternMatchHeavy(): Unit =
    Interpreter(devNull).runSections(modPatternMatch)

  @Benchmark
  def effectPure(): Unit =
    Interpreter(devNull).runSections(modEffectPure)

  @Benchmark
  def tupleMonoid(): Unit =
    Interpreter(devNull).runSections(modTupleMonoid)
