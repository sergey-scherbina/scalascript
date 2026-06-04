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
    // Fast path: evaluate body first; if Pure (no perform calls), skip handler setup.
    val initial = interp.eval(body, env)
    initial match
      case p: Pure => return p
      case _ =>

    val handledOps: Set[(String, String)] = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some((eff, op))
        case _ => None
    }.toSet

    def dispatchCase(eff: String, op: String, args: List[Value], resume: Value): Computation =
      cases.iterator.flatMap { c =>
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
      throw InterpretError("unreachable")

    handleInterp(initial)

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
