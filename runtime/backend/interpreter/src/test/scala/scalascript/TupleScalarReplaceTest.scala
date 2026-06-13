package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** jit-loop-tuple: a `while` body whose only tuple use is a leading
 *  `val t = <static tuple>` accessed by `t._K` is scalar-replaced (the tuple is
 *  inlined away) so the loop compiles to the Long-while JIT. tupleMonoid 13.3 → 0.005
 *  ms/op. These pin that the optimisation is result-identical to the tree-walk and
 *  that the soundness guards keep non-scalar-replaceable shapes correct. */
class TupleScalarReplaceTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# T\n\n```scalascript\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("tuple-monoid concat ++ accumulation (JIT path), full scale"):
    // sum_{i=0}^{999} (i + (i+1) + (i+2) + (i+3)) = sum(4i+6) = 2_004_000
    run("""
      var i = 0
      var s = 0
      while i < 1000 do
        val t = (i, i + 1) ++ (i + 2, i + 3)
        s = s + t._1 + t._2 + t._3 + t._4
        i = i + 1
      println(s)
    """) shouldBe "2004000"

  test("plain 2-tuple, accessed by _1/_2 (no ++)"):
    run("""
      var i = 0
      var s = 0
      while i < 10 do
        val p = (i, i * 2)
        s = s + p._2 - p._1
        i = i + 1
      println(s)
    """) shouldBe "45"   // sum_{0..9} (2i - i) = sum i = 45

  test("nested ++ (three pairs → 6-tuple)"):
    run("""
      var i = 0
      var s = 0
      while i < 5 do
        val t = (i, i) ++ (i, i) ++ (i, i)
        s = s + t._1 + t._6
        i = i + 1
      println(s)
    """) shouldBe "20"   // sum_{0..4} (i + i) = 2*10 = 20

  test("accessors out of source order"):
    run("""
      var i = 0
      var s = 0
      while i < 4 do
        val t = (i, i + 1) ++ (i + 2, i + 3)
        s = s + t._4 - t._1
        i = i + 1
      println(s)
    """) shouldBe "12"   // each iter: (i+3) - i = 3; ×4 = 12

  test("SOUNDNESS: t used non-_K (passed whole) stays correct (no scalar-replace)"):
    run("""
      def firstOf(p: (Int, Int)): Int = p._1
      var i = 0
      var s = 0
      while i < 5 do
        val t = (i, i + 1)
        s = s + firstOf(t)
        i = i + 1
      println(s)
    """) shouldBe "10"   // sum_{0..4} i = 10

  test("SOUNDNESS: single-arg ++ with a tuple-valued var stays correct"):
    run("""
      val pair = (100, 200)
      var i = 0
      var s = 0
      while i < 3 do
        val t = (i, i) ++ pair
        s = s + t._3 + t._4
        i = i + 1
      println(s)
    """) shouldBe "900"  // each iter: 100 + 200 = 300; ×3 = 900

  test("result tuple still materialisable when bound and returned"):
    run("""
      val t = (1, 2) ++ (3, 4)
      println(t._1 + t._2 + t._3 + t._4)
    """) shouldBe "10"
