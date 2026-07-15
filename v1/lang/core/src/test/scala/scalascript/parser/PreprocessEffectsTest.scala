package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

/** `effect Name:` blocks are rewritten into `object Name { def op(...) = __effectOp__ }`
 *  before scalameta sees them. The synthetic `= __effectOp__` body MUST be inserted before any
 *  trailing line-comment, and a `=>` in a function-type param must not be mistaken for a body —
 *  otherwise the op silently loses its marker, the whole effect degrades to a plain instance, and
 *  every `Name.op(...)` perform throws `No method 'op' on InstanceV(Name)` at runtime.
 *  Regression for the effect-op-trailing-comment bug (found building busi's KSeF port). */
class PreprocessEffectsTest extends AnyFunSuite:

  test("a trailing line-comment does not swallow the synthetic body"):
    val out = Parser.preprocessEffects(
      "effect E:\n  def op(x: Int): Int   // returns the next value")
    assert(out.contains(": Int = __effectOp__"),
      s"the op should get its body before the comment; got:\n$out")
    assert(!out.contains("// returns the next value = __effectOp__"),
      s"the marker must NOT land inside the comment; got:\n$out")
    assert(out.contains("// returns the next value"), "the comment should be preserved")

  test("a function-type param (=>) is not mistaken for a body"):
    val out = Parser.preprocessEffects(
      "effect E:\n  def run(f: Int => Int): Unit")
    assert(out.contains(": Unit = __effectOp__"),
      s"a `=>` param must still get the synthetic body; got:\n$out")

  test("a plain op still gets the synthetic body"):
    val out = Parser.preprocessEffects("effect E:\n  def op(x: Int): Int")
    assert(out.contains(": Int = __effectOp__"))

  test("effect origin survives only as private type evidence"):
    val empty = Parser.preprocessEffects("effect Empty:\n")
    assert(empty.contains("private type __effectDecl__ = true"))
    assert(!empty.contains("val __effectDecl__"))

    val generic = Parser.preprocessEffects("effect State[A]:\n")
    assert(generic.contains("private type __effectDecl__ = true"))
    assert(generic.contains("private type __effectUnsupportedShape__ = true"))
    assert(!generic.contains("val __effectUnsupportedShape__"))

    val ordinary = "object Empty:\n  ()"
    assert(Parser.preprocessEffects(ordinary) == ordinary)

  test("an op that already has a body is left alone"):
    val out = Parser.preprocessEffects("effect E:\n  def op(x: Int): Int = x")
    assert(!out.contains("__effectOp__"), s"a real body must not be doubled; got:\n$out")

  test("multiple ops, mixed comments, all rewritten"):
    val out = Parser.preprocessEffects(
      "effect Ksef:\n  def pull(token: String, since: String): List[String]   // FA(3) docs\n  def status(id: String): String")
    assert(out.contains(": List[String] = __effectOp__"))
    assert(out.contains(": String = __effectOp__"))

  // the rewritten block must also actually parse (end-to-end through the preprocessor chain)
  private def parseBlock(body: String): scalascript.ast.Module =
    Parser.parse(s"# T\n\n```scalascript\n$body\n```\n")
  private def blockTree(m: scalascript.ast.Module): Option[scalascript.ast.ScalaNode] =
    m.sections.headOption.flatMap(_.content.collectFirst {
      case cb: scalascript.ast.Content.CodeBlock => cb.tree
    }).flatten

  test("effect with a commented op parses end-to-end"):
    val m = parseBlock("effect Ksef:\n  def pull(token: String, since: String): List[String]   // FA(3)")
    assert(blockTree(m).isDefined, "the commented effect op should still parse")

  test("splitLineComment ignores // inside a string literal"):
    assert(Parser.splitLineComment("""  def op(d: String = "a//b"): Int""")
      == ("""  def op(d: String = "a//b"): Int""", ""))
    val (code, cmt) = Parser.splitLineComment("  def op(x: Int): Int   // c")
    assert(code == "  def op(x: Int): Int   " && cmt == "// c")
