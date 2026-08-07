package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** An `else` belongs to the `if` whose BLOCK it does not close.
  *
  * The dialect took an `else` unconditionally, so an `if` written as the last statement of an
  * indented block swallowed the `else` of the block's OWNER:
  *
  * {{{
  * if x == 1 then
  *   r = 1
  *   if x == 1 then r = 11   // a statement of the block at column 5
  * else if x == 2 then       // `else` at column 3 CLOSES that block
  *   r = 2
  * }}}
  *
  * The outer `if` was left with no else branch at all, so `f(2)` evaluated to 0 where the reference
  * front answers 2. **A wrong answer, not a loss** — no diagnostic count, no coverage figure and no
  * losslessness check can see it, and none did: it was found by v3's FRONT DIFFERENTIAL, where it
  * was every one of the 74 corpus disagreements. All 74 were `scljet-*` cases, and they were 74
  * because they import one `scljet/sql.ssc` that spells this shape once.
  *
  * The rule is columnar and NOT "an `else` binds to the nearest `if`". Both are tested below,
  * because the second is what an `else if` chain relies on and a fix that only looked at nesting
  * would break it.
  */
final class SpikeDanglingElseSpec extends AnyFunSuite:
  private val src = SourceId("memory:dangling-else")

  private def ast(text: String): String =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r.roots.flatMap(root => SpikeAst.walk(SpikeTyped.module(root)))
      .collect { case i: SpikeAst.If => i }
      .map(i => s"if(then=${shape(i.thenE)}, else=${i.elseE.map(shape).getOrElse("NONE")})")
      .mkString(" | ")

  private def shape(e: SpikeAst.Expr): String = e match
    case _: SpikeAst.If    => "IF"
    case _: SpikeAst.Block => "BLOCK"
    case _                 => "expr"

  test("an `else` that would close the block does NOT bind to an `if` inside it") {
    // The outer `if` must end up WITH an else; the inner one WITHOUT.
    val out = ast(
      "def f(x: Int): Int =\n" +
      "  var r = 0\n" +
      "  if x == 1 then\n" +
      "    r = 1\n" +
      "    if x == 1 then r = 11\n" +
      "  else if x == 2 then\n" +
      "    r = 2\n" +
      "  else\n" +
      "    r = 3\n" +
      "  r\n")
    assert(out.contains("if(then=BLOCK, else=IF)"),
      s"the OUTER if lost its else branch — the inner one swallowed it: $out")
    assert(out.contains("if(then=expr, else=NONE)"),
      s"the INNER single-line if took an else that closes its block: $out")
  }

  test("an `else if` CHAIN still binds to the inner `if` — the rule is columnar, not nesting") {
    // The control that a fix keyed on nesting would fail. Here the second `else` is at the same
    // column as the first and belongs to the `if` introduced by `else if`, which is what Scala
    // means and what every chained conditional in the corpus relies on.
    val out = ast(
      "def f(x: Int): Int =\n" +
      "  if x == 1 then\n" +
      "    1\n" +
      "  else if x == 2 then\n" +
      "    2\n" +
      "  else\n" +
      "    3\n")
    assert(!out.contains("else=NONE"), s"a chained else-if lost a branch: $out")
    assert(out.contains("if(then=BLOCK, else=IF)"), s"the chain did not nest: $out")
  }

  test("the shapes that always worked are untouched") {
    // Single-line, block-bodied, and an if with no else at all — none of these involves an `else`
    // at a closing column, so the new condition must be invisible to them.
    assert(ast("def f(x: Int): Int = if x > 0 then 1 else 2") == "if(then=expr, else=expr)")
    assert(ast("def f(x: Int): Int =\n  if x > 0 then\n    1\n  else\n    2\n") == "if(then=BLOCK, else=BLOCK)")
    assert(ast("def f(x: Int): Unit =\n  if x > 0 then\n    println(1)\n") == "if(then=BLOCK, else=NONE)")
    // an inner if WITH its own else on the same line keeps it — the else does not close anything
    val out = ast(
      "def f(x: Int): Int =\n" +
      "  var r = 0\n" +
      "  if x == 1 then\n" +
      "    if x == 1 then r = 1 else r = 2\n" +
      "  else\n" +
      "    r = 3\n" +
      "  r\n")
    assert(out.contains("if(then=expr, else=expr)"), s"the inner same-line else was refused: $out")
    assert(out.contains("if(then=BLOCK, else=BLOCK)"), s"the outer if lost its else: $out")
  }
