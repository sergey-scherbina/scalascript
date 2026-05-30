package scalascript.interpreter

import scala.meta.*
import Computation.{Pure, FlatMap, Perform}

/** TCO trampoline, suspension stepper, and AST tail-call analysis.
 *
 *  All methods are pure (no mutable state of their own); interpreter fields
 *  are accessed via the `interp` parameter.
 */
private[interpreter] object TcoRuntime:

  // Singleton returned for every unnamed lambda — avoids allocation + cache pollution.
  private val noTcoInfo = TcoInfo(Set.empty, isSelfTailRec = false, noNonTailSelf = false, hasSelfNameRef = false)

  def tcoInfoFor(f: Value.FunV, interp: Interpreter): TcoInfo =
    if f.name.isEmpty then noTcoInfo
    else
      val cached = interp.tcoCache.get(f.body)
      if cached != null then cached
      else
        val targets = tailCallTargets(f.body, f.name, tailPos = true)
        val selfTR  = callsInTailPos(f.body, f.name)
        val noNTS   = !hasNonTailSelfCall(f.body, f.name, tailPos = true)
        val selfRef = containsSelfNameRef(f.body, f.name)
        val info    = TcoInfo(targets, selfTR, noNTS, selfRef)
        interp.tcoCache.put(f.body, info)
        info

  /** TCO trampoline that survives effect suspensions.
   *
   *  Body code is evaluated under an env that maps the function name (and any
   *  mutually-recursive friends) to native fns that throw `TailCall` /
   *  `MutualTailCall`. The outer while-loop catches those and re-runs the body
   *  with the new arg vector — same trick as the classic trampoline, but here
   *  the inner step uses `runUntilSuspension` so Performs propagate.
   *
   *  When the body suspends at `FlatMap(Perform, k)`, the trampoline returns
   *  the Perform to its caller (the enclosing handler) but wraps `k` so that
   *  when resume invokes it, control re-enters `tcoTrampoline` with `k(v)` as
   *  the initial Computation. This way TailCalls thrown by the post-suspension
   *  code are caught by the trampoline, not by an already-exited frame.
   *
   *  Each `resume` invocation pays one Scala stack frame for re-entering the
   *  trampoline; subsequent tail iterations and bind-chain stepping use the
   *  while-loops and stay O(1). */
  def tcoTrampoline(
    initialFun:  Value.FunV,
    initialArgs: List[Value],
    initialComp: Computation,
    interp:      Interpreter
  ): Computation =
    var curFun: Value.FunV   = initialFun
    var curArgs: List[Value] = initialArgs
    var current: Computation = initialComp
    var profileCurrentFun    = Profiler.enabled && curFun.name.nonEmpty
    // Stable, per-curFun part of the call env: closure + self-tco stub +
    // mutual-tail-call stubs. The only thing that varies across tail
    // iterations is the param binding, so build this once per `curFun`
    // and refresh it only when a mutual jump changes `curFun`.
    var envStable: Map[String, Value] = null
    var envStableFor: Value.FunV      = null
    while true do
      try
        if current == null then
          if (envStable eq null) || (envStableFor ne curFun) then
            val targets = tcoInfoFor(curFun, interp).tailTargets
            val selfTco = Value.NativeFnV(curFun.name, a => {
              interp.tailCallSig.args = a
              throw interp.tailCallSig
            })
            val selfEnv: Env = FrameMap.one(curFun.name, selfTco, curFun.closure)
            envStable =
              if targets.isEmpty then selfEnv
              else
                val mutualEntries: Map[String, Value] = targets.flatMap { name =>
                  (interp.globals.get(name) orElse curFun.closure.get(name)).collect {
                    case fn: Value.FunV =>
                      name -> (Value.NativeFnV(name, a => {
                        interp.mutualTailCallSig.f    = fn
                        interp.mutualTailCallSig.args = a
                        throw interp.mutualTailCallSig
                      }): Value)
                  }
                }.toMap
                FrameMap.fromMap(mutualEntries, selfEnv)
            envStableFor  = curFun
          val callEnv: Env = curFun.params match
            case Nil               => envStable
            case p :: Nil          => FrameMap.one(p, curArgs.head, envStable)
            case p1 :: p2 :: Nil   => FrameMap.two(p1, curArgs.head, p2, curArgs(1), envStable)
            case ps                =>
              var names = interp.paramsArrayCache.get(curFun.body)
              if names == null then { names = ps.toArray; interp.paramsArrayCache.put(curFun.body, names) }
              val vals = curArgs.iterator.take(names.length).toArray
              FrameMap.of(names, vals, envStable)
          current = interp.eval(curFun.body, callEnv)
        // Inner step loop — re-associate FlatMaps and step Pure short-circuits.
        // Exits via `return` inside the match; the condition stays `true`.
        while true do
          current match
            case Pure(_)              => return current
            case Perform(_, _, _)     => return current
            case FlatMap(sub, k) => sub match
              case Pure(v)               => current = k(v)
              case Perform(eff, op, a)   =>
                val funSnapshot  = curFun
                val argsSnapshot = curArgs
                // Compute `k(v)` lazily inside a try so a TailCall thrown by
                // a tail-recursive self-call in `k`'s body re-enters the
                // trampoline rather than escaping. Without this the resume
                // continuation evaluates `k(v)` eagerly as a strict argument
                // to tcoTrampoline and any TailCall it throws falls through
                // both the outer trampoline's try (already exited) and the
                // re-entry's try (not yet armed).
                return FlatMap(Perform(eff, op, a), v =>
                  try tcoTrampoline(funSnapshot, argsSnapshot, k(v), interp)
                  catch
                    case tc: TailCall       => tcoTrampoline(funSnapshot, tc.args, null, interp)
                    case mc: MutualTailCall =>
                      val next = mc.f
                      if next.name.nonEmpty && tcoInfoFor(next, interp).noNonTailSelf then
                        tcoTrampoline(next, mc.args, null, interp)
                      else interp.callFun(next, mc.args))
              case FlatMap(sub2, g)      =>
                current = FlatMap(sub2, x => FlatMap(g(x), k))
      catch
        case r: ReturnSignal    => return Pure(r.value)
        case tc: TailCall       =>
          // Each TailCall is one additional recursive invocation.
          if profileCurrentFun then
            Profiler.record(curFun.name, 0L)
          curArgs = tc.args
          current = null
        case mc: MutualTailCall =>
          val next = mc.f
          if next.name.nonEmpty && tcoInfoFor(next, interp).noNonTailSelf then
            // Mutual tail call counts as one call to `next`.
            val profileNext = Profiler.enabled && next.name.nonEmpty
            if profileNext then
              Profiler.record(next.name, 0L)
            curFun  = next
            curArgs = mc.args
            profileCurrentFun = profileNext
            current = null
          else
            return interp.callFun(next, mc.args)
    throw InterpretError("unreachable")

  /** Run a Computation through Pure short-circuits and FlatMap re-associations
   *  until it either resolves to Pure, or hits a Perform that needs to escape
   *  to an outer handler. The while-loop with right-association makes this
   *  stack-safe regardless of how deep the bind chain is (Bjarnason 2012).
   *
   *  ReturnSignal / TailCall / MutualTailCall propagate to the caller. */
  def runUntilSuspension(c: Computation): Computation =
    var current: Computation = c
    while true do
      current match
        case Pure(_)             => return current
        case Perform(_, _, _)    => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)              => current = f(v)
          case Perform(eff, op, args) =>
            return FlatMap(Perform(eff, op, args), f)
          case FlatMap(sub2, g)     =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
    throw InterpretError("unreachable")

  /** True if `fname` appears in a tail position of `tree`. */
  private def callsInTailPos(tree: Tree, fname: String): Boolean = tree match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
    case t: Term.If =>
      callsInTailPos(t.thenp, fname) || callsInTailPos(t.elsep, fname)
    case Term.Block(stats) =>
      stats.lastOption.exists { case t: Term => callsInTailPos(t, fname); case _ => false }
    case t: Term.Match =>
      t.casesBlock.cases.exists(c => callsInTailPos(c.body, fname))
    case _ => false

  /** Returns names of functions called in tail position in term (excluding selfName). */
  private def tailCallTargets(tree: Tree, selfName: String, tailPos: Boolean): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  /** Returns true if term has a self-call to fname NOT in tail position. */
  private def hasNonTailSelfCall(term: Term, fname: String, tailPos: Boolean): Boolean =
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        if tailPos then argClause.values.collect { case t: Term => t }
                                        .exists(hasNonTailSelfCall(_, fname, tailPos = false))
        else true
      case t: Term.If =>
        hasNonTailSelfCall(t.cond,  fname, tailPos = false) ||
        hasNonTailSelfCall(t.thenp, fname, tailPos = tailPos) ||
        hasNonTailSelfCall(t.elsep, fname, tailPos = tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = false)
          case _       => false
        } || stats.lastOption.exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = tailPos)
          case _       => false
        }
      case t: Term.Match =>
        hasNonTailSelfCall(t.expr, fname, tailPos = false) ||
        t.casesBlock.cases.exists(c => hasNonTailSelfCall(c.body, fname, tailPos = tailPos))
      case other =>
        anywhereContainsSelfCall(other, fname)

  private def anywhereContainsSelfCall(tree: Tree, fname: String): Boolean =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
      case t => t.children.exists(anywhereContainsSelfCall(_, fname))

  private def containsSelfNameRef(tree: Tree, fname: String): Boolean =
    tree match
      case Term.Name(`fname`) => true
      case t                  => t.children.exists(containsSelfNameRef(_, fname))
