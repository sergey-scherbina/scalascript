package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** exact-numerics v1.64.4 — JVM codegen conformance.
 *
 *  Generates Scala 3 source via JvmGen, compiles + runs it with scala-cli,
 *  and asserts the program output equals the interpreter's output for the
 *  same `.ssc` source.  Skipped when scala-cli is not on PATH. */
class NumericJvmConformanceTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scala\n$code\n```\n")

  private def interp(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))
    ps.flush()
    buf.toString.trim

  private def hasScalaCli: Boolean = ProcTestUtil.commandOk("scala-cli", "version")

  private def runJvm(code: String): String =
    val scala = JvmGen.generate(module(code))
    // JvmGen targets a Scala *script* (.sc) — top-level statements are allowed.
    val tmp   = java.io.File.createTempFile("ssc-num-", ".sc")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + scala).getBytes(StandardCharsets.UTF_8))
    // Keep stdout (program output) separate from stderr (scala-cli's
    // "Compiling…/Compiled" progress + warnings) so the latter doesn't pollute
    // the compared output.
    val _r  = ProcTestUtil.runCaptured(Seq("scala-cli", "run", "--server=false", tmp.getAbsolutePath))
    val out = _r.out
    val err = _r.err
    val ok  = _r.exit
    if ok != 0 then fail(s"scala-cli run failed ($ok):\n$err")
    out.trim

  /** Assert JVM output equals interpreter output for the same source. */
  private def conforms(code: String): Unit =
    assume(hasScalaCli, "scala-cli not available")
    val expected = interp(code)
    runJvm(code) shouldBe expected

  test("BigInt: construction, arithmetic, mixing with Int"):
    conforms("""
      val a = BigInt("123456789012345678901234567890")
      println(a + 1)
      println(a * BigInt("2"))
      println(BigInt("100") + 5)
      println(5 + BigInt("100"))
      println(BigInt("2").pow(64))
    """)

  test("Decimal: exact arithmetic and scale"):
    conforms("""
      val b = Decimal("1.50")
      println(b + Decimal("2.25"))
      println(Decimal("0.1") + Decimal("0.2"))
      println(b * 2)
      println(Decimal(123, 2))
    """)

  test("Decimal: setScale and divide with rounding"):
    conforms("""
      println(Decimal("1.2345").setScale(2, RoundingMode.HALF_UP))
      println(Decimal("10").divide(Decimal("3"), 4, RoundingMode.HALF_UP))
      println(Decimal("12.340").scale)
    """)

  test("conversions and comparisons"):
    conforms("""
      println(5.toBigInt + BigInt("1"))
      println(5.toDecimal + Decimal("0.5"))
      println(BigInt("100").toDecimal)
      println(Decimal("1.5") < Decimal("1.6"))
      println(Decimal("9.99").toBigInt)
    """)
