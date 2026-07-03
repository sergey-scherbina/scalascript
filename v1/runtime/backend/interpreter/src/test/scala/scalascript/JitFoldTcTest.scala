package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.JitFoldTcStats
import scalascript.parser.Parser

/** jit-foldleft-tc — memoize the (empty, combine) of a typeclass fold
 *  `xs.foldLeft(summon[M].empty)(summon[M].combine)` per call-site, keyed by given
 *  identity (flag `-Dssc.jit.foldtc=1`, off by default).
 *
 *  DIFFERENTIAL: every program must give the same output memo-ON vs memo-OFF —
 *  including the polymorphic case where one call-site resolves DIFFERENT givens on
 *  successive calls (the cache must re-evaluate, not return stale). */
class JitFoldTcTest extends AnyFunSuite with Matchers:

  private def runWith(on: Boolean, code: String): String =
    val prev = System.getProperty("ssc.jit.foldtc")
    System.setProperty("ssc.jit.foldtc", if on then "1" else "0")
    try
      val buf = java.io.ByteArrayOutputStream(); val ps = java.io.PrintStream(buf, true)
      Interpreter(ps).run(Parser.parse(s"# T\n\n```scala\n$code\n```\n"))
      ps.flush(); buf.toString.trim
    finally
      if prev == null then System.clearProperty("ssc.jit.foldtc")
      else System.setProperty("ssc.jit.foldtc", prev)

  private val prelude =
    """trait Monoid[A]:
      |  def empty: A
      |  def combine(a: A, b: A): A
      |given intMonoid: Monoid[Int] with
      |  def empty: Int = 0
      |  def combine(a: Int, b: Int): Int = a + b
      |given strMonoid: Monoid[String] with
      |  def empty: String = ""
      |  def combine(a: String, b: String): String = a + b
      |def combineAll[A: Monoid](xs: List[A]): A =
      |  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
      |""".stripMargin

  private def diff(code: String): String =
    val full = prelude + code
    val off = runWith(false, full); val on = runWith(true, full)
    withClue(s"memo-on($on) vs memo-off($off) disagree — ") { on shouldBe off }
    off

  test("single call"):
    diff("println(combineAll(List(1, 2, 3, 4)))") shouldBe "10"
  test("repeated calls (memo hits)"):
    diff("""var s = 0
           |var i = 0
           |while i < 60 do
           |  s = s + combineAll(List(1, 2, 3, 4, 5))
           |  i = i + 1
           |println(s)""".stripMargin) shouldBe "900"   // 15 * 60
  test("single-element list (trivial fold)"):
    diff("println(combineAll(List(0)))") shouldBe "0"
  test("empty generic typeclass-fold is a PRE-EXISTING limitation (fails memo-on AND -off)"):
    // `combineAll(List[Int]())` can't infer A at runtime → no given for Monoid[A].
    // The memo must not change this: it throws the same way with the flag on or off.
    def msg(on: Boolean) =
      try { runWith(on, prelude + "println(combineAll(List[Int]()))"); "ok" }
      catch case e: Throwable => e.getMessage
    msg(true) shouldBe msg(false)
  test("string monoid (different given, same call-site)"):
    diff("""println(combineAll(List("a", "b", "c")))""") shouldBe "abc"
  test("POLYMORPHIC: same call-site, two givens alternating — must not go stale"):
    diff("""var out = ""
           |var i = 0
           |while i < 30 do
           |  out = out + combineAll(List(1, 2, 3)).toString
           |  out = out + combineAll(List("x", "y"))
           |  i = i + 1
           |println(out.length)""".stripMargin) shouldBe "90"   // ("6" + "xy") = 3 chars × 30
  test("nested combineAll calls"):
    diff("""println(combineAll(List(combineAll(List(1, 2)), combineAll(List(3, 4)), 5)))""") shouldBe "15"

  test("the memo path is actually exercised (diffs above are not vacuous)"):
    val before = JitFoldTcStats.hits.get()
    runWith(true, prelude +
      """var i = 0
        |while i < 40 do { combineAll(List(1, 2, 3)); i = i + 1 }
        |println("ok")""".stripMargin)
    withClue("foldTcMemoHits did not increase — the memo never hit: ") {
      JitFoldTcStats.hits.get() should be > before
    }
