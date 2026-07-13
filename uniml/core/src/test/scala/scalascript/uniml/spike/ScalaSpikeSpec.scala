package scalascript.uniml.spike

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.nio.file.{Files, Paths}

/** P6.0 gate (precedence-through-UniML) + P6.1 (total, error-resilient pipeline).
  * Also emits projections + toy sources for the end-to-end run-ir diff harness
  * (specs/v2.2-p6.0-spike-verify.sh).
  */
final class ScalaSpikeSpec extends AnyFunSuite:
  private val src = SourceId("memory:spike.scala")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def kindOf(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(t)  => t.kind

  private def findBranch(n: UniNode, kind: String): Option[UniNode.Branch] = n match
    case b: UniNode.Branch =>
      if b.kind == kind then Some(b)
      else b.edges.iterator.flatMap(e => findBranch(e.child, kind)).nextOption()
    case _ => None

  private def allBranches(n: UniNode, kind: String): Vector[UniNode.Branch] = n match
    case b: UniNode.Branch =>
      val here = if b.kind == kind then Vector(b) else Vector.empty
      here ++ b.edges.flatMap(e => allBranches(e.child, kind))
    case _ => Vector.empty

  private def childWithRole(b: UniNode.Branch, role: String): Option[UniNode] =
    b.edges.collectFirst { case UniEdge(Some(r), c) if r == role => c }

  private def defBody(pr: ParseResult): UniNode =
    val prog = pr.roots.collectFirst { case b @ UniNode.Branch("spike.program", _, _, _) => b }.get
    val dfn  = findBranch(prog, "spike.def").get
    childWithRole(dfn, "def.body").get

  // ══ P6.0 — the gate: operator precedence is faithfully nested in the CST ══════

  test("`1 + 2 * 3` nests as add(1, mul(2,3)) — * binds tighter") {
    val pr = parse("def main(): Int = 1 + 2 * 3")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val add = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(add.kind == "spike.add")
    assert(kindOf(childWithRole(add, "bin.left").get) == "spike.int")
    assert(kindOf(childWithRole(add, "bin.right").get) == "spike.mul")
  }

  test("`1 * 2 + 3` nests as add(mul(1,2), 3) — left-side product") {
    val body = defBody(parse("def main(): Int = 1 * 2 + 3")).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.add")
    assert(kindOf(childWithRole(body, "bin.left").get) == "spike.mul")
    assert(kindOf(childWithRole(body, "bin.right").get) == "spike.int")
  }

  test("`(1 + 2) * 3` — parens override precedence") {
    val body = defBody(parse("def main(): Int = (1 + 2) * 3")).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.mul")
    val paren = childWithRole(body, "bin.left").get.asInstanceOf[UniNode.Branch]
    assert(paren.kind == "spike.paren")
    assert(kindOf(childWithRole(paren, "paren.inner").get) == "spike.add")
  }

  test("source order of significant tokens is preserved (lossless up to trivia)") {
    val pr = parse("def main(): Int = 1 * 2 + 3")
    val got = UniNode.sourceTokens(pr.roots.head).filter(_.kind != "spike.ws").map(_.lexeme).mkString(" ")
    assert(got == "def main ( ) : Int = 1 * 2 + 3", got)
  }

  test("if / call / params parse into the expected frames") {
    val pr = parse("def f(x: Int): Int = if x then g(x, 1 + 2) else 0")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val body = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.if")
    val call = findBranch(body, "spike.call").get
    val args = call.edges.collect { case UniEdge(Some("call.arg"), c) => kindOf(c) }
    assert(args == Vector("spike.id", "spike.add"), args)
  }

  // ══ P6.1 — total, error-resilient pipeline ════════════════════════════════════

  test("parser + projection are TOTAL — never throw on garbage") {
    val garbage = Seq(
      "", "   ", "def", "def (", ")))", "1 + + 2", "def f(: = )", "@#$%^",
      "def main(): Int =", "if if if", "def main(): Int = (1 + ", "def a b c d e"
    )
    for g <- garbage do
      val pr = parse(g) // must not throw
      val proj = pr.roots.headOption.map(SpikeProject.program).getOrElse("Nil") // must not throw
      assert(proj != null)
  }

  test("error CONTAINMENT — a broken def does not poison a sibling") {
    val pr = parse("def broken(): Int = 1 +\ndef main(): Int = 2 * 3")
    assert(pr.status == CompletionStatus.Incomplete)
    assert(pr.diagnostics.exists(_.code == "spike.missing-operand"), pr.diagnostics)
    val prog = pr.roots.head
    // both defs survive
    assert(allBranches(prog, "spike.def").size == 2)
    // the second def (`main`) parsed cleanly as a product — untouched by the broken sibling
    val mainDef = allBranches(prog, "spike.def")(1)
    assert(kindOf(childWithRole(mainDef, "def.body").get) == "spike.mul")
    // broken's body is an add with a MISSING right operand (→ hole in projection)
    val brokenDef = allBranches(prog, "spike.def")(0)
    val badAdd = childWithRole(brokenDef, "def.body").get.asInstanceOf[UniNode.Branch]
    assert(badAdd.kind == "spike.add")
    assert(childWithRole(badAdd, "bin.right").isEmpty) // no right → hole
  }

  test("junk between defs is wrapped in a spike.error node and both defs survive") {
    val pr = parse("def a(): Int = 1\n@ @ @\ndef main(): Int = 2 * 3")
    val prog = pr.roots.head
    assert(findBranch(prog, "spike.error").isDefined) // junk salvaged into an error frame
    assert(allBranches(prog, "spike.def").size == 2)
    assert(pr.diagnostics.exists(_.severity == Severity.Error))
  }

  test("missing operand projects to a __notImplemented__ hole; the rest is intact") {
    val pr = parse("def broken(): Int = 1 +\ndef main(): Int = 2 * 3")
    val proj = SpikeProject.program(pr.roots.head)
    assert(proj.contains("__notImplemented__"))        // the hole is emitted
    assert(proj.contains("""mkInf("*", mkInt("2"), mkInt("3"))""")) // main survives faithfully
  }

  test("diagnostics carry a code, Error severity and a span") {
    val pr = parse("def main(): Int = 1 +")
    assert(pr.diagnostics.nonEmpty)
    val d = pr.diagnostics.head
    assert(d.severity == Severity.Error)
    assert(d.code.startsWith("spike."))
    assert(d.span.isDefined)
  }

  // ══ emit projections + toys for the end-to-end run-ir diff harness ═════════════

  test("emit projections + toys for run-ir diff (well-formed + broken)") {
    val outDir = sys.env.getOrElse("SPIKE_OUT",
      "/private/tmp/claude-501/-Users-sergiy-work-my-scalascript/0ae59ae0-0693-4dfa-b393-87f68bf3d01b/scratchpad/p6.0")
    Files.createDirectories(Paths.get(outDir))
    // well-formed: diff both my-path and ssc1-front against .expect
    val wellFormed = Seq(
      ("add-mul", "def main(): Int = 1 + 2 * 3", "7"),
      ("mul-add", "def main(): Int = 1 * 2 + 3", "5"),
      ("paren",   "def main(): Int = (1 + 2) * 3", "9"),
      ("nested",  "def main(): Int = 2 * (3 + 4) - 5", "9")
    )
    // broken: my-path only (ssc1-front is not the oracle for malformed input);
    // the broken sibling must not poison `main`, which still returns its value.
    val broken = Seq(
      ("broken-sibling", "def broken(): Int = 1 +\ndef main(): Int = 2 * 3", "6")
    )
    for (name, code, expect) <- wellFormed do
      val proj = SpikeProject.program(parse(code).roots.head)
      Files.writeString(Paths.get(outDir, s"$name.proj"), proj)
      Files.writeString(Paths.get(outDir, s"$name.toy.ssc"), code + "\n")
      Files.writeString(Paths.get(outDir, s"$name.expect"), expect)
    for (name, code, expect) <- broken do
      val proj = SpikeProject.program(parse(code).roots.head)
      Files.writeString(Paths.get(outDir, s"$name.proj"), proj)
      Files.writeString(Paths.get(outDir, s"$name.expect"), expect)
      Files.deleteIfExists(Paths.get(outDir, s"$name.toy.ssc")) // no oracle for broken
    Files.writeString(Paths.get(outDir, "EMITTED"), (wellFormed.map(_._1) ++ broken.map(_._1)).mkString("\n"))
    succeed
  }
