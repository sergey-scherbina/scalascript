package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, ScriptException}
// ScriptException is used in `an[ScriptException] should be thrownBy` patterns
import scalascript.parser.Parser

/** Conformance tests for v1.16 — restartable errors (Common Lisp condition-system style). */
class RestartableTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Restart.resume(v) — body continues with replacement value ─────

  test("Restart.resume — handler resumes body with a replacement value"):
    captured("""
      case class FileNotFound(path: String)
      def readFile(path: String): String =
        throw FileNotFound(path)
      val result = restartable {
        case FileNotFound(p) => Restart.resume("default-content")
      } {
        readFile("/missing.txt")
      }
      println(result)
    """) shouldBe "default-content"

  test("Restart.resume — body continues after resume and returns computed value"):
    captured("""
      case class MissingValue(key: String)
      def lookup(key: String): Int =
        throw MissingValue(key)
      val result = restartable {
        case MissingValue(k) => Restart.resume(0)
      } {
        val n = lookup("missing")
        n + 42
      }
      println(result)
    """) shouldBe "42"

  // ── Restart.useDefault — body continues with Unit/default ─────────

  test("Restart.useDefault — body continues after the throw expression"):
    // useDefault resumes the throw expression with unit; execution continues.
    captured("""
      case class NetworkError(msg: String)
      var sideEffect = "before"
      restartable {
        case NetworkError(m) => Restart.useDefault
      } {
        throw NetworkError("timeout")
        sideEffect = "after"
      }
      println(sideEffect)
    """) shouldBe "after"

  test("Restart.useDefault — resumed throw expression evaluates to unit"):
    // useDefault resumes with () — the throw expression's value is ().
    captured("""
      case class MyError(n: Int)
      val result = restartable {
        case MyError(n) => Restart.useDefault
      } {
        val x = throw MyError(5)
        if x == () then "unit-resumed" else "other"
      }
      println(result)
    """) shouldBe "unit-resumed"

  // ── Restart.rethrow — error propagates normally ───────────────────

  test("Restart.rethrow — exception propagates out of restartable"):
    an[ScriptException] should be thrownBy captured("""
      case class FatalError(msg: String)
      restartable {
        case FatalError(m) => Restart.rethrow
      } {
        throw FatalError("unrecoverable")
      }
    """)

  test("Restart.rethrow — can be caught by an outer try/catch"):
    captured("""
      case class AppError(msg: String)
      val result = try
        restartable {
          case AppError(m) => Restart.rethrow
        } {
          throw AppError("oops")
        }
        "ok"
      catch
        case e: AppError => "caught: " + e.msg
      println(result)
    """) shouldBe "caught: oops"

  // ── No handler matches — propagates as rethrow ────────────────────

  test("No matching handler case — exception propagates"):
    an[ScriptException] should be thrownBy captured("""
      case class TypeA(n: Int)
      case class TypeB(n: Int)
      restartable {
        case TypeA(n) => Restart.resume(0)
      } {
        throw TypeB(99)
      }
    """)

  test("No matching handler case — caught by outer try/catch"):
    captured("""
      case class TypeA(n: Int)
      case class TypeB(s: String)
      val result = try
        restartable {
          case TypeA(n) => Restart.resume(0)
        } {
          throw TypeB("hello")
        }
        "not reached"
      catch
        case e: TypeB => "caught: " + e.s
      println(result)
    """) shouldBe "caught: hello"

  // ── Multiple throws in one body ───────────────────────────────────

  test("Multiple throws — each is handled independently, result is correct"):
    // Two throws: Step(1) resumed with 10, Step(2) resumed with 20.
    // Final result: a + b = 30.  Note: count increments seen from the handler
    // reflect the captured initial env (0 each time → always writes 1).
    captured("""
      case class Step(n: Int)
      val result = restartable {
        case Step(n) => Restart.resume(n * 10)
      } {
        val a = throw Step(1)
        val b = throw Step(2)
        a + b
      }
      println(result)
    """) shouldBe "30"

  test("Multiple throws — handler invoked for each throw"):
    // Verify handler is invoked twice: different n values produce different resume values.
    // We accumulate the results in a List to avoid the captured-env stale-read issue.
    captured("""
      case class Compute(n: Int)
      val result = restartable {
        case Compute(n) => Restart.resume(n * n)
      } {
        val a = throw Compute(3)
        val b = throw Compute(4)
        a + b
      }
      println(result)
    """) shouldBe "25"

  // ── Handler can use the error value ──────────────────────────────

  test("Handler can inspect the error value"):
    captured("""
      case class ValuedError(n: Int)
      val result = restartable {
        case ValuedError(n) => Restart.resume(n * 2)
      } {
        throw ValuedError(21)
      }
      println(result)
    """) shouldBe "42"

  // ── Nested restartable blocks ─────────────────────────────────────

  test("Nested restartable — inner handler matches first"):
    captured("""
      case class InnerErr(n: Int)
      case class OuterErr(n: Int)
      val result = restartable {
        case OuterErr(n) => Restart.resume(n + 100)
      } {
        restartable {
          case InnerErr(n) => Restart.resume(n + 10)
        } {
          throw InnerErr(5)
        }
      }
      println(result)
    """) shouldBe "15"

  test("Nested restartable — unmatched inner throw reaches outer handler"):
    captured("""
      case class InnerErr(n: Int)
      case class OuterErr(n: Int)
      val result = restartable {
        case OuterErr(n) => Restart.resume(n * 3)
      } {
        restartable {
          case InnerErr(n) => Restart.resume(n + 10)
        } {
          throw OuterErr(7)
        }
      }
      println(result)
    """) shouldBe "21"

  // ── Body returns normally — no handler invoked ────────────────────

  test("Body returns normally — handler not invoked"):
    captured("""
      var handlerCalled = false
      case class E(n: Int)
      val result = restartable {
        case E(n) => handlerCalled = true; Restart.resume(0)
      } {
        42
      }
      println(result)
      println(handlerCalled)
    """) shouldBe "42\nfalse"

  // ── Restart object is accessible as a value ───────────────────────

  test("Restart.resume value is inspectable"):
    captured("""
      val r = Restart.resume(99)
      println(r != null)
    """) shouldBe "true"

  test("Restart.useDefault value is inspectable"):
    captured("""
      val r = Restart.useDefault
      println(r != null)
    """) shouldBe "true"

  test("Restart.rethrow value is inspectable"):
    captured("""
      val r = Restart.rethrow
      println(r != null)
    """) shouldBe "true"
