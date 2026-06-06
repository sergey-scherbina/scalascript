package scalascript.interpreter.vm.jit

import scala.meta.{Lit, Term}

object JitHofShape:

  final case class UnaryLong(op: Int, c: Long)
  final case class PredicateLong(pred: Int, c1: Long, c2: Long)

  def unaryLong(fn: Term.Function): UnaryLong | Null =
    val p = oneParamName(fn)
    if p == null then return null
    core(fn.body) match
      case Lit.Int(v)  => UnaryLong(JitHofDispatch.OpConst, v.toLong)
      case Lit.Long(v) => UnaryLong(JitHofDispatch.OpConst, v)
      case Term.Name(`p`) =>
        UnaryLong(JitHofDispatch.OpId, 0L)
      case Term.ApplyInfix.After_4_6_0(Term.Name(`p`), op, _, ac)
          if ac.values.lengthCompare(1) == 0 =>
        ac.values.head match
          case Term.Name(`p`) if op.value == "*" =>
            UnaryLong(JitHofDispatch.OpSquare, 0L)
          case lit =>
            longLit(lit) match
              case Some(c) =>
                op.value match
                  case "+" => UnaryLong(JitHofDispatch.OpAdd, c)
                  case "-" => UnaryLong(JitHofDispatch.OpSub, c)
                  case "*" => UnaryLong(JitHofDispatch.OpMul, c)
                  case "/" => UnaryLong(JitHofDispatch.OpDiv, c)
                  case "%" => UnaryLong(JitHofDispatch.OpMod, c)
                  case _   => null
              case None => null
      case _ => null

  def globalLong(fn: Term.Function): String | Null =
    val p = oneParamName(fn)
    if p == null then return null
    core(fn.body) match
      case ap: Term.Apply if ap.argClause.values.lengthCompare(1) == 0 =>
        ap.fun match
          case Term.Name(name) =>
            ap.argClause.values.head match
              case Term.Name(`p`) => name
              case _              => null
          case _ => null
      case _ => null

  def predicateLong(fn: Term.Function): PredicateLong | Null =
    val p = oneParamName(fn)
    if p == null then return null
    core(fn.body) match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, rhsAc)
          if op.value == "==" && rhsAc.values.lengthCompare(1) == 0 =>
        lhs match
          case Term.ApplyInfix.After_4_6_0(Term.Name(`p`), mod, _, modAc)
              if mod.value == "%" && modAc.values.lengthCompare(1) == 0 =>
            (longLit(modAc.values.head), longLit(rhsAc.values.head)) match
              case (Some(m), Some(eq)) => PredicateLong(JitHofDispatch.PredModEq, m, eq)
              case _                  => null
          case _ => null
      case _ => null

  def constantLong(fn: Term.Function): java.lang.Long | Null =
    longLit(core(fn.body)).map(java.lang.Long.valueOf).orNull

  def foldAdd(fn: Term.Function): Boolean =
    if fn.paramClause.values.lengthCompare(2) != 0 then return false
    val a = fn.paramClause.values.head.name.value
    val b = fn.paramClause.values(1).name.value
    core(fn.body) match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`a`), op, _, ac)
          if op.value == "+" && ac.values.lengthCompare(1) == 0 =>
        ac.values.head match
          case Term.Name(`b`) => true
          case _              => false
      case _ => false

  private def oneParamName(fn: Term.Function): String | Null =
    if fn.paramClause.values.lengthCompare(1) != 0 then null
    else
      val n = fn.paramClause.values.head.name.value
      if n.isEmpty then null else n

  private def core(t: Term): Term =
    t match
      case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
        b.stats.head match
          case inner: Term => core(inner)
          case _           => t
      case _ => t

  private def longLit(t: Term): Option[Long] =
    t match
      case Lit.Int(v)  => Some(v.toLong)
      case Lit.Long(v) => Some(v)
      case _           => None
