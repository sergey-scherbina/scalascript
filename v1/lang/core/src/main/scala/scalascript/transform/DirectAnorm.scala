package scalascript.transform

import scala.collection.mutable
import scala.meta.*

/** A-normalization and union-type utilities for `direct[M]` blocks.
 *
 *  v1.8.1: postfix `.!` bind operator and effect-row union type acceptance. */

object DirectAnorm:

  /** Expand all `.!` occurrences in a `direct[M]` statement list into
   *  explicit bind statements prepended before the containing statement.
   *
   *  `fa.!` anywhere in expression position becomes a fresh `_bN = fa`
   *  bind followed by `_bN` in the original position. `.!` inside nested
   *  `direct[M]` blocks, lambda bodies, or `Term.Block`s is not lifted. */
  def expand(stats: List[Stat]): List[Stat] =
    var counter = 0
    def fresh(): String = { val n = s"_b$counter"; counter += 1; n }
    val result = mutable.ListBuffer[Stat]()
    for stat <- stats do
      val buf = mutable.ListBuffer[Stat]()
      val rewritten = anormStat(stat, buf, fresh)
      result ++= buf
      result += rewritten
    result.toList

  private def isNestedDirect(app: Term.Apply): Boolean =
    app.fun match
      case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
      case _ => false

  private def anormStat(stat: Stat, buf: mutable.ListBuffer[Stat], fresh: () => String): Stat =
    stat match
      case Term.Assign(lhs, rhs)          => Term.Assign(lhs, anormTerm(rhs, buf, fresh))
      case Defn.Val(mods, pats, tpe, rhs) => Defn.Val(mods, pats, tpe, anormTerm(rhs, buf, fresh))
      case t: Term                         => anormTerm(t, buf, fresh)
      case other                           => other

  private def anormTerm(t: Term, buf: mutable.ListBuffer[Stat], fresh: () => String): Term =
    t match
      // Nested direct[M] — stop descent
      case app: Term.Apply if isNestedDirect(app) => app
      // Lambda bodies and blocks — don't lift .! out of these
      case _: Term.Function | _: Term.AnonymousFunction | _: Term.Block => t

      // The .! bind operator — lift out into a prepended bind
      case Term.Select(fa, Term.Name("!")) =>
        val fa2 = anormTerm(fa, buf, fresh)
        val n   = fresh()
        buf    += Term.Assign(Term.Name(n), fa2)
        Term.Name(n)

      // Recurse into qualifier of a field/method select
      case Term.Select(qual, name) =>
        val q2 = anormTerm(qual, buf, fresh)
        if q2 eq qual then t else Term.Select(q2, name)

      // Function application
      case Term.Apply.After_4_6_0(fun, argClause) =>
        val fun2  = anormTerm(fun, buf, fresh)
        val args2 = argClause.values.map(a => anormTerm(a, buf, fresh))
        if (fun2 eq fun) && args2.corresponds(argClause.values)(_ eq _) then t
        else Term.Apply.After_4_6_0(fun2, Term.ArgClause(args2, argClause.mod))

      // Infix application (binary operators)
      case Term.ApplyInfix.After_4_6_0(lhs, op, targs, argClause) =>
        val lhs2  = anormTerm(lhs, buf, fresh)
        val args2 = argClause.values.map(a => anormTerm(a, buf, fresh))
        if (lhs2 eq lhs) && args2.corresponds(argClause.values)(_ eq _) then t
        else Term.ApplyInfix.After_4_6_0(lhs2, op, targs, Term.ArgClause(args2, argClause.mod))

      // Tuple construction
      case Term.Tuple(args) =>
        val args2 = args.map(a => anormTerm(a, buf, fresh))
        if args2.corresponds(args)(_ eq _) then t else Term.Tuple(args2)

      // String interpolation s"..." / f"..." etc.
      case Term.Interpolate(prefix, parts, args) =>
        val args2 = args.map(a => anormTerm(a, buf, fresh))
        if args2.corresponds(args)(_ eq _) then t
        else Term.Interpolate(prefix, parts, args2)

      // Type ascription: (expr: T)
      case Term.Ascribe(expr, tpe) =>
        val expr2 = anormTerm(expr, buf, fresh)
        if expr2 eq expr then t else Term.Ascribe(expr2, tpe)

      // Named argument (x = rhs) inside a call — recurse into rhs only
      case Term.Assign(lhs, rhs) =>
        val rhs2 = anormTerm(rhs, buf, fresh)
        if rhs2 eq rhs then t else Term.Assign(lhs, rhs2)

      // Everything else: no .! can appear (or unsupported position)
      case other => other


object DirectTypeUtils:

  /** Extract the leftmost type constructor name from a possibly-union type.
   *
   *  - `Type.Name("Option")`          → `Some("Option")`
   *  - `Type.ApplyInfix(A, "|", B)`   → `extractPrimaryMonad(A)` (recurse left)
   *  - `Type.Apply(F, _)`             → name of `F` */
  def extractPrimaryMonad(tpe: Type): Option[String] = tpe match
    case Type.Name(n)                                  => Some(n)
    case Type.ApplyInfix(lhs, Type.Name("|"), _)       => extractPrimaryMonad(lhs)
    case ta: Type.Apply                                =>
      ta.tpe match { case Type.Name(n) => Some(n); case _ => None }
    case _                                             => None

  /** Reject non-`|` infix type operators in `direct[M]` type arguments.
   *  `|` (union), simple type names, and applied types are all accepted. */
  def validateDirectTypeArg(tpe: Type): Unit = tpe match
    case _: Type.Name                               => ()
    case Type.ApplyInfix(lhs, Type.Name("|"), rhs) =>
      validateDirectTypeArg(lhs); validateDirectTypeArg(rhs)
    case Type.ApplyInfix(_, Type.Name(op), _)      =>
      throw new RuntimeException(
        s"direct[M]: unsupported type operator '$op' — only '|' union is allowed in effect-row position")
    case _: Type.Apply                              => ()
    case _                                          => ()
