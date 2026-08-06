package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A `for … do` body accepts what a block statement accepts.
  *
  * It handled `x = e` and not `x += e`, so `for k <- 1 to 3 do g += k` reached `parseExpr`, which
  * reads `g` and stops at the `+=`. The same compound assignment one line above, at statement
  * level, was fine — which is the tell: two positions that should accept the same thing, spelled
  * separately, and only one of them updated when compound assignment was added.
  *
  * `tests/conformance/js-compound-assign.ssc:53` is exactly this and it passes on int, so the gap
  * was never in the source.
  */
final class SpikeForBodySpec extends AnyFunSuite:
  private val src = SourceId("memory:for-body")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("compound assignment in a for body, inline and indented") {
    clean("def f(): Int =\n  var g = 0\n  for k <- 1 to 3 do g += k\n  g\n")
    clean("def f(): Int =\n  var g = 0\n  for k <- 1 to 3 do\n    g += k\n  g\n")
    clean("def f(): Int =\n  var g = 1\n  for k <- 1 to 3 do g *= k\n  g\n")
  }

  test("a THREE-character compound operator — the lexer gap this spec pinned as open, now closed") {
    // Written as an assertion that `++=` still FAILED, with a note telling whoever closed the lexer
    // gap to come here. That happened: maximal munch lexes it as one token and it broke this test on
    // the first run, which is the whole reason it was written that way rather than left out.
    clean("def f(): String =\n  var s = \"\"\n  for k <- 1 to 3 do s ++= \"x\"\n  s\n")
    clean("def f(): Int =\n  var g = 1\n  g **= 2\n  g\n")
  }

  test("the forms that already worked still do") {
    clean("def f(): Int =\n  var g = 0\n  for k <- 1 to 3 do g = g + k\n  g\n")
    clean("def f(): Unit =\n  for k <- 1 to 3 do println(k)\n")
    clean("def f(): Int =\n  var g = 0\n  g += 1\n  g\n")
  }

  test("a COMPARISON in a for body is not an assignment — the control") {
    // isCompoundAssign excludes ==, !=, <=, >=, := — and this is where that exclusion is load-bearing,
    // because a body is an expression position and `k <= 3` must stay one.
    clean("def f(): Unit =\n  for k <- 1 to 3 do println(k <= 3)\n")
    clean("def f(): Unit =\n  for k <- 1 to 3 do println(k == 3)\n")
    clean("def f(): Unit =\n  for k <- 1 to 3 do println(k != 3)\n")
  }
