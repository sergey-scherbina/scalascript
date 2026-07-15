package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

class InterpBracketTest extends AnyFunSuite:
  // Regression: a `[` inside a string literal within a ${…} splice was rewritten to
  // `List(` by preprocessListLiterals because its string-skip wasn't interpolation-aware.
  test("preprocessListLiterals leaves brackets inside ${…} string literals untouched") {
    val cases = List(
      """s"c: ${xs.mkString("[", ", ", "]")}"""",
      """s"${xs.mkString("[")}"""",
      """s"a ${f("[")} b ${g("]")} c"""",
      """println(s"list: ${xs.mkString("[", ", ", "]")}")"""
    )
    for c <- cases do
      val out = Parser.preprocessListLiterals(c)
      assert(out == c, s"interpolation splice string mangled:\n  in:  $c\n  out: $out")
  }

  // Real list literals OUTSIDE interpolation must still be rewritten.
  test("preprocessListLiterals still rewrites genuine list literals") {
    assert(Parser.preprocessListLiterals("val a = [1, 2, 3]") == "val a = List(1, 2, 3)")
    assert(Parser.preprocessListLiterals("""["a", "b"]""") == """List("a", "b")""")
    // a list literal that itself contains an interpolated string with a bracket in a splice
    assert(Parser.preprocessListLiterals("""[s"${x.mkString("[")}", 2]""")
             == """List(s"${x.mkString("[")}", 2)""")
  }
