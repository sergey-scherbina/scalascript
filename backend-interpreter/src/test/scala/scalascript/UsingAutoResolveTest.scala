package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class UsingAutoResolveTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── using auto-resolution ─────────────────────────────────────────────

  test("using — single concrete given resolved automatically"):
    run("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "int:" + a
      def display[A](x: A)(using s: Show[A]): String = s.show(x)
      println(display(42))
    """) shouldBe "int:42"

  test("using — explicit given still works"):
    run("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "int:" + a
      def display[A](x: A)(using s: Show[A]): String = s.show(x)
      println(display(42)(using showInt))
    """) shouldBe "int:42"

  test("using — type variable inferred from List element"):
    run("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A
      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b
      def combineAll[A](xs: List[A])(using m: Monoid[A]): A =
        xs.foldLeft(m.empty)((acc, x) => m.combine(acc, x))
      println(combineAll(List(1, 2, 3, 4)))
    """) shouldBe "10"

  // ── summon ───────────────────────────────────────────────────────────

  test("summon — retrieves given by type"):
    run("""
      trait Printer[A]:
        def print(a: A): String
      given intPrinter: Printer[Int] with
        def print(a: Int): String = "<<" + a + ">>"
      println(summon[Printer[Int]].print(99))
    """) shouldBe "<<99>>"

  // ── context bounds ────────────────────────────────────────────────────

  test("context bound — [A: Show] desugars to using Show[A]"):
    run("""
      trait Show[A]:
        def show(a: A): String
      given showStr: Show[String] with
        def show(a: String): String = "[" + a + "]"
      def display[A: Show](x: A): String = summon[Show[A]].show(x)
      println(display("hello"))
    """) shouldBe "[hello]"
