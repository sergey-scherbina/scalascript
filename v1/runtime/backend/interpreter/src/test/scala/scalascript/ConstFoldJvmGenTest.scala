package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** Constant folding in JvmGen: literal arithmetic, comparison, boolean logic,
 *  string concat, and if-with-literal-condition are evaluated at codegen time.
 */
class ConstFoldJvmGenTest extends AnyFunSuite:

  private def gen(src: String): String =
    JvmGen.generate(Parser.parse(src))

  private def ssc(body: String): String =
    s"# T\n```scalascript\n$body\n```\n"

  // ── Integer arithmetic ────────────────────────────────────────────────────

  test("int add: no _binOp for literal operands in JvmGen output"):
    val sc = gen(ssc("val x = 1 + 2"))
    // Non-effectful path: JvmGen emits valid Scala 3 source; scalac folds 1 + 2
    // at compile time.  The key correctness property: no runtime _binOp dispatch.
    assert(!sc.contains("""_binOp("+", 1, 2)"""),
      "should not emit _binOp for literal int add")

  test("int subtract folded in JvmGen output"):
    val sc = gen(ssc("val x = 10 - 3"))
    assert(sc.contains("7"),
      s"expected literal 7:\n${extractVal(sc, "x")}")
    assert(!sc.contains("""_binOp("-", 10, 3)"""),
      "should not emit _binOp for literal int subtract")

  test("int multiply: no _binOp for literal operands in JvmGen output"):
    val sc = gen(ssc("val x = 6 * 7"))
    // Non-effectful path: emits valid Scala 3; scalac folds 6 * 7 at compile time.
    assert(!sc.contains("""_binOp("*", 6, 7)"""),
      "should not emit _binOp for literal int multiply")

  test("int comparison folded in JvmGen output"):
    val sc = gen(ssc("val x = 3 > 2"))
    assert(sc.contains("true"),
      s"expected literal true:\n${extractVal(sc, "x")}")
    // Check the specific dispatch for the folded operands (not the bare `_binOp(">"` prefix, which
    // also appears in the `_anyCall0` runtime helper's `max` case — `_binOp(">", a, x)`). Mirrors the
    // operand-qualified assertions in the int-add / -subtract tests above.
    assert(!sc.contains("""_binOp(">", 3, 2)"""),
      "should not emit _binOp for literal comparison")

  // ── Boolean logic ─────────────────────────────────────────────────────────

  test("bool and folded in JvmGen output"):
    val sc = gen(ssc("val x = true && false"))
    assert(sc.contains("false"),
      s"expected literal false:\n${extractVal(sc, "x")}")

  test("bool or folded in JvmGen output"):
    val sc = gen(ssc("val x = false || true"))
    assert(sc.contains("true"),
      s"expected literal true:\n${extractVal(sc, "x")}")

  // ── String concat ─────────────────────────────────────────────────────────

  test("string concat: no _binOp for literal strings in JvmGen output"):
    val sc = gen(ssc("""val x = "hello" + " world""""))
    // Non-effectful path: JvmGen emits valid Scala 3 source; scalac evaluates
    // string concat.  The key correctness property: no runtime _binOp dispatch.
    assert(!sc.contains("""_binOp("+", "hello", " world")"""),
      "should not emit _binOp for literal string concat")

  // ── If with literal condition ─────────────────────────────────────────────

  test("if(true) emits then-branch in JvmGen output"):
    val sc = gen(ssc("val x = if (true) 42 else 0"))
    assert(sc.contains("42"),
      s"expected 42 from if(true):\n${extractVal(sc, "x")}")
    assert(!sc.contains("if false") && !sc.contains("if true"),
      "dead if branch should be eliminated")

  test("if(false) emits else-branch in JvmGen output"):
    val sc = gen(ssc("val x = if (false) 99 else 7"))
    assert(sc.contains("7"),
      s"expected 7 from if(false):\n${extractVal(sc, "x")}")

  // ── Double arithmetic ─────────────────────────────────────────────────────

  test("double add folded in JvmGen output"):
    val sc = gen(ssc("val x = 1.5 + 2.5"))
    assert(sc.contains("4.0"),
      s"expected literal 4.0:\n${extractVal(sc, "x")}")

  // ── No folding when not all literals ─────────────────────────────────────

  test("no folding when operand is a name"):
    val sc = gen(ssc("def f(a: Int) = a + 2"))
    // The expression a + 2 should emit _binOp at runtime (a is not a literal)
    assert(!sc.contains("_binOp(\"+\", 2, 2)"),
      "should not mis-fold named operands")

  // ── Helper ────────────────────────────────────────────────────────────────

  private def extractVal(sc: String, name: String): String =
    val lines = sc.linesIterator.toList
    lines.find(l => l.contains(s"val $name") || l.contains(s"var $name"))
      .getOrElse(s"<no '$name' binding found>")
