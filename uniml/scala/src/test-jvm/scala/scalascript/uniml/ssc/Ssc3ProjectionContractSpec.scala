package scalascript.uniml.ssc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.UniNode
import scalascript.uniml.dialect.scalascript.{SpikeAst, SpikeTyped}
import java.nio.file.{Files, Path}

/** The assumptions `v3/specs/50-uniml-projection.md` rests on, held against the corpus.
  *
  * That spec maps `SpikeAst` onto v3's `Ast` node by node, and several entries are safe only
  * because of something true about the corpus TODAY rather than about the types. §7 lists four such
  * questions and says they must be MEASURED before the projection is written. They were, on
  * 2026-08-06; this is the same measurement turned into a gate, so the day one of the answers
  * changes is the day a test goes red instead of the day a front quietly projects the wrong tree.
  *
  * It is deliberately on the UniML side. Each assumption is a property of what the DIALECT
  * produces, so it belongs where that is built — and it stays true whoever writes the v3 half.
  *
  * ONE OF THESE CANNOT BE CHECKED THROUGH THE AST, and that is the whole reason this file is
  * careful. `CaseClass.parent` is an `Option[String]`, so an AST-side test asking "does any class
  * have two parents?" reads `Option` and answers "no" for every input that has ever existed —
  * including one with three. It would be a test whose subject is unreachable through the thing it
  * tests: green by construction, informative about nothing. That check therefore reads the SOURCE,
  * and a companion test proves the reader can SEE a multi-parent class before the corpus test is
  * allowed to claim there are none. This repository has paid for the other order more than once. */
