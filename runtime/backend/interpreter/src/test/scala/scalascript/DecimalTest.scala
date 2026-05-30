package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** exact-numerics v1.64.2 — arbitrary-precision Decimal on the interpreter. */
class DecimalTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  private def runErr(code: String): String =
    try { run(code); "NO-ERROR" }
    catch case t: Throwable => Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

  test("Decimal from string preserves scale on display"):
    run("""println(Decimal("12.3400"))""") shouldBe "12.3400"

  test("Decimal(unscaled, scale)"):
    run("""println(Decimal(123, 2))""") shouldBe "1.23"

  test("exact addition has no binary-float error (0.1 + 0.2 == 0.3)"):
    run("""
      val r = Decimal("0.1") + Decimal("0.2")
      println(r)
      println(r == Decimal("0.3"))
    """) shouldBe "0.3\ntrue"

  test("multiplication keeps full precision"):
    run("""println(Decimal("1.10") * Decimal("1.10"))""") shouldBe "1.2100"

  test("value equality ignores scale (1.0 == 1.00)"):
    run("""
      println(Decimal("1.0") == Decimal("1.00"))
      println(Decimal("2.50") == Decimal("2.5"))
    """) shouldBe "true\ntrue"

  test("Decimal mixes with Int and BigInt, widening to Decimal"):
    run("""
      println(Decimal("1.50") + 2)
      println(3 - Decimal("0.5"))
      println(Decimal("2.5") * BigInt("4"))
    """) shouldBe "3.50\n2.5\n10.0"

  test("comparisons across Decimal / Int"):
    run("""
      println(Decimal("1.5") < Decimal("1.6"))
      println(Decimal("2.0") > 1)
      println(Decimal("2.00") <= 2)
      println(Decimal("3") >= Decimal("3.0"))
    """) shouldBe "true\ntrue\ntrue\ntrue"

  test("setScale with rounding modes"):
    run("""
      println(Decimal("1.2345").setScale(2, RoundingMode.HALF_UP))
      println(Decimal("1.2355").setScale(2, RoundingMode.HALF_EVEN))
      println(Decimal("1.999").setScale(0, RoundingMode.FLOOR))
      println(Decimal("1.001").setScale(0, RoundingMode.CEILING))
    """) shouldBe "1.23\n1.24\n1\n2"

  test("divide with scale + rounding mode (non-terminating)"):
    run("""println(Decimal("10").divide(Decimal("3"), 4, RoundingMode.HALF_UP))""") shouldBe "3.3333"

  test("Decimal methods: abs, negate, signum, scale, toBigInt, pow"):
    run("""
      println(Decimal("-3.14").abs)
      println(Decimal("5.5").negate)
      println(Decimal("-2.0").signum)
      println(Decimal("12.340").scale)
      println(Decimal("9.99").toBigInt)
      println(Decimal("1.1").pow(2))
    """) shouldBe "3.14\n-5.5\n-1\n3\n9\n1.21"

  test("mixing Decimal and Double is a deliberate error"):
    runErr("""println(Decimal("1.5") + 2.0)""") should include("Decimal and Double")

  test("building Decimal from Double is rejected"):
    runErr("""println(Decimal(1.5))""") should include("inexact")

  test("Int.toDecimal and BigInt.toDecimal"):
    run("""
      println(5.toDecimal + Decimal("0.5"))
      println(BigInt("100").toDecimal)
    """) shouldBe "5.5\n100"

  test("Decimal round-trips through the value serializer preserving scale"):
    val v = scalascript.interpreter.Value.DecimalV(BigDecimal("12.3400"))
    val json = scalascript.interpreter.ValueSerializer.serialize(v)
    val back = scalascript.interpreter.ValueSerializer.deserialize(json)
    back shouldBe v
    scalascript.interpreter.Value.show(back) shouldBe "12.3400"
