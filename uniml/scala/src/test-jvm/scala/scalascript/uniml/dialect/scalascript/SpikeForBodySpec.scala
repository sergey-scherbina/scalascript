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

  test("a THREE-character compound operator is a separate, LEXER-level gap — pinned open") {
    // `s ++= "x"` still fails, and not in the for body: the lexer's operator table is hand-written
    // and munches at most two characters, so `++=` lexes as `++` then `=`. Same root cause as
    // `def <~>` in tests/conformance/js-symbolic-infix-operator.ssc — one gap, two symptoms.
    // Asserted as still-failing rather than left out, so closing the lexer gap breaks this test and
    // whoever does it reads this note.
    val r = UniML.parse(SourceInput.fromString(src, "def f(): String =\n  var s = \"\"\n  for k <- 1 to 3 do s ++= \"x\"\n  s\n"), SpikeDialect)
    assert(r.diagnostics.map(_.message).exists(_.contains("++")),
      "`++=` now lexes as one token — delete this test and note the lexer gap closed")
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
