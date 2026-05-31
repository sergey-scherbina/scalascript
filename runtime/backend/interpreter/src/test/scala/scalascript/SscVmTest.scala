package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.Value
import scalascript.interpreter.vm.{SscVm, VmCompiler}
import scalascript.parser.Parser

/** Verifies the proof-of-concept bytecode VM (docs/vm-jit-spec.md):
 *  compiling real parsed integer functions and checking VM results equal
 *  the known mathematical values, plus that unsupported functions bail to None.
 */
class SscVmTest extends AnyFunSuite with Matchers:

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  /** Define top-level `defs` (no call), return the named closure. */
  private def funOf(name: String, defs: String): Value.FunV =
    val interp = Interpreter(devNull)
    interp.run(Parser.parse(s"# T\n\n```scala\n$defs\n```\n"))
    interp.globalsView(name).asInstanceOf[Value.FunV]

  /** Define top-level `defs`, return the interpreter so callers can build a
   *  resolver over its globals (for mutual / sibling-call compilation). */
  private def interpOf(defs: String): Interpreter =
    val interp = Interpreter(devNull)
    interp.run(Parser.parse(s"# T\n\n```scala\n$defs\n```\n"))
    interp

  /** Resolve a free name to a sibling/top-level FunV from `interp`'s globals. */
  private def globalsResolve(interp: Interpreter): VmCompiler.Resolve =
    (_, name) =>
      interp.globalsView.get(name) match
        case Some(fv: Value.FunV) => fv
        case _                    => null

  test("compiles and runs recursive fib") {
    val fib = funOf("fib",
      """def fib(n: Int): Int =
        |  if n <= 1 then n else fib(n - 1) + fib(n - 2)""".stripMargin)
    val cfn = VmCompiler.compile(fib)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 55L
    SscVm.run(cfn.get, Array(20L)) shouldBe 6765L
    SscVm.run(cfn.get, Array(30L)) shouldBe 832040L
  }

  test("compiles and runs two-arg tail-recursive sum") {
    val f = funOf("sumTco",
      """def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc else sumTco(n - 1, acc + n)""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(100L, 0L)) shouldBe 5050L
    // Tail recursion is compiled to a loop, so deep input needs no extra stack.
    SscVm.run(cfn.get, Array(100000L, 0L)) shouldBe 5000050000L
  }

  test("handles arithmetic, division and modulo") {
    val f = funOf("g",
      """def g(x: Int): Int =
        |  if x % 2 == 0 then x / 2 else x * 3 + 1""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 5L
    SscVm.run(cfn.get, Array(7L))  shouldBe 22L
  }

  test("compiles a three-argument integer function") {
    val f = funOf("f3",
      """def f3(a: Int, b: Int, c: Int): Int = a * 100 + b * 10 + c""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(1L, 2L, 3L)) shouldBe 123L
    SscVm.run(cfn.get, Array(9L, 0L, 7L)) shouldBe 907L
  }

  test("compiles a call to another integer function via the resolver") {
    val interp = interpOf(
      """def dbl(x: Int): Int = x * 2
        |def useDbl(n: Int): Int = dbl(n) + dbl(n + 1)""".stripMargin)
    val useDbl = interp.globalsView("useDbl").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(useDbl, globalsResolve(interp))
    cfn shouldBe defined
    // useDbl(3) = dbl(3) + dbl(4) = 6 + 8 = 14
    SscVm.run(cfn.get, Array(3L)) shouldBe 14L
  }

  test("compiles mutual recursion (isEven / isOdd)") {
    val interp = interpOf(
      """def isEven(n: Int): Int = if n == 0 then 1 else isOdd(n - 1)
        |def isOdd(n: Int): Int  = if n == 0 then 0 else isEven(n - 1)""".stripMargin)
    val isEven = interp.globalsView("isEven").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(isEven, globalsResolve(interp))
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 1L
    SscVm.run(cfn.get, Array(7L))  shouldBe 0L
    SscVm.run(cfn.get, Array(0L))  shouldBe 1L
  }

  // ── Double-domain support (raw VM result is double *bits*) ──────────
  private def asD(raw: Long): Double = java.lang.Double.longBitsToDouble(raw)
  private def bitsOf(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

  test("compiles and runs a double-typed arithmetic function") {
    val f = funOf("h", """def h(x: Double): Double = x * 2.0""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(true)
    asD(SscVm.run(cfn.get, Array(bitsOf(3.5)))) shouldBe 7.0
    asD(SscVm.run(cfn.get, Array(bitsOf(-1.25)))) shouldBe -2.5
  }

  test("promotes an Int param into the double domain (Int -> Double)") {
    val f = funOf("scale", """def scale(x: Int): Double = x * 1.5""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(false)   // x is Int — caller passes a raw int
    asD(SscVm.run(cfn.get, Array(4L))) shouldBe 6.0
    asD(SscVm.run(cfn.get, Array(10L))) shouldBe 15.0
  }

  test("compiles a tail-recursive double accumulator") {
    val f = funOf("dsum",
      """def dsum(n: Int, acc: Double): Double =
        |  if n <= 0 then acc else dsum(n - 1, acc + 0.5)""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(false, true)
    asD(SscVm.run(cfn.get, Array(4L, bitsOf(0.0)))) shouldBe 2.0
    asD(SscVm.run(cfn.get, Array(100L, bitsOf(1.0)))) shouldBe 51.0
  }

  test("double comparison drives a branch") {
    val f = funOf("clamp",
      """def clamp(x: Double): Double = if x > 10.0 then 10.0 else x""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    asD(SscVm.run(cfn.get, Array(bitsOf(3.5)))) shouldBe 3.5
    asD(SscVm.run(cfn.get, Array(bitsOf(42.0)))) shouldBe 10.0
  }

  test("bails (None) on unsupported string operations") {
    val f = funOf("s", """def s(x: Int): String = "n=" + x""")
    VmCompiler.compile(f) shouldBe None
  }
