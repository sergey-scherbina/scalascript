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

  // Pure non-match-bodied 1-param function called in a tight loop with an Int
  // accumulator — exercises the A4 (Tier-2b pure-call) target. `pureCallValue`
  // historically only direct-style-ran match-bodied funcs; A4 generalizes that
  // to any pure compilable body (here just `x + 1`) via
  // `PatternRuntime.compileExpr`/`compileExprSlot1`, returning the result as
  // a bare `Value` so the enclosing `total + f(i)` skips the per-call `Pure`
  // wrapper and the param-frame allocation.
  private val modPureCallSum: Module = src(
    """def f(x: Int): Int = x + 1
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + f(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // 2-param parallel of `pureCallSum` — exercises the LApply2 raw-Long arg
  // inlining inside `tryLongWhileAssign`. `g(x, y) = x + y` keeps the body in
  // the `compileSlotD` arith subset; both args read as raw Long via DSlot(0)
  // / DSlot(1), result stays in the unboxed-Long while loop.
  private val modPureCallSum2: Module = src(
    """def g(x: Int, y: Int): Int = x + y
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + g(i, i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Direction A.2 validation: 1-param pure fn whose body is a `Term.If`
  // expression. Before the walkLocalSlotCtx Term.If extension, `walkLocalSlot`
  // bailed on the `if x < 0 then -x else x` body, forcing the call site to
  // route through the LExpr LApply path (~13 ms). After A.2, the callee
  // compiles to a Java ternary inside the same while-JIT class, matching
  // pureCallSum's JVM-parity speed.
  private val modPureCallSumIf: Module = src(
    """def absIf(x: Int): Int = if x < 0 then -x else x
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + absIf(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Direction A.1 validation: 1-param pure fn whose body is a single-stmt
  // block (`{ x + 1 }`). Same JIT-able subset as pureCallSum but with an
  // extra Term.Block wrapper that walkLocalSlotCtx bailed on before A.1.
  private val modPureCallSumBlock: Module = src(
    """def fblk(x: Int): Int = { x + 1 }
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + fblk(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Recursive int fib whose base case multiplies by a top-level `val`
  // constant — exercises the BytecodeJit free-name (global) read path. Before
  // the globals extension, the `mul` reference forces `walkLong` to bail and
  // the function falls through to `SscVm.exec`.
  private val modFibMul: Module = src(
    """val mul = 7
      |def fibMul(n: Int): Int =
      |  if n <= 1 then n * mul else fibMul(n - 1) + fibMul(n - 2)
      |fibMul(30)""".stripMargin
  )

  // Double-typed recursive fib — exercises the BytecodeJit Double subset
  // (params + return as `double`, `Lit.Double` constants, double arithmetic).
  private val modFibD: Module = src(
    """def fibD(n: Double): Double =
      |  if n <= 1.0 then n else fibD(n - 1.0) + fibD(n - 2.0)
      |fibD(30.0)""".stripMargin
  )

  // Double-typed recursive fib whose base case multiplies by a top-level
  // `val` constant of `Double` type — exercises the BytecodeJit Double-globals
  // read path (`readGlobalDouble`). Parallel to `modFibMul` (which exercises
  // the Int-globals path). Before the `phase-c-bytecode-double-globals` slice,
  // `walkDouble`'s `Term.Name` case bailed on any free name and the fn fell
  // through to `SscVm.exec`.
  private val modFibMulD: Module = src(
    """val mul = 7.0
      |def fibMulD(n: Double): Double =
      |  if n <= 1.0 then n * mul else fibMulD(n - 1.0) + fibMulD(n - 2.0)
      |fibMulD(30.0)""".stripMargin
  )

  // Inline match-in-hot-loop: `while i < 1M: total += p match { case Pair(a,b) => a+b }`.
  // Since 2026-06-02 (LMatch in tryLongWhileAssign) the whole while runs in the Long-slot
  // array — no FrameMap2, no Pure, no IntV per iter. Floor: ~16.6 ms/op (1M iters)
  // via `scripts/bench interp instanceFieldAccess` (2-fork, 2026-06-02). Remaining
  // cost is one HashMap lookup per field read inside CompiledMatch.runValueLong;
  // replacing with Array[Value] slots would shave a further ~5-10 ms.
  private val modInstanceFieldAccess: Module = src(
    """case class Pair(a: Int, b: Int)
      |val p = Pair(3, 4)
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + (p match { case Pair(a, b) => a + b })
      |  i = i + 1
      |total""".stripMargin
  )

  // Map.foreach with a 2-arg `(k, v) =>` closure — exercises the callEntry
  // path in `DispatchRuntime.dispatchMap` "foreach" case. Not currently
  // covered by FastTier (which only handles 1-arg foreach closures via
  // `tryDoubleAccumForeach*`/`tryLongAccumForeach*`); the natural follow-up
  // is a 2-arg LApply2-style fast path for entry-style closures.
  private val modMapForeach: Module = src(
    """val m = Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
      |var total = 0
      |var i = 0
      |while i < 100000 do
      |  m.foreach((k, v) => { total = total + v })
      |  i = i + 1
      |total""".stripMargin
  )

  // Mixed-type self-recursion — `g(scale: Int, e: Expr): Int` carries an
  // Int accumulator alongside an Expr ref scrutinee. Each recursive call
  // re-passes the Int param and a child Expr field. Exercises the
  // BytecodeJit per-param Object/long marshaling in a single MH signature.
  private val modGEval: Module = src(
    """sealed trait Expr
      |case class Num(n: Int) extends Expr
      |case class Add(l: Expr, r: Expr) extends Expr
      |case class Mul(l: Expr, r: Expr) extends Expr
      |def gEval(scale: Int, e: Expr): Int = e match
      |  case Num(n)    => n * scale
      |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
      |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
      |def build(d: Int): Expr =
      |  if d <= 0 then Num(1)
      |  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
      |val tree = build(8)
      |var total = 0
      |var i = 0
      |while i < 1000 do
      |  total = total + gEval(3, tree)
      |  i = i + 1
      |total""".stripMargin
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

  // `patternMatchHeavy` with a `Set[Shape]` receiver instead of `List[Shape]`.
  // Verifies that `Set.foreach` routes through `DispatchRuntime.dispatchSet`
  // → `dispatchList(s.toList, ...)` → `CallRuntime.foreachReusing` → FastTier,
  // so the foreach-accumulator wins land automatically without any FastTier
  // changes. Uses `.toSet` (rather than the `Set(...)` ctor) because the
  // bench harness skips `BuiltinsRuntime.initBuiltins` (which is where the
  // `Set` companion gets registered) and lazy plugin loading only covers the
  // intrinsics in `intrinsics/Core.scala` — an unrelated harness gap.
  private val modPatternMatchSet: Module = src(
    """sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |def area(s: Shape): Double = s match
      |  case Circle(r)      => 3.14159 * r * r
      |  case Rect(w, h)     => w * h
      |  case Triangle(b, h) => 0.5 * b * h
      |val shapes = List(Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0)).toSet
      |var total = 0.0
      |var i = 0
      |while i < 100000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin
  )

  @Benchmark
  def patternMatchHeavy(): Unit =
    Interpreter(devNull).runSections(modPatternMatch)

  @Benchmark
  def patternMatchSet(): Unit =
    Interpreter(devNull).runSections(modPatternMatchSet)

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

  @Benchmark
  def pureCallSum(): Unit =
    Interpreter(devNull).runSections(modPureCallSum)

  @Benchmark
  def pureCallSum2(): Unit =
    Interpreter(devNull).runSections(modPureCallSum2)

  @Benchmark
  def pureCallSumIf(): Unit =
    Interpreter(devNull).runSections(modPureCallSumIf)

  @Benchmark
  def pureCallSumBlock(): Unit =
    Interpreter(devNull).runSections(modPureCallSumBlock)

  @Benchmark
  def recursionFibMul(): Unit =
    Interpreter(devNull).runSections(modFibMul)

  @Benchmark
  def recursionFibD(): Unit =
    Interpreter(devNull).runSections(modFibD)

  @Benchmark
  def recursionFibMulD(): Unit =
    Interpreter(devNull).runSections(modFibMulD)

  @Benchmark
  def recursiveEvalMixed(): Unit =
    Interpreter(devNull).runSections(modGEval)

  @Benchmark
  def instanceFieldAccess(): Unit =
    Interpreter(devNull).runSections(modInstanceFieldAccess)

  @Benchmark
  def mapForeach(): Unit =
    Interpreter(devNull).runSections(modMapForeach)
