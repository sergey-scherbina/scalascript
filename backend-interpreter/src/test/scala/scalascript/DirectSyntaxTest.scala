package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Tests for v1.8 direct-syntax do-notation (interpreter phase).
 *  Uses Option and List as the test monads — both have built-in flatMap
 *  in the interpreter's dispatch table. */
class DirectSyntaxTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── DS-1 / DS-2: explicit direct[M] marker ────────────────────────

  test("direct[Option] — monadic bind with Some"):
    run("""
      val result = direct[Option] {
        x = Some(40)
        y = Some(2)
        Some(x + y)
      }
      println(result)
    """) shouldBe "Some(42)"

  test("direct[Option] — short-circuits on None"):
    run("""
      val result = direct[Option] {
        x = Some(1)
        y = None
        Some(x + y)
      }
      println(result)
    """) shouldBe "None"

  test("direct[List] — monadic bind produces Cartesian product"):
    run("""
      val result = direct[List] {
        x = List(1, 2)
        y = List(10, 20)
        List(x + y)
      }
      println(result)
    """) shouldBe "List(11, 21, 12, 22)"

  // ── DS-2: pure bindings via val ───────────────────────────────────

  test("val binding inside direct block is pure (no bind)"):
    run("""
      val result = direct[Option] {
        x = Some(10)
        val doubled = x * 2   // pure — no monadic dispatch
        Some(doubled)
      }
      println(result)
    """) shouldBe "Some(20)"

  test("multiple val and bind mixed"):
    run("""
      val result = direct[Option] {
        a = Some(3)
        val b = a + 1    // pure: b = 4
        c = Some(b * 2)  // bind: c = 8
        val d = c - 1    // pure: d = 7
        Some(d)
      }
      println(result)
    """) shouldBe "Some(7)"

  // ── DS-2: explicit bind-and-discard with _ = expr ─────────────────

  test("val _ = expr performs bind-and-discard"):
    run("""
      val result = direct[Option] {
        val _ = Some("logged")   // monadic bind, result discarded
        x = Some(42)
        Some(x)
      }
      println(result)
    """) shouldBe "Some(42)"

  // ── DS-2: var declarations stay mutable ──────────────────────────

  test("var inside direct block is mutable (not a bind)"):
    run("""
      val result = direct[Option] {
        var counter = 0
        x = Some(10)
        counter = counter + x   // mutation, not bind
        Some(counter)
      }
      println(result)
    """) shouldBe "Some(10)"

  // ── Bare expressions as side effects ─────────────────────────────

  test("bare expression evaluated for side effect"):
    run("""
      val result = direct[Option] {
        x = Some(5)
        println("side effect")   // pure side effect
        Some(x * 2)
      }
      println(result)
    """) shouldBe "side effect\nSome(10)"

  // ── Nested direct blocks ──────────────────────────────────────────

  test("nested direct blocks are independent"):
    run("""
      val inner = direct[Option] {
        a = Some(3)
        Some(a + 1)
      }
      val outer = direct[Option] {
        x = inner
        Some(x * 10)
      }
      println(outer)
    """) shouldBe "Some(40)"

  // ── Single expression (no stmts) ─────────────────────────────────

  test("direct block with only a tail expression"):
    run("""
      val r = direct[Option] { Some(99) }
      println(r)
    """) shouldBe "Some(99)"
