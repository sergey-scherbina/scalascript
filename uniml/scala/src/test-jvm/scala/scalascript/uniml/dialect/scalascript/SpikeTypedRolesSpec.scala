package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** The `if` bug was a CLASS, not an incident. This is the class, asserted construct by construct.
  *
  * `SpikeTyped` picks children by role name, and a role that names a KEYWORD (`if.then`) reads
  * exactly as plausibly as one that names a SUBTREE (`if.thenE`). Reading `ScalaSpike` against the
  * projection found five more siblings before a line of code was written, in four shapes:
  *
  *   - a role the dialect NEVER EMITS (`enum.case`) — the read silently yields nothing;
  *   - a role read with the wrong FILTER (`group.elem` kept only when the child is a branch, so a
  *     parenthesised identifier is discarded for being a token);
  *   - a role read with a helper that returns `""` for a branch (`case.pat`) — every structured
  *     pattern in the corpus became the empty string;
  *   - a role never read at all (`given.body`, `cc.method`, `def.paramType`, `cc.fieldType`).
  *
  * Every one produces a WELL-FORMED node whose contents are wrong or empty, so no `Unsupported`
  * fires and no count moves. Worse: a dropped child is absent from BOTH sides of the coverage
  * ratio, so dropping RAISES the number. These are therefore assertions on SHAPE — the only thing
  * that can tell "not modelled" from "modelled wrongly".
  *
  * The reference for what each role means is `SpikeProject` in the same file: it reads the same
  * CST and has fed the v2 front for months, so where the two disagree it is right and this
  * projection is wrong. Line references below point at it. */
