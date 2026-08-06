package ssc3

// The canonical text form of an `Ast`. The same role `Text.write` plays for the IR, and it exists
// for the same reason: two things that must agree need a form in which their disagreement is a diff
// a person can read.
//
// Its one job is the FRONT SWAP (`v3/specs/40-front-on-uniml.md` §7). A second front — UniML's
// typed projection mapped into this same `Ast` — is adopted when both fronts print identically for
// every fixture. Comparing parsers any other way means comparing them through the lowering, the
// verifier and a backend, where a difference arrives far from its cause and a compensating pair of
// differences arrives not at all.
//
// POSITIONS ARE NOT PRINTED. Two fronts may legitimately attribute a node to different columns —
// one may point at an operator, the other at its left operand — and a diff full of position noise
// is a diff nobody reads. What must agree is the SHAPE and the NAMES. A position disagreement is
// real but it is a separate check, and mixing the two would hide the one that matters.
object AstText:

  private def esc(s: String): String =
    val b = new StringBuilder
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '"' then b.append("\\\"")
      else if c == '\\' then b.append("\\\\")
      else if c == '\n' then b.append("\\n")
      else if c == '\t' then b.append("\\t")
      else if c == '\r' then b.append("\\r")
      else b.append(c)
      i = i + 1
    b.toString

  private def q(s: String): String = "\"" + esc(s) + "\""

  private def sx(head: String, parts: List[String]): String =
    if parts.isEmpty then "(" + head + ")" else "(" + head + " " + parts.mkString(" ") + ")"

  /** A whole program. Declarations come out in SOURCE ORDER within each kind, and the kinds in a
    * fixed order — a front that discovers classes while walking statements and one that collects
    * them first must still print the same thing, or the gate would fail on bookkeeping. */
  def render(p: Program): String =
    val out = new StringBuilder
    out.append("(program\n")
    p.classes.foreach(c => out.append("  " + cls(c) + "\n"))
    p.traits.foreach(t => out.append("  " + trt(t) + "\n"))
    p.objects.foreach(o => out.append("  " + obj(o) + "\n"))
    p.defs.foreach(d => out.append("  " + dfn(d) + "\n"))
    p.topLevel.foreach(s => out.append("  " + stmt(s) + "\n"))
    out.append(")\n")
    out.toString

  private def param(x: Param): String =
    x.default match
      case None    => sx("p", List(q(x.name)))
      case Some(d) => sx("p", List(q(x.name), expr(d)))

  private def dfn(d: Def): String =
    sx("def", q(d.name) :: sx("params", d.params.map(param)) :: List(expr(d.body)))

  private def cls(c: ClassDef): String =
    sx("class", List(q(c.name), sx("fields", c.fields.map(param)),
                     sx("parents", c.parents.map(q)), sx("methods", c.methods.map(dfn))))

  private def trt(t: TraitDef): String =
    sx("trait", List(q(t.name), sx("parents", t.parents.map(q)), sx("methods", t.methods.map(dfn))))

  private def obj(o: ObjectDef): String =
    sx("object", List(q(o.name), sx("members", o.defs.map(dfn))))

  private def stmt(s: Stmt): String = s match
    case Stmt.Val(n, v, mutable, _) =>
      sx(if mutable then "var" else "val", List(q(n), expr(v)))
    case Stmt.Exp(e) => sx("do", List(expr(e)))

  private def pat(p: Pat): String = p match
    case Pat.PWild(_)          => "(_)"
    case Pat.PBind(n, _)       => sx("bind", List(q(n)))
    case Pat.PLit(v, _)        => sx("lit", List(expr(v)))
    case Pat.PCtor(n, args, _) => sx("ctor", q(n) :: args.map(pat))
    case Pat.PAlt(alts, _)     => sx("alt", alts.map(pat))

  private def arm(a: MatchArm): String =
    a.guard match
      case None    => sx("arm", List(pat(a.pat), expr(a.body)))
      case Some(g) => sx("arm", List(pat(a.pat), sx("if", List(expr(g))), expr(a.body)))

  /** Every expression kind, by NAME. A new node with no arm here is a compile error rather than a
    * silently unprinted subtree — which is the same defect UniML found in its own projection, where
    * a dropped child vanished from both sides of a coverage ratio and RAISED it. */
  def expr(e: Expr): String = e match
    case Expr.IntLit(v, _)     => sx("int", List(v.toString))
    case Expr.DoubleLit(v, _)  => sx("float", List(Text.floatText(v)))
    case Expr.StrLit(v, _)     => sx("str", List(q(v)))
    case Expr.CharLit(c, _)    => sx("char", List(c.toString))
    case Expr.BoolLit(v, _)    => sx("bool", List(if v then "true" else "false"))
    case Expr.UnitLit(_)       => "(unit)"
    case Expr.Name(n, _)       => sx("name", List(q(n)))
    case Expr.Interp(parts, xs, _) =>
      sx("interp", sx("parts", parts.map(q)) :: xs.map(expr))
    case Expr.Bin(o, l, r, _)  => sx("bin", List(q(o), expr(l), expr(r)))
    case Expr.Neg(x, _)        => sx("neg", List(expr(x)))
    case Expr.Not(x, _)        => sx("not", List(expr(x)))
    case Expr.Call(fn, as, _)  => sx("call", q(fn) :: as.map(expr))
    case Expr.MethodCall(r, n, as, _) => sx("send", expr(r) :: q(n) :: as.map(expr))
    case Expr.NamedArg(n, v, _)  => sx("named", List(q(n), expr(v)))
    case Expr.If(c, t, el, _)  =>
      sx("if", expr(c) :: expr(t) :: el.map(x => List(expr(x))).getOrElse(Nil))
    case Expr.While(c, b, _)   => sx("while", List(expr(c), expr(b)))
    case Expr.Assign(n, v, _)  => sx("set", List(q(n), expr(v)))
    case Expr.Update(a, i, v, _) => sx("update", List(expr(a), expr(i), expr(v)))
    case Expr.Match(sc, arms, _) => sx("match", expr(sc) :: arms.map(arm))
    case Expr.Lambda(ps, b, _) => sx("lam", List(sx("params", ps.map(param)), expr(b)))
    case Expr.Try(b, x, h, _)  => sx("try", List(expr(b), q(x), expr(h)))
    // A block with NO statements and a result IS that result — `Block(Nil, Some(e))` and `e` mean
    // the same thing to every later phase. Printing them identically is a CANONICALISATION, not a
    // fudge: two fronts may legitimately differ on whether a one-expression body is wrapped, and a
    // gate that reported it would report a difference nobody can act on. Measured: it was the
    // single largest source of front-to-front difference on the first comparison.
    case Expr.Block(Nil, Some(r), _) => expr(r)
    case Expr.Block(sts, res, _) =>
      sx("block", sts.map(stmt) ++ res.map(x => List(sx("=>", List(expr(x))))).getOrElse(Nil))
