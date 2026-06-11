package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi: a ScalaScript `try/catch` did not catch a Java exception thrown by an
 *  extern / runtime op when the catch used a supertype pattern (`case e: Any`,
 *  `case e: Exception`, `case e: Throwable`). The synthesized exception InstanceV
 *  carries the JVM exception's simple name, and `Pat.Typed` only matched on the
 *  exact type name, so `Any`/`Throwable`/`Exception` never matched → rethrow. */
class TryCatchExternTest extends AnyFunSuite:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("catch case e: Any catches a runtime-thrown Java exception"):
    val out = captured(
      """val z = 0
        |val r = try { 1 / z; "no-throw" } catch { case e: Any => "caught" }
        |println(r)
        |""".stripMargin)
    assert(out == "caught", s"expected the Any catch to fire, got: $out")

  test("catch case e: Throwable catches a runtime-thrown Java exception"):
    val out = captured(
      """val z = 0
        |val r = try { 1 / z; "no" } catch { case e: Throwable => "caught" }
        |println(r)
        |""".stripMargin)
    assert(out == "caught", s"expected the Throwable catch to fire, got: $out")

  test("catch case e: Exception catches and exposes the message"):
    val out = captured(
      """val z = 0
        |val r = try { 1 / z; "no" } catch { case e: Exception => "caught:" + e.message }
        |println(r)
        |""".stripMargin)
    assert(out.startsWith("caught:"), s"expected the Exception catch to fire with a message, got: $out")

  test("a specific user exception type still matches precisely (no over-broadening of normal matches)"):
    // A normal (non-catch) match on a user ADT must still discriminate by type.
    val out = captured(
      """enum Shape:
        |  case Circle(r: Int)
        |  case Square(s: Int)
        |def area(x: Shape): String = x match
        |  case c: Circle => "circle"
        |  case s: Square => "square"
        |println(area(Shape.Square(3)))
        |""".stripMargin)
    assert(out == "square", s"typed match must still discriminate user types, got: $out")
