package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** exact-numerics v1.64.7 — literal sugar end-to-end: `123n`/`12.34m` flow
 *  through parse → eval and produce BigInt/Decimal, identically across
 *  interpreter, JVM, and JS.
 *
 *  Note (2026-07-17, int-literal-failopen): a BARE oversized integer literal is
 *  no longer auto-promoted to BigInt — ssc `Int` is 64-bit
 *  (specs/numeric-widths.md §2) and a literal past Int64 now FAILS CLOSED (a loud
 *  parse error) instead of silently changing type. Arbitrary precision is the
 *  explicit `n` suffix or `BigInt(...)`, which is what these tests use. */
class NumericSugarE2ETest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def interp(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))
    ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    ProcTestUtil.runOrThrow(cmd*)

  test("interpreter: n / m suffixes and oversized ints"):
    interp("""
      println(2n.pow(100))
      println(12.34m + 1)
      println(9223372036854775808n + 1)
      println(0.1m + 0.2m)
      println(1_000_000_000_000_000_000_000n * 2)
    """) shouldBe
      "1267650600228229401496703205376\n13.34\n9223372036854775809\n0.3\n2000000000000000000000"

  test("n / m produce the exact BigInt / Decimal types"):
    interp("""
      println(5n + 5n)
      println(2.50m * 2)
      println(2.50m == Decimal("2.5"))
    """) shouldBe "10\n5.00\ntrue"

  test("JVM conformance for literal sugar"):
    assume(has("scala-cli"), "scala-cli not available")
    val code = """
      println(2n.pow(64))
      println(12.34m + 1)
      println(9223372036854775808n + 1)
    """
    val tmp = java.io.File.createTempFile("ssc-sugar-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module(code))).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe interp(code)

  test("JS conformance for literal sugar"):
    assume(has("node"), "node not available")
    val code = """
      println(2n.pow(64))
      println(12.34m + 1)
      println(0.1m + 0.2m)
    """
    val tmp = java.io.File.createTempFile("ssc-sugar-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath,
      (rt + "\n" + JsGen.generate(module(code))).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp(code)
