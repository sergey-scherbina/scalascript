package scalascript.uniml.dialect.markdown

import org.scalatest.funsuite.AnyFunSuite

/** Proves the portable `MdChars` classification (a generated BMP range table +
  * an enumerated whitespace set) is EXACTLY equivalent to the
  * `java.lang.Character`-based definitions it replaced, for every BMP code unit.
  *
  * JVM-only: `Character.getType`/`isSpaceChar` here is the oracle the table was
  * generated from, so this test is what makes the table trustworthy. (On
  * Scala.js `MdChars` uses the same table, and the behavioural markdown suite
  * covers it there.) */
final class MdCharsParitySpec extends AnyFunSuite:

  private def oraclePunct(c: Char): Boolean =
    MdChars.isAsciiPunctuation(c) || {
      val t = Character.getType(c)
      t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION ||
      t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION ||
      t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION ||
      t == Character.OTHER_PUNCTUATION || t == Character.MATH_SYMBOL ||
      t == Character.CURRENCY_SYMBOL || t == Character.MODIFIER_SYMBOL || t == Character.OTHER_SYMBOL
    }

  private def oracleWhitespace(c: Char): Boolean =
    MdChars.isAsciiWhitespace(c) || Character.isSpaceChar(c)

  private def firstMismatch(oracle: Char => Boolean, actual: Char => Boolean): Int =
    var c = 0
    var mismatch = -1
    while c <= 0xFFFF && mismatch < 0 do
      if actual(c.toChar) != oracle(c.toChar) then mismatch = c
      c += 1
    mismatch

  test("isPunctuation matches Character.getType for every BMP code unit") {
    val m = firstMismatch(oraclePunct, MdChars.isPunctuation)
    assert(m == -1, f"isPunctuation mismatch at U+$m%04X")
  }

  test("isUnicodeWhitespace matches Character.isSpaceChar for every BMP code unit") {
    val m = firstMismatch(oracleWhitespace, MdChars.isUnicodeWhitespace)
    assert(m == -1, f"isUnicodeWhitespace mismatch at U+$m%04X")
  }
