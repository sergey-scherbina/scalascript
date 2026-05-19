package scalascript.transform

import scalascript.ir
import scala.meta.*

/** Translate a scalameta `Tree` to an `ir.IrExpr`.
 *
 *  v2.0 / Stage 5+ — unblocks the Linker's cross-module symbol
 *  rewriter (`Linker.rewriteExpr`).  Previously `Normalize` emitted
 *  `body = Nil` for every code block; without populated bodies the
 *  rewriter had no data to walk, so cross-module `VarRef`s could not
 *  be rewritten to mangled FQNs at link time.
 *
 *  The translator handles the common scalameta term shapes:
 *
 *    Term.Name(n)          → ir.VarRef(n)
 *    Term.Apply(fn, args)  → ir.Apply(toIrExpr(fn), args.map(toIrExpr))
 *    Term.ApplyInfix       → ir.Apply(Select(lhs, op), args)
 *    Term.Select(q, n)     → ir.Select(toIrExpr(q), n.value)
 *    Lit.Int(v)            → ir.Lit(LitValue.IntL(v))
 *    Lit.Long(v)           → ir.Lit(LitValue.IntL(v))
 *    Lit.Double(v)         → ir.Lit(LitValue.DoubleL(v))
 *    Lit.String(v)         → ir.Lit(LitValue.StringL(v))
 *    Lit.Boolean(v)        → ir.Lit(LitValue.BoolL(v))
 *    Lit.Unit()            → ir.Lit(LitValue.UnitL)
 *    Term.Block(stats)     → ir.Block(stats.map(toIrExpr))
 *    Term.If(c,t,e)        → ir.If(c, t, Some(e))
 *    Term.Function(ps, b)  → ir.Lambda(paramNames, toIrExpr(b))
 *    Source(stats)         → ir.Block(stats.map(toIrExpr))
 *
 *  Anything else — definitions (`Defn.Def`, `Defn.Val`, `Defn.Class`,
 *  `Defn.Object`), advanced pattern matching, types, etc. — falls
 *  back to `ir.Unsupported(node.syntax)` so the body is non-empty
 *  but opaque.  The Linker doesn't need to look inside `Unsupported`:
 *  it only rewrites `VarRef` nodes whose name resolves to a foreign
 *  module export.  When new node kinds are needed by the rewriter,
 *  add cases here without breaking existing consumers. */