final class SpikeTypedRolesSpec extends AnyFunSuite:

  private def project(text: String): SpikeAst.Module =
    val r = UniML.parse(SourceInput.fromString(SourceId("memory:roles"), text), SpikeDialect)
    SpikeTyped.module(r.roots.head)

  private def decls(text: String): Vector[SpikeAst.Decl] = project(text).decls

  private def defs(text: String): Vector[SpikeAst.Def] =
    SpikeAst.walk(project(text)).collect { case d: SpikeAst.Def => d }

  private def arms(text: String): Vector[SpikeAst.Arm] =
    SpikeAst.walk(project(text)).collect { case a: SpikeAst.Arm => a }

  /** A `def`'s indented body is a `Block`. Unwrap a single-statement one so that a test about the
    * EXPRESSION does not quietly become a test about block structure. */
  private def bodyOf(text: String): SpikeAst.Expr = defs(text).head.body match
    case SpikeAst.Block(Vector(one), _) => one
    case other                          => other

  // ── 1. an enum's cases are `spike.enumcase` BRANCHES, not an `enum.case` role ──────────────
  // `ScalaSpike.scala:883` frames each case as `spike.enumcase`; the reference reads them by KIND
  // (:2334). `enum.case` is a role the dialect never emits, so the read yielded nothing and every
  // EnumDecl in the corpus carried an EMPTY case list.

  test("an enum's cases reach the AST") {
    val es = decls("enum Color:\n  case Red, Green, Blue\ndef main(): Int = 0").collect {
      case e: SpikeAst.EnumDecl => e
    }
    assert(es.sizeIs == 1, s"expected one EnumDecl, got ${es.size}")
    assert(es.head.name == "Color", s"enum name is ${es.head.name}")
    assert(es.head.cases.map(_.name) == Vector("Red", "Green", "Blue"), s"cases are ${es.head.cases}")
  }

  test("a parametrised enum case keeps its fields and their types") {
    val e = decls("enum Opt:\n  case Sm(v: Int)\n  case Nn\ndef main(): Int = 0").collect {
      case e: SpikeAst.EnumDecl => e
    }.head
    assert(e.cases.map(_.name) == Vector("Sm", "Nn"), s"cases are ${e.cases.map(_.name)}")
    assert(e.cases.head.fields.map(_.name) == Vector("v"), s"Sm's fields are ${e.cases.head.fields}")
    assert(e.cases.head.fields.head.tpe.map(_.text).contains("Int"), s"v's type is ${e.cases.head.fields.head.tpe}")
    assert(e.cases(1).fields.isEmpty, s"Nn should have no fields, got ${e.cases(1).fields}")
  }

  // ── 2. a paren/tuple element may be a TOKEN ───────────────────────────────────────────────
  // `ScalaSpike.scala:1920` frames both as `spike.paren` / `spike.tuple` with elements under the
  // role `group.elem`; the reference selects on that role (:2511-2512). Selecting on
  // `kind != "token"` kept a parenthesised CALL and discarded a parenthesised IDENTIFIER.

  test("a parenthesised identifier is that identifier, not unit") {
    val b = bodyOf("def f(x: Int): Int =\n  (x)\n")
    assert(b.isInstanceOf[SpikeAst.Ident], s"body of `(x)` projected as $b")
    assert(b.asInstanceOf[SpikeAst.Ident].name == "x")
  }

  test("a tuple of literals keeps its elements") {
    bodyOf("def f(): Int =\n  (1, 2)\n") match
      case SpikeAst.Tuple(elems, _) =>
        assert(elems.sizeIs == 2, s"(1, 2) projected with ${elems.size} elements: $elems")
        assert(elems.forall(_.isInstanceOf[SpikeAst.IntLit]), s"elements are $elems")
      case other => fail(s"expected a Tuple, got $other")
  }

  test("an empty paren is unit, not a tuple of nothing") {
    // `()` is framed as a `spike.tuple` with zero elements, so it arrives at the tuple case
    // rather than the paren one. A zero-element tuple is not a thing the language has.
    val b = bodyOf("def f(): Int =\n  ()\n")
    assert(b.isInstanceOf[SpikeAst.UnitLit], s"`()` projected as $b")
  }

  // ── 3. `given` has a type and a body, and both were dropped ───────────────────────────────
  // `ScalaSpike.scala:930` puts the right-hand side under `given.body` and the ascribed type under
  // `given.type`. Only `given.name` was read, so the initialiser — an arbitrary expression, of any
  // size — never entered the AST at all.

  test("a given's name, type and body all reach the AST") {
    val g = decls("given g: Int = 42\ndef main(): Int = 0").collect { case g: SpikeAst.Given => g }.head
    assert(g.name.contains("g"), s"given name is ${g.name}")
    assert(g.tpe.map(_.text).contains("Int"), s"given type is ${g.tpe}")
    g.body match
      case Some(SpikeAst.IntLit(v, _)) => assert(v == "42", s"body value is $v")
      case other                       => fail(s"given body projected as $other")
  }

  test("a given's body is WALKED, so its own contents are counted") {
    // The drop that mattered most: an unwalked child is invisible to every coverage number,
    // because it is absent from the numerator AND the denominator.
    val n = SpikeAst.walk(project("given g: Int = 1 + 2\ndef main(): Int = 0")).count {
      case _: SpikeAst.Infix => true
      case _                 => false
    }
    assert(n == 1, s"the given's `1 + 2` should appear once in the walk, appeared $n times")
  }

  // ── 4. a def's parameters have types and defaults; a case class has typed fields and methods ─
  // `def.paramType` (:672), `def.dflt` (:676), `cc.fieldType` (:819) and `cc.method` (:766) are
  // all emitted and all read by the reference (:2419, :2407, :2447). `SpikeTyped` hard-coded
  // `tpe = None` and collected only `cc.field`.

  test("a parameter keeps its declared type") {
    val d = defs("def f(x: Int, y: String): Int = x").head
    assert(d.params.map(_.name) == Vector("x", "y"), s"params are ${d.params.map(_.name)}")
    assert(d.params.map(_.tpe.map(_.text)) == Vector(Some("Int"), Some("String")), s"types are ${d.params.map(_.tpe)}")
    assert(d.ret.map(_.text).contains("Int"), s"result type is ${d.ret}")
  }

  test("a default value belongs to the parameter it follows, not to the first one") {
    // The grouping is POSITIONAL: `def.dflt` follows its `def.param` among the same siblings.
    // Collecting the roles independently and zipping pairs the default with the wrong parameter
    // the moment one parameter has none — which is the common case.
    val d = defs("def f(a: Int, b: Int = 7): Int = a").head
    assert(d.params.map(_.name) == Vector("a", "b"), s"params are ${d.params.map(_.name)}")
    assert(d.params.head.default.isEmpty, s"`a` should have no default, got ${d.params.head.default}")
    d.params(1).default match
      case Some(SpikeAst.IntLit("7", _)) => ()
      case other                         => fail(s"`b`'s default projected as $other")
  }

  test("a case class keeps its typed fields and its methods") {
    val c = decls("case class P(x: Int, y: String):\n  def sum(): Int = x\ndef main(): Int = 0").collect {
      case c: SpikeAst.CaseClass => c
    }.head
    assert(c.fields.map(_.name) == Vector("x", "y"), s"fields are ${c.fields.map(_.name)}")
    assert(c.fields.map(_.tpe.map(_.text)) == Vector(Some("Int"), Some("String")), s"types are ${c.fields.map(_.tpe)}")
    assert(c.methods.map(_.name) == Vector("sum"), s"methods are ${c.methods.map(_.name)}")
  }

  // ── 5. a pattern is a TREE, and it was a String ───────────────────────────────────────────
  // `Arm.pattern` read `case.pat` with a helper returning `""` for a branch, so every structured
  // pattern projected as the empty string: 4,579 of them, the largest entry in the drop census.
  // `case Some(x) =>` and `case _ =>` were indistinguishable. Mirrors the reference's `patProj`
  // (:2630) role for role.

  test("a constructor pattern keeps its name and its arguments") {
    arms("def f(o: Opt): Int =\n  o match\n    case Sm(v) => v\n    case Nn => 0\n").map(_.pattern) match
      case Vector(SpikeAst.PatCtor("Sm", args, _), SpikeAst.PatCtor("Nn", empty, _)) =>
        assert(args.map { case SpikeAst.PatVar(n, _) => n; case o => fail(s"arg is $o") } == Vector("v"))
        assert(empty.isEmpty, s"`Nn` should bind nothing, got $empty")
      case other => fail(s"patterns projected as $other")
  }

  test("a wildcard, a binder and a literal are three different patterns") {
    val ps = arms("def f(n: Int): Int =\n  n match\n    case 1 => 1\n    case x => x\n    case _ => 0\n").map(_.pattern)
    assert(ps.sizeIs == 3, s"expected three arms, got ${ps.size}: $ps")
    assert(ps(0).isInstanceOf[SpikeAst.PatLit], s"`case 1` projected as ${ps(0)}")
    assert(ps(1).isInstanceOf[SpikeAst.PatVar], s"`case x` projected as ${ps(1)}")
    assert(ps(2).isInstanceOf[SpikeAst.PatWild], s"`case _` projected as ${ps(2)}")
  }

  test("a cons pattern keeps head and tail, not one of them") {
    arms("def f(xs: List): Int =\n  xs match\n    case h :: t => h\n    case _ => 0\n").head.pattern match
      case SpikeAst.PatCons(SpikeAst.PatVar("h", _), SpikeAst.PatVar("t", _), _) => ()
      case other => fail(s"`h :: t` projected as $other")
  }

  test("a typed pattern keeps both the pattern and the type") {
    arms("def f(a: Any): Int =\n  a match\n    case x: Int => x\n    case _ => 0\n").head.pattern match
      case SpikeAst.PatTyped(SpikeAst.PatVar("x", _), tpe, _) =>
        assert(tpe.map(_.text).contains("Int"), s"ascribed type is $tpe")
      case other => fail(s"`x: Int` projected as $other")
  }

  test("a nested pattern nests, rather than flattening to one name") {
    arms("def f(o: Opt): Int =\n  o match\n    case Sm(Sm(v)) => v\n    case _ => 0\n").head.pattern match
      case SpikeAst.PatCtor("Sm", Vector(SpikeAst.PatCtor("Sm", Vector(SpikeAst.PatVar("v", _)), _)), _) => ()
      case other => fail(s"`Sm(Sm(v))` projected as $other")
    }

  test("every pattern node is walked, so patterns enter the coverage counts at all") {
    val n = SpikeAst.walk(project("def f(o: Opt): Int =\n  o match\n    case Sm(v) => v\n    case _ => 0\n"))
      .count(_.isInstanceOf[SpikeAst.Pattern])
    assert(n == 3, s"expected 3 pattern nodes (Sm, v, _), walked $n")
  }

  // ── 6. the five constructs that were HONESTLY unmodelled ──────────────────────────────────
  // These differ in kind from everything above: they said `Unsupported` and were counted. That is
  // the difference between a gap and a lie, and it is why they were safe to leave until the audit
  // was done. Shape assertions all the same — the count that reported them cannot tell whether
  // what replaced them is right.

  test("a named argument keeps its label and its value, and is not an assignment") {
    val args = SpikeAst.walk(project("def f(): Int =\n  g(label = 1)\n")).collect {
      case n: SpikeAst.NamedArg => n
    }
    assert(args.sizeIs == 1, s"expected one NamedArg, got $args")
    assert(args.head.name == "label", s"label is ${args.head.name}")
    assert(args.head.value.isInstanceOf[SpikeAst.IntLit], s"value is ${args.head.value}")
  }

  test("a list literal keeps its elements") {
    // In VALUE position. A leading `[` in statement position is a Markdown link-import, so
    // `def f(): Int =\n  [1, 2, 3]` produces a no-op and no list at all — which is what the first
    // version of this test measured, and the empty result is what said so.
    SpikeAst.walk(project("val xs = [1, 2, 3]\ndef main(): Int = 0")).collect {
      case l: SpikeAst.ListLit => l
    } match
      case Vector(l) => assert(l.elems.sizeIs == 3, s"[1, 2, 3] projected with ${l.elems.size}: ${l.elems}")
      case other     => fail(s"expected one ListLit, got $other")
  }

  test("a block argument keeps both the callee and the block") {
    SpikeAst.walk(project("def f(): Int =\n  g { 1 }\n")).collect { case b: SpikeAst.BlockApply => b } match
      case Vector(b) =>
        assert(b.fn.isInstanceOf[SpikeAst.Ident], s"callee is ${b.fn}")
        assert(b.fn.asInstanceOf[SpikeAst.Ident].name == "g")
        assert(SpikeAst.walk(b.arg).exists(_.isInstanceOf[SpikeAst.IntLit]), s"block arg is ${b.arg}")
      case other => fail(s"expected one BlockApply, got $other")
  }

  test("an interpolation keeps its prefix and its raw text") {
    // The dialect does not decompose an interpolation — two tokens, prefix and raw — so this is
    // the whole of what the CST has. `$x` staying inside the text is not a drop.
    SpikeAst.walk(project("def f(x: Int): Int =\n  s\"a $x b\"\n")).collect { case i: SpikeAst.Interp => i } match
      case Vector(i) =>
        assert(i.prefix == "s", s"prefix is ${i.prefix}")
        assert(i.raw.contains("$x"), s"raw text is ${i.raw}")
      case other => fail(s"expected one Interp, got $other")
  }

  test("an import carries its path") {
    // This assertion is what FOUND the gap: it was written, it failed, and the failure was the
    // finding — `parseImportStmt` consumed `a.b.c` and threw it away, so two different imports
    // produced indistinguishable nodes. The fix went into the dialect, which is where the path was
    // missing; this is now the regression test for it.
    val ds = decls("import a.b.c\ndef main(): Int = 0")
    ds.collect { case i: SpikeAst.ImportDecl => i } match
      case Vector(i) => assert(i.path == "a.b.c", s"import path is ${i.path}")
      case other     => fail(s"expected one ImportDecl, got ${ds.map(_.getClass.getSimpleName)} / $other")
  }

  test("two different imports are DISTINGUISHABLE — the property that was missing") {
    val one = decls("import a.b.c\ndef main(): Int = 0").collect { case i: SpikeAst.ImportDecl => i }.head
    val two = decls("import x.y\ndef main(): Int = 0").collect { case i: SpikeAst.ImportDecl => i }.head
    assert(one.path != two.path, s"both imports projected as ${one.path}")
  }

  test("a selector import and a wildcard import keep what distinguishes them") {
    val sel = decls("import a.b.{x, y}\ndef main(): Int = 0").collect { case i: SpikeAst.ImportDecl => i }.head
    assert(sel.selectors == Vector("x", "y"), s"selectors are ${sel.selectors}")
    val wild = decls("import a.b.*\ndef main(): Int = 0").collect { case i: SpikeAst.ImportDecl => i }.head
    assert(wild.wildcard, "the `.*` form should be marked as a wildcard")
    assert(!sel.wildcard, "a selector import is not a wildcard")
  }

  test("an anonymous given is still a genuine no-op, not an import with an empty path") {
    // The two share the `spike.sealed` kind, so the projection has to tell them apart by roles.
    val ds = decls("given Int = 1\ndef main(): Int = 0")
    assert(ds.collect { case i: SpikeAst.ImportDecl => i }.isEmpty,
           s"an anonymous given projected as an import: ${ds.map(_.getClass.getSimpleName)}")
  }

  // ── 7. what only a TOKEN-aware census could find ──────────────────────────────────────────
  // Both of these are tokens, so the branch-only census was blind to them by construction and the
  // shape tests did not cover them because nobody thought to. They were found the moment the
  // census could judge a token — which is the argument for measuring rather than for reading
  // harder.

  test("an extension keeps its RECEIVER") {
    val e = decls("extension (s: Int) def twice(): Int = s\ndef main(): Int = 0").collect {
      case e: SpikeAst.Extension => e
    }.head
    assert(e.defs.map(_.name) == Vector("twice"), s"methods are ${e.defs.map(_.name)}")
    e.recv match
      case Some(p) =>
        assert(p.name == "s", s"receiver name is ${p.name}")
        assert(p.tpe.map(_.text).contains("Int"), s"receiver type is ${p.tpe}")
      case None => fail("the extension receiver was dropped — an extension without it is wrong, not smaller")
  }

  test("a dotted def name keeps every segment") {
    val d = defs("def Source.distributed(): Int = 1").head
    assert(d.name == "Source.distributed", s"dotted def name projected as ${d.name}")
  }

  // ── 8. the remaining constructs, closed to the reachable floor ────────────────────────────

  private def firstOf[T](src: String)(pf: PartialFunction[SpikeAst.Node, T]): T =
    SpikeAst.walk(project(src)).collectFirst(pf).getOrElse(fail(s"no matching node in:\n$src"))

  test("throw keeps its operand") {
    val t = firstOf("def f(): Int =\n  throw Err(1)\n") { case t: SpikeAst.Throw => t }
    assert(t.value.isInstanceOf[SpikeAst.Apply], s"throw operand is ${t.value}")
  }

  test("try keeps body, handler and finalizer as three separate things") {
    val t = firstOf("def f(): Int =\n  try g() catch { case e => 0 } finally h()\n") { case t: SpikeAst.Try => t }
    assert(t.handler.isDefined, "the catch handler was dropped")
    assert(t.finalizer.isDefined, "the finally block was dropped")
    assert(SpikeAst.walk(t.handler.get).exists(_.isInstanceOf[SpikeAst.Arm]), s"handler is ${t.handler}")
  }

  test("a for comprehension keeps its generators, its guard and whether it yields") {
    val f = firstOf("def f(xs: List): Int =\n  for x <- xs if x > 0 yield x\n") { case f: SpikeAst.For => f }
    assert(f.isYield, "the `yield` was lost, which changes what the expression MEANS")
    assert(f.gens.sizeIs == 1, s"generators are ${f.gens}")
    assert(f.gens.head.binders == Vector("x"), s"binders are ${f.gens.head.binders}")
    assert(f.gens.head.guard.isDefined, "the generator's guard was dropped")
  }

  test("a range keeps which word was written") {
    // `to` and `until` differ by one element. Projecting both as the same node would be a silent
    // off-by-one in every loop in the corpus.
    assert(firstOf("def f(): Int =\n  1 to 10\n") { case r: SpikeAst.RangeOp => r }.op == "to")
    assert(firstOf("def f(): Int =\n  1 until 10\n") { case r: SpikeAst.RangeOp => r }.op == "until")
  }

  test("summon keeps the WHOLE type application, not just its head") {
    // Keeping only `Show` never matches an instance — the reference says so explicitly.
    val s = firstOf("def f(): Int =\n  summon[Show[Int]]\n") { case s: SpikeAst.Summon => s }
    assert(s.tpe.startsWith("Show"), s"summon type is ${s.tpe}")
    assert(s.tpe.contains("Int"), s"the type ARGUMENT was dropped: ${s.tpe}")
  }

  test("a partial-function literal keeps its arms") {
    val p = firstOf("def f(): Int =\n  g { case 1 => 2\n        case _ => 3 }\n") { case p: SpikeAst.PartialFn => p }
    assert(p.arms.sizeIs == 2, s"arms are ${p.arms}")
  }

  test("a local def is a declaration inside a block, not an unsupported expression") {
    val l = firstOf("def f(): Int =\n  def g(): Int = 1\n  g()\n") { case l: SpikeAst.LocalDef => l }
    assert(l.decl.name == "g", s"local def name is ${l.decl.name}")
  }

  test("an effect declaration keeps its name, its ops and whether it is multi-shot") {
    val e = firstOf("effect Ask:\n  def ask(): Int\ndef main(): Int = 0") { case e: SpikeAst.EffectDecl => e }
    assert(e.name == "Ask", s"effect name is ${e.name}")
    assert(e.ops.nonEmpty, "the effect's operations were dropped")
    assert(!e.multi, "a plain effect is not multi-shot")
  }

  test("a given instance with members keeps them") {
    val g = firstOf("given g: Show with\n  def show(): Int = 1\ndef main(): Int = 0") {
      case g: SpikeAst.GivenObject => g
    }
    assert(g.members.nonEmpty, "the instance's methods were dropped")
  }

  // ── 9. what only ANOTHER IMPLEMENTATION could find ────────────────────────────────────────
  // Every defect below was found by v3's front differential on 2026-08-06 — not by any measurement
  // UniML takes of itself. That is the lesson worth more than the fixes: a projection can be
  // audited against its own CST until the drop census reads 0 and the coverage reads 100%, and a
  // construct consumed into a MODELLED BUT CONTENTLESS node is still invisible to all of it. The
  // parse-error count sees nothing wrong, the drop census sees a modelled node with no subtree
  // beneath it, and the coverage figure counts it as typed. Comparing against a second
  // implementation asks a question self-measurement cannot.

  test("a trait keeps its name, its parents and its METHODS") {
    // The worst of them: `trait Shape:` with a body was consumed whole into `NoOpDecl` — the node
    // documented as "parsed, and genuinely carrying nothing". It gates 137 corpus cases for v3.
    val t = decls("trait Shape extends Base with Drawable:\n  def area(): Int = 1\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(t.sizeIs == 1, s"expected one TraitDecl, got ${decls("trait Shape:\n  def area(): Int = 1\ndef main(): Int = 0").map(_.getClass.getSimpleName)}")
    assert(t.head.keyword == "trait", s"keyword is ${t.head.keyword}")
    assert(t.head.name == "Shape", s"name is ${t.head.name}")
    assert(t.head.parents == Vector("Base", "Drawable"), s"parents are ${t.head.parents}")
    assert(t.head.members.collect { case d: SpikeAst.Def => d.name } == Vector("area"),
           s"the trait's methods were dropped: ${t.head.members}")
  }

  test("a plain class KEEPS ITS CONSTRUCTOR PARAMETERS — names, types, modifiers, generics") {
    // `class Box(n: Int)` routes to `TraitDecl` (kw = "class") and its clause went through
    // `skipBalancedParens` — consumed, recorded nowhere — so v3's projection printed `(fields)`
    // where v3's own front printed `(fields (p "n"))`. Found, like everything in this section, by
    // the front DIFFERENTIAL, three declared rows' worth.
    // (v3/BUGS.md uniml-traitdecl-drops-class-parameters.)
    val plain = decls("class Box(n: Int):\n  def plus(a: Int): Int = n + a\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(plain.sizeIs == 1, s"expected one TraitDecl, got ${plain.map(_.getClass.getSimpleName)}")
    assert(plain.head.keyword == "class", s"keyword is ${plain.head.keyword}")
    assert(plain.head.params.map(_.name) == Vector("n"), s"ctor params are ${plain.head.params}")
    assert(plain.head.params.head.tpe.exists(_.text == "Int"),
           s"the param's type is ${plain.head.params.head.tpe}")
    assert(plain.head.members.collect { case d: SpikeAst.Def => d.name } == Vector("plus"),
           s"the class's methods were dropped: ${plain.head.members}")
    // `val n` (kr-hk-field-arity's `class Unused(val n: Int)`) — the modifier must not end the list.
    val withVal = decls("class Unused(val n: Int):\n  def get(): Int = n\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(withVal.head.params.map(_.name) == Vector("n"), s"`val n` params are ${withVal.head.params}")
    // a generic type argument (`class Wrapper[F[_]](app: Applicative[F])`) — erased from the type
    // TEXT (nothing reads a field's type arguments yet) but the NAME must survive.
    val generic = decls("class Wrapper[F[_]](app: Applicative[F]):\n  def get(): Applicative[F] = app\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(generic.head.params.map(_.name) == Vector("app"), s"generic-field params are ${generic.head.params}")
    // two params with a default — the comma loop and the default capture.
    val two = decls("class Pt(x: Int, y: Int = 7):\n  def sum(): Int = x + y\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(two.head.params.map(_.name) == Vector("x", "y"), s"two-param clause is ${two.head.params}")
    assert(two.head.params(1).default.nonEmpty, "the default `= 7` was dropped")
    // a TRAIT keeps an empty clause, and a bodyless class still parses.
    val bare = decls("class Marker(n: Int)\ndef main(): Int = 0").collect { case t: SpikeAst.TraitDecl => t }
    assert(bare.head.params.map(_.name) == Vector("n"), s"bodyless-class params are ${bare.head.params}")
  }

  test("an erased MODIFIER must not shift the offside anchor — extern/sealed/private keep their members") {
    // `parseProgram`'s loop erases decl modifiers before dispatching, and the decl parsers
    // anchored their offside line at the KEYWORD's column — so `extern class Widget:` read its
    // members (column 3, keyword at column 8) as "not indented past the declaration" and
    // re-parsed them as top-level siblings. Same hoist for `sealed trait` and `private object`.
    // (v3/BUGS.md uniml-extern-class-members-hoist-to-top-level.)
    val ext = decls("extern class Widget:\n  def render(): String\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(ext.sizeIs == 1, s"expected the extern class, got $ext")
    assert(ext.head.members.collect { case d: SpikeAst.Def => d.name } == Vector("render"),
           s"the extern class's members hoisted to top level: ${ext.head.members}")
    val sealedT = decls("sealed trait Shape:\n  def area(): Int = 1\ndef main(): Int = 0")
      .collect { case t: SpikeAst.TraitDecl => t }
    assert(sealedT.head.members.collect { case d: SpikeAst.Def => d.name } == Vector("area"),
           s"the sealed trait's methods hoisted: ${sealedT.head.members}")
    val privO = decls("private object Registry:\n  def get(): Int = 2\ndef main(): Int = 0")
      .collect { case o: SpikeAst.ObjectDecl => o }
    assert(privO.head.members.collect { case d: SpikeAst.Def => d.name } == Vector("get"),
           s"the private object's members hoisted: ${privO.head.members}")
  }

  test("a trait's body is WALKED, so it can never be invisible to a count again") {
    val n = SpikeAst.walk(project("trait S:\n  def f(): Int = 1 + 2\ndef main(): Int = 0"))
      .count(_.isInstanceOf[SpikeAst.Infix])
    assert(n == 1, s"the trait's `1 + 2` should appear once in the walk, appeared $n times")
  }

  test("a var is not a val — projecting one as the other is a wrong answer, not a smaller tree") {
    val vs = SpikeAst.walk(project("var counter = 0\nval fixed = 1\ndef main(): Int = 0"))
      .collect { case v: SpikeAst.ValDef => v }
    assert(vs.map(_.name) == Vector("counter", "fixed"), s"got ${vs.map(_.name)}")
    assert(vs.head.isVar, "`var counter` projected as a val, which makes every later assignment a refusal")
    assert(!vs(1).isVar, "`val fixed` projected as a var")
  }

  test("a character literal is not the integer with the same code") {
    // `'x'` lexes as spike.int; the QUOTES in the lexeme are the only thing that distinguishes it.
    val body = bodyOf("def f(): Int =\n  'x'\n")
    body match
      case SpikeAst.CharLit(code, _) => assert(code == "120", s"code is $code")
      case other                     => fail(s"`'x'` projected as $other — indistinguishable from 120")
    assert(bodyOf("def f(): Int =\n  120\n").isInstanceOf[SpikeAst.IntLit], "120 should still be an IntLit")
  }

  test("an infix operator keeps the SPELLING that was written") {
    // `SpikeOp.meaning` rewrites `+:` to `::` and `:::` to `++`, which is true for v2 where every
    // Seq is a Cons-list and false in general. The typed AST says what was written.
    val ops = SpikeAst.walk(project("def f(x: Int, xs: List): List =\n  x +: xs\n"))
      .collect { case i: SpikeAst.Infix => i.op }
    assert(ops.contains("+:"), s"`+:` was normalised away, ops seen: $ops")
  }

  test("a case object is distinguishable from an empty object") {
    val os = decls("case object Red\nobject Blue\ndef main(): Int = 0").collect { case o: SpikeAst.ObjectDecl => o }
    assert(os.map(_.name) == Vector("Red", "Blue"), s"objects are ${os.map(_.name)}")
    assert(os.head.isCase, "`case object Red` projected as a plain object")
    assert(!os(1).isCase, "`object Blue` projected as a case object")
  }

  // ── the general form, stated once over every construct above ──────────────────────────────

  test("no keyword or punctuation token projects as an expression") {
    // `spike.kw` was the `if` bug's signature: 2,120 gaps that were syntax, not constructs.
    // Stated over a program touching each construct so a sibling making the same mistake fails here.
    val src =
      "enum Color:\n  case Red, Green\ncase class P(x: Int):\n  def sum(): Int = x\n" +
        "given g: Int = 42\ndef f(x: Int): Int =\n  if x > 0 then (x) else 2\n"
    val g = SpikeAst.walk(project(src)).collect {
      case SpikeAst.Unsupported(k, _)     => k
      case SpikeAst.UnsupportedDecl(k, _) => k
      case SpikeAst.PatUnsupported(k, _)  => k
    }
    assert(!g.contains("spike.kw"), s"keyword tokens projected as expressions: ${g.distinct}")
  }
