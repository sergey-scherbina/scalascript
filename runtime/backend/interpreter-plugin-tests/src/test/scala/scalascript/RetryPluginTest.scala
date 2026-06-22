package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Retry effect, after extraction from interpreter core into `retry-effect-plugin`
 *  (core-minimization). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runRetry` / `runRetryNoSleep` resolve via the lazy ServiceLoader path, exactly as in
 *  production. `Retry.attempt(n, delayMs)(thunk)` re-invokes the thunk via `BlockContext.applyFn`. */
class RetryPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("Retry.attempt returns value on immediate success (via plugin)"):
    captured("""
      runRetryNoSleep {
        val r = Retry.attempt(3, 0) { () => 42 }
        println(r)
      }
    """) shouldBe "42"

  test("Retry.attempt rethrows after max attempts exhausted (via plugin)"):
    an[Exception] should be thrownBy captured("""
      runRetryNoSleep {
        Retry.attempt(2, 0) { () =>
          throw RuntimeException("always fails")
        }
      }
    """)

  test("Retry.attempt n=0 runs exactly once even if it succeeds (via plugin)"):
    captured("""
      runRetryNoSleep {
        val r = Retry.attempt(0, 0) { () => "once" }
        println(r)
      }
    """) shouldBe "once"
