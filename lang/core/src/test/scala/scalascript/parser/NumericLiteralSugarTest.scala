package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** exact-numerics v1.64.7 — the numeric-literal-sugar preprocessor. */
class NumericLiteralSugarTest extends AnyFunSuite:

  private def pp(s: String): String = Parser.preprocessNumericLiterals(s)

  test("`n` suffix → BigInt(\"…\")"):
    pp("val x = 123n") shouldBe_ """val x = BigInt("123")"""

  test("`m` suffix → Decimal(\"…\") (with and without fraction)"):
    pp("val x = 12.34m") shouldBe_ """val x = Decimal("12.34")"""
    pp("val y = 5m")     shouldBe_ """val y = Decimal("5")"""

  test("underscores are stripped from the emitted argument"):
    pp("val x = 1_000_000n") shouldBe_ """val x = BigInt("1000000")"""

  test("oversized integer literal auto-promotes to BigInt"):
    pp("val x = 9223372036854775808")    shouldBe_ """val x = BigInt("9223372036854775808")"""
    pp("val y = 100000000000000000000")  shouldBe_ """val y = BigInt("100000000000000000000")"""

  test("Long.MaxValue and smaller integers are left as plain literals"):
    pp("val x = 9223372036854775807") shouldBe_ "val x = 9223372036854775807"
    pp("val y = 42")                  shouldBe_ "val y = 42"

  test("typed literals (L/f/d) are left untouched"):
    pp("val a = 100L"); pp("val b = 1.5f"); pp("val c = 2.0d")
    pp("val a = 100L") shouldBe_ "val a = 100L"
    pp("val b = 1.5f") shouldBe_ "val b = 1.5f"

  test("`1.toString` is not consumed as a decimal"):
    pp("val s = 1.toString") shouldBe_ "val s = 1.toString"

  test("tuple access `t._1` is untouched"):
    pp("val a = t._1 + t._2") shouldBe_ "val a = t._1 + t._2"

  test("identifiers containing digits are untouched"):
    pp("val base64 = decode(x1, y2)") shouldBe_ "val base64 = decode(x1, y2)"

  test("hex / binary literals pass through"):
    pp("val h = 0xFF")   shouldBe_ "val h = 0xFF"
    pp("val b = 0b1010") shouldBe_ "val b = 0b1010"

  test("plain doubles and exponents are left alone"):
    pp("val d = 2.0")     shouldBe_ "val d = 2.0"
    pp("val e = 1.5e3")   shouldBe_ "val e = 1.5e3"

  test("literal text inside strings is NOT rewritten"):
    pp("""val s = "price is 123n dollars"""") shouldBe_ """val s = "price is 123n dollars""""

  test("text inside line comments is NOT rewritten"):
    pp("val x = 1 // see 999999999999999999999 and 5m") shouldBe_
      "val x = 1 // see 999999999999999999999 and 5m"

  test("multiple literals on one line"):
    pp("f(1n, 2.5m, 3)") shouldBe_ """f(BigInt("1"), Decimal("2.5"), 3)"""

  extension (actual: String)
    private def shouldBe_(expected: String): Unit =
      assert(actual == expected, s"\n  expected: $expected\n  actual:   $actual")
