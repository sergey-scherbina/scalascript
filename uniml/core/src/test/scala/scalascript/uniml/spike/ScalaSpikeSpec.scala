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

  // ══ string interpolation — s / ${expr} / md, mirroring ssc1-front interpParts/partsToExpr ═══════

  test("s-interpolation: literals + $name fold into a right-assoc ++ concatenation") {
    val proj = SpikeProject.program(parse("def g(name: String): String = s\"hi $name!\"").roots.head)
    assert(proj.contains("""mkInf("++", mkStr("hi "), mkInf("++", mkVar("name"), mkStr("!")))"""), proj)
  }

  test("s-interpolation: ${expr} re-parses the inner expression with the spike front itself") {
    val proj = SpikeProject.program(parse("def f(x: Int): String = s\"n=${x + 1}\"").roots.head)
    assert(proj.contains("""mkInf("++", mkStr("n="), mkInf("+", mkVar("x"), mkInt("1")))"""), proj)
  }

  test("md-interpolation routes the s-interpolated string through __mdStrip__") {
    val proj = SpikeProject.program(parse("def h(t: String): String = md\"# $t\"").roots.head)
    assert(proj.contains("""Pair("prim", Pair("__mdStrip__", Cons(mkInf("++", mkStr("# "), mkVar("t")), Nil)))"""), proj)
  }

  test("f-interpolation: a %spec is peeled off the next literal → __fInterpolate__ (buildFInterp)") {
    val proj = SpikeProject.program(parse("def f(x: Int): String = f\"n=$x%d!\"").roots.head)
    assert(proj.contains(
      """mkApp(mkVar("__fInterpolate__"), Cons(mkStr("n="), Cons(mkStr("%d"), Cons(mkVar("x"), Cons(mkStr("!"), Nil)))))"""
    ), proj)
  }

  // ══ booleans, floats, lambdas, comments — closing ssc1-front grammar the spike lacked ══════════

  test("true/false are boolean literals (mkBool), not variables") {
    assert(SpikeProject.program(parse("def m(): Int = if true then 1 else 0").roots.head).contains("""mkBool("true")"""))
    assert(SpikeProject.program(parse("def m(): Boolean = false").roots.head).contains("""mkBool("false")"""))
  }

  test("float literals project to mkFloat; `1.field` stays int + selector") {
    assert(SpikeProject.program(parse("def m(): Double = 3.14").roots.head).contains("""mkFloat("3.14")"""))
    val sel = SpikeProject.program(parse("def m(): Int = 1.toInt").roots.head)
    assert(sel.contains("""mkInt("1")""") && sel.contains("toInt"), sel) // 1.toInt = sel, not a float
  }

  test("lambdas: `x => e` and `(a, b) => e` project to mkLam with the right param list") {
    assert(SpikeProject.program(parse("def m(): Int = f(x => x + 1)").roots.head)
      .contains("""mkLam(Cons("x", Nil), mkInf("+", mkVar("x"), mkInt("1")))"""))
    assert(SpikeProject.program(parse("def m(): Int = g((a, b) => a + b)").roots.head)
      .contains("""mkLam(Cons("a", Cons("b", Nil)), mkInf("+", mkVar("a"), mkVar("b")))"""))
  }

  test("`(a, b)` with no `=>` stays a tuple — the lambda lookahead backtracks") {
    assert(SpikeProject.program(parse("def m(): Int = (1, 2) match\n  case (a, b) => a + b").roots.head)
      .contains("mkTup"))
  }

  test("comments (// and /* */) are trivia — no effect on the projection") {
    val withC = SpikeProject.program(parse("def m(): Int =\n  // pick\n  val a = 2 /* two */\n  a + 1").roots.head)
    val without = SpikeProject.program(parse("def m(): Int =\n  val a = 2\n  a + 1").roots.head)
    assert(withC == without, s"comments changed the projection:\n$withC\n$without")
  }

  test("if/else branches may be indented blocks (offside), not just inline expressions") {
    val proj = SpikeProject.program(parse("def f(n: Int): Int =\n  if n > 0 then\n    val b = 2\n    n + b\n  else\n    0").roots.head)
    assert(proj.contains("mkIf") && proj.contains("mkVal"), proj) // the then-branch block carries a val
  }

  test("function types are erased; chained application f(a)(b) applies twice; List[T] params erase") {
    assert(SpikeProject.program(parse("def mk(): Int => Int = x => x + 1").roots.head).contains("mkLam"))
    assert(SpikeProject.program(parse("def m(): Int = mk()(4)").roots.head)
      .contains("""mkApp(mkApp(mkVar("mk"), Nil), Cons(mkInt("4"), Nil))"""))
    assert(SpikeProject.program(parse("def len(xs: List[Int]): Int = 0").roots.head).contains("""mkDef("len""""))
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
    // a scale program: enum + match + case class + given/summon + if/else-if + interpolation +
    // lambdas + blocks + recursion, all in one module — a "gold-standard" whole-front interaction test.
    val scaleProg =
      """|enum Expr:
         |  case Num(v: Int)
         |  case Add(l: Expr, r: Expr)
         |  case Mul(l: Expr, r: Expr)
         |def eval(e: Expr): Int = e match
         |  case Num(v) => v
         |  case Add(l, r) => eval(l) + eval(r)
         |  case Mul(l, r) => eval(l) * eval(r)
         |case class Point(x: Int, y: Int)
         |def dist(p: Point): Int = p.x * p.x + p.y * p.y
         |given base: Int = 10
         |def scale(n: Int): Int =
         |  val factor = summon[Int]
         |  n * factor
         |def describe(n: Int): String =
         |  if n > 0 then s"positive:$n"
         |  else if n < 0 then s"negative:$n"
         |  else "zero"
         |def twice(n: Int): Int =
         |  val f = x => x + x
         |  f(n)
         |def main(): Int =
         |  val e = Add(Num(3), Mul(Num(4), Num(5)))
         |  val d = dist(Point(3, 4))
         |  eval(e) + d + scale(2) + twice(5)""".stripMargin
    // more gold-standard scale programs, each stressing a different interaction boundary.
    val scaleDecls = // declaration boundaries: enum → match-def → extension → given → match-def → main
      """|enum Color:
         |  case Red, Green, Blue
         |def rank(c: Color): Int = c match
         |  case Red => 1
         |  case Green => 2
         |  case Blue => 3
         |extension (n: Int)
         |  def bump: Int = n + 10
         |given base: Int = 100
         |def classify(n: Int): Int = n match
         |  case 0 => 0
         |  case x if x > 0 => 1
         |  case _ => 2
         |def main(): Int =
         |  val a = rank(Green).bump
         |  a + classify(5) + summon[Int]""".stripMargin
    val scaleNested = // nested match inside a match arm, lambda in a block, deep offside
      """|def process(n: Int): Int =
         |  val transform = x => x + 1
         |  n match
         |    case 0 => 0
         |    case m =>
         |      m match
         |        case 1 => transform(10)
         |        case _ => transform(m)
         |def main(): Int = process(1)""".stripMargin
    val scaleInterp = // interpolation with ${field} holes + nested interpolation in blocks
      """|case class User(name: String, age: Int)
         |def greet(u: User): String = s"${u.name}=${u.age}"
         |def wrap(u: User): String =
         |  val g = greet(u)
         |  s"[$g]"
         |def main(): Int =
         |  val u = User("Al", 30)
         |  wrap(u).size""".stripMargin
    val scaleHof = // recursion + multi-param lambda + operators + if
      """|def fold(n: Int, acc: Int): Int =
         |  if n == 0 then acc
         |  else fold(n - 1, acc + n)
         |def main(): Int =
         |  val sum = fold(5, 0)
         |  val mul = (a, b) => a * b
         |  sum + mul(2, 3)""".stripMargin
    // ══ P6.4 SELF-HOST PROOF ══════════════════════════════════════════════════════════════════
    // A real compiler, written entirely in the subset: a prefix-notation arithmetic language is
    // tokenised (Cons-list of ints; -1=+, -2=*, ≥0=literal), parsed into an AST (int-tagged tuples),
    // COMPILED to a stack-machine program, and executed by a stack VM. If the spike compiles THIS
    // to Core IR byte-identical to ssc1-front AND it runs to the right answer, the subset hosts a
    // compiler and the spike compiles it exactly as the trusted front does.  + 3 * 4 5  →  3+(4*5) = 23.
    val selfhostArith =
      """|def parseBin(tag: Int, r0: Int): Int = r0 match { case (l, r1) => parse(r1) match { case (r, r2) => ((tag, (l, r)), r2) } }
         |def parse(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) => if t == -1 then parseBin(2, parse(rest)) else if t == -2 then parseBin(3, parse(rest)) else ((0, t), rest)
         |  case Nil => ((0, 0), Nil)
         |}
         |def compile(e: Int, acc: List[Int]): List[Int] = e match {
         |  case (0, n) => Cons((0, n), acc)
         |  case (2, lr) => lr match { case (l, r) => Cons((1, 0), compile(r, compile(l, acc))) }
         |  case (3, lr) => lr match { case (l, r) => Cons((2, 0), compile(r, compile(l, acc))) }
         |  case _ => acc
         |}
         |def rev(xs: List[Int], acc: List[Int]): List[Int] = xs match { case Nil => acc case Cons(h, t) => rev(t, Cons(h, acc)) }
         |def exec(ops: List[Int], stack: List[Int]): List[Int] = ops match {
         |  case Nil => stack
         |  case Cons(op, rest) => op match {
         |    case (0, n) => exec(rest, Cons(n, stack))
         |    case (k, u) => stack match { case Cons(b, Cons(a, s)) => exec(rest, Cons(if k == 1 then a + b else a * b, s)) case _ => exec(rest, stack) }
         |  }
         |}
         |def top(s: List[Int]): Int = s match { case Cons(h, t) => h case Nil => 0 }
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def run(ts: List[Int]): Int =
         |  val ast = fst(parse(ts))
         |  val prog = rev(compile(ast, Nil), Nil)
         |  top(exec(prog, Nil))
         |def main(): Int = run(Cons(-1, Cons(3, Cons(-2, Cons(4, Cons(5, Nil))))))""".stripMargin
    // A second self-host artifact: an interpreter for a let/variable language (de Bruijn indices +
    // an environment) — scoping, the heart of a real compiler.  let x = 3+4 in x*(x+1)  →  7*8 = 56.
    val selfhostEval =
      """|def lookup(env: List[Int], id: Int): Int = env match { case Nil => 0 case Cons(v, rest) => if id == 0 then v else lookup(rest, id - 1) }
         |def eval(e: Int, env: List[Int]): Int = e match {
         |  case (0, n) => n
         |  case (1, id) => lookup(env, id)
         |  case (2, lr) => lr match { case (l, r) => eval(l, env) + eval(r, env) }
         |  case (3, lr) => lr match { case (l, r) => eval(l, env) * eval(r, env) }
         |  case (4, vb) => vb match { case (value, body) => eval(body, Cons(eval(value, env), env)) }
         |  case _ => 0
         |}
         |def main(): Int = eval((4, ((2, ((0, 3), (0, 4))), (3, ((1, 0), (2, ((1, 0), (0, 1))))))), Nil)""".stripMargin
    // A third self-host artifact: a HIGHER-ORDER interpreter with CLOSURES — the meta-circular heart.
    // Values are tagged: (0,n)=int, (1,(body,env))=closure capturing its defining environment. A lambda
    // (tag 7) evaluates to a closure; application (tag 8) extends the closure's captured env with the arg.
    // (λf. f(f(3))) (λx. x*2)  →  double(double(3))  →  12.  This is first-class functions + lexical
    // capture — the core of any real compiler — written entirely in the subset, and it runs.
    val selfhostClosures =
      """|def lookup(env: List[Int], id: Int): Int = env match { case Nil => (0, 0) case Cons(v, rest) => if id == 0 then v else lookup(rest, id - 1) }
         |def asInt(v: Int): Int = v match { case (0, n) => n case _ => 0 }
         |def apply1(fv: Int, av: Int): Int = fv match { case (1, be) => be match { case (body, cenv) => eval(body, Cons(av, cenv)) } case _ => (0, 0) }
         |def eval(e: Int, env: List[Int]): Int = e match {
         |  case (0, n) => (0, n)
         |  case (1, id) => lookup(env, id)
         |  case (2, lr) => lr match { case (l, r) => (0, asInt(eval(l, env)) + asInt(eval(r, env))) }
         |  case (3, lr) => lr match { case (l, r) => (0, asInt(eval(l, env)) * asInt(eval(r, env))) }
         |  case (7, body) => (1, (body, env))
         |  case (8, fa) => fa match { case (f, a) => apply1(eval(f, env), eval(a, env)) }
         |  case _ => (0, 0)
         |}
         |def main(): Int =
         |  val dbl = (7, (3, ((1, 0), (0, 2))))
         |  val twice = (7, (8, ((1, 0), (8, ((1, 0), (0, 3))))))
         |  asInt(eval((8, (twice, dbl)), Nil))""".stripMargin
    // The strongest self-host artifact: a COMPLETE compiler from SOURCE TEXT. It reads actual characters
    // — a real lexer (s.charAt / s.length, digits, `+`/`*`, spaces) turns a prefix-arithmetic string into
    // a token list, a recursive-descent parser builds an AST, and an evaluator computes the result. Lexer
    // + parser + evaluator over a `String`, all in the subset.  "+ 1 * 2 3"  →  1 + (2*3)  →  7.
    val selfhostFull =
      """|def isDigit(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int =
         |  if i >= n then (acc, i)
         |  else
         |    val c = s.charAt(i)
         |    if isDigit(c) == 1 then scanNum(s, i + 1, n, acc * 10 + (c - 48))
         |    else (acc, i)
         |def tokenize(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 43 then Cons(-1, tokenize(s, i + 1, n))
         |    else if c == 42 then Cons(-2, tokenize(s, i + 1, n))
         |    else if c == 32 then tokenize(s, i + 1, n)
         |    else if isDigit(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons(v, tokenize(s, j, n)) }
         |    else tokenize(s, i + 1, n)
         |def parseBin(tag: Int, r0: Int): Int = r0 match { case (l, r1) => parse(r1) match { case (r, r2) => ((tag, (l, r)), r2) } }
         |def parse(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) => if t == -1 then parseBin(2, parse(rest)) else if t == -2 then parseBin(3, parse(rest)) else ((0, t), rest)
         |  case Nil => ((0, 0), Nil)
         |}
         |def eval(e: Int): Int = e match {
         |  case (0, n) => n
         |  case (2, lr) => lr match { case (l, r) => eval(l) + eval(r) }
         |  case (3, lr) => lr match { case (l, r) => eval(l) * eval(r) }
         |  case _ => 0
         |}
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def compile(src: String): Int = eval(fst(parse(tokenize(src, 0, src.length))))
         |def main(): Int = compile("+ 1 * 2 3")""".stripMargin
    // The full compiler pipeline, in the subset: source TEXT → tokens → AST → CoreIR-like S-EXPRESSION
    // TEXT. Unlike selfhost-full (which evaluates), this LOWERS — emitting the same shape of nested
    // S-expression a real Core IR is, built with string concatenation + int→string. lexer + parser +
    // lowerer, all in the subset.  "+ 1 * 2 3"  →  "(add (int 1) (mul (int 2) (int 3)))".
    val selfhostCompiler =
      """|def isDigit(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int =
         |  if i >= n then (acc, i)
         |  else
         |    val c = s.charAt(i)
         |    if isDigit(c) == 1 then scanNum(s, i + 1, n, acc * 10 + (c - 48))
         |    else (acc, i)
         |def tokenize(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 43 then Cons(-1, tokenize(s, i + 1, n))
         |    else if c == 42 then Cons(-2, tokenize(s, i + 1, n))
         |    else if c == 32 then tokenize(s, i + 1, n)
         |    else if isDigit(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons(v, tokenize(s, j, n)) }
         |    else tokenize(s, i + 1, n)
         |def parseBin(tag: Int, r0: Int): Int = r0 match { case (l, r1) => parse(r1) match { case (r, r2) => ((tag, (l, r)), r2) } }
         |def parse(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) => if t == -1 then parseBin(2, parse(rest)) else if t == -2 then parseBin(3, parse(rest)) else ((0, t), rest)
         |  case Nil => ((0, 0), Nil)
         |}
         |def lower(e: Int): String = e match {
         |  case (0, n) => "(int " + n + ")"
         |  case (2, lr) => lr match { case (l, r) => "(add " + lower(l) + " " + lower(r) + ")" }
         |  case (3, lr) => lr match { case (l, r) => "(mul " + lower(l) + " " + lower(r) + ")" }
         |  case _ => "(err)"
         |}
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def compile(src: String): String = lower(fst(parse(tokenize(src, 0, src.length))))
         |def main(): String = compile("+ 1 * 2 3")""".stripMargin
    // THE literal-self-host capstone: a compiler in the subset that emits REAL, EXECUTABLE Core IR for an
    // expression language with arithmetic + comparison + CONTROL FLOW. The lowerer produces `(prim i.add…)`
    // / `i.mul` / `i.sub` / `i.lt` / `(if c t e)` / `(lit (int n))`, wrapped in a runnable
    // `(program (defs (def main (lam 0 …))) (entry (app (global main))))`. Two-stage self-compilation: the
    // spike compiles THIS compiler byte-identically to ssc1-front; running it EMITS Core IR text; that text
    // runs on `run-ir` to the answer. Verified end-to-end by the harness (the `.emit` check). (The Core IR
    // target also supports functions/recursion — `(lam n …)`/`(local i)`/`(app (global f) …)`, verified
    // separately — so extending the object language to a Turing-complete one is mechanical, not a gap.)
    val selfhostEmit =
      """|def isDigit(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int =
         |  if i >= n then (acc, i)
         |  else
         |    val c = s.charAt(i)
         |    if isDigit(c) == 1 then scanNum(s, i + 1, n, acc * 10 + (c - 48))
         |    else (acc, i)
         |def tokenize(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 43 then Cons(-1, tokenize(s, i + 1, n))
         |    else if c == 42 then Cons(-2, tokenize(s, i + 1, n))
         |    else if c == 45 then Cons(-5, tokenize(s, i + 1, n))
         |    else if c == 63 then Cons(-6, tokenize(s, i + 1, n))
         |    else if c == 60 then Cons(-7, tokenize(s, i + 1, n))
         |    else if c == 32 then tokenize(s, i + 1, n)
         |    else if isDigit(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons(v, tokenize(s, j, n)) }
         |    else tokenize(s, i + 1, n)
         |def parseBin(tag: Int, r0: Int): Int = r0 match { case (l, r1) => parse(r1) match { case (r, r2) => ((tag, (l, r)), r2) } }
         |def parseTri(r0: Int): Int = r0 match { case (c, r1) => parse(r1) match { case (t, r2) => parse(r2) match { case (e, r3) => ((4, (c, (t, e))), r3) } } }
         |def parse(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) =>
         |    if t == -1 then parseBin(2, parse(rest))
         |    else if t == -2 then parseBin(3, parse(rest))
         |    else if t == -5 then parseBin(5, parse(rest))
         |    else if t == -7 then parseBin(6, parse(rest))
         |    else if t == -6 then parseTri(parse(rest))
         |    else ((0, t), rest)
         |  case Nil => ((0, 0), Nil)
         |}
         |def lower(e: Int): String = e match {
         |  case (0, n) => "(lit (int " + n + "))"
         |  case (2, lr) => lr match { case (l, r) => "(prim i.add " + lower(l) + " " + lower(r) + ")" }
         |  case (3, lr) => lr match { case (l, r) => "(prim i.mul " + lower(l) + " " + lower(r) + ")" }
         |  case (5, lr) => lr match { case (l, r) => "(prim i.sub " + lower(l) + " " + lower(r) + ")" }
         |  case (6, lr) => lr match { case (l, r) => "(prim i.lt " + lower(l) + " " + lower(r) + ")" }
         |  case (4, cte) => cte match { case (c, te) => te match { case (t, e) => "(if " + lower(c) + " " + lower(t) + " " + lower(e) + ")" } }
         |  case _ => "(lit (int 0))"
         |}
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def compile(src: String): String =
         |  val body = lower(fst(parse(tokenize(src, 0, src.length))))
         |  "(program (defs (def main (lam 0 " + body + "))) (entry (app (global main))))"
         |def main(): String = compile("? < 3 5 - 10 2 + 100 200")""".stripMargin
    // The Turing-complete milestone: a compiler in the subset for a language with FUNCTIONS + RECURSION +
    // variables + control flow, emitting EXECUTABLE Core IR. The object language is one recursive function
    // f(x): `x`→(local 0), `@ e`→f(e) = (app (global f) …), `?`=if, `< + - *`=prims. It compiles the body
    // of a recursive factorial from source text into `(def f (lam 1 …)) (def main (lam 0 (app (global f)
    // (lit (int 5)))))`; the emitted Core IR runs on run-ir to factorial(5) = 120. Two-stage self-compile.
    val selfhostRec =
      """|def isDigit(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int =
         |  if i >= n then (acc, i)
         |  else
         |    val c = s.charAt(i)
         |    if isDigit(c) == 1 then scanNum(s, i + 1, n, acc * 10 + (c - 48))
         |    else (acc, i)
         |def tokenize(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 43 then Cons(-1, tokenize(s, i + 1, n))
         |    else if c == 42 then Cons(-2, tokenize(s, i + 1, n))
         |    else if c == 45 then Cons(-5, tokenize(s, i + 1, n))
         |    else if c == 63 then Cons(-6, tokenize(s, i + 1, n))
         |    else if c == 60 then Cons(-7, tokenize(s, i + 1, n))
         |    else if c == 64 then Cons(-8, tokenize(s, i + 1, n))
         |    else if c == 120 then Cons(-10, tokenize(s, i + 1, n))
         |    else if c == 32 then tokenize(s, i + 1, n)
         |    else if isDigit(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons(v, tokenize(s, j, n)) }
         |    else tokenize(s, i + 1, n)
         |def parseBin(tag: Int, r0: Int): Int = r0 match { case (l, r1) => parse(r1) match { case (r, r2) => ((tag, (l, r)), r2) } }
         |def parseTri(r0: Int): Int = r0 match { case (c, r1) => parse(r1) match { case (t, r2) => parse(r2) match { case (e, r3) => ((4, (c, (t, e))), r3) } } }
         |def parseApp(r0: Int): Int = r0 match { case (a, r1) => ((8, a), r1) }
         |def parse(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) =>
         |    if t == -1 then parseBin(2, parse(rest))
         |    else if t == -2 then parseBin(3, parse(rest))
         |    else if t == -5 then parseBin(5, parse(rest))
         |    else if t == -7 then parseBin(6, parse(rest))
         |    else if t == -6 then parseTri(parse(rest))
         |    else if t == -8 then parseApp(parse(rest))
         |    else if t == -10 then ((10, 0), rest)
         |    else ((0, t), rest)
         |  case Nil => ((0, 0), Nil)
         |}
         |def lower(e: Int): String = e match {
         |  case (0, n) => "(lit (int " + n + "))"
         |  case (10, z) => "(local 0)"
         |  case (2, lr) => lr match { case (l, r) => "(prim i.add " + lower(l) + " " + lower(r) + ")" }
         |  case (3, lr) => lr match { case (l, r) => "(prim i.mul " + lower(l) + " " + lower(r) + ")" }
         |  case (5, lr) => lr match { case (l, r) => "(prim i.sub " + lower(l) + " " + lower(r) + ")" }
         |  case (6, lr) => lr match { case (l, r) => "(prim i.lt " + lower(l) + " " + lower(r) + ")" }
         |  case (4, cte) => cte match { case (c, te) => te match { case (t, e) => "(if " + lower(c) + " " + lower(t) + " " + lower(e) + ")" } }
         |  case (8, a) => "(app (global f) " + lower(a) + ")"
         |  case _ => "(lit (int 0))"
         |}
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def compile(src: String): String =
         |  val body = lower(fst(parse(tokenize(src, 0, src.length))))
         |  "(program (defs (def f (lam 1 " + body + ")) (def main (lam 0 (app (global f) (lit (int 5)))))) (entry (app (global main))))"
         |def main(): String = compile("? < x 1 1 * x @ - x 1")""".stripMargin
    // ══ P6.5 literal-port F1: a Scala-SUBSET LEXER written in the subset ═══════════════════════════
    // A real port of SpikeLex's core: skip whitespace, scan multi-char identifiers (letter + alnum) and
    // classify keyword vs id, scan integers, scan operator runs (+-*/%<>=!&|^~:), and single-char
    // punctuation — rendering each token as `tag:lexeme`. Uses charAt/length/substring/== (all proven).
    val selfhostLexer =
      """|def isAl(c: Int): Int = if c >= 97 then (if c <= 122 then 1 else 0) else if c >= 65 then (if c <= 90 then 1 else 0) else if c == 95 then 1 else 0
         |def isDig(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def isAlnum(c: Int): Int = if isAl(c) == 1 then 1 else isDig(c)
         |def isOp(c: Int): Int =
         |  if c == 43 then 1 else if c == 45 then 1 else if c == 42 then 1 else if c == 47 then 1
         |  else if c == 37 then 1 else if c == 60 then 1 else if c == 62 then 1 else if c == 61 then 1
         |  else if c == 33 then 1 else if c == 38 then 1 else if c == 124 then 1 else if c == 94 then 1
         |  else if c == 126 then 1 else if c == 58 then 1 else 0
         |def kw(s: String): Int =
         |  if s == "def" then 1 else if s == "if" then 1 else if s == "then" then 1 else if s == "else" then 1
         |  else if s == "val" then 1 else if s == "match" then 1 else if s == "case" then 1 else 0
         |def scanWhile(s: String, i: Int, n: Int, kind: Int): Int =
         |  if i >= n then i
         |  else
         |    val c = s.charAt(i)
         |    val ok = if kind == 0 then isAlnum(c) else if kind == 1 then isDig(c) else isOp(c)
         |    if ok == 1 then scanWhile(s, i + 1, n, kind) else i
         |def lex(s: String, i: Int, n: Int): String =
         |  if i >= n then ""
         |  else
         |    val c = s.charAt(i)
         |    if c == 32 then lex(s, i + 1, n)
         |    else if isAl(c) == 1 then
         |      val j = scanWhile(s, i, n, 0)
         |      val w = s.substring(i, j)
         |      val tag = if kw(w) == 1 then "kw:" else "id:"
         |      tag + w + " " + lex(s, j, n)
         |    else if isDig(c) == 1 then
         |      val j = scanWhile(s, i, n, 1)
         |      "int:" + s.substring(i, j) + " " + lex(s, j, n)
         |    else if isOp(c) == 1 then
         |      val j = scanWhile(s, i, n, 2)
         |      "op:" + s.substring(i, j) + " " + lex(s, j, n)
         |    else "pn:" + s.substring(i, i + 1) + " " + lex(s, i + 1, n)
         |def main(): String =
         |  val src = "def f = if x then 1 else x + 2"
         |  lex(src, 0, src.length)""".stripMargin
    // ══ P6.5 literal-port F1+F2+L1: a compiler that reads REAL Scala-subset SYNTAX ═════════════════
    // Not prefix/toy syntax — actual `def f(x) = if x < 1 then 1 else x * f(x - 1)`. A lexer produces
    // tagged tokens ((0,n)=int, (1,ch)=ident, (2,k)=keyword/op/punct); a precedence-climbing parser with
    // `if`/`then`/`else`, function calls `f(e)`, parens, and variables builds the body; name resolution
    // maps the param → (local 0) and the function → (global f); the lowerer emits EXECUTABLE Core IR.
    // The emitted Core IR runs to factorial(5) = 120 (two-stage self-compilation).
    val selfhostScala =
      """|def isAl(c: Int): Int = if c >= 97 then (if c <= 122 then 1 else 0) else 0
         |def isDig(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanId(s: String, i: Int, n: Int): Int = if i >= n then i else if isAl(s.charAt(i)) == 1 then scanId(s, i + 1, n) else i
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int = if i >= n then (acc, i) else if isDig(s.charAt(i)) == 1 then scanNum(s, i + 1, n, acc * 10 + (s.charAt(i) - 48)) else (acc, i)
         |def kwCode(s: String): Int = if s == "def" then 1 else if s == "if" then 2 else if s == "then" then 3 else if s == "else" then 4 else 0
         |def lex(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 32 then lex(s, i + 1, n)
         |    else if isAl(c) == 1 then
         |      val j = scanId(s, i, n)
         |      val kc = kwCode(s.substring(i, j))
         |      if kc > 0 then Cons((2, kc), lex(s, j, n)) else Cons((1, c), lex(s, j, n))
         |    else if isDig(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons((0, v), lex(s, j, n)) }
         |    else if c == 61 then Cons((2, 5), lex(s, i + 1, n))
         |    else if c == 40 then Cons((2, 6), lex(s, i + 1, n))
         |    else if c == 41 then Cons((2, 7), lex(s, i + 1, n))
         |    else if c == 43 then Cons((2, 8), lex(s, i + 1, n))
         |    else if c == 45 then Cons((2, 9), lex(s, i + 1, n))
         |    else if c == 42 then Cons((2, 10), lex(s, i + 1, n))
         |    else if c == 60 then Cons((2, 11), lex(s, i + 1, n))
         |    else lex(s, i + 1, n)
         |def prec(t: Int): Int = t match { case (2, 11) => 1 case (2, 8) => 2 case (2, 9) => 2 case (2, 10) => 3 case _ => 0 }
         |def opStr(k: Int): String = if k == 8 then "i.add" else if k == 9 then "i.sub" else if k == 10 then "i.mul" else "i.lt"
         |def hd(ts: List[Int]): Int = ts match { case Cons(h, t) => h case Nil => (0, 0) }
         |def tl(ts: List[Int]): List[Int] = ts match { case Cons(h, t) => t case Nil => Nil }
         |def snd(p: Int): Int = p match { case (a, b) => b }
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def parseAtom(ts: List[Int], pch: Int, fch: Int): Int = ts match {
         |  case Cons(t, rest) => t match {
         |    case (0, n) => ("(lit (int " + n + "))", rest)
         |    case (1, ch) =>
         |      if ch == fch then parseExpr(tl(rest), 0, pch, fch) match { case (arg, r3) => ("(app (global f) " + arg + ")", tl(r3)) }
         |      else ("(local 0)", rest)
         |    case (2, 6) => parseExpr(rest, 0, pch, fch) match { case (e, r2) => (e, tl(r2)) }
         |    case (2, 2) => parseExpr(rest, 0, pch, fch) match { case (c, r2) => parseExpr(tl(r2), 0, pch, fch) match { case (th, r3) => parseExpr(tl(r3), 0, pch, fch) match { case (el, r4) => ("(if " + c + " " + th + " " + el + ")", r4) } } }
         |    case _ => ("(lit (int 0))", rest)
         |  }
         |  case Nil => ("(lit (int 0))", Nil)
         |}
         |def climb(left: String, ts: List[Int], minPrec: Int, pch: Int, fch: Int): Int = ts match {
         |  case Cons(t, rest) =>
         |    val p = prec(t)
         |    if p >= minPrec then
         |      if p > 0 then parseExpr(rest, p + 1, pch, fch) match { case (right, r2) => t match { case (2, k) => climb("(prim " + opStr(k) + " " + left + " " + right + ")", r2, minPrec, pch, fch) case _ => (left, ts) } }
         |      else (left, ts)
         |    else (left, ts)
         |  case Nil => (left, ts)
         |}
         |def parseExpr(ts: List[Int], minPrec: Int, pch: Int, fch: Int): Int = parseAtom(ts, pch, fch) match { case (l, r) => climb(l, r, minPrec, pch, fch) }
         |def compile(src: String): String =
         |  val toks = lex(src, 0, src.length)
         |  val fch = snd(hd(tl(toks)))
         |  val pch = snd(hd(tl(tl(tl(toks)))))
         |  val body = fst(parseExpr(tl(tl(tl(tl(tl(tl(toks)))))), 0, pch, fch))
         |  "(program (defs (def f (lam 1 " + body + ")) (def main (lam 0 (app (global f) (lit (int 5)))))) (entry (app (global main))))"
         |def main(): String = compile("def f(x) = if x < 1 then 1 else x * f(x - 1)")""".stripMargin
    // ══ P6.5: a GENERAL multi-function compiler in the subset (reads real Scala syntax) ════════════
    // Several `def`s with string-named globals + helper/nested calls + a 0-param main. The lexer keeps
    // identifiers as strings ((1, "sq")); parseDefs walks a sequence of `def name(param) = body` (and a
    // `def main() = body`), resolving a bare ident → (local 0) and a call `g(e)` → (app (global g) e).
    // `def sq(n)=n*n def inc(n)=n+1 def main()=inc(sq(4))` → sq(4)=16, inc(16)=17; emitted Core IR runs to 17.
    val selfhostMulti =
      """|def isAl(c: Int): Int = if c >= 97 then (if c <= 122 then 1 else 0) else 0
         |def isDig(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanId(s: String, i: Int, n: Int): Int = if i >= n then i else if isAl(s.charAt(i)) == 1 then scanId(s, i + 1, n) else i
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int = if i >= n then (acc, i) else if isDig(s.charAt(i)) == 1 then scanNum(s, i + 1, n, acc * 10 + (s.charAt(i) - 48)) else (acc, i)
         |def kwCode(s: String): Int = if s == "def" then 1 else if s == "if" then 2 else if s == "then" then 3 else if s == "else" then 4 else 0
         |def lex(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 32 then lex(s, i + 1, n)
         |    else if isAl(c) == 1 then
         |      val j = scanId(s, i, n)
         |      val w = s.substring(i, j)
         |      if kwCode(w) > 0 then Cons((2, kwCode(w)), lex(s, j, n)) else Cons((1, w), lex(s, j, n))
         |    else if isDig(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons((0, v), lex(s, j, n)) }
         |    else if c == 61 then Cons((2, 5), lex(s, i + 1, n))
         |    else if c == 40 then Cons((2, 6), lex(s, i + 1, n))
         |    else if c == 41 then Cons((2, 7), lex(s, i + 1, n))
         |    else if c == 43 then Cons((2, 8), lex(s, i + 1, n))
         |    else if c == 45 then Cons((2, 9), lex(s, i + 1, n))
         |    else if c == 42 then Cons((2, 10), lex(s, i + 1, n))
         |    else if c == 60 then Cons((2, 11), lex(s, i + 1, n))
         |    else lex(s, i + 1, n)
         |def prec(t: Int): Int = t match { case (2, 11) => 1 case (2, 8) => 2 case (2, 9) => 2 case (2, 10) => 3 case _ => 0 }
         |def opStr(k: Int): String = if k == 8 then "i.add" else if k == 9 then "i.sub" else if k == 10 then "i.mul" else "i.lt"
         |def hd(ts: List[Int]): Int = ts match { case Cons(h, t) => h case Nil => (0, 0) }
         |def tl(ts: List[Int]): List[Int] = ts match { case Cons(h, t) => t case Nil => Nil }
         |def snd(p: Int): Int = p match { case (a, b) => b }
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def isLP(ts: List[Int]): Int = hd(ts) match { case (2, 6) => 1 case _ => 0 }
         |def parseAtom(ts: List[Int], pch: String): Int = ts match {
         |  case Cons(t, rest) => t match {
         |    case (0, n) => ("(lit (int " + n + "))", rest)
         |    case (1, id) =>
         |      if isLP(rest) == 1 then parseExpr(tl(rest), 0, pch) match { case (arg, r2) => ("(app (global " + id + ") " + arg + ")", tl(r2)) }
         |      else ("(local 0)", rest)
         |    case (2, 6) => parseExpr(rest, 0, pch) match { case (e, r2) => (e, tl(r2)) }
         |    case (2, 2) => parseExpr(rest, 0, pch) match { case (c, r2) => parseExpr(tl(r2), 0, pch) match { case (th, r3) => parseExpr(tl(r3), 0, pch) match { case (el, r4) => ("(if " + c + " " + th + " " + el + ")", r4) } } }
         |    case _ => ("(lit (int 0))", rest)
         |  }
         |  case Nil => ("(lit (int 0))", Nil)
         |}
         |def climb(left: String, ts: List[Int], minPrec: Int, pch: String): Int = ts match {
         |  case Cons(t, rest) =>
         |    val p = prec(t)
         |    if p >= minPrec then
         |      if p > 0 then parseExpr(rest, p + 1, pch) match { case (right, r2) => t match { case (2, k) => climb("(prim " + opStr(k) + " " + left + " " + right + ")", r2, minPrec, pch) case _ => (left, ts) } }
         |      else (left, ts)
         |    else (left, ts)
         |  case Nil => (left, ts)
         |}
         |def parseExpr(ts: List[Int], minPrec: Int, pch: String): Int = parseAtom(ts, pch) match { case (l, r) => climb(l, r, minPrec, pch) }
         |def parseDefs(ts: List[Int]): String = ts match {
         |  case Nil => ""
         |  case Cons(d, r1) =>
         |    val nm = snd(hd(r1))
         |    val r3 = tl(tl(r1))
         |    hd(r3) match {
         |      case (2, 7) => parseExpr(tl(tl(r3)), 0, "") match { case (body, r6) => "(def main (lam 0 " + body + ")) " + parseDefs(r6) }
         |      case _ => parseExpr(tl(tl(tl(r3))), 0, snd(hd(r3))) match { case (body, r6) => "(def " + nm + " (lam 1 " + body + ")) " + parseDefs(r6) }
         |    }
         |}
         |def compile(src: String): String = "(program (defs " + parseDefs(lex(src, 0, src.length)) + ") (entry (app (global main))))"
         |def main(): String = compile("def sq(n) = n * n def inc(n) = n + 1 def main() = inc(sq(4))")""".stripMargin
    // ══ P6.5: a compiler with a proper lexical ENVIRONMENT + let-bindings ══════════════════════════
    // The foundation for nested scopes (needed for match, and ultimately self-application). The env is a
    // list of names, innermost first; a variable resolves to (local idxOf(env)). `let x = e1 in e2`
    // desugars to (app (lam 1 e2) e1), evaluating e1 in the current env and e2 in x::env — which lines up
    // exactly with Core IR's slot shifting (a (lam 1) pushes the enclosing locals up by one). Reads real
    // Scala syntax. def f(x) = let y = x + 1 in y * y ; f(4) → y=5, y*y = 25.
    val selfhostEnv =
      """|def isAl(c: Int): Int = if c >= 97 then (if c <= 122 then 1 else 0) else 0
         |def isDig(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanId(s: String, i: Int, n: Int): Int = if i >= n then i else if isAl(s.charAt(i)) == 1 then scanId(s, i + 1, n) else i
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int = if i >= n then (acc, i) else if isDig(s.charAt(i)) == 1 then scanNum(s, i + 1, n, acc * 10 + (s.charAt(i) - 48)) else (acc, i)
         |def kwCode(s: String): Int = if s == "def" then 1 else if s == "if" then 2 else if s == "then" then 3 else if s == "else" then 4 else if s == "let" then 5 else if s == "in" then 6 else 0
         |def lex(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 32 then lex(s, i + 1, n)
         |    else if isAl(c) == 1 then
         |      val j = scanId(s, i, n)
         |      val w = s.substring(i, j)
         |      if kwCode(w) > 0 then Cons((2, kwCode(w)), lex(s, j, n)) else Cons((1, w), lex(s, j, n))
         |    else if isDig(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons((0, v), lex(s, j, n)) }
         |    else if c == 61 then Cons((2, 20), lex(s, i + 1, n))
         |    else if c == 40 then Cons((2, 21), lex(s, i + 1, n))
         |    else if c == 41 then Cons((2, 22), lex(s, i + 1, n))
         |    else if c == 43 then Cons((2, 23), lex(s, i + 1, n))
         |    else if c == 45 then Cons((2, 24), lex(s, i + 1, n))
         |    else if c == 42 then Cons((2, 25), lex(s, i + 1, n))
         |    else if c == 60 then Cons((2, 26), lex(s, i + 1, n))
         |    else lex(s, i + 1, n)
         |def prec(t: Int): Int = t match { case (2, 26) => 1 case (2, 23) => 2 case (2, 24) => 2 case (2, 25) => 3 case _ => 0 }
         |def opStr(k: Int): String = if k == 23 then "i.add" else if k == 24 then "i.sub" else if k == 25 then "i.mul" else "i.lt"
         |def hd(ts: List[Int]): Int = ts match { case Cons(h, t) => h case Nil => (0, 0) }
         |def tl(ts: List[Int]): List[Int] = ts match { case Cons(h, t) => t case Nil => Nil }
         |def snd(p: Int): Int = p match { case (a, b) => b }
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def isLP(ts: List[Int]): Int = hd(ts) match { case (2, 21) => 1 case _ => 0 }
         |def idxOf(env: List[Int], name: String, i: Int): Int = env match { case Nil => 0 case Cons(h, t) => if h == name then i else idxOf(t, name, i + 1) }
         |def parseAtom(ts: List[Int], env: List[Int]): Int = ts match {
         |  case Cons(t, rest) => t match {
         |    case (0, n) => ("(lit (int " + n + "))", rest)
         |    case (1, id) =>
         |      if isLP(rest) == 1 then parseExpr(tl(rest), 0, env) match { case (arg, r2) => ("(app (global " + id + ") " + arg + ")", tl(r2)) }
         |      else ("(local " + idxOf(env, id, 0) + ")", rest)
         |    case (2, 21) => parseExpr(rest, 0, env) match { case (e, r2) => (e, tl(r2)) }
         |    case (2, 2) => parseExpr(rest, 0, env) match { case (c, r2) => parseExpr(tl(r2), 0, env) match { case (th, r3) => parseExpr(tl(r3), 0, env) match { case (el, r4) => ("(if " + c + " " + th + " " + el + ")", r4) } } }
         |    case (2, 5) => parseExpr(tl(tl(rest)), 0, env) match { case (e1, r3) => parseExpr(tl(r3), 0, Cons(snd(hd(rest)), env)) match { case (e2, r4) => ("(app (lam 1 " + e2 + ") " + e1 + ")", r4) } }
         |    case _ => ("(lit (int 0))", rest)
         |  }
         |  case Nil => ("(lit (int 0))", Nil)
         |}
         |def climb(left: String, ts: List[Int], minPrec: Int, env: List[Int]): Int = ts match {
         |  case Cons(t, rest) =>
         |    val p = prec(t)
         |    if p >= minPrec then
         |      if p > 0 then parseExpr(rest, p + 1, env) match { case (right, r2) => t match { case (2, k) => climb("(prim " + opStr(k) + " " + left + " " + right + ")", r2, minPrec, env) case _ => (left, ts) } }
         |      else (left, ts)
         |    else (left, ts)
         |  case Nil => (left, ts)
         |}
         |def parseExpr(ts: List[Int], minPrec: Int, env: List[Int]): Int = parseAtom(ts, env) match { case (l, r) => climb(l, r, minPrec, env) }
         |def parseDefs(ts: List[Int]): String = ts match {
         |  case Nil => ""
         |  case Cons(d, r1) =>
         |    val nm = snd(hd(r1))
         |    val r3 = tl(tl(r1))
         |    hd(r3) match {
         |      case (2, 22) => parseExpr(tl(tl(r3)), 0, Nil) match { case (body, r6) => "(def main (lam 0 " + body + ")) " + parseDefs(r6) }
         |      case _ => parseExpr(tl(tl(tl(r3))), 0, Cons(snd(hd(r3)), Nil)) match { case (body, r6) => "(def " + nm + " (lam 1 " + body + ")) " + parseDefs(r6) }
         |    }
         |}
         |def compile(src: String): String = "(program (defs " + parseDefs(lex(src, 0, src.length)) + ") (entry (app (global main))))"
         |def main(): String = compile("def f(x) = let y = x + 1 in y * y def main() = f(4)")""".stripMargin
    // The hardest part of a real parser, in the subset: a PRECEDENCE-CLIMBING infix parser with
    // PARENTHESES — the same algorithm as the spike's own parseExpr (climb while the next operator binds
    // tighter than minPrec). Tokenises "2 * (1 + 3)" (with `(`=-3, `)`=-4), parses respecting `*` over `+`
    // and grouping, evaluates.  2 * (1 + 3)  →  2 * 4  →  8.  (Uses block-bodied match arms, just fixed.)
    val selfhostInfix =
      """|def isDigit(c: Int): Int = if c >= 48 then (if c <= 57 then 1 else 0) else 0
         |def scanNum(s: String, i: Int, n: Int, acc: Int): Int =
         |  if i >= n then (acc, i)
         |  else
         |    val c = s.charAt(i)
         |    if isDigit(c) == 1 then scanNum(s, i + 1, n, acc * 10 + (c - 48))
         |    else (acc, i)
         |def tokenize(s: String, i: Int, n: Int): List[Int] =
         |  if i >= n then Nil
         |  else
         |    val c = s.charAt(i)
         |    if c == 43 then Cons(-1, tokenize(s, i + 1, n))
         |    else if c == 42 then Cons(-2, tokenize(s, i + 1, n))
         |    else if c == 40 then Cons(-3, tokenize(s, i + 1, n))
         |    else if c == 41 then Cons(-4, tokenize(s, i + 1, n))
         |    else if c == 32 then tokenize(s, i + 1, n)
         |    else if isDigit(c) == 1 then scanNum(s, i, n, 0) match { case (v, j) => Cons(v, tokenize(s, j, n)) }
         |    else tokenize(s, i + 1, n)
         |def prec(op: Int): Int = if op == -1 then 1 else if op == -2 then 2 else 0
         |def drop1(ts: List[Int]): List[Int] = ts match { case Cons(t, rest) => rest case Nil => Nil }
         |def parseAtom(ts: List[Int]): Int = ts match {
         |  case Cons(t, rest) => if t == -3 then parseExpr(rest, 0) match { case (v, r2) => (v, drop1(r2)) } else (t, rest)
         |  case Nil => (0, Nil)
         |}
         |def climb(left: Int, ts: List[Int], minPrec: Int): Int = ts match {
         |  case Cons(op, rest) =>
         |    val p = prec(op)
         |    if p >= minPrec then
         |      if p > 0 then parseExpr(rest, p + 1) match { case (right, r2) => climb(if op == -1 then left + right else left * right, r2, minPrec) }
         |      else (left, ts)
         |    else (left, ts)
         |  case Nil => (left, ts)
         |}
         |def parseExpr(ts: List[Int], minPrec: Int): Int = parseAtom(ts) match { case (left, rest) => climb(left, rest, minPrec) }
         |def fst(p: Int): Int = p match { case (a, b) => a }
         |def compile(src: String): Int = fst(parseExpr(tokenize(src, 0, src.length), 0))
         |def main(): Int = compile("2 * (1 + 3)")""".stripMargin
    // well-formed — the harness requires byte-identical Core IR vs ssc1-front.
    val wellFormed = Seq(
      "selfhost-arith"    -> selfhostArith,
      "selfhost-eval"     -> selfhostEval,
      "selfhost-closures" -> selfhostClosures,
      "selfhost-full"     -> selfhostFull,
      "selfhost-compiler" -> selfhostCompiler,
      "selfhost-emit"     -> selfhostEmit,
      "selfhost-rec"      -> selfhostRec,
      "selfhost-lexer"    -> selfhostLexer,
      "selfhost-scala"    -> selfhostScala,
      "selfhost-multi"    -> selfhostMulti,
      "selfhost-env"      -> selfhostEnv,
      "selfhost-infix"    -> selfhostInfix,
      "scale-prog"   -> scaleProg,
      "scale-decls"  -> scaleDecls,
      "scale-nested" -> scaleNested,
      "scale-interp" -> scaleInterp,
      "scale-hof"    -> scaleHof,
      // edge probes — likely gaps
      "funret"    -> "def mk(): Int => Int = x => x + 1\ndef main(): Int = mk()(4)",
      "interp-if" -> "def f(n: Int): String = s\"${if n > 0 then \"p\" else \"n\"}\"\ndef main(): Int = 0",
      "nested3"   -> "def f(n: Int): Int =\n  val a = 1\n  if n > 0 then\n    val b = 2\n    a + b\n  else\n    val c = 3\n    a + c\ndef main(): Int = f(1)",
      // building blocks for the self-host compiler (de-risk before the full programs)
      "nested-pat" -> "def f(xs: List[Int]): Int = xs match\n  case Cons(b, Cons(a, s)) => a + b\n  case _ => 0\ndef main(): Int = f(1 :: 2 :: Nil)",
      "tag-pat"    -> "def f(e: Int): Int = e match\n  case (0, n) => n\n  case (2, lr) => 99\n  case _ => 0\ndef main(): Int = f((0, 42))",
      "neg-tup"    -> "def main(): Int =\n  val e = (2, ((0, 3), (0, 4)))\n  e match\n    case (2, lr) => lr match\n      case (l, r) => 3\n    case _ => 0",
      "block-arm"  -> "def f(n: Int): Int = n match\n  case 0 => 0\n  case m =>\n    val a = m + 1\n    val b = a * 2\n    a + b\ndef main(): Int = f(3)",
      // string-return / building / int->string probes (for a mini-lowerer)
      "strret"    -> "def main(): String = \"ab\"",
      "strconc3"  -> "def f(): String = \"(\" + \"x\" + \")\"\ndef main(): String = f()",
      "intstr"    -> "def main(): String = \"v\" + 5",
      "strbuild"  -> "def rep(s: String, n: Int): String = if n == 0 then \"\" else s + rep(s, n - 1)\ndef main(): String = rep(\"x\", 3)",
      // string-op probes (do they RUN on the VM?)
      "strlen"    -> "def main(): Int = \"hello\".length",
      "streq"     -> "def main(): Int = if \"abc\" == \"abc\" then 1 else 0",
      "strne"     -> "def main(): Int = if \"abc\" == \"xyz\" then 1 else 0",
      "strcat"    -> "def main(): Int = (\"ab\" + \"cde\").length",
      "strsub"    -> "def main(): Int = \"hello\".substring(1, 3).length",
      "strcharat" -> "def main(): Int = \"abc\".charAt(1)",
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
      "str-escape" -> "def s(): String = \"a\\tb\\nc\"\ndef main(): Int = 6",
      // P6.3e — string interpolation: s (var + ${expr}), md, f (format specs), byte-identical to ssc1-front
      "interp-var"  -> "def greet(name: String): String = s\"hi $name!\"\ndef main(): Int = 0",
      "interp-expr" -> "def f(x: Int): String = s\"n=${x + 1}\"\ndef main(): Int = 0",
      "interp-md"   -> "def h(t: String): String = md\"# $t\"\ndef main(): Int = 0",
      "interp-f"    -> "def f(x: Int): String = f\"n=$x%d!\"\ndef main(): Int = 0",
      // P6.4 grammar completeness — booleans, floats, lambdas, comments (all byte-identical)
      "bool"    -> "def main(): Int = if true && false then 0 else 1",
      "float"   -> "def f(): Int =\n  val pi = 3.14\n  0\ndef main(): Int = f()",
      "lambda1" -> "def main(): Int =\n  val f = x => x + 1\n  f(4)",
      "lambda2" -> "def main(): Int =\n  val g = (a, b) => a + b\n  g(3, 4)",
      "comment" -> "def main(): Int =\n  // pick two\n  val a = 2 /* the value */\n  a + 1"
    )
    // broken — no oracle; the harness proves containment (`main` still runs).
    val broken = Seq(
      ("broken-sibling", "def broken(): Int = 1 +\ndef main(): Int = 2 * 3", "6")
    )
    for (name, code) <- wellFormed do
      Files.writeString(Paths.get(outDir, s"$name.proj"), SpikeProject.program(parse(code).roots.head))
      Files.writeString(Paths.get(outDir, s"$name.toy.ssc"), code + "\n")
      Files.deleteIfExists(Paths.get(outDir, s"$name.expect"))
    // selfhost-emit is a COMPILER (in the subset) that emits executable Core IR; the harness runs the IR
    // it emits and checks it evaluates to this. Program: "? < 3 5 - 10 2 + 100 200" = if 3<5 then 10-2
    // else 100+200 = 8 (two-stage self-compilation: arithmetic + comparison + control flow).
    Files.writeString(Paths.get(outDir, "selfhost-emit.emit"), "8")
    // selfhost-rec compiles a recursive factorial from source text; the Core IR it emits runs to 5! = 120.
    Files.writeString(Paths.get(outDir, "selfhost-rec.emit"), "120")
    // selfhost-lexer (F1) tokenises subset source; the harness checks its rendered token stream equals this.
    Files.writeString(Paths.get(outDir, "selfhost-lexer.want"),
      "kw:def id:f op:= kw:if id:x kw:then int:1 kw:else id:x op:+ int:2 ")
    // selfhost-scala reads REAL Scala-subset syntax (def f(x) = if x < 1 then 1 else x * f(x-1)) and emits
    // executable Core IR; the harness runs it → factorial(5) = 120.
    Files.writeString(Paths.get(outDir, "selfhost-scala.emit"), "120")
    // selfhost-multi compiles several functions (sq, inc, main) with helper/nested calls → emitted IR runs
    // to inc(sq(4)) = inc(16) = 17.
    Files.writeString(Paths.get(outDir, "selfhost-multi.emit"), "17")
    // selfhost-env compiles let-bindings with a proper lexical environment; f(4) = let y=5 in y*y = 25.
    Files.writeString(Paths.get(outDir, "selfhost-env.emit"), "25")
    for (name, code, expect) <- broken do
      Files.writeString(Paths.get(outDir, s"$name.proj"), SpikeProject.program(parse(code).roots.head))
      Files.writeString(Paths.get(outDir, s"$name.expect"), expect)
      Files.deleteIfExists(Paths.get(outDir, s"$name.toy.ssc"))
    Files.writeString(Paths.get(outDir, "EMITTED"), (wellFormed.map(_._1) ++ broken.map(_._1)).mkString("\n"))
    succeed
  }
