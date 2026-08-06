package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A single-line `if` branch may be an ASSIGNMENT, including an indexed one.
  *
  * `v3/specs/40-front-on-uniml.md` §5b item 1 — the last breadth gap in SSC3 core, and one v3
  * already supports with a green fixture on both lanes, so a front swap without it would lose a
  * working feature.
  *
  * `branchExpr` already handled `then r = n` with a two-token lookahead: an identifier followed by
  * `=`. An INDEXED target cannot be recognised that way, because `a(i)` is only known to be an
  * assignment target after it has been parsed as a call — which is exactly why `parseStmt` decides
  * the same question AFTER parsing rather than before it. The branch position needed the same
  * treatment, not a wider lookahead.
  *
  * The failure was silent in the way that costs the most: `a(i)` parsed fine as the branch body and
  * the `= v` was left for the enclosing block, so the diagnostic landed on whatever followed rather
  * than on the construct that broke. */
final class SpikeIfBranchStmtSpec extends AnyFunSuite:

  private def parse(text: String) =
    UniML.parse(SourceInput.fromString(SourceId("memory:ifbranch"), text), SpikeDialect)

  private def kinds(n: UniNode): Vector[String] = n match
    case b: UniNode.Branch => b.kind +: b.edges.flatMap(e => kinds(e.child))
    case _                 => Vector.empty

  test("an indexed assignment is a valid single-line then-branch") {
    val r = parse("def f(a: Array, i: Int, v: Int): Unit =\n  if i > 0 then a(i) = v\n")
    assert(r.diagnostics.isEmpty, s"diagnostics: ${r.diagnostics.map(_.message).mkString("; ")}")
    val ks = kinds(r.roots.head)
    assert(ks.contains("spike.idxassign"), s"no idxassign in the branch; kinds: ${ks.distinct.mkString(", ")}")
  }

  test("an indexed assignment works in the ELSE branch too") {
    // The twin position. A fix applied to one arm and not the other is a shape this repo has
    // shipped before, so both are stated.
    val r = parse("def f(a: Array, i: Int, v: Int): Unit =\n  if i > 0 then v else a(i) = v\n")
    assert(r.diagnostics.isEmpty, s"diagnostics: ${r.diagnostics.map(_.message).mkString("; ")}")
    assert(kinds(r.roots.head).contains("spike.idxassign"), "no idxassign in the else branch")
  }

  test("the plain assignment branch still works") {
    val r = parse("def f(r: Int, n: Int): Unit =\n  if n > 0 then r = n\n")
    assert(r.diagnostics.isEmpty, s"diagnostics: ${r.diagnostics.map(_.message).mkString("; ")}")
    assert(kinds(r.roots.head).contains("spike.assign"), "the `then r = n` form regressed")
  }

  test("a branch that is an ordinary CALL is still a call, not an assignment") {
    // The control. If the new rule fired on any call it would turn `if c then f(x)` into an
    // assignment with a missing right-hand side, and every one-line call branch in the corpus
    // would change meaning. It fires only when an `=` actually follows.
    val r = parse("def f(x: Int): Unit =\n  if x > 0 then g(x)\n")
    assert(r.diagnostics.isEmpty, s"diagnostics: ${r.diagnostics.map(_.message).mkString("; ")}")
    val ks = kinds(r.roots.head)
    assert(ks.contains("spike.call"), "the call vanished")
    assert(!ks.contains("spike.idxassign"), "a plain call branch became an index assignment")
  }

  test("an equality test in a branch is not an assignment") {
    // `==` lexes as its own operator, so this is a different token — asserted anyway, because the
    // cost of being wrong is a comparison silently becoming a store.
    val r = parse("def f(a: Array, i: Int): Int =\n  if i > 0 then a(i) == 1 else 0\n")
    assert(!kinds(r.roots.head).contains("spike.idxassign"), "`==` was read as an assignment")
  }
