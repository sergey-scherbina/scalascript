package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** hof-glue-jit-compile: the fused curried `qual.foldLeft(z)(g)` fast-path in
 *  `evalApplyGeneral` must preserve semantics for every receiver/combine shape —
 *  the List+FunV fast path AND the generic fallback (Range/Set/Vector receivers,
 *  NativeFnV combine). ~10% on `typeclassFoldMacro`; this locks the behaviour. */
class FusedFoldLeftTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("List.foldLeft(z)(g) — fast path (FunV combine)"):
    run("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.foldLeft(0)((a, b) => a + b))
      println(xs.foldLeft(100)((a, b) => a - b))
      println(List[Int]().foldLeft(7)((a, b) => a + b))
    """) shouldBe "15\n85\n7"

  test("List.foldLeft with a named def combine"):
    run("""
      def add(a: Int, b: Int): Int = a + b
      println(List(1, 2, 3, 4).foldLeft(0)(add))
    """) shouldBe "10"

  test("foldLeft fallback — Range receiver"):
    run("""
      println((1 to 5).foldLeft(0)((a, b) => a + b))
      println((1 until 5).foldLeft(1)((a, b) => a * b))
    """) shouldBe "15\n24"

  test("foldLeft fallback — Set / String-accumulator combine"):
    run("""
      println(Set(1, 2, 3).foldLeft(0)((a, b) => a + b))
      println(List("a", "b", "c").foldLeft("")((a, b) => a + b))
    """) shouldBe "6\nabc"

  test("typeclass fold (the hof-glue workload) still correct"):
    run("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A
      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b
      def combineAll[A: Monoid](xs: List[A]): A =
        xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
      println(combineAll(List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
    """) shouldBe "55"

  test("foldLeft producing a List accumulator (non-numeric)"):
    run("""
      val r = List(1, 2, 3).foldLeft(List[Int]())((acc, x) => x :: acc)
      println(r)
    """) shouldBe "List(3, 2, 1)"
