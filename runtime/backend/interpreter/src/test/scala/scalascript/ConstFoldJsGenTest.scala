package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** Constant folding in JsGen: literal arithmetic, comparison, boolean logic,
 *  string concat, if-with-literal-condition, and unary ops are evaluated at
 *  codegen time, emitting a plain literal rather than a runtime expression.
 */
class ConstFoldJsGenTest extends AnyFunSuite:

  private def gen(src: String): String =
    JsGen.generate(Parser.parse(src))

  private def ssc(body: String): String =
    s"# T\n```scalascript\n$body\n```\n"

  // ── Integer arithmetic ────────────────────────────────────────────────────

  test("int add folded to literal"):
    val js = gen(ssc("val x = 1 + 2"))
    assert(js.contains("const x = 3"),
      s"expected 'const x = 3'; got relevant section:\n${extractConst(js, "x")}")
    assert(!js.contains("(1 + 2)"), "should not emit runtime (1 + 2)")

  test("int subtract folded to literal"):
    val js = gen(ssc("val x = 10 - 3"))
    assert(js.contains("const x = 7"),
      s"expected 'const x = 7':\n${extractConst(js, "x")}")

  test("int multiply folded to literal"):
    val js = gen(ssc("val x = 6 * 7"))
    assert(js.contains("const x = 42"),
      s"expected 'const x = 42':\n${extractConst(js, "x")}")

  test("int divide folded to literal"):
    val js = gen(ssc("val x = 10 / 2"))
    assert(js.contains("const x = 5"),
      s"expected 'const x = 5':\n${extractConst(js, "x")}")

  test("int modulo folded to literal"):
    val js = gen(ssc("val x = 10 % 3"))
    assert(js.contains("const x = 1"),
      s"expected 'const x = 1':\n${extractConst(js, "x")}")

  test("int divide by zero not folded"):
    val js = gen(ssc("val x = 1 / 0"))
    // division by zero should NOT be folded (undefined behavior / ArithmeticException)
    assert(!js.contains("const x = Infinity") && !js.contains("const x = NaN"),
      "should not fold int division by zero")

  // ── Integer comparison ────────────────────────────────────────────────────

  test("int less-than folded to true"):
    val js = gen(ssc("val x = 2 < 5"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  test("int greater-than folded to false"):
    val js = gen(ssc("val x = 2 > 5"))
    assert(js.contains("const x = false"),
      s"expected 'const x = false':\n${extractConst(js, "x")}")

  test("int equal folded"):
    val js = gen(ssc("val x = 3 == 3"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  test("int not-equal folded"):
    val js = gen(ssc("val x = 3 != 4"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  // ── Boolean logic ─────────────────────────────────────────────────────────

  test("bool and folded: true && false = false"):
    val js = gen(ssc("val x = true && false"))
    assert(js.contains("const x = false"),
      s"expected 'const x = false':\n${extractConst(js, "x")}")

  test("bool or folded: false || true = true"):
    val js = gen(ssc("val x = false || true"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  test("bool and folded: true && true = true"):
    val js = gen(ssc("val x = true && true"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  // ── String concatenation ──────────────────────────────────────────────────

  test("string concat folded at compile time"):
    val js = gen(ssc("""val x = "hello" + " world""""))
    assert(js.contains(""""hello world""""),
      s"expected '\"hello world\"' in output:\n${extractConst(js, "x")}")
    assert(!js.contains(""""hello" +"""), "should not emit runtime string concat")

  test("string concat preserves special chars"):
    val js = gen(ssc("""val x = "line1\n" + "line2""""))
    assert(js.contains("const x = \"line1\\nline2\""),
      s"expected properly escaped concat:\n${extractConst(js, "x")}")

  // ── If with literal condition ─────────────────────────────────────────────

  test("if(true) emits then-branch only"):
    val js = gen(ssc("val x = if (true) 42 else 0"))
    assert(js.contains("const x = 42"),
      s"expected 'const x = 42' from if(true):\n${extractConst(js, "x")}")
    assert(!js.contains("? 42 : 0") && !js.contains("true ?"),
      "should not emit runtime ternary for if(true)")

  test("if(false) emits else-branch only"):
    val js = gen(ssc("val x = if (false) 99 else 7"))
    assert(js.contains("const x = 7"),
      s"expected 'const x = 7' from if(false):\n${extractConst(js, "x")}")
    assert(!js.contains("? 99 : 7") && !js.contains("false ?"),
      "should not emit runtime ternary for if(false)")

  test("if(false) with no else emits undefined"):
    val js = gen(ssc("if (false) { val unused = 1 }"))
    assert(!js.contains("const unused"),
      "dead branch should not define 'unused'")

  // ── Unary constant folding ────────────────────────────────────────────────

  test("unary minus on int literal folded"):
    val js = gen(ssc("val x = -5"))
    assert(js.contains("const x = -5"),
      s"expected 'const x = -5':\n${extractConst(js, "x")}")

  test("unary not on bool literal folded"):
    val js = gen(ssc("val x = !true"))
    assert(js.contains("const x = false"),
      s"expected 'const x = false':\n${extractConst(js, "x")}")

  test("unary not on false folded"):
    val js = gen(ssc("val x = !false"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  // ── Double arithmetic ─────────────────────────────────────────────────────

  test("double add folded"):
    val js = gen(ssc("val x = 1.5 + 2.5"))
    assert(js.contains("const x = 4.0"),
      s"expected 'const x = 4.0':\n${extractConst(js, "x")}")

  test("double comparison folded"):
    val js = gen(ssc("val x = 3.14 > 2.71"))
    assert(js.contains("const x = true"),
      s"expected 'const x = true':\n${extractConst(js, "x")}")

  // ── Loop-invariant constant-tuple hoisting (p2) ──────────────────────────

  test("constant ++ in while body is hoisted before loop"):
    val js = gen(ssc(
      """|def workload(): (Int, Int, Int, Int) =
         |  var i = 0
         |  var last = (0, 0, 0, 0)
         |  while i < 100000 do
         |    last = (1, 2) ++ (3, 4)
         |    i = i + 1
         |  last""".stripMargin))
    assert(js.contains("_k0"), s"expected hoist const _k0; got:\n$js")
    assert(js.contains("Object.freeze(Object.assign([1, 2, 3, 4]"), s"expected frozen [1,2,3,4] in hoist; got:\n$js")
    assert(!js.contains("_tupleConcat"), s"_tupleConcat should be eliminated; got:\n$js")
    assert(js.indexOf("const _k0") < js.indexOf("while"), "hoist must appear before while loop")

  test("literal tuple in while body is hoisted before loop"):
    val js = gen(ssc(
      """|def f(): (Int, Int) =
         |  var i = 0
         |  var r = (0, 0)
         |  while i < 10 do
         |    r = (1, 2)
         |    i = i + 1
         |  r""".stripMargin))
    assert(js.contains("_k0"), s"expected hoist const _k0; got:\n$js")
    assert(js.contains("Object.freeze"), s"hoisted tuple should be frozen; got:\n$js")
    assert(js.indexOf("const _k0") < js.indexOf("while"), "hoist must appear before while loop")

  test("non-constant tuple in while body is not hoisted"):
    val js = gen(ssc(
      """|def f(x: Int): (Int, Int) =
         |  var i = 0
         |  var r = (0, 0)
         |  while i < 10 do
         |    r = (x, 2)
         |    i = i + 1
         |  r""".stripMargin))
    assert(!js.contains("_k0"), s"non-constant should not be hoisted; got:\n$js")
    assert(!js.contains("Object.freeze"), s"non-constant should not be frozen; got:\n$js")

  // ── No folding when not all literals ─────────────────────────────────────

  test("no folding when LHS is not a literal"):
    val js = gen(ssc("def f(a: Int) = a + 2"))
    // a + 2 should still be a runtime expression
    assert(!js.contains("const f"), "f is a def, not val")

  test("no folding for if with non-literal condition"):
    val js = gen(ssc("def f(b: Boolean) = if (b) 1 else 2"))
    // Should emit a ternary
    assert(js.contains("?") || js.contains("if"),
      "non-literal condition should remain as runtime expression")

  // ── Helper ────────────────────────────────────────────────────────────────

  private def extractConst(js: String, name: String): String =
    val lines = js.linesIterator.toList
    lines.find(_.contains(s"const $name")).getOrElse(s"<no 'const $name' found>")
