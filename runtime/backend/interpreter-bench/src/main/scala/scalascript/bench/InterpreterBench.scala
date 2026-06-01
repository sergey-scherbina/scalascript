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

  // Wide ADT (12 constructors) with Int case bodies — exercises pure-body
  // compilation across many constructors without Double-boxing noise.
  private val modPatternMatchWide: Module = src(
    """sealed trait Op
      |case class A(x: Int) extends Op
      |case class B(x: Int) extends Op
      |case class C(x: Int) extends Op
      |case class D(x: Int) extends Op
      |case class E(x: Int) extends Op
      |case class F(x: Int) extends Op
      |case class G(x: Int) extends Op
      |case class H(x: Int) extends Op
      |case class I(x: Int) extends Op
      |case class J(x: Int) extends Op
      |case class K(x: Int) extends Op
      |case class L(x: Int) extends Op
      |def eval(o: Op): Int = o match
      |  case A(x) => x + 1
      |  case B(x) => x + 2
      |  case C(x) => x + 3
      |  case D(x) => x + 4
      |  case E(x) => x + 5
      |  case F(x) => x + 6
      |  case G(x) => x + 7
      |  case H(x) => x + 8
      |  case I(x) => x + 9
      |  case J(x) => x + 10
      |  case K(x) => x + 11
      |  case L(x) => x + 12
      |val ops = List(A(1), B(1), C(1), D(1), E(1), F(1), G(1), H(1), I(1), J(1), K(1), L(1))
      |var total = 0
      |var i = 0
      |while i < 50000 do
      |  ops.foreach(o => { total = total + eval(o) })
      |  i = i + 1
      |total""".stripMargin
  )

  // Recursive ADT tree-walk: a classic expression evaluator dispatching over
  // constructors and recursing into children — the canonical interpreter
  // workload (and the target of VM 2a). The tree is built once; the loop only
  // measures the recursive `eval`, not construction.
  private val modRecursiveEval: Module = src(
    """sealed trait Expr
      |case class Num(n: Int) extends Expr
      |case class Add(l: Expr, r: Expr) extends Expr
      |case class Mul(l: Expr, r: Expr) extends Expr
      |def eval(e: Expr): Int = e match
      |  case Num(n)    => n
      |  case Add(l, r) => eval(l) + eval(r)
      |  case Mul(l, r) => eval(l) * eval(r)
      |def build(d: Int): Expr =
      |  if d <= 0 then Num(1)
      |  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
      |val tree = build(8)
      |var total = 0
      |var i = 0
      |while i < 1000 do
      |  total = total + eval(tree)
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
  def patternMatchWide(): Unit =
    Interpreter(devNull).runSections(modPatternMatchWide)

  @Benchmark
  def recursiveEval(): Unit =
    Interpreter(devNull).runSections(modRecursiveEval)

  @Benchmark
  def effectPure(): Unit =
    Interpreter(devNull).runSections(modEffectPure)

  @Benchmark
  def tupleMonoid(): Unit =
    Interpreter(devNull).runSections(modTupleMonoid)
