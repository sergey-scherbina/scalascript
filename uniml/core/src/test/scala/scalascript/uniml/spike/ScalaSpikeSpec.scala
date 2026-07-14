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

  test("type parameters on defs are erased (plain [A])") {
    val pr = parse("def useIt[A](x: A): Int = x")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    assert(SpikeProject.program(pr.roots.head).contains("""mkDef("useIt", Cons("x", Nil)"""))
  }

  test("prefix (-e), range (a to b) and typed pattern (p: T) parse to the right nodes") {
    assert(defBody(parse("def main(): Int = -5")).asInstanceOf[UniNode.Branch].kind == "spike.pre")
    assert(defBody(parse("def main(): Int = 1 to 5")).asInstanceOf[UniNode.Branch].kind == "spike.rangeop")
    val m = defBody(parse("def main(): Int = 5 match\n  case x: Int => x\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    assert(childWithRole(arms(m).head, "case.pat").get.asInstanceOf[UniNode.Branch].kind == "spike.tpat")
  }

  test("cons `::` (right-associative) and arrow `->` operators") {
    val cons = defBody(parse("def main(): Int = 1 :: 2 :: Nil")).asInstanceOf[UniNode.Branch]
    assert(cons.kind == "spike.infix" && opOf(cons) == "::")
    assert(opOf(childWithRole(cons, "bin.right").get.asInstanceOf[UniNode.Branch]) == "::") // right-assoc
    val arrow = defBody(parse("def main(): Int = 1 -> 2")).asInstanceOf[UniNode.Branch]
    assert(arrow.kind == "spike.infix" && opOf(arrow) == "->")
  }

  test("`(1 + 2) * 3` — parens override precedence") {
    val mul = defBody(parse("def main(): Int = (1 + 2) * 3")).asInstanceOf[UniNode.Branch]
    assert(mul.kind == "spike.infix" && opOf(mul) == "*")
    val paren = childWithRole(mul, "bin.left").get.asInstanceOf[UniNode.Branch]
    assert(paren.kind == "spike.paren")
    assert(kindOf(childWithRole(paren, "group.elem").get) == "spike.infix")
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

  // ══ P6.2c — match + patterns (int-literal / `_` / variable, + guard) ══════════

  private def arms(m: UniNode.Branch): Vector[UniNode.Branch] =
    m.edges.collect { case UniEdge(_, a @ UniNode.Branch("spike.arm", _, _, _)) => a }

  test("match: offside literal + wildcard arms") {
    val m = defBody(parse("def main(): Int = 5 match\n  case 5 => 42\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    assert(m.kind == "spike.match")
    assert(childWithRole(m, "match.scrut").exists { case UniNode.Token(t) => t.lexeme == "5"; case _ => false })
    val as = arms(m)
    assert(as.size == 2)
    assert(childWithRole(as(0), "case.pat").exists(kindOf(_) == "spike.int"))
    assert(childWithRole(as(1), "case.pat").exists { case UniNode.Token(t) => t.lexeme == "_"; case _ => false })
  }

  test("match: braced form parses the same arms") {
    val m = defBody(parse("def main(): Int = 3 match { case 3 => 30 case _ => 0 }")).asInstanceOf[UniNode.Branch]
    assert(m.kind == "spike.match")
    assert(arms(m).size == 2)
  }

  test("match: a variable pattern binds; a guard is captured") {
    val m = defBody(parse("def main(): Int = 8 match\n  case n if n > 5 => n\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    val a0 = arms(m).head
    assert(childWithRole(a0, "case.pat").exists { case UniNode.Token(t) => t.lexeme == "n"; case _ => false })
    assert(childWithRole(a0, "case.guard").isDefined)
  }

  test("ctor pattern: Some(x) / None parse to spike.cpat with the ctor name") {
    val m = defBody(parse("def main(): Int = Some(5) match\n  case Some(x) => x\n  case None => 0")).asInstanceOf[UniNode.Branch]
    val p0 = childWithRole(arms(m)(0), "case.pat").get.asInstanceOf[UniNode.Branch]
    assert(p0.kind == "spike.cpat")
    assert(childWithRole(p0, "cpat.name").exists { case UniNode.Token(t) => t.lexeme == "Some"; case _ => false })
    assert(childWithRole(arms(m)(1), "case.pat").get.asInstanceOf[UniNode.Branch].kind == "spike.cpat") // None nullary
  }

  test("alternative (A | B) and bind (n @ P) patterns parse to spike.apat / spike.bpat") {
    val m = defBody(parse("def main(): Int = 2 match\n  case 1 | 2 | 3 => 100\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    assert(childWithRole(arms(m).head, "case.pat").get.asInstanceOf[UniNode.Branch].kind == "spike.apat")
    val m2 = defBody(parse("def main(): Int = Some(9) match\n  case n @ Some(v) => v\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    assert(childWithRole(arms(m2).head, "case.pat").get.asInstanceOf[UniNode.Branch].kind == "spike.bpat")
  }

  test("case class: declaration + field access project to mkCaseCls / mkSel") {
    val pr = parse("case class Point(x: Int, y: Int)\ndef main(): Int = Point(3, 4).x")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val prog = pr.roots.head
    val cc = allBranches(prog, "spike.casecls")
    assert(cc.size == 1)
    assert(childWithRole(cc.head, "cc.name").exists { case UniNode.Token(t) => t.lexeme == "Point"; case _ => false })
    assert(cc.head.edges.count { case UniEdge(Some("cc.field"), _) => true; case _ => false } == 2)
    val body = childWithRole(allBranches(prog, "spike.def").head, "def.body").get
    assert(kindOf(body) == "spike.sel")
    val proj = SpikeProject.program(prog)
    assert(proj.contains("""mkCaseCls("Point""""), proj)
    assert(proj.contains("mkSel("), proj)
  }

  test("given + summon parse to spike.given / spike.summon and project to the right nodes") {
    val pr = parse("given g: Int = 42\ndef main(): Int = summon[Int]")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val prog = pr.roots.head
    assert(allBranches(prog, "spike.given").size == 1)
    assert(kindOf(childWithRole(allBranches(prog, "spike.def").head, "def.body").get) == "spike.summon")
    val proj = SpikeProject.program(prog)
    assert(proj.contains("""Pair("given", Pair("g", Pair("Int","""), proj)
    assert(proj.contains("""Pair("summon", "Int")"""), proj)
  }

  test("enum: nullary + parametrized cases parse and project") {
    val pr = parse("enum Opt:\n  case Sm(v: Int)\n  case Nn\ndef main(): Int = 0")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val e = allBranches(pr.roots.head, "spike.enum").head
    assert(e.edges.count { case UniEdge(_, UniNode.Branch("spike.enumcase", _, _, _)) => true; case _ => false } == 2)
    val proj = SpikeProject.program(pr.roots.head)
    assert(proj.contains("""Pair("enum", Pair("Opt""""), proj)
    assert(proj.contains("""Pair("Sm", Cons("v", Nil))"""), proj)
  }

  test("enum: comma-separated nullary cases (Scala sugar)") {
    val pr = parse("enum Color:\n  case Red, Green, Blue\ndef main(): Int = 0")
    val e = allBranches(pr.roots.head, "spike.enum").head
    assert(e.edges.count { case UniEdge(_, UniNode.Branch("spike.enumcase", _, _, _)) => true; case _ => false } == 3)
  }

  test("extension: receiver prepended to the method params; markers emitted") {
    val pr = parse("extension (x: Int) def double: Int = x * 2\ndef main(): Int = 5.double")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    assert(allBranches(pr.roots.head, "spike.extension").size == 1)
    val proj = SpikeProject.program(pr.roots.head)
    assert(proj.contains("""Pair("extension_start", "")"""), proj)
    assert(proj.contains("""mkDef("double", Cons("x", Nil)"""), proj)
    assert(proj.contains("""Pair("extension_end", "")"""), proj)
  }

  test("tuple pattern parses to spike.tuppat; uid application projects to mkUVar/mkApp") {
    val m = defBody(parse("def main(): Int = Pair(1, 2) match\n  case (a, b) => a\n  case _ => 0")).asInstanceOf[UniNode.Branch]
    assert(childWithRole(arms(m).head, "case.pat").get.asInstanceOf[UniNode.Branch].kind == "spike.tuppat")
    val proj = SpikeProject.program(parse("def main(): Int = Some(5)").roots.head)
    assert(proj.contains("""mkApp(mkUVar("Some")"""), proj)
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

  // ══ string literals — plain + escapes, mirroring ssc1-front buildStr ══════════════════════════

  test("a plain string literal projects to mkStr with the decoded value") {
    val proj = SpikeProject.program(parse("def greet(): String = \"hello world\"").roots.head)
    assert(proj.contains("""mkStr("hello world")"""), proj)
  }

  test("string escapes: \\n and \\t decode, \\<other> is the bare char (ssc1-front buildStr)") {
    // source: "a\tb\nc\"d\\e"  → value  a<TAB>b<NL>c"d\e  → re-encoded mkStr("a\tb\nc\"d\\e")
    val proj = SpikeProject.program(parse("def s(): String = \"a\\tb\\nc\\\"d\\\\e\"").roots.head)
    assert(proj.contains("""mkStr("a\tb\nc\"d\\e")"""), proj)
  }

  test("a triple-quoted string is raw — no escape processing") {
    val proj = SpikeProject.program(parse("def s(): String = \"\"\"a\\tb\"\"\"").roots.head)
    assert(proj.contains("""mkStr("a\\tb")"""), proj) // \t stays two chars: backslash, t
  }

  // ══ trailing-newline tolerance: a trailing EOL is trivia, never a projection change ═══════════

  test("a trailing newline is trivia — the projection (hence Core IR) is invariant to it") {
    val bare = SpikeProject.program(parse("def main(): Int = 1 + 2 * 3").roots.head)
    val one  = SpikeProject.program(parse("def main(): Int = 1 + 2 * 3\n").roots.head)
    val many = SpikeProject.program(parse("def main(): Int = 1 + 2 * 3\n\n").roots.head)
    val crlf = SpikeProject.program(parse("def main(): Int = 1 + 2 * 3\r\n").roots.head)
    assert(bare == one, "one trailing \\n must not change the projection")
    assert(bare == many, "trailing blank lines must not change the projection")
    assert(bare == crlf, "a trailing CRLF must not change the projection")
    // multi-statement bodies too: the fence body a composer feeds always ends in a newline
    val ccBare = SpikeProject.program(parse("case class P(x: Int)\ndef main(): Int = P(1).x").roots.head)
    val ccNl   = SpikeProject.program(parse("case class P(x: Int)\ndef main(): Int = P(1).x\n").roots.head)
    assert(ccBare == ccNl)
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
      "block-cont"   -> "def main(): Int =\n  val a = 1 +\n    2\n  a * 10",
      // P6.2c match — int-literal / `_` / variable patterns + guard
      "match-lit"    -> "def main(): Int = 5 match\n  case 5 => 42\n  case _ => 0",
      "match-var"    -> "def main(): Int = 7 match\n  case 0 => 1\n  case n => n + 100",
      "match-guard"  -> "def main(): Int = 8 match\n  case n if n > 5 => n * 2\n  case _ => 0",
      "match-braced" -> "def main(): Int = 3 match { case 3 => 30 case _ => 0 }",
      // P6.2c part 2 — constructor / tuple patterns + uid (uppercase) ctor apps
      "ctor-some" -> "def main(): Int = Some(5) match\n  case Some(x) => x\n  case None => 0",
      "ctor-none" -> "def main(): Int = None match\n  case Some(x) => x\n  case None => 42",
      "ctor-cons" -> "def main(): Int = Cons(3, Nil) match\n  case Cons(h, t) => h\n  case Nil => 0",
      "tuple-pat" -> "def main(): Int = (4, 5) match\n  case (a, b) => a + b\n  case _ => 0",
      // P6.2d — case class declaration + construction + field access + ctor pattern
      "cc-field"  -> "case class Point(x: Int, y: Int)\ndef main(): Int = Point(3, 4).x",
      "cc-arith"  -> "case class Point(x: Int, y: Int)\ndef main(): Int = Point(3, 4).x + Point(3, 4).y",
      "cc-match"  -> "case class Box(a: Int, b: Int)\ndef main(): Int = Box(10, 20) match\n  case Box(x, y) => x + y\n  case _ => 0",
      // P6.2e — given instance + summon (typeclass resolution core; lowerProg does dict-passing)
      "given-summon"  -> "given g: Int = 42\ndef main(): Int = summon[Int]",
      "given-summon2" -> "given g: Int = 7\ndef main(): Int = summon[Int] + 1",
      // P6.2f — enum (nullary comma-sugar + parametrized cases); lowerProg reuses the ctor path
      "enum-nullary" -> "enum Color:\n  case Red, Green, Blue\ndef main(): Int = Green match\n  case Red => 1\n  case Green => 2\n  case Blue => 3",
      "enum-params"  -> "enum Opt:\n  case Sm(v: Int)\n  case Nn\ndef main(): Int = Sm(9) match\n  case Sm(v) => v\n  case Nn => 0",
      // P6.2g — extension methods (receiver prepended; `.m` dispatch via extensionMethodsCell)
      "ext-method" -> "extension (x: Int)\n  def double: Int = x * 2\ndef main(): Int = 5.double",
      // P6.2h — pattern completion: alternatives (A | B) and bind (n @ P)
      "pat-alt"  -> "def main(): Int = 2 match\n  case 1 | 2 | 3 => 100\n  case _ => 0",
      "pat-bind" -> "def main(): Int = Some(9) match\n  case n @ Some(v) => v\n  case _ => 0",
      // P6.2i — special operators: `::` (cons, right-assoc) and `->` (pair)
      "op-cons"  -> "def main(): Int = (1 :: 2 :: Nil) match\n  case Cons(h, t) => h\n  case Nil => 0",
      "op-arrow" -> "def main(): Int = (1 -> 2) match\n  case (a, b) => a + b\n  case _ => 0",
      // P6.2j — prefix ops, to/until ranges, typed patterns
      "op-prefix" -> "def main(): Int = -5 + 3",
      "op-range"  -> "def main(): Int = (1 until 5) match\n  case _ => 7",
      "pat-typed" -> "def main(): Int = 5 match\n  case x: Int => x\n  case _ => 0",
      // P6.2k — type parameters on defs (plain [A], erased); summon a concrete given from a generic def
      "tparam" -> "given g: Int = 99\ndef useIt[A](x: A): Int = summon[Int]\ndef main(): Int = useIt(5)",
      // P6.3d — string literals: plain + \t/\n escapes (mkStr, buildStr semantics)
      "str-plain"  -> "def greet(): String = \"hello world\"\ndef main(): Int = 5",
      "str-escape" -> "def s(): String = \"a\\tb\\nc\"\ndef main(): Int = 6"
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
