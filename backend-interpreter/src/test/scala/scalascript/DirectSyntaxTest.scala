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

  // ── v1.8.1 Feature 1: postfix .! bind operator ────────────────────

  test(".! operator — inline in function argument"):
    run("""
      val result = direct[Option] {
        println(Some(42).!)
        Some(())
      }
      println(result)
    """) shouldBe "42\nSome(())"

  test(".! operator — inline expression combining two binds"):
    run("""
      val result = direct[Option] {
        Some(Some(10).! + Some(32).!)
      }
      println(result)
    """) shouldBe "Some(42)"

  test(".! operator — short-circuits on None"):
    run("""
      val result = direct[Option] {
        val x = None.!
        Some(x + 1)
      }
      println(result)
    """) shouldBe "None"

  test(".! operator — chained method calls"):
    run("""
      def wrap(n: Int): Option[Int] = Some(n)
      val result = direct[Option] {
        Some(wrap(7).! * wrap(6).!)
      }
      println(result)
    """) shouldBe "Some(42)"

  // ── v1.8.1 Feature 2: effect-row union type ───────────────────────

  test("effect-row union type direct[Option | List] is accepted"):
    run("""
      val result = direct[Option | List] {
        x = Some(21)
        Some(x * 2)
      }
      println(result)
    """) shouldBe "Some(42)"

  // ── v1.8.1 Feature 3: transformer-aware lift ──────────────────────

  test("transformer lift — direct[Option] auto-lifts Right to Some track"):
    run("""
      case class Right[A](value: A)
      case class Left[A](value: A)
      val result = direct[Option] {
        x = Right(42)
        Some(x * 2)
      }
      println(result)
    """) shouldBe "Some(84)"

  test("transformer lift — direct[Option] auto-lifts Left to None"):
    run("""
      case class Right[A](value: A)
      case class Left[A](value: A)
      val result = direct[Option] {
        x = Left("error")
        Some(x)
      }
      println(result)
    """) shouldBe "None"

  test("transformer lift — direct[Either] auto-lifts Some to Right track"):
    run("""
      case class Right[A](value: A)
      case class Left[A](value: A)
      val result = direct[Either] {
        x = Some(21)
        Right(x * 2)
      }
      println(result)
    """) shouldBe "Right(42)"

  test("transformer lift — direct[Either] auto-lifts None to Left"):
    run("""
      case class Right[A](value: A)
      case class Left[A](value: A)
      val result = direct[Either] {
        x = None
        Right(x)
      }
      println(result)
    """) shouldBe "Left(())"