final class Ssc3ProjectionContractSpec extends AnyFunSuite:

  private def repoRoot: Path = SscCorpus.repoRoot

  private def corpusFiles(root: Path): Vector[Path] = SscCorpus.files(root)

  private def scalaSubtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b)
      else b.edges.flatMap(e => scalaSubtrees(e.child))
    case _ => Vector.empty

  private def astOf(text: String): Vector[SpikeAst.Node] =
    scalaSubtrees(SscCompose.parse(text).root).flatMap(sr => SpikeAst.walk(SpikeTyped.module(sr)))

  // ── Q1 · a case class with MORE THAN ONE parent ───────────────────────────────────────────
  // `CaseClass.parent` is `Option[String]`; v3's `ClassDef.parents` is a `List`. The projection is
  // faithful only while no class writes `extends A with B`. Measured 2026-08-06: none does.
  //
  // The dialect keeps only the FIRST parent — `cc.parent` is a single leaf and `skipExtendsClause`
  // consumes the rest — so a second one is lost with no node, no `Unsupported` and no drop for the
  // census to find. "Sufficient" and "safe" are different claims, and only the first is true.

  /** Case-class declarations naming more than one parent, read from SOURCE TEXT.
    *
    * Text and not the tree, because the tree cannot represent the answer: see the class comment.
    * Deliberately loose — it looks for `extends … with` on the declaration line — since for a
    * tripwire a false alarm costs a look and a miss costs the thing it exists to catch. */
  private def multiParentCaseClasses(text: String): Vector[String] =
    val decl = """(?m)^\s*(?:case\s+class|class)\s+\w+[^\n]*?\bextends\b[^\n]*?\bwith\b[^\n]*$""".r
    decl.findAllIn(text).toVector.map(_.trim)

  test("the multi-parent reader can SEE one — the control for the corpus test below") {
    // Run FIRST and in its own test. A corpus sweep asserting "none found" proves nothing until
    // the finder has been shown finding. This is that demonstration, and if the regex rots it is
    // this test that fails rather than the sweep silently going quiet.
    val planted = "case class C(x: Int) extends A with B:\n  def f(): Int = x\n"
    assert(multiParentCaseClasses(planted).sizeIs == 1, "the reader cannot see a planted `extends A with B`")
    assert(multiParentCaseClasses("case class C(x: Int) extends A\n").isEmpty, "single parent must not match")
    assert(multiParentCaseClasses("case class C(x: Int)\n").isEmpty, "no parent must not match")
    // `with` elsewhere on an unrelated line must not trip it
    assert(multiParentCaseClasses("val s = \"done with it\"\n").isEmpty, "prose containing `with` must not match")
  }

  test("Q1 · no case class in the corpus has more than one parent") {
    val root = repoRoot
    val hits = corpusFiles(root).flatMap { p =>
      multiParentCaseClasses(new String(Files.readAllBytes(p), "UTF-8"))
        .map(d => s"${root.relativize(p)}  $d")
    }
    assert(hits.isEmpty,
      s"""${hits.size} case class(es) now name several parents, and UniML keeps only the FIRST.
         |`SpikeAst.CaseClass.parent` is an Option, so the second is lost with no diagnostic, and
         |v3's `ClassDef.parents` will be short by one. Grow the UniML node before projecting these:
         |${hits.take(10).mkString("\n")}""".stripMargin)
  }

  // ── Q2 · a lambda parameter carries no type ───────────────────────────────────────────────
  // `Lambda.params` is `Vector[String]`. v3's `Param` has no type either, so the projection is
  // lossless FOR V3 — but only while the strings really are bare names. If the dialect ever starts
  // folding `x: Int` into the lexeme, that becomes a name no lowering can bind.

  test("Q2 · every lambda parameter is a bare name, never a name plus its type") {
    val root = repoRoot
    val bad = corpusFiles(root).flatMap { p =>
      astOf(new String(Files.readAllBytes(p), "UTF-8")).collect {
        case l: SpikeAst.Lambda => l
      }.flatMap(_.params).filter(n => n.contains(":") || n.contains(" ") || n.isEmpty)
        .map(n => s"${root.relativize(p)}  [$n]")
    }
    assert(bad.isEmpty, s"lambda params are no longer bare names: ${bad.take(10).mkString(", ")}")
  }

  // ── Q3 · what an object may hold ──────────────────────────────────────────────────────────
  // v3's `ObjectDef` holds `def`s only, and `Parser.parseObject` already REFUSES anything else at
  // Tier 0 — so the projection refusing too is agreement, not a gap. What would be news is a member
  // KIND nobody has seen, because that is a case the projection has never been written against.

  test("Q3 · an object's members are only the kinds the projection was written against") {
    val root = repoRoot
    val known = Set("Def", "TopExpr", "ObjectDecl", "CaseClass")
    var objects = 0
    var withNonDef = 0
    val kinds = scala.collection.mutable.Map.empty[String, Int]
    corpusFiles(root).foreach { p =>
      astOf(new String(Files.readAllBytes(p), "UTF-8")).foreach {
        case o: SpikeAst.ObjectDecl =>
          objects += 1
          val others = o.members.filterNot(_.isInstanceOf[SpikeAst.Def])
          if others.nonEmpty then withNonDef += 1
          others.foreach { m =>
            val k = m.getClass.getSimpleName
            kinds(k) = kinds.getOrElse(k, 0) + 1
          }
        case _ => ()
      }
    }
    info(f"objects=$objects  holding a non-def member=$withNonDef")
    kinds.toVector.sortBy(-_._2).foreach((k, c) => info(f"  $c%5d  $k"))
    val unknown = kinds.keySet.toSet -- known
    assert(unknown.isEmpty,
      s"an object now holds a member kind the v3 projection has no case for: ${unknown.mkString(", ")}")
    assert(objects > 100, s"only $objects objects reached the projection — the sweep measured nothing")
  }

  // ── Q4 · every span can become a v3 `Pos` ─────────────────────────────────────────────────
  // v3's `Pos` is 1-based line and column, and `Pos(0, 0)` is RESERVED — it means "the file as a
  // whole", for a diagnostic with no single line. So a real node arriving with line 0 would not be
  // a rounding error; it would be indistinguishable from that sentinel.

  test("Q4 · no node's span could be mistaken for v3's whole-file position") {
    val root = repoRoot
    var nodes = 0
    val bad = scala.collection.mutable.ArrayBuffer.empty[String]
    corpusFiles(root).foreach { p =>
      astOf(new String(Files.readAllBytes(p), "UTF-8")).foreach { n =>
        nodes += 1
        val s = n.span.start
        if s.line <= 0 || s.column <= 0 then
          if bad.sizeIs < 10 then bad += s"${root.relativize(p)}  ${n.getClass.getSimpleName} at ${s.line}:${s.column}"
      }
    }
    info(f"nodes=$nodes with a usable line:column = ${nodes - bad.size}")
    assert(bad.isEmpty, s"spans that cannot become a v3 Pos: ${bad.mkString("; ")}")
    assert(nodes > 100000, s"only $nodes nodes reached the projection — the sweep measured nothing")
  }
