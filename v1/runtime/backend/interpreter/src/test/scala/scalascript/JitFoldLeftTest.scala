package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.interpreter.vm.VmCompiler

/** jit-foldleft Slice A — `recv.foldLeft(z)((a,b) => body)` compiled to an inline
 *  VM loop (flag-gated `-Dssc.jit.foldleft=1`, statically `List[Int]`-only).
 *
 *  DIFFERENTIAL discipline: every program must produce the SAME output with the
 *  foldLeft-JIT ON and OFF (the JIT is on every hot path — the danger is a
 *  silently-wrong result, not a crash). `f` is called 40× (> the JIT threshold of
 *  8) so it compiles; with the flag on, that compile goes through
 *  `tryCompileFoldLeft`. A separate test asserts the compile actually happened so
 *  the `on == off` checks are not vacuous. */
class JitFoldLeftTest extends AnyFunSuite with Matchers:

  private def runWith(foldJit: Boolean, code: String): String =
    val prev = System.getProperty("ssc.jit.foldleft")
    // Feature is ON by default — to test OFF we set the kill-switch "0", not clear.
    if foldJit then System.setProperty("ssc.jit.foldleft", "1")
    else System.setProperty("ssc.jit.foldleft", "0")
    try
      val buf = java.io.ByteArrayOutputStream()
      val ps  = java.io.PrintStream(buf, true)
      Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
      ps.flush(); buf.toString.trim
    finally
      if prev == null then System.clearProperty("ssc.jit.foldleft")
      else System.setProperty("ssc.jit.foldleft", prev)

  /** Assert JIT-on result == JIT-off result, and return it. */
  private def diffEq(code: String): String =
    val off = runWith(false, code)
    val on  = runWith(true, code)
    withClue(s"JIT-on($on) vs JIT-off($off) disagree — ") { on shouldBe off }
    off

  // f is the function whose body is the foldLeft; called 40× so it JIT-compiles.
  private def loopProg(body: String, mkXs: String = "List(3, 1, 4, 1, 5, 9, 2, 6)"): String =
    s"""
      def f(xs: List[Int]): Int = $body
      val xs: List[Int] = $mkXs
      var i = 0
      var r = 0
      while i < 40 do
        r = f(xs)
        i = i + 1
      println(r)
    """

  test("sum"):
    diffEq(loopProg("xs.foldLeft(0)((a, b) => a + b)")) shouldBe "31"
  test("product"):
    diffEq(loopProg("xs.foldLeft(1)((a, b) => a * b)")) shouldBe "6480"
  test("subtract (order-sensitive)"):
    diffEq(loopProg("xs.foldLeft(100)((a, b) => a - b)")) shouldBe "69"
  test("count — element ignored"):
    diffEq(loopProg("xs.foldLeft(0)((a, b) => a + 1)")) shouldBe "8"
  test("max via if-expression"):
    diffEq(loopProg("xs.foldLeft(0)((a, b) => if a > b then a else b)")) shouldBe "9"
  test("accumulator read twice (no clobber)"):
    // a used twice in the body — the result must be computed from the OLD acc.
    diffEq(loopProg("xs.foldLeft(0)((a, b) => a + a - b)")) shouldBe "-678"
  test("constant + arithmetic in body"):
    diffEq(loopProg("xs.foldLeft(0)((a, b) => a + b * 2 + 1)")) shouldBe "70"
  test("empty list — returns the seed"):
    diffEq(loopProg("xs.foldLeft(7)((a, b) => a + b)", "List[Int]()")) shouldBe "7"
  test("single element"):
    diffEq(loopProg("xs.foldLeft(10)((a, b) => a + b)", "List(5)")) shouldBe "15"
  test("negative seed + mixed"):
    diffEq(loopProg("xs.foldLeft(-50)((a, b) => a + b)")) shouldBe "-19"

  // ── the real win: foldLeft INSIDE a larger function ───────────────────────
  // Previously the whole function bailed to tree-walk because of the foldLeft;
  // now the loop AND the surrounding code compile. f is called 40× to JIT it.
  private def biggerProg(body: String, call: String = "f(xs)", extra: String = ""): String =
    s"""
      $extra
      def f(xs: List[Int]): Int =
        $body
      val xs: List[Int] = List(2, 4, 6, 8)
      var i = 0
      var r = 0
      while i < 40 do
        r = $call
        i = i + 1
      println(r)
    """

  test("foldLeft then trailing arithmetic (whole fn compiles)"):
    diffEq(biggerProg(
      """val s = xs.foldLeft(0)((a, b) => a + b)
        |        s * 3 + 1""".stripMargin)) shouldBe "61"   // sum=20 → 20*3+1
  test("code before AND after the fold"):
    diffEq(biggerProg(
      """val base = xs.foldLeft(0)((a, b) => a + b)
        |        val doubled = base + base
        |        doubled - 5""".stripMargin)) shouldBe "35"  // 20 → 40-5
  test("fold result feeds an if"):
    diffEq(biggerProg(
      """val s = xs.foldLeft(0)((a, b) => a + b)
        |        if s > 10 then s * 2 else s""".stripMargin)) shouldBe "40"
  test("two folds in one function"):
    diffEq(biggerProg(
      """val s = xs.foldLeft(0)((a, b) => a + b)
        |        val p = xs.foldLeft(1)((a, b) => a * b)
        |        s + p""".stripMargin)) shouldBe "404"       // 20 + (2*4*6*8=384)

  test("the whole surrounding function actually compiles (the real win)"):
    // The trailing-arithmetic case must compile f (not just tree-walk), proving the
    // benefit: a foldLeft no longer bails the whole function.
    val before = VmCompiler.foldLeftCompileCount.get()
    runWith(true, biggerProg(
      """val s = xs.foldLeft(0)((a, b) => a + b)
        |        s * 3 + 1""".stripMargin))
    withClue("the foldLeft inside a larger function was not compiled: ") {
      VmCompiler.foldLeftCompileCount.get() should be > before
    }

  test("the foldLeft-JIT path is actually exercised (diffs above are not vacuous)"):
    val before = VmCompiler.foldLeftCompileCount.get()
    runWith(true, loopProg("xs.foldLeft(0)((a, b) => a + b)"))
    withClue("foldLeftCompileCount did not increase — f was never compiled via tryCompileFoldLeft: ") {
      VmCompiler.foldLeftCompileCount.get() should be > before
    }

  test("flag OFF leaves the receiver/body untouched (no compile)"):
    val before = VmCompiler.foldLeftCompileCount.get()
    runWith(false, loopProg("xs.foldLeft(0)((a, b) => a + b)"))
    VmCompiler.foldLeftCompileCount.get() shouldBe before
