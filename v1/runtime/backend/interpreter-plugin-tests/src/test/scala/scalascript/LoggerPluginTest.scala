package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Logger effect, after extraction from interpreter core into `logger-effect-plugin`
 *  (core-minimization). These are the four cases formerly in `StdEffectsTest`, now run with NO
 *  explicit `installPlugins` — so `runLogger` resolves purely via the lazy ServiceLoader path
 *  (the plugin is on this module's classpath through `allPlugins`), exactly as in production. */
class LoggerPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("runLogger writes [LEVEL] lines to output (via plugin)"):
    captured("""
      runLogger {
        Logger.info("hello")
        Logger.warn("watch out")
        Logger.error("boom")
        Logger.debug("trace")
      }
    """) shouldBe "[INFO] hello\n[WARN] watch out\n[ERROR] boom\n[DEBUG] trace"

  test("runLoggerJson writes newline-delimited JSON (via plugin)"):
    captured("""
      runLoggerJson {
        Logger.info("hi")
        Logger.error("oops")
      }
    """) shouldBe """{"level":"info","msg":"hi"}""" + "\n" + """{"level":"error","msg":"oops"}"""

  test("runLoggerToList returns (result, log) pair (via plugin)"):
    captured("""
      val (result, log) = runLoggerToList {
        Logger.info("a")
        Logger.warn("b")
        42
      }
      println(result)
      log.foreach { case (level, msg) => println(level + ":" + msg) }
    """) shouldBe "42\ninfo:a\nwarn:b"

  test("Logger body result is returned by runLogger (via plugin)"):
    captured("""
      val x = runLogger { Logger.info("side"); 99 }
      println(x)
    """) shouldBe "[INFO] side\n99"
