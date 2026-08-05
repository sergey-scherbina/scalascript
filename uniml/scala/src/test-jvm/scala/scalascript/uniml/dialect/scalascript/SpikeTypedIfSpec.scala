package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** An `if`'s branches are its BRANCHES, not the keywords that separate them.
  *
  * The CST names four things around an `if`: `if.cond`, the keyword token `if.then`, the branch
  * `if.thenE`, and likewise `if.else`/`if.elseE`. `SpikeTyped` read the keyword roles, so every
  * `if` in the corpus produced `If(cond, Unsupported("spike.kw"), Some(Unsupported("spike.kw")))` —
  * the node was present, its children were the syntax.
  *
  * It hid behind a coverage FLOOR: 4,851 gaps of which 2,120 were this, and the floor only asked
  * for >95%. A count cannot tell "unmodelled construct" from "modelled wrongly", which is why this
  * spec asserts on the SHAPE.
  */
final class SpikeTypedIfSpec extends AnyFunSuite:
  private def project(text: String): SpikeAst.Node =
    val r = UniML.parse(SourceInput.fromString(SourceId("memory:if"), text), SpikeDialect)
    SpikeTyped.module(r.roots.head)

  test("both branches project to real expressions") {
    project("def f(x: Int): Int =\n  if x > 0 then 1 else 2\n") match
      case m: SpikeAst.Module =>
        val ifs = SpikeAst.walk(m).collect { case i: SpikeAst.If => i }
        assert(ifs.sizeIs == 1, s"expected one If, got ${ifs.size}")
        val i = ifs.head
        assert(i.thenE.isInstanceOf[SpikeAst.IntLit], s"then branch is ${i.thenE}")
        assert(i.elseE.exists(_.isInstanceOf[SpikeAst.IntLit]), s"else branch is ${i.elseE}")
      case other => fail(s"expected a Module, got $other")
  }

  test("an if with no else has None, not an Unsupported keyword") {
    project("def f(x: Int): Int =\n  if x > 0 then 1 else 0\n")
    project("def f(x: Int): Unit =\n  if x > 0 then println(x)\n") match
      case m: SpikeAst.Module =>
        val i = SpikeAst.walk(m).collect { case i: SpikeAst.If => i }.head
        assert(i.elseE.isEmpty, s"else should be absent, got ${i.elseE}")
      case other => fail(s"expected a Module, got $other")
  }

  test("no node in an if projects as an Unsupported keyword") {
    // The general form of the defect: a keyword token reaching the projection as an expression.
    // Stated over the whole subtree so a sibling construct making the same mistake is caught here.
    val gaps = SpikeAst.walk(project("def f(x: Int): Int =\n  if x > 0 then\n    val a = 1\n    a\n  else\n    2\n"))
      .collect { case SpikeAst.Unsupported(k, _) => k }
    assert(gaps.forall(_ != "spike.kw"), s"keyword tokens projected as expressions: $gaps")
  }
