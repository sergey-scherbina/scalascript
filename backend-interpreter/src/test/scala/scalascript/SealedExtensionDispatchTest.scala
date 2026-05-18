package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.13 Phase 5 — Sealed-trait extension dispatch in the interpreter.
 *
 *  An `extension (fa: Either[E, A])` registered under "Either" must fire
 *  for `Right(x)` and `Left(e)` values, which carry `typeName = "Right"`
 *  / `"Left"`.  The sealed-parent chain lookup fills this gap.
 */
class SealedExtensionDispatchTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── enum cases → enum parent ─────────────────────────────────────────

  test("enum case dispatches extension registered on the enum type"):
    run("""
      enum Color:
        case Red
        case Blue
      extension (c: Color)
        def label: String = c match
          case Color.Red  => "red"
          case Color.Blue => "blue"
      println(Color.Red.label)
      println(Color.Blue.label)
    """) shouldBe "red\nblue"

  // ── case class → sealed parent ───────────────────────────────────────

  test("case class subtype dispatches extension on sealed parent"):
    run("""
      sealed class Shape
      case class Circle(r: Double) extends Shape
      case class Rect(w: Double, h: Double) extends Shape
      extension (s: Shape)
        def area: Double = s match
          case c: Circle => 3.14 * c.r * c.r
          case r: Rect   => r.w * r.h
      println(Circle(2.0).area)
      println(Rect(3.0, 4.0).area)
    """) shouldBe "12.56\n12"

  // ── Either-like sealed hierarchy ────────────────────────────────────

  test("Right and Left dispatch extension on Either"):
    run("""
      sealed class MyEither[A, B]
      case class MyLeft[A, B](value: A) extends MyEither[A, B]
      case class MyRight[A, B](value: B) extends MyEither[A, B]
      extension [A, B](e: MyEither[A, B])
        def isRight: Boolean = e match
          case r: MyRight[A, B] => true
          case l: MyLeft[A, B]  => false
      val r: MyEither[String, Int] = MyRight("ignored", 42)
      val l: MyEither[String, Int] = MyLeft("err", 0)
      println(r.isRight)
      println(l.isRight)
    """) shouldBe "true\nfalse"

  // ── Option-like ──────────────────────────────────────────────────────

  test("Some dispatches extension on Option"):
    run("""
      extension [A](oa: Option[A])
        def orElse(default: A): A = oa match
          case Some(a) => a
          case None    => default
      println(Some(42).orElse(0))
      println(None.orElse(99))
    """) shouldBe "42\n99"
