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

  test("bails (None) on unsupported double-typed function") {
    val f = funOf("h", """def h(x: Double): Double = x * 2.0""")
    VmCompiler.compile(f) shouldBe None
  }

  test("bails (None) on unsupported string operations") {
    val f = funOf("s", """def s(x: Int): String = "n=" + x""")
    VmCompiler.compile(f) shouldBe None
  }
