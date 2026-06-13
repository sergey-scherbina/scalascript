package scalascript.interpreter

import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import Computation.{Pure, FlatMap, Perform}

/** Algebraic-effect handle interpreter (`evalHandle`) and restartable-error
 *  handler (`evalRestartable`).
 */
private[interpreter] object EffectsRuntime:

  def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

  /** Interpret `handle(body) { cases }` — trampolined.
   *
   *  The body is evaluated to a Computation tree. We walk it with a while-loop:
   *
   *    Pure(v)                     → return Pure(v)
   *    Perform(eff, op, args)      → handler matched? dispatch with resume = identity
   *                                   else propagate as-is (no continuation to wrap)
   *    FlatMap(Pure(v), f)         → step to f(v)
   *    FlatMap(FlatMap(c, g), f)   → re-associate to FlatMap(c, x => FlatMap(g(x), f))
   *    FlatMap(Perform(...), f)    → handler matched? dispatch with resume = v => interp(f(v))
   *                                   else propagate as FlatMap(Perform, v => interp(f(v)))
   *
   *  The re-association keeps the Scala call stack O(1) regardless of how deeply
   *  the bind chain is nested. `resume(v) = interp(k(v))` is itself a closure;
   *  invoking it (from the handler case body) starts a fresh trampoline.
   *  Multi-shot is calling that closure more than once — each invocation walks
   *  a fresh branch of the tree.
   */
  def evalHandle(body: Term, cases: List[Case], env: Env, interp: Interpreter,
                 multiShotEffects: Set[String] = Set.empty): Computation =
    // A `case Return(x) => expr` (unqualified `Return`, vs the `Eff.op(...)` effect-op
    // cases which are qualified `Select`s) is the optional RETURN CLAUSE: it maps the
    // handled computation's final pure value. This is what makes the textbook deep-handler
    // accumulation `msg :: resume(())` work — `resume(())` of the final continuation yields
    // the return-clause result (e.g. `Nil`) instead of the raw pure value. Without it, the
    // pure value is returned unchanged (backward-compatible).
    val (returnCases, opCases) = cases.partition { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Name("Return"), _) => true
        case _                                               => false
    }
    def applyReturn(v: Value): Computation =
      if returnCases.isEmpty then Pure(v)
      else returnCases.iterator.flatMap { c =>
        val innerPat: Pat = c.pat match
          case Pat.Extract.After_4_6_0(_, ac) if ac.values.nonEmpty => ac.values.head.asInstanceOf[Pat]
          case _                                                    => Pat.Wildcard()
        PatternRuntime.matchPat(innerPat, v, env, interp) match
          case null    => None
          case bindEnv =>
            val be = bindEnv.asInstanceOf[Env]
            val guardOk = c.cond.forall { g =>
              Computation.run(interp.eval(g, be)) match { case Value.BoolV(b) => b; case _ => false }
            }
            if guardOk then Some(interp.eval(c.body, be)) else None
      }.nextOption().getOrElse(Pure(v))

    // Fast path: evaluate body first; if Pure (no perform calls), apply the return clause
    // (if any) and skip handler setup.
    val initial = interp.eval(body, env)
    initial match
      case Pure(v) => return applyReturn(v)
      case _ =>

    val handledOps: Set[(String, String)] = opCases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some((eff, op))
        case _ => None
    }.toSet

    def dispatchCase(eff: String, op: String, args: List[Value], resume: Value): Computation =
      opCases.iterator.flatMap { c =>
        c.pat match
          case Pat.Extract.After_4_6_0(Term.Select(Term.Name(`eff`), Term.Name(`op`)), argClause) =>
            val patArgs   = argClause.values
            val argPats   = patArgs.dropRight(1).map(_.asInstanceOf[Pat])
            val resumePat = patArgs.lastOption
            var matchEnv: Env | Null = env
            val pairs = argPats.zip(args)
            var pi = 0
            while matchEnv != null && pi < pairs.length do
              val (pat, v) = pairs(pi)
              matchEnv = PatternRuntime.matchPat(pat, v, matchEnv.asInstanceOf[Env], interp)
              pi += 1
            if matchEnv == null then None
            else
              val argEnv = matchEnv.asInstanceOf[Env]
              val finalEnv = resumePat match
                case Some(pv: Pat.Var) => FrameMap.one(pv.name.value, resume, argEnv)
                case _                 => argEnv
              val guardOk = c.cond.forall { g =>
                Computation.run(interp.eval(g, finalEnv)) match
                  case Value.BoolV(b) => b
                  case _              => false
              }
              if guardOk then Some(interp.eval(c.body, finalEnv)) else None
          case _ => None
      }.nextOption()
        .getOrElse(throw InterpretError(s"Unhandled effect: $eff.$op (no matching case)"))

    // ── One-shot tail-resume fast path ───────────────────────────────────────────
    // The overwhelmingly common one-shot handler arm is a BARE tail-position resume:
    //   `case Eff.op(a.., resume) => resume(rexpr..)`   (no guard, single arm for the op).
    // Such an arm is provably tail-position and its body just feeds a value back into the
    // continuation, so it needs neither the per-perform lazy `placeholder` (used only to
    // *detect* tail position) nor the `resume` NativeFnV (we apply the continuation `f`
    // directly). Precompute (op-arg pats, resume-arg terms) per (eff,op); restricted to
    // resume-args that are literals / names (always `Pure`, so `f(v)` is exact). Absent
    // key → the general placeholder path below (unchanged).
    val tailResumeFast: Map[(String, String), (List[Pat], List[Term])] =
      def simpleArg(t: Term, resumeName: String): Boolean = t match
        case _: Lit            => true
        case n: Term.Name      => n.value != resumeName
        case Term.Tuple(args)  => args.forall(simpleArg(_, resumeName))
        case _                 => false
      opCases.groupBy { c =>
        c.pat match
          case Pat.Extract.After_4_6_0(Term.Select(Term.Name(e), Term.Name(o)), _) => (e, o)
          case _ => ("", "")
      }.flatMap { (key, cs) =>
        if cs.lengthCompare(1) != 0 then None
        else
          val c = cs.head
          if c.cond.isDefined then None
          else c.pat match
            case Pat.Extract.After_4_6_0(Term.Select(Term.Name(_), Term.Name(_)), argClause) =>
              val resumeName = argClause.values.lastOption match
                case Some(pv: Pat.Var) => pv.name.value
                case _                 => null
              if resumeName == null then None
              else c.body match
                case Term.Apply.After_4_6_0(Term.Name(`resumeName`), rArgClause)
                    if rArgClause.values.forall(simpleArg(_, resumeName)) =>
                  Some(key -> (argClause.values.dropRight(1).map(_.asInstanceOf[Pat]), rArgClause.values))
                case _ => None
            case _ => None
      }

    /** Apply a bare tail-resume arm directly: bind op-args, evaluate the (simple) resume
     *  args, and feed the value into the continuation `f`. Returns null if the op-arg
     *  patterns fail to match (→ caller uses the general path). */
    def tryTailResume(argPats: List[Pat], rArgTerms: List[Term],
                      args: List[Value], f: Value => Computation): Computation | Null =
      var matchEnv: Env | Null = env
      var pi = 0
      while matchEnv != null && pi < argPats.length do
        matchEnv = PatternRuntime.matchPat(argPats(pi), args(pi), matchEnv.asInstanceOf[Env], interp)
        pi += 1
      if matchEnv == null then null
      else
        val be = matchEnv.asInstanceOf[Env]
        val v = rArgTerms match
          case Nil      => Value.UnitV
          case t :: Nil => Computation.run(interp.eval(t, be))
          case ts       => Value.TupleV(ts.map(t => Computation.run(interp.eval(t, be))))
        f(v)

    def handleInterp(initial: Computation): Computation =
      var current: Computation = initial
      while true do
        current match
          case Pure(_) => return current
          case Perform(eff, op, args) =>
            if !handledOps.contains((eff, op)) then return current
            else
              val effIsOneShot = !multiShotEffects.contains(eff)
              var _resumed = false
              val resume = Value.NativeFnV("resume", rargs => {
                if effIsOneShot && _resumed then
                  throw InterpretError(s"One-shot violation: $eff.$op resumed more than once")
                _resumed = true
                val v = rargs match
                  case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                Pure(v)
              })
              current = dispatchCase(eff, op, args, resume)
          case FlatMap(sub, f) => sub match
            case Pure(v) =>
              current = f(v)
            case FlatMap(sub2, g) =>
              current = FlatMap(sub2, x => FlatMap(g(x), f))
            case Perform(eff, op, args) =>
              if !handledOps.contains((eff, op)) then
                return FlatMap(Perform(eff, op, args), v => handleInterp(f(v)))
              else if multiShotEffects.contains(eff) then
                // Multi-shot: resume may be called more than once; each call
                // must independently evaluate its continuation branch.  The
                // lazy-placeholder trick cannot be used here because with n
                // sequential dispatches each resolved_i = f(v) already contains
                // all prior continuations, producing an O(n²) FlatMap tree that
                // exhausts the heap.  The O(n) JVM stack depth is the lesser
                // evil: real nondeterminism programs rarely chain more than a
                // handful of sequential multi-shot dispatches before the
                // combinatorial result space itself becomes the bottleneck.
                val resume = Value.NativeFnV("resume", rargs => {
                  val v = rargs match
                    case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                  handleInterp(f(v))
                })
                current = dispatchCase(eff, op, args, resume)
              else
                // One-shot tail-resume fast path: a bare `case Eff.op(..) => resume(rexpr)`
                // arm. Skips the placeholder + resume-NativeFnV allocation — apply the
                // continuation directly. Falls through to the general path on a pattern miss.
                val fast: Computation | Null = tailResumeFast.get((eff, op)) match
                  case Some((argPats, rArgTerms)) => tryTailResume(argPats, rArgTerms, args, f)
                  case None                       => null
                if fast != null then current = fast
                else {
                // One-shot: use a lazy sentinel to detect tail-position resume.
                //
                // resume.f stores f(v) in `resolved` and returns the stable
                // `placeholder` object.  After dispatchCase:
                //
                //   caseBodyResult eq placeholder  →  case body IS resume(v),
                //     tail position.  Set current = resolved and loop — zero
                //     extra JVM frames per dispatch, O(1) stack for chained
                //     tail-position effects (e.g. 1000 Counter.tick dispatches).
                //
                //   caseBodyResult ne placeholder  →  resume was wrapped in a
                //     larger expression (e.g. msg :: resume(())), non-tail.
                //     Eagerly evaluate resolved = handleInterp(f(v)) now so that
                //     accumulated values keep their left-to-right order when the
                //     outer while-loop steps through FlatMap(placeholder, k).
                var resolved: Computation = null
                val placeholder = FlatMap(Pure(Value.UnitV), (_: Value) => resolved)
                var _resumed = false
                val resume = Value.NativeFnV("resume", rargs => {
                  if _resumed then
                    throw InterpretError(s"One-shot violation: $eff.$op resumed more than once")
                  _resumed = true
                  val v = rargs match
                    case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                  resolved = f(v)
                  placeholder
                })
                val caseBodyResult = dispatchCase(eff, op, args, resume)
                if caseBodyResult eq placeholder then
                  current = resolved          // tail position: iterate, no new frame
                else if _resumed then
                  resolved = handleInterp(resolved)  // non-tail: eager for ordering
                  current = caseBodyResult
                else
                  current = caseBodyResult   // resume not called (e.g. early abort)
                }
      throw InterpretError("unreachable")

    // ── Return-clause path: a clean RECURSIVE handler ────────────────────────────
    // Used only when the handler has a `case Return(x) => …`. It is correct where the
    // optimized one-shot loop above can't be: `resume` is a DIRECT eager function
    // `x => handleWithReturn(continuation(x))`, so (a) the return clause maps each
    // continuation's completion (not op-case-body results — no double application), and
    // (b) `resume(v)` in a non-tail position like `msg :: resume(())` yields a concrete
    // value (no lazy placeholder to mis-thread). Cost: O(depth) JVM stack for chained
    // tail resumes — acceptable, and isolated to return-clause handlers (the common
    // no-return-clause path keeps the O(1) loop unchanged).
    def makeReturnResume(eff: String, op: String, k: Value => Computation): Value =
      val isOneShot = !multiShotEffects.contains(eff)
      var resumed = false
      Value.NativeFnV("resume", rargs => {
        if isOneShot && resumed then
          throw InterpretError(s"One-shot violation: $eff.$op resumed more than once")
        resumed = true
        val v = rargs match { case List(x) => x; case Nil => Value.UnitV; case xs => Value.TupleV(xs) }
        handleWithReturn(k(v))
      })

    def handleWithReturn(comp: Computation): Computation =
      var current: Computation = comp
      while true do
        current match
          case Pure(v) => return applyReturn(v)
          case Perform(eff, op, args) =>
            if !handledOps.contains((eff, op)) then return current
            else return dispatchCase(eff, op, args, makeReturnResume(eff, op, x => Pure(x)))
          case FlatMap(sub, f) => sub match
            case Pure(v)            => current = f(v)
            case FlatMap(sub2, g)   => current = FlatMap(sub2, x => FlatMap(g(x), f))
            case Perform(eff, op, args) =>
              if !handledOps.contains((eff, op)) then
                return FlatMap(Perform(eff, op, args), v => handleWithReturn(f(v)))
              else
                return dispatchCase(eff, op, args, makeReturnResume(eff, op, f))
      throw InterpretError("unreachable")

    if returnCases.isEmpty then handleInterp(initial) else handleWithReturn(initial)

  /** Interpret `restartable { cases } { body }` — Common Lisp condition-system style.
   *
   *  Protocol:
   *   fromBody (cap=1 SynchronousQueue): RSYielded (threw), RSReturned (finished), RSErrored (uncaught)
   *   toBody   (SynchronousQueue):       Right(resumeVal) | Left(rethrowVal)
   */
  def evalRestartable(body: Term, cases: List[Case], env: Env, interp: Interpreter): Computation =
    val errChan  = new java.util.concurrent.SynchronousQueue[Value]()
    val resChan  = new java.util.concurrent.SynchronousQueue[Either[Value, Value]]()
    val doneChan = new java.util.concurrent.SynchronousQueue[Either[Value, Value]]()

    val handle = new RestartableHandle(errChan, resChan)

    Thread.ofVirtual().start { () =>
      interp.restartableStack().addFirst(handle)
      try
        val result = Computation.run(interp.eval(body, env))
        interp.restartableStack().pollFirst()
        doneChan.put(Right(result))
      catch
        case rr: RestartableRethrow =>
          interp.restartableStack().pollFirst()
          doneChan.put(Left(rr.value))
        case se: ScriptException =>
          interp.restartableStack().pollFirst()
          errChan.put(se.value)
          resChan.take() match
            case Right(resumeVal) =>
              doneChan.put(Right(resumeVal))
            case Left(rethrowVal) =>
              doneChan.put(Left(rethrowVal))
        case t: Throwable =>
          interp.restartableStack().pollFirst()
          val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          doneChan.put(Left(Value.InstanceV("RuntimeException",
            new IMap.Map1("message", Value.StringV(msg)))))
    }

    @annotation.tailrec
    def waitForSignal(): Either[Value, Either[Value, Value]] =
      val errNow  = errChan.poll(1, java.util.concurrent.TimeUnit.MILLISECONDS)
      if errNow != null then Left(errNow)
      else
        val doneNow = doneChan.poll(1, java.util.concurrent.TimeUnit.MILLISECONDS)
        if doneNow != null then Right(doneNow)
        else waitForSignal()

    def loop(): Computation =
      waitForSignal() match
        case Right(Right(v)) => Pure(v)
        case Right(Left(se)) =>
          throw ScriptException(se)
        case Left(errVal) =>
          handleErr(errVal)

    def handleErr(errVal: Value): Computation =
      val handlerResultOpt: Option[Value] =
        cases.iterator.flatMap { c =>
          val bound = PatternRuntime.matchPat(c.pat, errVal, env, interp)
          if bound == null then Iterator.empty else Iterator.single((c, bound))
        }.nextOption().map { case (matchedCase, bound) =>
          Computation.run(interp.eval(matchedCase.body, env ++ bound))
        }
      handlerResultOpt match
        case None =>
          resChan.put(Left(errVal))
          loop()
        case Some(restartDecision) =>
          decodeRestart(restartDecision, errVal)

    def decodeRestart(decision: Value, errVal: Value): Computation =
      decision match
        case Value.InstanceV("Restart$resume", fields) =>
          val v = fields.getOrElse("value", Value.UnitV)
          resChan.put(Right(v))
          loop()
        case Value.InstanceV("Restart$useDefault", _) =>
          resChan.put(Right(Value.UnitV))
          loop()
        case Value.InstanceV("Restart$rethrow", fields) =>
          val rethrowVal = fields.getOrElse("value", errVal)
          resChan.put(Left(rethrowVal))
          loop()
        case _ =>
          resChan.put(Left(errVal))
          loop()

    loop()
