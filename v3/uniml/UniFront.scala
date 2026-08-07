package ssc3

import scalascript.uniml.UniNode
import scalascript.uniml.SourceSpan
import scalascript.uniml.Severity
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

  /** The ScalaScript subtrees the composer spliced under the code fences — or, for an unfenced
    * file, under the whole-file `<bare>` fence.
    *
    * THE WRAPPER IS GONE. This used to prepend "```scalascript\n" to unfenced text, because the
    * composer yielded zero subtrees for it. UniML has bare mode now, and the workaround had a cost
    * the fixtures made visible: the added line shifted every SourceSpan down by one, so the
    * synthetic name a `{ case … }` lambda takes from its position read `$m14_17` on this front and
    * `$m13_17` on v3's. Positions are not printed by `AstText`, which is why this survived — it
    * leaked through the one place a position becomes a NAME. */
  private def subtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b)
      else b.edges.flatMap(e => subtrees(e.child))
    case _ => Vector.empty

  def parse(text: String): Program =
    val composed = SscCompose.parse(text)

    // A DIAGNOSTIC IS A REFUSAL HERE, and it has to be said explicitly because UniML's parser is
    // error-TOLERANT by design: it reports what went wrong and carries on, so that a tool showing
    // a document can still show the parts that are fine. For a compiler front that is the wrong
    // contract. `v3/tests/front/unclosed-brace.ssc` — `def main(): Unit = {` with no `}` — came
    // back as a clean two-line program with the brace quietly forgotten, and the front gate caught
    // it the moment this front became the default: "unclosed-brace was ACCEPTED — the front emits
    // for anything". Compiling a file the user did not write is worse than refusing one they did.
    //
    // Errors only. A `Warning` or an `Info` is a remark about legal source, and refusing those
    // would make the front stricter than the language.
    composed.diagnostics.find(d => d.severity == Severity.Error || d.severity == Severity.Fatal)
      .foreach { d =>
        val at = d.span.map(pos).getOrElse(Pos(0, 0))
        throw ParseFail(at, d.message + " [" + d.code + "]")
      }

    val subs = subtrees(composed.root)
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
    // `isCase` arrived 2026-08-07, closing item 3 of the hand-over. A `case object` is a NULLARY
    // CONSTRUCTOR — a value — and an `object` is a namespace; without the flag the two were
    // indistinguishable and the projection could not guess, because an empty object is useless but
    // legal and turning it into a constructor would invent a value nobody wrote.
    case U.ObjectDecl(n, parents, ms, isCase, s) =>
      if isCase then Sorted.C(List(ClassDef(n, Nil, Nil, parents.toList, pos(s))))
      else
        // An `object` is a NAMESPACE: its `def`s become `O_name` and its `val`s become the
        // object's own state. Both kinds are kept — dropping the `val`s left the `def`s reading
        // names that no longer existed, which fails at RUN time rather than at projection time.
        var ds: List[Def] = Nil
        var vs: List[Stmt.Val] = Nil
        ms.foreach { m => m match
          case dd: U.Def =>
            ds = ds :+ Def(dd.name, dd.params.toList.map(param), expr(dd.body), pos(dd.span))
          case U.TopExpr(U.ValDef(vn, rhs, isVar, vsp), _) =>
            vs = vs :+ Stmt.Val(vn, expr(rhs), isVar, pos(vsp))
          case other => no("a non-`def`, non-`val` member of an `object`", other.span)
        }
        Sorted.O(ObjectDef(n, ds, vs, pos(s)))

    // A `trait` — it used to VANISH into `NoOpDecl`, invisible to every measurement UniML makes
    // about itself. `keyword` distinguishes `trait` from the other things the dialect routes here.
    case U.TraitDecl(_, n, parents, ms, s) =>
      val defs = ms.toList.flatMap { m => m match
        case dd: U.Def =>
          // An ABSTRACT signature — no `=`, no body — arrives with `NotImplemented` as its body
          // since 2026-08-07. v3 spells the same thing `__abstract__`, and `Lower` reads that name
          // to decide the method dispatches to a subclass instead of running. Before the two were
          // told apart, `def area(): Double` in a trait projected as a method that RETURNS UNIT,
          // and every call on the trait got unit rather than the override's answer.
          val b = dd.body match
            case U.NotImplemented(bs) => Expr.Name("__abstract__", pos(bs))
            case other                => expr(other)
          List(Def(dd.name, dd.params.toList.map(param), b, pos(dd.span)))
        case _ => Nil
      }
      Sorted.T(TraitDef(n, defs, parents.toList, pos(s)))

    // `val id: String` with no `=`. v3's traits carry methods, not abstract state.
    case U.AbstractVal(n, s) => no("the abstract `val` '" + n + "'", s)
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

  /** A 64-bit literal, or a POSITIONED refusal. `ssc` integers are 64-bit, so a literal outside
    * `[-2^63, 2^63-1]` is a real error — and it has to arrive as one. Left to `parseLong` it came
    * out as a bare `NumberFormatException` stack trace, which `corpus-report.sh` classifies as
    * CRASH rather than as a clean refusal, so an out-of-range constant read as a v3 defect. */
  private def longOf(v: String, p: Pos): Long =
    val digits = v.replace("L", "").replace("l", "").replace("_", "")
    try java.lang.Long.parseLong(digits)
    catch case _: NumberFormatException =>
      throw ParseFail(p, "the integer literal '" + v + "' does not fit in 64 bits")

  private def param(p: U.Param): Param =
    if p.using_ then no("a `using` parameter", p.span)
    Param(p.name, pos(p.span), p.default.map(expr))

  /** A top-level or block element as v3 STATEMENTS. `ValDef` and the destructuring `TupleVal`
    * expand to several; everything else is one expression statement. */
  private def stmtsOf(e: U.Expr, s: SourceSpan): List[Stmt] = e match
    // `isVar` arrived with the same commit — item 2. A `var` read as a `val` made every later
    // assignment to it a refusal, which is a wrong answer rather than a smaller tree.
    case U.ValDef(n, rhs, isVar, sp) => List(Stmt.Val(n, expr(rhs), isVar, pos(sp)))
    case U.TupleVal(names, rhs, sp) =>
      // `val (a, _) = pair` — a WILDCARD binds nothing. It used to bind a variable literally named
      // `_`, so two wildcards in one scope were a redefinition and the value was reachable by a
      // name no source can mean. It also went into the temporary's name, giving `$tup_a__` where
      // v3's own front produces `$tup_a` — a difference the fixtures could not see, because a
      // temporary's name only escapes when two fronts print the same program.
      val bound = names.toList.zipWithIndex.filter((n, _) => n != "_")
      val tmp = "$tup_" + bound.map((n, _) => n).mkString("_")
      Stmt.Val(tmp, expr(rhs), false, pos(sp)) ::
        bound.map((n, i) =>
          Stmt.Val(n, Expr.MethodCall(Expr.Name(tmp, pos(sp)), "_" + (i + 1), Nil, pos(sp)),
                   false, pos(sp)))
    // A `def` inside a `def`. v3 lifts it in `Lower` with its captures as leading parameters, so
    // it is an ordinary statement here and not a refusal.
    case U.LocalDef(d, sp) =>
      List(Stmt.LocalDef(Def(d.name, d.params.toList.map(param), expr(d.body), pos(sp))))
    case other             => List(Stmt.Exp(expr(other)))

  /** v3's `Block` separates the statements from the block's VALUE; UniML's is a flat vector in
    * which a `val` is an ordinary expression. The last element is the result UNLESS it is a `val` —
    * a `val` tail prints nothing, and auto-output depends on that rule. */
  private def block(stmts: Vector[U.Expr], s: SourceSpan): Expr =
    val all = stmts.toList
    if all.isEmpty then Expr.UnitLit(pos(s))
    else
      val isVal = all.last match
        case U.ValDef(_, _, _, _) => true
        case U.TupleVal(_, _, _) => true
        case _                   => false
      if isVal then Expr.Block(all.flatMap(x => stmtsOf(x, s)), None, pos(s))
      else Expr.Block(all.dropRight(1).flatMap(x => stmtsOf(x, s)), Some(expr(all.last)), pos(s))

  def expr(e: U.Expr): Expr = e match
    case U.IntLit(v, s)   => Expr.IntLit(longOf(v, pos(s)), pos(s))
    case U.FloatLit(v, s) => Expr.DoubleLit(v.toDouble, pos(s))
    case U.StrLit(v, s)   => Expr.StrLit(v, pos(s))
    case U.UnitLit(s)     => Expr.UnitLit(pos(s))
    // Its own node since 2026-08-07. It used to arrive as an `IntLit`, indistinguishable from the
    // integer — a wrong answer, because `println('x')` is `x` and `println(120)` is `120`.
    case U.CharLit(code, s) => Expr.CharLit(code.toInt, pos(s))
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
    // The MINUS JOINS THE DIGITS before parsing. `-9223372036854775808` is `Long.MinValue` and its
    // digit string is 2^63, which overflows on its own — so negating after parsing throws on the
    // one literal that most needs to work. v3's own lexer learned this and says so at
    // `Lexer.scala:13`; this was the second copy of the mistake, and it reached the corpus as a raw
    // `NumberFormatException` rather than a diagnostic.
    case U.Prefix("-", U.IntLit(v, _), s) => Expr.IntLit(longOf("-" + v, pos(s)), pos(s))
    case U.Prefix("-", U.FloatLit(v, _), s) => Expr.DoubleLit(-v.toDouble, pos(s))
    case U.Prefix(op, x, s) =>
      if op == "-" then Expr.Neg(expr(x), pos(s))
      else if op == "!" then Expr.Not(expr(x), pos(s))
      else no("the prefix operator '" + op + "'", s)

    // Four UniML call shapes, two v3 ones. UniML keeps them apart because the CST does; here is
    // where collapsing them is the lowering's job rather than a premature decision.
    case U.Apply(U.Ident(f, _), as, s)          => Expr.Call(f, as.toList.map(expr), pos(s))
    case U.Apply(U.Select(r, m, _), as, s)      => Expr.MethodCall(expr(r), m, as.toList.map(expr), pos(s))
    case U.Apply(f, as, s)                      => Expr.Apply(expr(f), as.toList.map(expr), pos(s))
    case U.BlockApply(U.Ident(f, _), a, s)      => Expr.Call(f, List(expr(a)), pos(s))
    case U.BlockApply(U.Select(r, m, _), a, s)  => Expr.MethodCall(expr(r), m, List(expr(a)), pos(s))
    case U.BlockApply(f, a, s)                  => Expr.Apply(expr(f), List(expr(a)), pos(s))
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
    case U.ValDef(n, rhs, isVar, s) =>
      Expr.Block(List(Stmt.Val(n, expr(rhs), isVar, pos(s))), None, pos(s))

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
    // `PatLit` carries the literal's own EXPRESSION NODE since 2026-08-07, so there is nothing to
    // re-parse: the same `expr` arm that builds a literal in a value position builds it here. The
    // string-typed predecessor made this a second decoder — one that read `case '\n'` as character
    // 92 and would have thrown `NumberFormatException` on `case "NULL"`, since a string pattern
    // arrived with its quotes already stripped and went to `parseLong`. No fixture had one.
    case U.PatLit(v, s)     => Pat.PLit(expr(v), pos(s))
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
