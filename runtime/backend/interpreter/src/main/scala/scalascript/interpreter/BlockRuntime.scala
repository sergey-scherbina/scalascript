package scalascript.interpreter

import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils}
import Computation.{Pure, FlatMap}
import DirectMonadTag.*

/** Block evaluation (`evalBlock`), direct-monad do-notation (`evalDirectBlock`),
 *  and supporting helpers.
 */
private[interpreter] object BlockRuntime:

  def extractDirectMonadTag(typeArgs: List[scala.meta.Type]): DirectMonadTag =
    val name = typeArgs.headOption.flatMap(DirectTypeUtils.extractPrimaryMonad).getOrElse("?")
    name match
      case "Option" => OptionM
      case "List"   => ListM
      case "Either" => EitherM
      case "Async"  => AsyncM
      case _        => OtherM

  /** Evaluate a block of statements; effects propagate through statements via flatMap.
   *  val/var declarations are threaded as Computation so effects in their rhs work. */
  def evalBlock(stats: List[Stat], env: Env, interp: Interpreter): Computation =
    // Only copy env entries that are absent from (or differ from) interp.globals.
    // These are params and shadowed vals; everything else is already visible via
    // interp.globals and the Term.Name fallback, so we skip copying it.
    // Shrinks the frame from O(N_env) to O(N_params + N_stale_closure) — typically 1–5 entries.
    // Walk only FrameMap local slots to avoid O(|globals|) iteration.
    // After the FrameMap chain, the terminal parent is either interp.globals
    // (skip it — huge) or a small closure map (iterate it to capture closures).
    val local: mutable.HashMap[String, Value] = env match
      case fm: FrameMap =>
        val b = mutable.HashMap.empty[String, Value]
        var cur: Map[String, Value] = fm
        while cur.isInstanceOf[FrameMap] do
          val fm2 = cur.asInstanceOf[FrameMap]
          fm2.appendLocalTo(b, interp.globals)
          cur = fm2.parent
        // If the terminal parent is the real globals map, skip it.
        // Otherwise it's a closure HashMap — iterate and capture non-global entries.
        if cur ne interp.globals then
          cur.foreachEntry { (k, v) =>
            if interp.globals.getOrElse(k, null) != v then b(k) = v
          }
        b
      case _ =>
        val b2 = mutable.HashMap.empty[String, Value]
        env.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b2(k) = v }
        b2
    val localView = new MutableEnvView(local)
    def step(remaining: List[Stat], lastVal: Value): Computation = remaining match
      case Nil => Pure(lastVal)
      case s :: rest =>
        s match
          case Defn.Val(_, pats, _, rhs) =>
            interp.eval(rhs, localView) match
              case Pure(rhsVal) =>
                pats match
                  case List(Pat.Var(n)) => local(n.value) = rhsVal
                  case List(pat) =>
                    val patEnv = PatternRuntime.matchPat(pat, rhsVal, localView, interp)
                    if patEnv == null then interp.located("Val pattern match failed")
                    else patEnv.foreachEntry { (k, v) => local(k) = v }
                  case _ =>
                step(rest, Value.UnitV)
              case rhsC => FlatMap(rhsC, { rhsVal =>
                pats match
                  case List(Pat.Var(n)) => local(n.value) = rhsVal
                  case List(pat) =>
                    val patEnv = PatternRuntime.matchPat(pat, rhsVal, localView, interp)
                    if patEnv == null then interp.located("Val pattern match failed")
                    else patEnv.foreachEntry { (k, v) => local(k) = v }
                  case _ =>
                step(rest, Value.UnitV)
              })
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
            interp.eval(rhs, localView) match
              case Pure(v) => local(n.value) = v; step(rest, Value.UnitV)
              case rhsC    => FlatMap(rhsC, { v => local(n.value) = v; step(rest, Value.UnitV) })
          // Variable assignment: write to local AND globals so that both the
          // current evalBlock and any enclosing while loop (via freshEnv) see it.
          // This also keeps local in sync, avoiding a stale-read on the next statement
          // without needing a full O(N) refresh scan.
          case Term.Assign(Term.Name(x), rhs) =>
            interp.eval(rhs, localView) match
              case Pure(v) => local(x) = v; interp.globals(x) = v; step(rest, Value.UnitV)
              case c       => FlatMap(c, { v => local(x) = v; interp.globals(x) = v; step(rest, Value.UnitV) })
          // Compound assignment inside a block (x += n, x -= n, etc.).
          case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
              if op.value.lengthIs > 1 && op.value.last == '=' &&
                 !BlockRuntime.isCompareOp(op.value) =>
            val baseOp = op.value.init
            if argClause.values.lengthCompare(1) == 0 then
              val rhsC = interp.eval(argClause.values.head, localView)
              interp.eval(lhs, localView) match
                case Pure(lhsV) =>
                  rhsC match
                    case Pure(rv) =>
                      interp.infix2(lhsV, baseOp, rv, localView) match
                        case Pure(newV) => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV)
                        case c          => FlatMap(c, { newV => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) })
                    case _ =>
                      FlatMap(rhsC, { rv =>
                        FlatMap(interp.infix2(lhsV, baseOp, rv, localView), { newV =>
                          local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) }) })
                case lhsC =>
                  FlatMap(lhsC, { lhsV =>
                    FlatMap(rhsC, { rv =>
                      FlatMap(interp.infix2(lhsV, baseOp, rv, localView), { newV =>
                        local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) }) }) })
            else
              val argComps   = argClause.values.map(interp.eval(_, localView))
              val argVsBlock = EvalRuntime.extractPureValues(argComps)
              interp.eval(lhs, localView) match
                case Pure(lhsV) if argVsBlock != null =>
                  interp.infix(lhsV, baseOp, argVsBlock, localView) match
                    case Pure(newV) => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV)
                    case c          => FlatMap(c, { newV => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) })
                case lhsC =>
                  FlatMap(lhsC, { lhsV =>
                    interp.threadValues(argComps) { argVs =>
                      FlatMap(interp.infix(lhsV, baseOp, argVs, localView), { newV =>
                        local(lhs.value)          = newV
                        interp.globals(lhs.value) = newV
                        step(rest, Value.UnitV)
                      })
                    }
                  })
          case t: Term =>
            interp.eval(t, localView) match
              case Pure(v) => step(rest, v)
              case c       => FlatMap(c, v => step(rest, v))
          case stat =>
            interp.execStat(stat, local)
            step(rest, Value.UnitV)
    step(stats, Value.UnitV)

  /** True when `op` is a comparison operator ending in `=` (not a compound assignment). */
  private[interpreter] inline def isCompareOp(op: String): Boolean =
    op == ">=" || op == "<=" || op == "!=" || op == "=="

  /** Intercept monadic bind to auto-lift foreign monad values.
   *
   *  When `direct[Async]` (tag = AsyncM) and the bound value is an `Option`
   *  or `Either`, the block uses OptionT/EitherT semantics automatically. */
  private def liftBindValue(
    monadValue: Value,
    tag:        DirectMonadTag,
    cont:       Value => Computation,
    cur:        Env,
    interp:     Interpreter
  ): Computation =
    (tag, monadValue) match
      case (AsyncM,  ov: Value.OptionV) =>
        if ov.inner != null then cont(ov.inner) else Computation.PureNone
      case (AsyncM,  Value.InstanceV("Right", f)) if f.contains("value") => cont(f("value"))
      case (AsyncM,  Value.InstanceV("Left", _))                  => Pure(monadValue)
      case (EitherM, ov: Value.OptionV) =>
        if ov.inner != null then cont(ov.inner)
        else Pure(Value.InstanceV("Left", new IMap.Map1("value", Value.UnitV)))
      case (OptionM, Value.InstanceV("Right", f)) if f.contains("value") => cont(f("value"))
      case (OptionM, Value.InstanceV("Left", _))                  => Computation.PureNone
      case (ListM | OtherM, _) | _ =>
        val contFn = Value.NativeFnV("direct-lift-cont", args => cont(args.head))
        DispatchRuntime.dispatch1(monadValue, "flatMap", contFn, cur, interp)

  private def checkDirectBlockStatics(stats: List[Stat], interp: Interpreter): Unit =
    def isNestedDirect(t: Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => false
      case _ => false
    def go(t: Tree): Unit = t match
      case _: Term.Return =>
        interp.located("'return' inside a direct block escapes the flatMap chain — for early failure use the monad's zero (None, Nil, Left(err), …) instead")
      case _ if isNestedDirect(t) => ()
      case _: Defn.Def | _: Term.Function => ()
      case other => other.children.foreach(go)
    stats.foreach(go)

  /** Evaluate a `direct[M] { stmts }` block by desugaring bind-forms
   *  (`x = expr`) into `monadValue.flatMap { x => rest }` calls. */
  def evalDirectBlock(
    stats:  List[Stat],
    env:    Env,
    tag:    DirectMonadTag,
    interp: Interpreter
  ): Computation =
    checkDirectBlockStatics(stats, interp)
    val expanded = DirectAnorm.expand(stats)
    val varNames: Set[String] = expanded.collect {
      case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
    }.toSet
    val prevInsideDirect = interp._insideDirectBlock.get()
    interp._insideDirectBlock.set(true)

    def step(remaining: List[Stat], cur: Env): Computation = remaining match
      case Nil => Computation.PureUnit

      case (t: Term.Throw) :: Nil =>
        interp.eval(t.expr, cur).flatMap { v =>
          Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        }

      case (last: Term) :: Nil =>
        interp.eval(last, cur)

      case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
        FlatMap(interp.eval(rhs, cur), { v => step(rest, FrameMap.one(x, v, cur)) })

      case Term.Assign(Term.Name(x), rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { monadValue =>
          liftBindValue(monadValue, tag, innerVal => step(rest, FrameMap.one(x, innerVal, cur)), cur, interp)
        })

      case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { monadValue =>
          liftBindValue(monadValue, tag, _ => step(rest, cur), cur, interp)
        })

      case Defn.Val(_, pats, _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { v =>
          pats match
            case List(Pat.Var(n)) => step(rest, FrameMap.one(n.value, v, cur))
            case List(pat) =>
              val patEnv = PatternRuntime.matchPat(pat, v, cur, interp)
              if patEnv == null then interp.located("direct block: val pattern match failed")
              else step(rest, cur ++ patEnv)
            case _ => step(rest, cur)
        })

      case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { v => step(rest, FrameMap.one(n.value, v, cur)) })

      case (t: Term.Throw) :: _ =>
        interp.eval(t.expr, cur).flatMap { v =>
          Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        }

      case (t: Term) :: rest =>
        FlatMap(interp.eval(t, cur), _ => step(rest, cur))

      case (d: Defn.Def) :: rest =>
        val allClauses2       = d.paramClauseGroups.flatMap(_.paramClauses)
        val regularClauses2   = allClauses2.filter(_.mod.isEmpty)
        val usingClauses2     = allClauses2.filter(_.mod.nonEmpty)
        val regularParamVals2 = regularClauses2.flatMap(_.values).toList
        val usingParamVals2   = usingClauses2.flatMap(_.values).toList
        @annotation.nowarn("msg=deprecated")
        val cbUsingParams2: List[(String, String)] =
          d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
            tp.cbounds.map { cb =>
              val tvName = tp.name.value
              val tcStr  = interp.typeToString(cb.asInstanceOf[scala.meta.Type])
              s"${tvName}$$${tcStr.takeWhile(_ != '[')}" -> s"$tcStr[$tvName]"
            }
          }
        val allRegularVals2 = regularParamVals2 ++ usingParamVals2
        val params2     = allRegularVals2.map(_.name.value) ++ cbUsingParams2.map(_._1)
        val defaults2   = allRegularVals2.map(_.default)    ++ cbUsingParams2.map(_ => None)
        val paramTypes2 = regularParamVals2.map(p => p.decltpe.fold("Any")(interp.typeToString))
        val usingInfo2: List[(String, String)] =
          usingParamVals2.map(p => p.name.value -> p.decltpe.fold("Any")(interp.typeToString)) ++ cbUsingParams2
        val capturedEnv: Map[String, Value] = cur match
          case fm: FrameMap =>
            val b = new scala.collection.mutable.HashMap[String, Value]
            var c: Map[String, Value] = fm
            while c.isInstanceOf[FrameMap] do
              c.asInstanceOf[FrameMap].appendLocalTo(b, interp.globals)
              c = c.asInstanceOf[FrameMap].parent
            if c ne interp.globals then
              c.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b(k) = v }
            b.toMap
          case _ =>
            val b = new scala.collection.mutable.HashMap[String, Value]
            cur.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b(k) = v }
            b.toMap
        val rThrows2 = d.decltpe.exists(interp.isThrowsType)
        val fn = Value.FunV(params2, d.body, capturedEnv, d.name.value, defaults2, paramTypes2, usingInfo2, rThrows2)
        step(rest, FrameMap.one(d.name.value, fn, cur))

      case _ :: rest =>
        step(rest, cur)

    try step(expanded, env)
    finally interp._insideDirectBlock.set(prevInsideDirect)
