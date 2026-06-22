package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Clock effect, after extraction from interpreter core into `clock-effect-plugin`
 *  (core-minimization). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runClock` / `runClockAt(t0)` resolve via the lazy ServiceLoader path, exactly as in production.
 *  `runClockAt(t0) { … }` also covers the block-form SPI's config-args path. */
class ClockPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("runClockAt freezes Clock.now at the given epoch ms (via plugin)"):
    captured("""
      runClockAt(1000000) {
        println(Clock.now())
        println(Clock.now())
      }
    """) shouldBe "1000000\n1000000"

  test("runClockAt freezes Clock.nowIso (via plugin)"):
    captured("""
      runClockAt(0) {
        println(Clock.nowIso())
      }
    """) shouldBe "1970-01-01T00:00:00Z"

  test("runClockAt Clock.sleep is a no-op (does not delay) (via plugin)"):
    val start = java.lang.System.currentTimeMillis()
    captured("""
      runClockAt(0) {
        Clock.sleep(10000)  // would be 10 s if real
        println("done")
      }
    """) shouldBe "done"
    val elapsed = java.lang.System.currentTimeMillis() - start
    elapsed should be < 2000L

  test("runClock returns actual Clock.now (close to wall time) (via plugin)"):
    val before = java.lang.System.currentTimeMillis()
    val out = captured("""
      runClock {
        println(Clock.now())
      }
    """)
    val after = java.lang.System.currentTimeMillis()
    val t = out.toLong
    t should be >= before
    t should be <= after + 100L
