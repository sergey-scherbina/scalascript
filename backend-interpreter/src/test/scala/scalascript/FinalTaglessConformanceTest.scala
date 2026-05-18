package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.13 Phase 6 — Conformance tests for Final-Tagless ergonomics.
 *
 *  Six tests covering the four typer dependencies:
 *   1. `using` clause auto-resolution (interpreter)
 *   2. Context bound `[A: TC]` + `summon` (interpreter)
 *   3. Multiple context bounds `[A: TC1: TC2]` (interpreter)
 *   4. std/semigroup-monoid rewritten with context bounds
 *   5. Sealed-trait extension dispatch for Either (bifunctor)
 *   6. Sealed-trait extension dispatch for Either (monaderror)
 */
class FinalTaglessConformanceTest extends AnyFunSuite with Matchers:

  private val repoRoot = os.pwd / os.up

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def runWithStd(imports: String, code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |$imports
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── 1: `using` clause auto-resolution ───────────────────────────────────

  test("using auto-resolve: single in-scope given injected at call site"):
    run("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "num:" + a
      def display(x: Int)(using s: Show[Int]): String = s.show(x)
      println(display(42))
    """) shouldBe "num:42"

  // ── 2: Context bound [A: TC] ────────────────────────────────────────────

  test("context bound [A: Monoid] auto-resolves and summon retrieves instance"):
    run("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A
      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b
      def combineAll[A: Monoid](xs: List[A]): A =
        xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
      println(combineAll(List(1, 2, 3, 4)))
    """) shouldBe "10"

  // ── 3: Multiple context bounds ──────────────────────────────────────────

  test("multiple context bounds [A: Show: Monoid] both resolve"):
    run("""
      trait Show[A]:
        def show(a: A): String
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A
      given showInt: Show[Int] with
        def show(a: Int): String = "num:" + a
      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b
      def showCombined[A: Show: Monoid](xs: List[A]): String =
        val result = xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
        summon[Show[A]].show(result)
      println(showCombined(List(1, 2, 3)))
    """) shouldBe "num:6"

  // ── 4: std/semigroup-monoid rewritten with context bounds ───────────────

  test("std combineAll[A: Monoid] auto-resolves given from imported std"):
    runWithStd(
      "[intSum, stringConcat, combineAll](std/semigroup-monoid.ssc)",
      """
        println(combineAll(List(1, 2, 3, 4, 5)))
        println(combineAll(List("a", "b", "c")))
      """
    ) shouldBe "15\nabc"

  // ── 5: Sealed-trait extension dispatch — Either bimap ───────────────────

  test("Either.bimap dispatches via sealed-parent chain (Left/Right → Either)"):
    runWithStd(
      "[Left, Right, eitherBifunctor](std/bifunctor.ssc)",
      """
        val r: Either[String, Int] = Right(42)
        val l: Either[String, Int] = Left("err")
        println(r.bimap((s: String) => s.length, (n: Int) => n * 2))
        println(l.bimap((s: String) => s.length, (n: Int) => n * 2))
      """
    ) shouldBe "Right(84)\nLeft(3)"

  // ── 6: Sealed-trait extension dispatch — Either handleError ─────────────

  test("Either.handleError dispatches via sealed-parent chain"):
    runWithStd(
      "[Left, Right, optionUnitError, handleEither](std/monaderror.ssc)",
      """
        val ok:  Either[String, Int] = Right(42)
        val err: Either[String, Int] = Left("boom")
        println(ok.handleError((e: String) => Right(0)))
        println(err.handleError((e: String) => Right(0)))
      """
    ) shouldBe "Right(42)\nRight(0)"
