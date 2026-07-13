package scalascript.uniml.spike

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.nio.file.{Files, Paths}

/** P6.0 gate (precedence) + P6.1 (total error model) + P6.2 (full infix table).
  * Also emits projections + toys for the end-to-end run-ir diff harness
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

  private def childKinds(b: UniNode.Branch): Vector[String] =
    b.edges.collect { case UniEdge(_, c: UniNode.Branch) => c.kind }

  /** the operator lexeme of a `spike.infix` node */
  private def opOf(b: UniNode.Branch): String =
    childWithRole(b, "bin.op").collect { case UniNode.Token(t) => t.lexeme }.getOrElse("?")

  private def defBody(pr: ParseResult): UniNode =
    val prog = pr.roots.collectFirst { case b @ UniNode.Branch("spike.program", _, _, _) => b }.get
    childWithRole(findBranch(prog, "spike.def").get, "def.body").get

  // ══ P6.0 — the gate: operator precedence is faithfully nested in the CST ══════

  test("`1 + 2 * 3` nests as +(1, *(2,3)) — * binds tighter") {
    val pr = parse("def main(): Int = 1 + 2 * 3")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val add = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(add.kind == "spike.infix" && opOf(add) == "+")
    assert(kindOf(childWithRole(add, "bin.left").get) == "spike.int")
    val right = childWithRole(add, "bin.right").get.asInstanceOf[UniNode.Branch]
    assert(right.kind == "spike.infix" && opOf(right) == "*")
  }

  test("`1 * 2 + 3` nests as +(*(1,2), 3) — left-side product") {
    val add = defBody(parse("def main(): Int = 1 * 2 + 3")).asInstanceOf[UniNode.Branch]
    assert(add.kind == "spike.infix" && opOf(add) == "+")
    val left = childWithRole(add, "bin.left").get.asInstanceOf[UniNode.Branch]
    assert(left.kind == "spike.infix" && opOf(left) == "*")
  }

  test("`(1 + 2) * 3` — parens override precedence") {
    val mul = defBody(parse("def main(): Int = (1 + 2) * 3")).asInstanceOf[UniNode.Branch]
    assert(mul.kind == "spike.infix" && opOf(mul) == "*")
    val paren = childWithRole(mul, "bin.left").get.asInstanceOf[UniNode.Branch]
    assert(paren.kind == "spike.paren")
    assert(kindOf(childWithRole(paren, "paren.inner").get) == "spike.infix")
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
    assert(args == Vector("spike.id", "spike.infix"), args)
  }

  // ══ P6.2 — the full infix precedence table (matches ssc1-front `opPrec`) ═══════

  test("mixed precedence: `1 + 2 * 3 - 4 / 2` → -( +(1,*(2,3)), /(4,2) )") {
    val top = defBody(parse("def main(): Int = 1 + 2 * 3 - 4 / 2")).asInstanceOf[UniNode.Branch]
    assert(opOf(top) == "-")
    assert(opOf(childWithRole(top, "bin.left").get.asInstanceOf[UniNode.Branch]) == "+")
    assert(opOf(childWithRole(top, "bin.right").get.asInstanceOf[UniNode.Branch]) == "/")
  }

  test("left-associativity: `10 - 3 - 2` → -( -(10,3), 2 )") {
    val top = defBody(parse("def main(): Int = 10 - 3 - 2")).asInstanceOf[UniNode.Branch]
    assert(opOf(top) == "-")
    assert(opOf(childWithRole(top, "bin.left").get.asInstanceOf[UniNode.Branch]) == "-")
    assert(kindOf(childWithRole(top, "bin.right").get) == "spike.int")
  }

  test("cross-tier precedence: comparison binds looser than arithmetic") {
    // `1 + 2 < 3 * 4` → <( +(1,2), *(3,4) )  (`+`,`*` tier > `<` tier)
    val top = defBody(parse("def main(): Int = 1 + 2 < 3 * 4")).asInstanceOf[UniNode.Branch]
    assert(opOf(top) == "<")
    assert(opOf(childWithRole(top, "bin.left").get.asInstanceOf[UniNode.Branch]) == "+")
    assert(opOf(childWithRole(top, "bin.right").get.asInstanceOf[UniNode.Branch]) == "*")
  }

  test("boolean tiers: `&&` binds tighter than `||`") {
    // `a && b || c` → ||( &&(a,b), c )
    val top = defBody(parse("def main(): Int = a && b || c")).asInstanceOf[UniNode.Branch]
    assert(opOf(top) == "||")
    assert(opOf(childWithRole(top, "bin.left").get.asInstanceOf[UniNode.Branch]) == "&&")
  }

  test("multi-char operators lex maximally: `==` `<=` `>>` are single infix ops") {
    for (code, op) <- Seq(("a == b", "=="), ("a <= b", "<="), ("a >> b", ">>"), ("a != b", "!="), ("a ++ b", "++")) do
      val top = defBody(parse(s"def main(): Int = $code")).asInstanceOf[UniNode.Branch]
      assert(top.kind == "spike.infix" && opOf(top) == op, s"$code → ${opOf(top)}")
  }

  // ══ P6.2b — offside layout (indented def-body blocks) ═════════════════════════

  test("offside: indented def body is a block(val, val, exprStmt)") {
    val pr = parse("def main(): Int =\n  val a = 1\n  val b = 2\n  a + b")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val body = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.block")
    assert(childKinds(body) == Vector("spike.val", "spike.val", "spike.exprStmt"))
  }

  test("offside: inline body stays a bare expr (no block)") {
    assert(defBody(parse("def main(): Int = 1 + 2")).asInstanceOf[UniNode.Branch].kind == "spike.infix")
  }

  test("offside: a leading-operator continuation line stays one statement") {
    // `val a = 1 +` ⏎ (deeper) `2` → RHS is `1 + 2`; then `a` is the final expr stmt
    val body = defBody(parse("def main(): Int =\n  val a = 1 +\n    2\n  a")).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.block")
    assert(childKinds(body) == Vector("spike.val", "spike.exprStmt"))
  }

  test("offside: a dedent to a top-level `def` ends the block; both defs survive") {
    val pr = parse("def a(): Int =\n  val x = 1\n  x\ndef main(): Int = 2")
    assert(allBranches(pr.roots.head, "spike.def").size == 2)
    assert(allBranches(pr.roots.head, "spike.block").size == 1) // only `a` has a block
  }

  // ══ P6.1 — total, error-resilient pipeline ════════════════════════════════════

  test("parser + projection are TOTAL — never throw on garbage") {
    val garbage = Seq(
      "", "   ", "def", "def (", ")))", "1 + + 2", "def f(: = )", "@#$%^",
      "def main(): Int =", "if if if", "def main(): Int = (1 + ", "def a b c d e", "1 <<< >>>= 2"
    )
    for g <- garbage do
      val pr = parse(g)
      val proj = pr.roots.headOption.map(SpikeProject.program).getOrElse("Nil")
      assert(proj != null)
  }

  test("error CONTAINMENT — a broken def does not poison a sibling") {
    val pr = parse("def broken(): Int = 1 +\ndef main(): Int = 2 * 3")
    assert(pr.status == CompletionStatus.Incomplete)
    assert(pr.diagnostics.exists(_.code == "spike.missing-operand"), pr.diagnostics)
    val prog = pr.roots.head
    assert(allBranches(prog, "spike.def").size == 2)
    val mainBody = childWithRole(allBranches(prog, "spike.def")(1), "def.body").get.asInstanceOf[UniNode.Branch]
    assert(mainBody.kind == "spike.infix" && opOf(mainBody) == "*")
    val badAdd = childWithRole(allBranches(prog, "spike.def")(0), "def.body").get.asInstanceOf[UniNode.Branch]
    assert(badAdd.kind == "spike.infix" && opOf(badAdd) == "+")
    assert(childWithRole(badAdd, "bin.right").isEmpty) // no right → hole
  }

  test("junk between defs is wrapped in a spike.error node and both defs survive") {
    val pr = parse("def a(): Int = 1\n@ @ @\ndef main(): Int = 2 * 3")
    val prog = pr.roots.head
    assert(findBranch(prog, "spike.error").isDefined)
    assert(allBranches(prog, "spike.def").size == 2)
    assert(pr.diagnostics.exists(_.severity == Severity.Error))
  }

  test("missing operand projects to a __notImplemented__ hole; the rest is intact") {
    val pr = parse("def broken(): Int = 1 +\ndef main(): Int = 2 * 3")
    val proj = SpikeProject.program(pr.roots.head)
    assert(proj.contains("__notImplemented__"))
    assert(proj.contains("""mkInf("*", mkInt("2"), mkInt("3"))"""))
  }

  test("diagnostics carry a code, Error severity and a span") {
    val pr = parse("def main(): Int = 1 +")
    assert(pr.diagnostics.nonEmpty)
    val d = pr.diagnostics.head
    assert(d.severity == Severity.Error)
    assert(d.code.startsWith("spike."))
    assert(d.span.isDefined)
  }

  // ══ emit projections + toys for the end-to-end run-ir / Core IR diff harness ═══

  test("emit projections + toys for the diff harness") {
    val outDir = sys.env.getOrElse("SPIKE_OUT",
      "/private/tmp/claude-501/-Users-sergiy-work-my-scalascript/0ae59ae0-0693-4dfa-b393-87f68bf3d01b/scratchpad/p6.0")
    Files.createDirectories(Paths.get(outDir))
    // well-formed — the harness requires byte-identical Core IR vs ssc1-front.
    val wellFormed = Seq(
      "add-mul"   -> "def main(): Int = 1 + 2 * 3",
      "mul-add"   -> "def main(): Int = 1 * 2 + 3",
      "paren"     -> "def main(): Int = (1 + 2) * 3",
      "nested"    -> "def main(): Int = 2 * (3 + 4) - 5",
      "ops-prec"  -> "def main(): Int = 1 + 2 * 3 - 4 / 2 % 3",
      "ops-assoc" -> "def main(): Int = 10 - 3 - 2",
      "ops-shift" -> "def main(): Int = 1 + 2 << 3 - 1",
      "ops-cmp"   -> "def main(): Int = 1 + 2 * 3 < 4 + 5",
      "ops-bool"  -> "def main(): Int = 1 < 2 && 3 > 4 || 5 == 5",
      "ops-bit"   -> "def main(): Int = 10 % 3 + 4 & 6 | 1",
      // P6.2b offside — indented def-body blocks (val bindings + final expr)
      "block-vals"   -> "def main(): Int =\n  val a = 1 + 2\n  val b = a * 3\n  a + b",
      "block-single" -> "def main(): Int =\n  1 + 2",
      "block-cont"   -> "def main(): Int =\n  val a = 1 +\n    2\n  a * 10"
    )
    // broken — no oracle; the harness proves containment (`main` still runs).
    val broken = Seq(
      ("broken-sibling", "def broken(): Int = 1 +\ndef main(): Int = 2 * 3", "6")
    )
    for (name, code) <- wellFormed do
      Files.writeString(Paths.get(outDir, s"$name.proj"), SpikeProject.program(parse(code).roots.head))
      Files.writeString(Paths.get(outDir, s"$name.toy.ssc"), code + "\n")
      Files.deleteIfExists(Paths.get(outDir, s"$name.expect"))
    for (name, code, expect) <- broken do
      Files.writeString(Paths.get(outDir, s"$name.proj"), SpikeProject.program(parse(code).roots.head))
      Files.writeString(Paths.get(outDir, s"$name.expect"), expect)
      Files.deleteIfExists(Paths.get(outDir, s"$name.toy.ssc"))
    Files.writeString(Paths.get(outDir, "EMITTED"), (wellFormed.map(_._1) ++ broken.map(_._1)).mkString("\n"))
    succeed
  }
