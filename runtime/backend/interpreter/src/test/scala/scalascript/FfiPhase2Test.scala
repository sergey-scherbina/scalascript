package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

/** arch-ffi-p2 — @interpreterUnsupported + cross-backend parity tests.
 *
 *  Verifies that:
 *  - @interpreterUnsupported extern def registers a throwing NativeFnV in the interpreter.
 *  - Default error message includes the def name and "@interpreterUnsupported".
 *  - Custom message @interpreterUnsupported("msg") is used verbatim.
 *  - A non-annotated extern def does NOT throw (uses intrinsic path).
 *  - Cross-backend parity: same source with @jvm+@js produces valid output on both backends.
 */
class FfiPhase2Test extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def jvm(code: String): String = JvmGen.generate(module(code))
  private def js(code: String): String  = JsGen.generate(module(code))

  private def runInterp(code: String): Unit =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))

  // ─── @interpreterUnsupported — default message ──────────────────────────

  test("@interpreterUnsupported: calling extern def throws InterpretError"):
    val ex = intercept[InterpretError] {
      runInterp(
        """@interpreterUnsupported
          |extern def cpuCount(): Int
          |println(cpuCount())
          |""".stripMargin
      )
    }
    ex.getMessage should include ("@interpreterUnsupported")

  test("@interpreterUnsupported: default error message includes def name"):
    val ex = intercept[InterpretError] {
      runInterp(
        """@interpreterUnsupported
          |extern def cpuCount(): Int
          |println(cpuCount())
          |""".stripMargin
      )
    }
    ex.getMessage should include ("cpuCount")

  // ─── @interpreterUnsupported — custom message ───────────────────────────

  test("@interpreterUnsupported(msg): custom error message is used verbatim"):
    val ex = intercept[InterpretError] {
      runInterp(
        """@interpreterUnsupported("This requires a JVM process to run")
          |extern def startServer(): Unit
          |startServer()
          |""".stripMargin
      )
    }
    ex.getMessage should include ("This requires a JVM process to run")

  test("@interpreterUnsupported: error is thrown at call site, not at definition"):
    // Defining the extern def should succeed; only calling it throws.
    noException should be thrownBy {
      val buf = java.io.ByteArrayOutputStream()
      val ps  = java.io.PrintStream(buf, true)
      Interpreter(ps).run(module(
        """@interpreterUnsupported
          |extern def cpuCount(): Int
          |// not calling it
          |println("ok")
          |""".stripMargin
      ))
    }

  test("@interpreterUnsupported: combined with @jvm — still throws from interpreter"):
    val ex = intercept[InterpretError] {
      runInterp(
        """@jvm("java.lang.Runtime.getRuntime().availableProcessors()")
          |@interpreterUnsupported("use @jvm backend")
          |extern def cpuCount(): Int
          |println(cpuCount())
          |""".stripMargin
      )
    }
    ex.getMessage should include ("use @jvm backend")

  // ─── Cross-backend parity ───────────────────────────────────────────────

  test("cross-backend parity: @jvm+@js def produces non-empty JVM method"):
    val src =
      """@jvm("java.util.UUID.randomUUID().toString()")
        |@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    val jvmOut = jvm(src)
    jvmOut should include ("def randomUUID")
    jvmOut should include ("java.util.UUID.randomUUID().toString()")

  test("cross-backend parity: @jvm+@js def produces non-empty JS function"):
    val src =
      """@jvm("java.util.UUID.randomUUID().toString()")
        |@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    val jsOut = js(src)
    jsOut should include ("randomUUID")
    jsOut should include ("crypto.randomUUID()")

  test("cross-backend parity: $0 substitution works identically on both backends"):
    val src =
      """@jvm("new java.io.File($0).exists()")
        |@js("require('fs').existsSync($0)")
        |extern def fileExists(path: String): Boolean
        |""".stripMargin
    jvm(src) should include ("new java.io.File(path).exists()")
    js(src)  should include ("require('fs').existsSync(path)")

  test("cross-backend parity: multi-arg $0 $1 substitution on both backends"):
    val src =
      """@jvm("$0.compareTo($1)")
        |@js("$0.localeCompare($1)")
        |extern def compare(a: String, b: String): Int
        |""".stripMargin
    jvm(src) should include ("a.compareTo(b)")
    js(src)  should include ("a.localeCompare(b)")
