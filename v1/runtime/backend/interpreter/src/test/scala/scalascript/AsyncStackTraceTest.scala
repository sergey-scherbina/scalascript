package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

/** Phase 4: runAsync exceptions must preserve the original error type and
 *  message, not be wrapped in a generic "Async error: …" string. */
class AsyncStackTraceTest extends AnyFunSuite with Matchers:

  private def runExpectError(code: String): Throwable =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val ex  = intercept[Throwable](Interpreter(ps).run(Parser.parse(src)))
    ex

  test("runAsync: exception inside body propagates as original type"):
    val ex = runExpectError("""
      runAsync {
        throw new RuntimeException("boom from user code")
      }
    """)
    ex shouldBe a [RuntimeException]
    ex.getMessage should include ("boom from user code")

  test("runAsync: InterpretError inside body propagates as InterpretError"):
    val ex = runExpectError("""
      runAsync {
        val xs: List[Int] = List(1, 2, 3)
        xs(99)
      }
    """)
    ex shouldBe a [InterpretError]

  test("runAsync: error inside Async.async propagates to await"):
    val ex = runExpectError("""
      runAsync {
        val f = Async.async(() => throw new IllegalStateException("inner fail"))
        Async.await(f)
      }
    """)
    ex.getMessage should include ("inner fail")

  test("runAsync: error message is not double-wrapped"):
    val ex = runExpectError("""
      runAsync {
        throw new RuntimeException("original")
      }
    """)
    ex.getMessage should not include "Async error:"
    ex.getMessage should include ("original")
