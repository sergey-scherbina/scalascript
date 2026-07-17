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

  // ssc `Int` is 64-bit (specs/numeric-widths.md §2), but scalameta's `Lit.Int`
  // holds only 32 bits. A bare integer literal whose magnitude exceeds
  // `Int.MaxValue` is emitted with an `L` suffix so scalameta yields `Lit.Long`
  // (a 64-bit `IntV`). This fixes the `(Int.Max, Long.Max]` band (a bare decimal
  // there overflows `Lit.Int` and the block is silently dropped → `null`) and
  // makes anything past Int64 fail CLOSED (scalameta rejects `<n>L` for
  // n > Long.Max, and accepts `-9223372036854775808L`). BigInt is `BigInt(...)`/`n`.
  test("integer literal above Int.MaxValue gets an L suffix (→ 64-bit Lit.Long)"):
    pp("val x = 2147483648")             shouldBe_ "val x = 2147483648L"           // 2^31
    pp("val y = 3000000000")             shouldBe_ "val y = 3000000000L"
    pp("val z = 9223372036854775807")    shouldBe_ "val z = 9223372036854775807L"  // Long.MaxValue

  test("min64 literal: -9223372036854775808 → -...L (scalameta parses it natively)"):
    // The `-` is a separate token; the magnitude 9223372036854775808 == 2^63 gets
    // the L suffix, and scalameta accepts `-9223372036854775808L` as Long.MinValue.
    pp("val x = -9223372036854775808") shouldBe_ "val x = -9223372036854775808L"

  test("a literal past Int64 gets L too, so scalameta REJECTS it (fail closed)"):
    // The preprocessor does not decide the value is BigInt (that is `BigInt(...)`);
    // it appends L, and scalameta's tokenizer then rejects the out-of-Long literal
    // with a hard parse error rather than silently truncating or dropping it.
    pp("val x = 9223372036854775808")    shouldBe_ "val x = 9223372036854775808L"   // 2^63
    pp("val y = 100000000000000000000")  shouldBe_ "val y = 100000000000000000000L"

  test("Int.MaxValue and smaller integers are left as plain literals"):
    pp("val x = 2147483647") shouldBe_ "val x = 2147483647"  // Int.MaxValue — fits Lit.Int
    pp("val y = 42")         shouldBe_ "val y = 42"

  test("underscores in a wide integer literal are stripped before the L suffix"):
    pp("val x = 2_147_483_648") shouldBe_ "val x = 2147483648L"

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
