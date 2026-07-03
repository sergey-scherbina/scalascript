package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** exact-numerics v1.64.5 — JS codegen conformance.
 *
 *  Generates JS via JsGen, runs it with node, and asserts the output equals
 *  the interpreter's output for the same `.ssc` source.  Skipped when node is
 *  not on PATH.  Uses the `scalascript` fenced dialect (the JS-lowered one). */
class NumericJsConformanceTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // Interpreter reference output (interpreter lowers `scalascript` blocks too).
  private def interp(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))
    ps.flush()
    buf.toString.trim

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val js  = JsRuntime + "\n" + JsGen.generate(module(code))
    val tmp = java.io.File.createTempFile("ssc-num-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val _r  = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    val out = _r.out
    val err = _r.err
    val ok  = _r.exit
    if ok != 0 then fail(s"node run failed ($ok):\n$err")
    out.trim

  private def conforms(code: String): Unit =
    assume(hasNode, "node not available")
    runJs(code) shouldBe interp(code)

  test("BigInt: construction, arithmetic, mixing with Int"):
    conforms("""
      val a = BigInt("123456789012345678901234567890")
      println(a + 1)
      println(a * BigInt("2"))
      println(BigInt("100") + 5)
      println(5 + BigInt("100"))
      println(BigInt("2").pow(64))
      println(BigInt("48").gcd(BigInt("36")))
    """)

  test("BigInt comparisons and methods"):
    conforms("""
      println(BigInt("100") < BigInt("200"))
      println(BigInt("100") >= 100)
      println(BigInt("-7").abs)
      println(BigInt("10").isEven)
      println(BigInt("7").signum)
    """)

  test("Decimal: exact arithmetic, no binary-float error"):
    conforms("""
      val b = Decimal("1.50")
      println(b + Decimal("2.25"))
      println(Decimal("0.1") + Decimal("0.2"))
      println(b * 2)
      println(Decimal("1.10") * Decimal("1.10"))
      println(Decimal(123, 2))
      println(Decimal("10.00") - Decimal("3.5"))
    """)

  test("Decimal: value equality ignores scale; comparisons"):
    conforms("""
      println(Decimal("1.0") == Decimal("1.00"))
      println(Decimal("1.5") < Decimal("1.6"))
      println(Decimal("2.00") <= 2)
      println(Decimal("3") >= Decimal("3.0"))
    """)

  test("Decimal: setScale and divide across all rounding modes"):
    conforms("""
      println(Decimal("1.2345").setScale(2, RoundingMode.HALF_UP))
      println(Decimal("1.2355").setScale(2, RoundingMode.HALF_EVEN))
      println(Decimal("1.999").setScale(0, RoundingMode.FLOOR))
      println(Decimal("1.001").setScale(0, RoundingMode.CEILING))
      println(Decimal("-1.5").setScale(0, RoundingMode.HALF_UP))
      println(Decimal("10").divide(Decimal("3"), 4, RoundingMode.HALF_UP))
      println(Decimal("1").divide(Decimal("8"), 5, RoundingMode.HALF_EVEN))
    """)

  test("Decimal: scale/precision/abs/toBigInt; conversions"):
    conforms("""
      println(Decimal("12.340").scale)
      println(Decimal("-3.14").abs)
      println(Decimal("9.99").toBigInt)
      println(5.toBigInt + BigInt("1"))
      println(5.toDecimal + Decimal("0.5"))
      println(BigInt("100").toDecimal)
    """)
