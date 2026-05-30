package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** exact-numerics v1.64.1 — arbitrary-precision BigInt on the interpreter. */
class BigIntTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("BigInt from String exceeds Long range exactly"):
    run("""println(BigInt("123456789012345678901234567890"))""") shouldBe
      "123456789012345678901234567890"

  test("BigInt multiplication stays exact past Long.MaxValue"):
    // 10^30, well beyond 9.2e18
    run("""
      val a = BigInt("1000000000000000")
      println(a * a)
    """) shouldBe "1000000000000000000000000000000"

  test("BigInt addition / subtraction / division / modulo"):
    run("""
      val a = BigInt("100000000000000000000")
      val b = BigInt("7")
      println(a + b)
      println(a - b)
      println(a / b)
      println(a % b)
    """) shouldBe
      "100000000000000000007\n99999999999999999993\n14285714285714285714\n2"

  test("Int and BigInt mix widens to BigInt"):
    run("""
      val big = BigInt("9223372036854775808")  // Long.MaxValue + 1
      println(big + 1)
      println(1 + big)
      println(big * 2)
    """) shouldBe
      "9223372036854775809\n9223372036854775809\n18446744073709551616"

  test("BigInt comparisons including mix with Int"):
    run("""
      val a = BigInt("100")
      val b = BigInt("200")
      println(a < b)
      println(b > a)
      println(a <= BigInt("100"))
      println(a >= 100)
      println(a < 101)
    """) shouldBe "true\ntrue\ntrue\ntrue\ntrue"

  test("BigInt equality, including numeric equality with Int"):
    run("""
      println(BigInt("5") == BigInt("5"))
      println(BigInt("5") == 5)
      println(5 == BigInt("5"))
      println(BigInt("5") != BigInt("6"))
    """) shouldBe "true\ntrue\ntrue\ntrue"

  test("BigInt methods: pow, abs, gcd, signum, isEven, isProbablePrime"):
    run("""
      println(BigInt("2").pow(100))
      println(BigInt("-42").abs)
      println(BigInt("48").gcd(BigInt("36")))
      println(BigInt("-7").signum)
      println(BigInt("10").isEven)
      println(BigInt("7").isProbablePrime)
    """) shouldBe
      "1267650600228229401496703205376\n42\n12\n-1\ntrue\ntrue"

  test("conversions: Int.toBigInt and BigInt.toInt/toLong"):
    run("""
      println(42.toBigInt)
      println(BigInt("9999999999").toLong)
      println(BigInt("100").toInt + 1)
    """) shouldBe "42\n9999999999\n101"

  test("BigInt round-trips through the value serializer"):
    val n = "123456789012345678901234567890"
    val v = scalascript.interpreter.Value.BigIntV(BigInt(n))
    val json = scalascript.interpreter.ValueSerializer.serialize(v)
    scalascript.interpreter.ValueSerializer.deserialize(json) shouldBe v
