package scalascript.uniml.ssc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.{SpikeAst, SpikeDialect, SpikeTyped}

/** The EIGHT things UniML owes v3, in one place, asserted rather than believed.
  *
  * `v3/specs/40-front-on-uniml.md` §5b lists them and says of the gap list that it is "**stale by
  * construction** — re-measure, do not inherit". That instruction had no apparatus: each item was
  * fixed under its own claim, each claim recorded its own verdict, and nothing afterwards asked all
  * eight the same question at once. So the spec drifted — its item 8 still reads "still open" while
  * `uniml/SPRINT.md` has it `[x]` — and the differential that would have caught the drift compares
  * nothing today, because `v3/src/Front.scala` lists only one runnable front.
  *
  * This is the standing answer. It is UniML's side, so it does not need v3 to run, and it fails the
  * moment any of the eight regresses.
  *
  * Each test names its item number and what a REGRESSION would look like, because six of the eight
  * are wrong ANSWERS rather than losses: a `var` projected as a `val`, a `Char` as an `Int`, a
  * `case object` as an empty one. Those survive a diagnostic count, a silent-drop census and a
  * coverage figure all at once — §5b item 3 is the worked example, and it is why counting is not
  * enough here.
  */
final class Ssc3HandoverSpec extends AnyFunSuite:
  private val src = SourceId("memory:ssc3-handover")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def clean(text: String): ParseResult =
    val r = parse(text)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r

  private def decls(text: String): Vector[SpikeAst.Decl] =
    clean(text).roots.flatMap(root => SpikeTyped.module(root).decls)

  private def nodes(text: String): Vector[SpikeAst.Node] =
    clean(text).roots.flatMap(root => SpikeAst.walk(SpikeTyped.module(root)))

  // ── 1 ─────────────────────────────────────────────────────────────────────
  test("1 — `if c then a(i) = v` parses; it was the only breadth gap in SSC3 core") {
    clean("def f(): Unit =\n  if c then a(i) = v\n")
    clean("def f(): Unit =\n  if c then\n    a(i) = v\n  else\n    a(i) = w\n")
  }

  // ── 2 ─────────────────────────────────────────────────────────────────────
  test("2 — a `.ssc` with NO fence yields a ScalaScript subtree (bare mode)") {
    // The failure this prevents is a whole program read as prose with NO diagnostic — the quietest
    // possible wrong answer. A heading is what separates code from documentation; both directions
    // are asserted, since "everything is code" was measured to hand a doc-only file to the parser.
    val bare = SscCompose.parse("def main(): Unit = println(1)\n")
    assert(spikeSubtrees(bare.root).nonEmpty,
      "a fenceless .ssc produced no ScalaScript subtree — the program read as prose")
    // The composer records the bare path as a SYNTHETIC fence named `<bare>` rather than as an
    // empty fence list, and that is the better design: the path names itself in `fences`, so a
    // consumer can tell "this file had no fence and was taken whole" from "this file had none and
    // nothing happened". My first version of this assertion demanded an empty list and failed —
    // the wrong half was the assertion.
    assert(bare.fences.map(_.lang) == Vector("<bare>"),
      s"the bare path should name itself in `fences`, got ${bare.fences.map(_.lang)}")
    assert(bare.fences.head.dialectId.contains("scalascript.spike"),
      s"the bare body was not handed to the ScalaScript dialect: ${bare.fences.head.dialectId}")

    val doc = SscCompose.parse("# A heading\n\nJust prose, no program here.\n")
    assert(spikeSubtrees(doc.root).isEmpty, "a heading-led document was handed to the parser as code")
  }

  private def spikeSubtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b) else b.edges.flatMap(e => spikeSubtrees(e.child))
    case _ => Vector.empty

  // ── 3 ─────────────────────────────────────────────────────────────────────
  test("3 — a `trait` with a body keeps its METHODS; it must not become a contentless node") {
    // The one that matters most: `trait` gates 137 corpus cases for v3. It used to project as
    // `NoOpDecl` with the methods absent from the CST entirely — invisible to the diagnostic count
    // (nothing failed), to the drop census (no subtree to drop) and to coverage (it was `typed`).
    // So the assertion is on the MEMBERS, not on the node's presence.
    val ds = decls("trait Shape:\n  def area(): Int = 1\n  def name(): String = \"s\"\n")
    val traits = ds.collect { case t: SpikeAst.TraitDecl => t }
    assert(traits.sizeIs == 1, s"expected one TraitDecl, got ${ds.map(_.getClass.getSimpleName)}")
    assert(traits.head.name == "Shape")
    assert(traits.head.members.sizeIs == 2,
      s"the trait's methods are gone — ${traits.head.members.size} member(s), expected 2")
  }

  // ── 4 ─────────────────────────────────────────────────────────────────────
  test("4 — `var` and `val` are DISTINGUISHABLE; a var read as a val refuses every later assignment") {
    val vs = nodes("def f(): Unit =\n  var counter = 0\n  val fixed = 1\n  counter = 2\n")
      .collect { case v: SpikeAst.ValDef => v.name -> v.isVar }
    assert(vs.contains("counter" -> true), s"`var counter` did not record mutability: $vs")
    assert(vs.contains("fixed" -> false), s"`val fixed` was recorded as mutable: $vs")
  }

  // ── 5 ─────────────────────────────────────────────────────────────────────
  test("5 — `case object` is distinguishable from a plain one; v3 needs it as a NULLARY CONSTRUCTOR") {
    val os = decls("case object A extends K:\n  def x(): Int = 1\n\nobject B:\n  def y(): Int = 2\n")
      .collect { case o: SpikeAst.ObjectDecl => o.name -> o.isCase }
    assert(os.contains("A" -> true), s"`case object A` lost its case marker: $os")
    assert(os.contains("B" -> false), s"plain `object B` was marked case: $os")
  }

  // ── 6 ─────────────────────────────────────────────────────────────────────
  test("6 — `+:` is NOT normalised to `::`; they are different methods with swapped operands") {
    val ops = nodes("def f(): Unit =\n  val a = 0 +: xs\n  val b = 0 :: ys\n")
      .collect { case i: SpikeAst.Infix => i.op }
    assert(ops.contains("+:"), s"`0 +: xs` was rewritten — operators seen: $ops")
    assert(ops.contains("::"), s"`0 :: ys` did not survive — operators seen: $ops")
  }

  // ── 7 ─────────────────────────────────────────────────────────────────────
  test("7 — a CHARACTER literal is a CharLit, not an IntLit") {
    // `println('x')` prints x and `println(120)` prints 120, and the language's Char IS an integer
    // that prints differently — which is exactly why the distinction has to survive the projection.
    val lits = nodes("def f(): Unit =\n  val c = 'x'\n  val n = 120\n").collect {
      case c: SpikeAst.CharLit => "char:" + c.code
      case i: SpikeAst.IntLit  => "int:" + i.value
    }
    assert(lits.exists(_.startsWith("char:")), s"the char literal projected as something else: $lits")
    assert(lits.contains("int:120"), s"the integer literal was lost or reshaped: $lits")
  }

  // ── 8 ─────────────────────────────────────────────────────────────────────
  test("8 — the alphabet is UniML's own; classification does not route through the host") {
    // The only item on §5b's list with a consequence for the LANGUAGE rather than for a file: route
    // classification through the host and the same source lexes differently on JVM, JS and the v2
    // VM. `UniAlphabetSweepSpec` proves agreement over the whole Char range; this asserts the
    // property v3 actually depends on — that a non-ASCII identifier lexes here at all, and that a
    // classifier exists to ask.
    assert(UniAlphabet.isIdStart('é'), "a non-ASCII identifier start was rejected")
    assert(UniAlphabet.isTypeNameStart('Δ'), "a non-ASCII uppercase was not recognised as a type name start")
    assert(!UniAlphabet.isTypeNameStart('δ'), "a lowercase Greek letter was taken for a type name start")
    clean("def f(): Unit =\n  val héllo = 1\n  println(héllo)\n")
  }
