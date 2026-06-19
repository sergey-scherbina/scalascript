package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Bitwise operators on Int (Long-backed): & | ^ << >> >>> and unary ~. */
class BitwiseTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("and / or / xor"):
    run("""
      println(0xF0 & 0x0F)
      println(0xF0 | 0x0F)
      println(0xF0 ^ 0xFF)
    """) shouldBe "0\n255\n15"

  test("shifts left / right / unsigned-right"):
    run("""
      println(1 << 4)
      println(256 >> 2)
      println(255 >>> 4)
    """) shouldBe "16\n64\n15"

  test("unary bitwise not"):
    run("""
      println(~0)
      println(~5)
    """) shouldBe "-1\n-6"

  test("masking a byte (the QR/GF256 use-case)"):
    run("""
      val x = (1 << 9) ^ 0x123
      println(x & 0xFF)
    """) shouldBe "35"  // 0x123 = 291; (512 ^ 291)=803; 803 & 255 = 35

  test("combining shift, and, or with parens"):
    run("""
      println((1 << 3) & 0xFF)
      println((10 | 5))
    """) shouldBe "8\n15"

  test("bitwise inside a function body (general dispatch path)"):
    run("""
      def xorAll(xs: List[Int]): Int =
        xs.foldLeft(0)((a, b) => a ^ b)
      println(xorAll(List(1, 2, 4, 8)))
      println(xorAll(List(5, 5)))
    """) shouldBe "15\n0"
