package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Env effect, after extraction from interpreter core into `env-effect-plugin`
 *  (core-minimization). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runEnv` / `runEnvWith(map)` resolve via the lazy ServiceLoader path, exactly as in production.
 *  `runEnvWith(map) { … }` also covers the block-form SPI's MapV config-args path. */
class EnvPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("runEnvWith provides key lookup via Env.get (via plugin)"):
    captured("""
      runEnvWith(Map("FOO" -> "bar")) {
        val v = Env.get("FOO")
        v match
          case Some(s) => println(s)
          case None    => println("missing")
      }
    """) shouldBe "bar"

  test("runEnvWith Env.get returns None for missing key (via plugin)"):
    captured("""
      runEnvWith(Map("A" -> "1")) {
        val v = Env.get("MISSING")
        v match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "none"

  test("runEnvWith Env.required returns value (via plugin)"):
    captured("""
      runEnvWith(Map("HOST" -> "localhost")) {
        println(Env.required("HOST"))
      }
    """) shouldBe "localhost"

  test("runEnvWith Env.required throws on missing key (via plugin)"):
    an[Exception] should be thrownBy captured("""
      runEnvWith(Map()) {
        Env.required("MISSING")
      }
    """)

  test("Env.set mutates the local overlay (via plugin)"):
    captured("""
      runEnvWith(Map()) {
        Env.set("X", "hello")
        Env.get("X") match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "hello"

  test("runEnv reads real process env (HOME or PATH is set) (via plugin)"):
    val home = Option(java.lang.System.getenv("HOME"))
      .orElse(Option(java.lang.System.getenv("PATH")))
      .getOrElse("")
    if home.nonEmpty then
      captured("""
        runEnv {
          val h = Env.get("HOME")
          h match
            case Some(_) => println("found")
            case None    =>
              val p = Env.get("PATH")
              p match
                case Some(_) => println("found")
                case None    => println("missing")
        }
      """) shouldBe "found"
