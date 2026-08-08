package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** The projection REFUSES an invalid CST instead of repairing it.
  *
  * `YamlProjection.project` flattens the tree to text and REPARSES it, which is the only reason the
  * order of the tokens matters at all. It used to call `.sortBy(_.id)` first — so a tree whose
  * traversal order and token ids disagreed was silently reordered, reparsed, and returned as a
  * semantic SUCCESS. `BUGS.md` `uniml-yaml-projection-reorders-invalid-cst`, whose acceptance
  * criteria this file implements.
  *
  * YAML is source-ORDERED — an anchor binds before the alias that uses it — so reordering does not
  * merely hide the caller's defect. It can change what the document MEANS, which is why the answer
  * is a diagnostic and not a repair.
  *
  * `JsonProjection` already refused rather than repaired, so this was the second decision site of
  * one rule and the wrong one. The tests below are written against the INVARIANTS the projection
  * relies on, one per test, so a future refactor that drops one is named by the failure.
  */
final class YamlProjectionCstSpec extends AnyFunSuite:
  private val src = SourceId("memory:yaml-cst")
  private val other = SourceId("memory:yaml-other")
  private val options = YamlProjectionOptions()

  private def at(source: SourceId, start: Int, end: Int): SourceSpan =
    SourceSpan(source, SourcePosition(start, 1, start + 1), SourcePosition(end, 1, end + 1))

  private def tok(id: Long, lexeme: String, start: Int, source: SourceId = src): UniNode =
    UniNode.Token(SourceToken(id, "yaml.scalar", lexeme, at(source, start, start + lexeme.length)))

  private def project(roots: Vector[UniNode]): YamlProjectionResult =
    YamlProjection.project(ParseResult(roots, Vector.empty, CompletionStatus.Complete), options)

  private def refused(roots: Vector[UniNode], because: String): Unit =
    val r = project(roots)
    assert(r.value.isEmpty, s"$because: the projection returned a VALUE for an invalid CST")
    assert(r.diagnostics.exists(_.code == "uniml.yaml.projection-invalid-cst"),
      s"$because: no projection-invalid-cst diagnostic; got ${r.diagnostics.map(_.code)}")

  // The GOOD tree, so every refusal below is a difference from something that works.
  private val wellFormed: Vector[UniNode] =
    Vector(tok(1, "a", 0), tok(2, ":", 1), tok(3, " ", 2), tok(4, "1", 3), tok(5, "\n", 4))

  test("a well-formed CST still projects — the control") {
    val r = project(wellFormed)
    assert(r.value.nonEmpty, s"the good tree was refused: ${r.diagnostics.map(_.code)}")
    assert(!r.diagnostics.exists(_.code == "uniml.yaml.projection-invalid-cst"))
  }

  test("traversal order disagreeing with token ids is REFUSED, not sorted") {
    // The reported defect, exactly: the same tokens, two of them swapped in traversal order. Sorting
    // by id would reconstruct valid YAML and return a success — which is the bug.
    val swapped = Vector(tok(1, "a", 0), tok(2, ":", 1), tok(4, "1", 3), tok(3, " ", 2), tok(5, "\n", 4))
    refused(swapped, "traversal/id order disagree")
  }

  test("duplicate token ids are REFUSED") {
    refused(Vector(tok(1, "a", 0), tok(1, ":", 1), tok(3, " ", 2), tok(4, "1", 3)),
      "duplicate ids")
  }

  test("two source identities in one CST are REFUSED") {
    refused(Vector(tok(1, "a", 0), tok(2, ":", 1), tok(3, " ", 2, other), tok(4, "1", 3)),
      "two sources")
  }

  test("spans that overlap or run backwards are REFUSED") {
    // Ids ascend, so the id check passes and only the span check can catch this — which is why the
    // two are separate invariants rather than one.
    val overlapping = Vector(tok(1, "a", 0), tok(2, ":", 1), tok(3, " ", 2), tok(4, "1", 1))
    refused(overlapping, "overlapping spans")
  }

  test("an empty CST is not an invalid one") {
    // The degenerate case, asserted so the validation cannot start refusing nothing: an empty tree
    // is a legitimate empty document, and the old code reached the same place via `headOption`.
    val r = project(Vector.empty)
    assert(!r.diagnostics.exists(_.code == "uniml.yaml.projection-invalid-cst"),
      s"an empty CST was called invalid: ${r.diagnostics.map(_.code)}")
  }
