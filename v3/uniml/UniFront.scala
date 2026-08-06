package ssc3

import scalascript.uniml.UniNode
import scalascript.uniml.SourceSpan
import scalascript.uniml.ssc.SscCompose
import scalascript.uniml.dialect.scalascript.SpikeTyped
import scalascript.uniml.dialect.scalascript.SpikeAst as U

// The UniML front: source -> UniML CST -> UniML's typed projection -> v3's `Ast`.
//
// The contract this implements is `v3/specs/50-uniml-projection.md`, and the argument for its shape
// — a projection into v3's OWN Ast rather than a transplant of the whole front — is
// `40-front-on-uniml.md` §5a.
//
// IT LIVES OUTSIDE `v3/src` ON PURPOSE. The kernel has zero dependencies (invariant I-1) and must
// keep compiling and running when UniML is not built at all; every gate depends on that. So this is
// a SECOND artifact: `v3/src` plus this file plus UniML's classpath. Selecting it is a driver
// decision, not a kernel one.
//
// REFUSALS ARE THE POINT. Every construct v3 does not have is refused BY NAME rather than erased,
// because an erased construct produces a smaller tree that still lowers, still runs, and prints
// something plausible — the failure mode this whole design is arranged around.
object UniFront:

  private def pos(s: SourceSpan): Pos = Pos(s.start.line, s.start.column)

  private def no(what: String, s: SourceSpan): Nothing =
    throw ParseFail(pos(s), what + " is outside SSC3 core Tier 0")

  /** The ScalaScript subtrees the composer spliced under the code fences.
    *
    * A BARE `.ssc` is fenced first: fences have been optional in this project since 2026-07-09, and
    * the composer yields ZERO subtrees for unfenced text — measured, and it would have read a whole
    * program as prose with no diagnostic. `50-uniml-projection.md` §6 files the bare mode as a
    * request to UniML; this is the workaround it names. */
  private def subtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b)
      else b.edges.flatMap(e => subtrees(e.child))
    case _ => Vector.empty

  def parse(text: String): Program =
    val fenced = if text.contains("```") then text else "```scalascript\n" + text + "\n```\n"
    val subs = subtrees(SscCompose.parse(fenced).root)
    var defs: List[Def] = Nil
    var classes: List[ClassDef] = Nil
    var objects: List[ObjectDef] = Nil
    var traits: List[TraitDef] = Nil
    var top: List[Stmt] = Nil
    subs.foreach { s =>
      SpikeTyped.module(s).decls.foreach { d =>
        decl(d) match
          case Sorted.D(x) => defs = defs :+ x
          case Sorted.C(x) => classes = classes ++ x
          case Sorted.O(x) => objects = objects :+ x
          case Sorted.T(x) => traits = traits :+ x
          case Sorted.S(x) => top = top ++ x
          case Sorted.Skip => ()
      }
    }
    Program(defs, top, classes, objects, traits)

  /** v3 sorts declarations into five buckets and UniML does not, so the projection has to say which
    * bucket each one lands in. An enum becomes SEVERAL classes, and a `TopExpr` becomes statements,
    * so this cannot be a plain function to one `Decl`. */
  private enum Sorted:
    case D(d: Def)
    // A LIST, because an `enum` becomes one class PER CASE. The first version of this returned one
    // class and left the rest in a mutable field that nothing drained — every enum case after the
    // first would have vanished, silently, which is the exact defect this file's refusals exist to
    // avoid. Caught by re-reading rather than by a test, which is luck; the front-diff gate is what
    // would have caught it for real.
    case C(cs: List[ClassDef])
    case O(o: ObjectDef)
    case T(t: TraitDef)
    case S(s: List[Stmt])
    case Skip

  private def decl(d: U.Decl): Sorted = d match
    case U.Def(n, ps, _, b, s)  => Sorted.D(Def(n, ps.toList.map(param), expr(b), pos(s)))
    case U.CaseClass(n, fs, parent, ms, s) =>
      Sorted.C(List(ClassDef(n, fs.toList.map(param), ms.toList.map(m =>
        Def(m.name, m.params.toList.map(param), expr(m.body), pos(m.span))),
        parent.toList, pos(s))))
    case U.ObjectDecl(n, ms, s) =>
      val defsOnly = ms.toList.map { m => m match
        case dd: U.Def => Def(dd.name, dd.params.toList.map(param), expr(dd.body), pos(dd.span))
        case other     => no("a non-`def` member of an `object`", other.span)
      }
      Sorted.O(ObjectDef(n, defsOnly, pos(s)))
    case U.TopExpr(e, s) => Sorted.S(stmtsOf(e, s))
    // An enum has no v3 node: each case IS a constructor with a tag, which is what the v3 parser
    // already produces. The cases are emitted one at a time by the caller's fold.
    case U.EnumDecl(_, cases, _) =>
      Sorted.C(cases.toList.map(c =>
        ClassDef(c.name, c.fields.toList.map(param), Nil, Nil, pos(c.span))))
    case U.ImportDecl(_, _, _, _) => Sorted.Skip   // §6: `Loader` builds the module graph, from TEXT
    case U.NoOpDecl(_)            => Sorted.Skip
    case U.Given(_, _, _, s)       => no("`given`", s)
    case U.GivenObject(_, _, _, s) => no("`given … with`", s)
    case U.EffectDecl(_, _, _, s)  => no("`effect`", s)
    case U.Extension(_, _, s)      => no("`extension`", s)
    case U.UnsupportedDecl(k, s)   => no("the declaration '" + k + "'", s)

  private def param(p: U.Param): Param =
    if p.using_ then no("a `using` parameter", p.span)
    Param(p.name, pos(p.span), p.default.map(expr))

  /** A top-level or block element as v3 STATEMENTS. `ValDef` and the destructuring `TupleVal`
    * expand to several; everything else is one expression statement. */
  private def stmtsOf(e: U.Expr, s: SourceSpan): List[Stmt] = e match
    case U.ValDef(n, rhs, sp) => List(Stmt.Val(n, expr(rhs), false, pos(sp)))
    case U.TupleVal(names, rhs, sp) =>
      val tmp = "$tup_" + names.mkString("_")
      Stmt.Val(tmp, expr(rhs), false, pos(sp)) ::
        names.toList.zipWithIndex.map((n, i) =>
          Stmt.Val(n, Expr.MethodCall(Expr.Name(tmp, pos(sp)), "_" + (i + 1), Nil, pos(sp)),
                   false, pos(sp)))
    case U.LocalDef(_, sp) => no("a `def` nested inside a `def`", sp)
    case other             => List(Stmt.Exp(expr(other)))

  /** v3's `Block` separates the statements from the block's VALUE; UniML's is a flat vector in
    * which a `val` is an ordinary expression. The last element is the result UNLESS it is a `val` —
    * a `val` tail prints nothing, and auto-output depends on that rule. */
  private def block(stmts: Vector[U.Expr], s: SourceSpan): Expr =
    val all = stmts.toList
    if all.isEmpty then Expr.UnitLit(pos(s))
    else
      val isVal = all.last match
        case U.ValDef(_, _, _)  => true
        case U.TupleVal(_, _, _) => true
        case _                   => false
      if isVal then Expr.Block(all.flatMap(x => stmtsOf(x, s)), None, pos(s))
      else Expr.Block(all.dropRight(1).flatMap(x => stmtsOf(x, s)), Some(expr(all.last)), pos(s))

  def expr(e: U.Expr): Expr = e match
    case U.IntLit(v, s)   => Expr.IntLit(java.lang.Long.parseLong(v.replace("L", "").replace("l", "")), pos(s))
    case U.FloatLit(v, s) => Expr.DoubleLit(v.toDouble, pos(s))
    case U.StrLit(v, s)   => Expr.StrLit(v, pos(s))
    case U.UnitLit(s)     => Expr.UnitLit(pos(s))
    case U.Ident(n, s)    =>
      if n == "true" then Expr.BoolLit(true, pos(s))
      else if n == "false" then Expr.BoolLit(false, pos(s))
      else Expr.Name(n, pos(s))

    // `s"…"` — the dialect keeps the raw text with the holes still inside it, so this is a RE-LEX.
    // `Parser.interp` is reused rather than reimplemented: two implementations of `${…}` nesting is
    // two implementations that will disagree about a brace inside a string.
    case U.Interp(prefix, raw, s) =>
      if prefix != "s" then no("the `" + prefix + "\"…\"` interpolator", s)
      Parser.interpFor(raw, pos(s))

    case U.Infix(op, l, r, s) => Expr.Bin(op, expr(l), expr(r), pos(s))
    // `-1` is a LITERAL in v3, not a negation of one — its lexer folds the sign in. UniML keeps the
    // written form, which is right for a CST-faithful tree and wrong for this one, so the fold
    // happens here. Without it every negative number in every fixture is a difference.
    case U.Prefix("-", U.IntLit(v, _), s) =>
      Expr.IntLit(-java.lang.Long.parseLong(v.replace("L", "").replace("l", "")), pos(s))
    case U.Prefix("-", U.FloatLit(v, _), s) => Expr.DoubleLit(-v.toDouble, pos(s))
    case U.Prefix(op, x, s) =>
      if op == "-" then Expr.Neg(expr(x), pos(s))
      else if op == "!" then Expr.Not(expr(x), pos(s))
      else no("the prefix operator '" + op + "'", s)

    // Four UniML call shapes, two v3 ones. UniML keeps them apart because the CST does; here is
    // where collapsing them is the lowering's job rather than a premature decision.
    case U.Apply(U.Ident(f, _), as, s)          => Expr.Call(f, as.toList.map(expr), pos(s))
    case U.Apply(U.Select(r, m, _), as, s)      => Expr.MethodCall(expr(r), m, as.toList.map(expr), pos(s))
    case U.Apply(f, as, s)                      => Expr.MethodCall(expr(f), "apply", as.toList.map(expr), pos(s))
    case U.BlockApply(U.Ident(f, _), a, s)      => Expr.Call(f, List(expr(a)), pos(s))
    case U.BlockApply(U.Select(r, m, _), a, s)  => Expr.MethodCall(expr(r), m, List(expr(a)), pos(s))
    case U.BlockApply(f, a, s)                  => Expr.MethodCall(expr(f), "apply", List(expr(a)), pos(s))
    case U.Select(r, m, s)                      => Expr.MethodCall(expr(r), m, Nil, pos(s))

    case U.NamedArg(n, v, s) => Expr.NamedArg(n, expr(v), pos(s))
    case U.ListLit(es, s)    => Expr.Call("List", es.toList.map(expr), pos(s))
    case U.Tuple(es, s)      => Expr.Call("Tuple" + es.length, es.toList.map(expr), pos(s))

    case U.If(c, t, el, s)   => Expr.If(expr(c), expr(t), el.map(expr), pos(s))
    case U.While(c, b, s)    => Expr.While(expr(c), expr(b), pos(s))
    case U.Block(ss, s)      => block(ss, s)
    case U.Match(sc, arms, s) => Expr.Match(expr(sc), arms.toList.map(arm), pos(s))
    case U.Lambda(ps, b, s)  => Expr.Lambda(ps.toList.map(n => Param(n, pos(s))), expr(b), pos(s))
    case U.Assign(n, rhs, s) => Expr.Assign(n, expr(rhs), pos(s))
    case U.ValDef(n, rhs, s) => Expr.Block(List(Stmt.Val(n, expr(rhs), false, pos(s))), None, pos(s))

    // `xs(i) = v`. The target must destructure to a one-argument application; anything else is a
    // shape v3's `Update` cannot express, and guessing would put the value in the wrong slot.
    case U.IndexAssign(U.Apply(arr, args, _), v, s) if args.length == 1 =>
      Expr.Update(expr(arr), expr(args.head), expr(v), pos(s))
    case U.IndexAssign(_, _, s) => no("an index assignment whose target is not `a(i)`", s)

    case U.Throw(v, s) => Expr.Call("__throw__", List(expr(v)), pos(s))
    case U.Try(b, handler, fin, s) =>
      if fin.isDefined then no("`finally`", s)
      handler match
        case Some(U.PartialFn(arms, _)) if arms.length == 1 =>
          arms.head.pattern match
            case U.PatVar(n, _)      => Expr.Try(expr(b), n, expr(arms.head.body), pos(s))
            case U.PatTyped(U.PatVar(n, _), _, _) => Expr.Try(expr(b), n, expr(arms.head.body), pos(s))
            case U.PatWild(_)        => Expr.Try(expr(b), "_caught", expr(arms.head.body), pos(s))
            case _ => no("a `catch` arm that is not a single binding", s)
        case _ => no("a `catch` with several arms or a non-literal handler", s)

    // `for` and `{ case … }` desugar to exactly what v3's parser produces, or the front-diff gate
    // reports the difference — which is why the gate exists before the projection does.
    case U.For(gens, body, isYield, s)  => forOf(gens.toList, body, isYield, pos(s))
    case U.PartialFn(arms, s) =>
      val v = "$m" + pos(s).line + "_" + pos(s).col
      Expr.Lambda(List(Param(v, pos(s))),
                  Expr.Match(Expr.Name(v, pos(s)), arms.toList.map(arm), pos(s)), pos(s))

    case U.LocalDef(_, s)          => no("a `def` nested inside a `def`", s)
    case U.TupleVal(_, _, s)       => no("a destructuring `val` in expression position", s)
    case U.CompoundAssign(_, o, _, s) => no("the compound assignment '" + o + "'", s)
    case U.RangeOp(o, _, _, s)     => no("the range operator '" + o + "'", s)
    case U.Summon(_, s)            => no("`summon`", s)
    case U.Quote(_, s)             => no("a quote", s)
    case U.Splice(_, s)            => no("a splice", s)
    case U.QuotedName(_, s)        => no("a quoted name", s)
    case U.Marker(n, _, _, s)      => no("the marker '" + n + "'", s)
    case U.NotImplemented(s)       => no("`???`", s)
    case U.Unsupported(k, s)       => no("the expression '" + k + "'", s)

  private def forOf(gens: List[U.ForGen], body: U.Expr, isYield: Boolean, p: Pos): Expr =
    var acc = expr(body)
    var first = true
    gens.reverse.foreach { g =>
      if g.binders.length != 1 then no("a `for` generator binding several names", g.span)
      val n = g.binders.head
      val src = g.guard.foldLeft(expr(g.source)) { (sofar, cond) =>
        Expr.MethodCall(sofar, "filter", List(Expr.Lambda(List(Param(n, p)), expr(cond), p)), p)
      }
      val method = if first then (if isYield then "map" else "foreach") else "flatMap"
      acc = Expr.MethodCall(src, method, List(Expr.Lambda(List(Param(n, p)), acc, p)), p)
      first = false
    }
    acc

  private def arm(a: U.Arm): MatchArm =
    MatchArm(pattern(a.pattern), a.guard.map(expr), expr(a.body))

  private def pattern(p: U.Pattern): Pat = p match
    case U.PatVar(n, s)     => Pat.PBind(n, pos(s))
    case U.PatWild(s)       => Pat.PWild(pos(s))
    case U.PatLit(v, s)     => Pat.PLit(litOf(v, pos(s)), pos(s))
    case U.PatCtor(n, as, s) => Pat.PCtor(n, as.toList.map(pattern), pos(s))
    case U.PatTuple(es, s)  => Pat.PCtor("Tuple" + es.length, es.toList.map(pattern), pos(s))
    case U.PatCons(h, t, s) => Pat.PCtor("Cons", List(pattern(h), pattern(t)), pos(s))
    case U.PatAlt(as, s)    => Pat.PAlt(as.toList.map(pattern), pos(s))
    // Erasing the type would make `case x: Int` match everything — a wrong answer, not a smaller
    // tree, which is why this refuses rather than unwrapping.
    case U.PatTyped(_, _, s) => no("a typed pattern", s)
    case U.PatBind(_, _, s)  => no("an `@` pattern", s)
    case U.PatUnsupported(k, s) => no("the pattern '" + k + "'", s)

  /** A pattern literal arrives as TEXT, because that is what the CST has. */
  private def litOf(v: String, p: Pos): Expr =
    if v == "true" then Expr.BoolLit(true, p)
    else if v == "false" then Expr.BoolLit(false, p)
    else if v.startsWith("\"") then Expr.StrLit(v.substring(1, v.length - 1), p)
    else if v.startsWith("'") && v.length >= 3 then Expr.CharLit(v.charAt(1).toInt, p)
    else if v.contains(".") then Expr.DoubleLit(v.toDouble, p)
    else Expr.IntLit(java.lang.Long.parseLong(v.replace("L", "").replace("l", "")), p)