object AstToIr:

  /** Top-level entry: translate a scalameta tree (typically a
   *  `Source` or a `Term.Block`) to a single `IrExpr`.  A `Source`
   *  becomes an `ir.Block` of its top-level statements; everything
   *  else is delegated to `term`. */
  def toIrExpr(t: Tree): ir.IrExpr = t match
    case Source(stats)         => ir.Block(stats.map(stat))
    case Pkg.After_4_9_9(_, body) => ir.Block(body.stats.map(stat))
    case Term.Block(stats)     => ir.Block(stats.map(stat))
    case term: Term            => translateTerm(term)
    case other                 => ir.Unsupported(other.syntax)

  /** Translate a "statement" — anything that can appear at the top
   *  level of a code block.  Statements that are also expressions
   *  fall through to `translateTerm`.  Definitions (`Defn.Val`,
   *  `Defn.Def`, `Defn.Var`) descend into their RHS so the bound
   *  names are visible as `VarRef`s the Linker can rewrite; the
   *  definition itself is represented as `Apply(VarRef("val"|"def"|"var"), [VarRef(name), rhs])`
   *  so the body shape carries the name binding too.  Other
   *  declarations (`Defn.Class`, `Defn.Object`, …) become
   *  `ir.Unsupported` carrying their source. */
  private def stat(s: Stat): ir.IrExpr = s match
    case t: Term         => translateTerm(t)
    case dv: Defn.Val    => defnVal(dv)
    case dv: Defn.Var    => defnVar(dv)
    case dd: Defn.Def    => defnDef(dd)
    case other           => ir.Unsupported(other.syntax)

  /** `val x = rhs` → `Apply(VarRef("val"), [VarRef("x"), toIrExpr(rhs)])`.
   *  The shape isn't semantic — it's just a way to encode the name and
   *  the RHS so both are visible to the Linker as IR nodes.  Multi-pat
   *  vals (`val (a, b) = …`) flatten to one Apply per name. */
  private def defnVal(dv: Defn.Val): ir.IrExpr =
    val rhs = translateTerm(dv.rhs)
    val names = dv.pats.flatMap(extractPatNames)
    if names.isEmpty then ir.Apply(ir.VarRef("val"), List(rhs))
    else ir.Block(names.map(n => ir.Apply(ir.VarRef("val"), List(ir.VarRef(n), rhs))))

  private def defnVar(dv: Defn.Var): ir.IrExpr =
    val rhs = dv match
      case Defn.Var.After_4_7_2(_, _, _, body: Term) => translateTerm(body)
      case _                                          => ir.Lit(ir.LitValue.UnitL)
    val names = dv.pats.flatMap(extractPatNames)
    if names.isEmpty then ir.Apply(ir.VarRef("var"), List(rhs))
    else ir.Block(names.map(n => ir.Apply(ir.VarRef("var"), List(ir.VarRef(n), rhs))))

  /** `def f(p1, …)(p2, …) = body` →
   *  `Apply(VarRef("def"), [VarRef(f), Lambda(allParamNames, toIrExpr(body))])`.
   *  Collects every value-parameter name across every clause group. */
  private def defnDef(dd: Defn.Def): ir.IrExpr =
    val name       = dd.name.value
    val paramNames = dd.paramClauseGroups.flatMap { g =>
      g.paramClauses.flatMap(_.values.map(_.name.value))
    }
    val body = translateTerm(dd.body)
    ir.Apply(
      ir.VarRef("def"),
      List(ir.VarRef(name), ir.Lambda(paramNames, body))
    )

  /** Extract the simple-name set bound by a pattern (`x`, `(a, b)`).
   *  Anything richer is conservatively flattened to no names. */
  private def extractPatNames(p: Pat): List[String] = p match
    case Pat.Var(name)    => List(name.value)
    case Pat.Tuple(args)  => args.flatMap(extractPatNames).toList
    case _                => Nil

  /** Translate an expression-position scalameta node.  Falls back to
   *  `ir.Unsupported(node.syntax)` for any term the translator does
   *  not yet model — never throws. */
  private def translateTerm(t: Term): ir.IrExpr = t match
    case Term.Name(n)                  => ir.VarRef(n)
    case Term.Select(qual, name)       => ir.Select(translateTerm(qual), name.value)
    case Term.Apply.After_4_6_0(fn, argClause)          => ir.Apply(translateTerm(fn), argClause.values.map(translateTerm))
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      // a + b   →   Apply(Select(a, "+"), [b])
      // Two-arg infix collapses to ordinary application against the operator name.
      ir.Apply(ir.Select(translateTerm(lhs), op.value), argClause.values.map(translateTerm))
    case Term.ApplyUnary(op, arg)      =>
      ir.Apply(ir.Select(translateTerm(arg), s"unary_${op.value}"), Nil)
    case Term.Block(stats)             => ir.Block(stats.map(stat))
    case t: Term.If =>
      ir.If(translateTerm(t.cond), translateTerm(t.thenp), Some(translateTerm(t.elsep)))
    case Term.Function.After_4_6_0(paramClause, body)   =>
      ir.Lambda(paramClause.values.map(_.name.value), translateTerm(body))
    case Term.AnonymousFunction(body)  =>
      // `_ + 1` / `foo(_)` — best effort: wrap the body opaquely.
      ir.Lambda(Nil, translateTerm(body))
    case Term.Assign(lhs, rhs)         =>
      // No first-class IR assignment node — model as Apply(Select(lhs, "="), [rhs]).
      ir.Apply(ir.Select(translateTerm(lhs), "="), List(translateTerm(rhs)))
    case Term.Return(expr)             => translateTerm(expr)
    case Term.Tuple(args)              => ir.Apply(ir.VarRef("Tuple"), args.map(translateTerm))
    case Term.Interpolate(_, _, _)     => ir.Unsupported(t.syntax)
    case Term.Throw(expr)              => ir.Apply(ir.VarRef("throw"), List(translateTerm(expr)))
    case Term.New(init)                =>
      // `new Foo(args)` — Init has `tpe` (Type) and `argClauses` (List[List[Term]]).
      val ctorName = init.tpe.syntax
      val args     = init.argClauses.flatMap(_.values).map(translateTerm).toList
      ir.Apply(ir.VarRef(s"new $ctorName"), args)
    case lit: Lit                      => literal(lit)
    case other                         => ir.Unsupported(other.syntax)

  private def literal(l: Lit): ir.IrExpr = l match
    case Lit.Int(v)     => ir.Lit(ir.LitValue.IntL(v.toLong))
    case Lit.Long(v)    => ir.Lit(ir.LitValue.IntL(v))
    case Lit.Double(v)  => ir.Lit(ir.LitValue.DoubleL(v.toDouble))
    case Lit.Float(v)   => ir.Lit(ir.LitValue.DoubleL(v.toDouble))
    case Lit.String(v)  => ir.Lit(ir.LitValue.StringL(v))
    case Lit.Boolean(v) => ir.Lit(ir.LitValue.BoolL(v))
    case Lit.Unit()     => ir.Lit(ir.LitValue.UnitL)
    case Lit.Null()     => ir.Unsupported(l.syntax)
    case other          => ir.Unsupported(other.syntax)
