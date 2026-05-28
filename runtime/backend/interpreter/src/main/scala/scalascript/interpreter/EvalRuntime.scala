package scalascript.interpreter

import scalascript.transform.DirectTypeUtils
import scala.collection.mutable
import scala.meta.*
import Computation.{Pure, FlatMap, Perform}

/** Expression evaluator: the core `eval(term, env, interp)` dispatch,
 *  plus the `evalArgs`, `collectApplyArgs`, and `threadValues` helpers.
 */
private[interpreter] object EvalRuntime:

  def eval(term: Term, env: Env, interp: Interpreter): Computation =
    interp.trackPos(term)
    // DAP step/breakpoint hook: called for every term so DebugHooks can decide
    // whether to suspend (breakpoint, stepIn, stepOver, stepOut).
    // currentSpan: block-relative 0-based line; docLine = debugBlockDocLine + blockLine + 1.
    // callStack.length is the current call depth (0 at top level).
    interp.debugHooks.foreach { hooks =>
      if interp.currentSpanLine >= 0 then
        val blockLine  = interp.currentSpanLine
        val docLine    = interp.debugBlockDocLine + blockLine + 1
        val callFrames = interp.callStack.toIndexedSeq.map { case (n, sf, l) =>
          scalascript.interpreter.debug.CallFrameEntry(n, sf, l)
        }
        val frame = scalascript.interpreter.debug.DebugFrame(0, "frame", interp.debugSourceFile, docLine, interp.callStack.length, env, callFrames)
        hooks.onStep(frame)
    }
    term match
    // Literals — interned by Lit identity so a hot loop reuses the same
    // `Pure(Value)` instance instead of reallocating on every eval. The
    // parser produces a stable AST; each `Lit.Int(1)` etc. is the same
    // Scala object across all visits.
    case lit: Lit =>
      val cached = interp.litCache.get(lit)
      if cached != null then cached
      else
        val c = lit match
          case Lit.Int(v)     => Computation.pureIntV(v.toLong)
          case Lit.Long(v)    => Computation.pureIntV(v)
          case Lit.Double(v)  => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.Float(v)   => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.String(v)  => Pure(Value.StringV(v))
          case Lit.Boolean(v) => Computation.pureBool(v)
          case Lit.Char(v)    => Pure(Value.CharV(v))
          case Lit.Unit()     => Computation.PureUnit
          case Lit.Null()     => Computation.PureNull
          case _              => Computation.PureNull
        interp.litCache.put(lit, c)
        c

    // Name lookup: local env first, then interp.globals.
    // Phase 2 lazy loading: on first miss we trigger the plugin ServiceLoader
    // scan (ensurePluginsLoaded) so scripts like `hello.ssc` that never touch
    // a plugin never pay the cost.  After plugins load we re-check globals; if
    // still missing, the name is genuinely undefined.
    case Term.Name(name) =>
      val v = env.getOrElse(name, interp.globals.getOrElse(name, null))
      if v != null then Pure(v)
      else if interp._pluginsLoaded then interp.located(s"Undefined: $name")
      else
        interp.ensurePluginsLoaded()
        Pure(interp.globals.getOrElse(name, interp.located(s"Undefined: $name")))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          EffectsRuntime.evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp,
            interp.multiShotEffects)
        case _ => interp.located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Special form: runAsync(body) — default Async handler.  The body is
    // a by-name expression; we evaluate it lazily so its interp.effects compose
    // into the Computation tree before the driver walks them.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special form: runAsyncParallel(body) — alternate Async handler
    // that executes thunks passed to `async` / `parallel` on real
    // JVM threads (ExecutorService + CompletableFuture).  `await`
    // blocks the calling thread on the future; `parallel` returns
    // results in declared order regardless of completion order so
    // value-deterministic code still produces byte-identical output.
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncParInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special forms: runStorage / runEphemeralStorage — Storage-effect
    // handlers.  The former hydrates from / persists to a JSON file
    // (path from the optional second arg or `SSC_STORAGE_PATH` env,
    // defaulting to `./ssc-storage.json`); the latter keeps the map
    // in-memory and discards it at scope exit.
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val pathOpt = bodyArgClause.values.lift(1).map { p =>
        Computation.run(eval(p, env, interp)) match
          case Value.StringV(s) => s
          case _                => StorageRuntime.defaultPath
      }
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), Some(pathOpt.getOrElse(StorageRuntime.defaultPath)))
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), None)

    // ── v1.4 Logger effect handlers ───────────────────────────────────────
    // runLogger { body }        — writes "[LEVEL] msg\n" to `interp.out`
    // runLoggerJson { body }    — writes {"level":"…","msg":"…"} newline JSON
    // runLoggerToList { body }  — collects log lines; returns (result, list)
    case Term.Apply.After_4_6_0(Term.Name("runLogger"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env, interp), "text", interp.out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerJson"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env, interp), "json", interp.out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerToList"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerToListRun(eval(bodyArgClause.values.head, env, interp))

    // ── v1.4 Random effect handlers ───────────────────────────────────────
    // runRandom { body }            — ThreadLocalRandom
    // runRandomSeeded(seed) { body } — deterministic LCG, seed is Long
    case Term.Apply.After_4_6_0(Term.Name("runRandom"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.randomRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runRandomSeeded"), seedClause),
        bodyClause)
        if seedClause.values.size == 1 && bodyClause.values.size == 1 =>
      val seed = Computation.run(eval(seedClause.values.head, env, interp)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runRandomSeeded(seed: Long) { body }")
      EffectHandlers.randomRun(eval(bodyClause.values.head, env, interp), Some(seed))

    // ── v1.4 Clock effect handlers ────────────────────────────────────────
    // runClock { body }        — real wall clock; Clock.sleep → Thread.sleep
    // runClockAt(t0) { body }  — frozen at t0 ms epoch; sleep is a no-op
    case Term.Apply.After_4_6_0(Term.Name("runClock"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.clockRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runClockAt"), t0Clause),
        bodyClause)
        if t0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val t0 = Computation.run(eval(t0Clause.values.head, env, interp)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runClockAt(t0: Long) { body }")
      EffectHandlers.clockRun(eval(bodyClause.values.head, env, interp), Some(t0))

    // ── v1.4 Env effect handlers ──────────────────────────────────────────
    // runEnv { body }               — reads real process env; Env.set is local
    // runEnvWith(Map(...)) { body }  — fixture map; Env.set mutates overlay
    case Term.Apply.After_4_6_0(Term.Name("runEnv"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.envRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runEnvWith"), mapClause),
        bodyClause)
        if mapClause.values.size == 1 && bodyClause.values.size == 1 =>
      val overlay = Computation.run(eval(mapClause.values.head, env, interp)) match
        case Value.MapV(m) =>
          m.map { (k, v) => Value.show(k) -> Value.show(v) }.toMap
        case _ => throw InterpretError("runEnvWith(map: Map[String, String]) { body }")
      EffectHandlers.envRun(eval(bodyClause.values.head, env, interp), Some(overlay))

    // ── v1.4 Http effect handlers ─────────────────────────────────────────
    // runHttp { body }                   — delegates to real httpGet/httpPost
    // runHttpStub(routes) { body }       — test stub: url→body map
    case Term.Apply.After_4_6_0(Term.Name("runHttp"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.httpRun(eval(bodyArgClause.values.head, env, interp), None, interp)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runHttpStub"), routesClause),
        bodyClause)
        if routesClause.values.size == 1 && bodyClause.values.size == 1 =>
      val routes = Computation.run(eval(routesClause.values.head, env, interp)) match
        case m @ Value.MapV(_) => m
        case _ => throw InterpretError("runHttpStub(routes: Map[String, String]) { body }")
      EffectHandlers.httpRun(eval(bodyClause.values.head, env, interp), Some(routes), interp)

    // ── v1.51.6 Stream effect handler
    // runStream { body }  — discharges Stream.emit(x) performs; returns Source[A]
    case Term.Apply.After_4_6_0(Term.Name("runStream"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.streamRun(eval(bodyArgClause.values.head, env, interp), interp)

    // ── v1.4 State effect handlers ────────────────────────────────────────
    // runState(s0) { body }  — runs body intercepting State performs;
    //                          returns (finalState, result) as a tuple
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runState"), s0Clause),
        bodyClause)
        if s0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val s0 = Computation.run(eval(s0Clause.values.head, env, interp))
      EffectHandlers.stateRun(eval(bodyClause.values.head, env, interp), s0, interp)

    // ── v1.4 Auth effect handlers ─────────────────────────────────────────
    // runAuthWith(user) { body }  — injects a fixed user via thread-local;
    // body is run synchronously so the thread-local is set during execution.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runAuthWith"), userClause),
        bodyClause)
        if userClause.values.size == 1 && bodyClause.values.size == 1 =>
      val user = Computation.run(eval(userClause.values.head, env, interp))
      val prior = interp._authUser.get()
      interp._authUser.set(Some(user))
      try Pure(Computation.run(eval(bodyClause.values.head, env, interp)))
      finally interp._authUser.set(prior)

    // ── v1.4 Retry effect handlers ────────────────────────────────────────
    // runRetry { body }        — real sleep between attempts
    // runRetryNoSleep { body } — test handler: retries without sleeping
    case Term.Apply.After_4_6_0(Term.Name("runRetry"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env, interp), sleep = true, interp)
    case Term.Apply.After_4_6_0(Term.Name("runRetryNoSleep"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env, interp), sleep = false, interp)

    // ── v1.4 Cache effect handlers ────────────────────────────────────────
    // runCache { body }        — explicit handler using process-local cache
    // runCacheBypass { body }  — caching disabled; always recomputes
    case Term.Apply.After_4_6_0(Term.Name("runCache"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env, interp), bypass = false, interp)
    case Term.Apply.After_4_6_0(Term.Name("runCacheBypass"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env, interp), bypass = true, interp)

    // ── v1.4 Tx effect handlers ───────────────────────────────────────────
    // runTx { body }  — default no-op handler (just runs body directly)
    case Term.Apply.After_4_6_0(Term.Name("runTx"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      eval(bodyArgClause.values.head, env, interp)

    // ── v1.5 httpClient(baseUrl) { block } ───────────────────────────────
    // Double-apply special form: evaluate body directly (not as a thunk) so
    // any statements inside the block run with feature-local HTTP state set,
    // then restore.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), baseClause),
        bodyClause)
        if baseClause.values.size == 1 && bodyClause.values.size == 1 =>
      val baseComp = eval(baseClause.values.head, env, interp)
      baseComp match
        case Pure(Value.StringV(base)) =>
          val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
          val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
          val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
          val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
          interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
          try eval(bodyClause.values.head, env, interp)
          finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
        case _ =>
          FlatMap(baseComp, {
            case Value.StringV(base) =>
              val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
              val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
              val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
              val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
              interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
              try eval(bodyClause.values.head, env, interp)
              finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
            case _ => throw InterpretError("httpClient(baseUrl: String) { body }")
          })

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    // `runActors { body }` installs an actor scheduler, spawns the body
    // as the root actor, and drives until quiescence.  The result is
    // whatever the root actor returned, or UnitV if it never did.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      interp.actorInterp(eval(bodyArgClause.values.head, env, interp))

    // v1.5 Tier 5 #20 — `validate { body }` runs `body` with an active
    // validation collector.  Returns `Right(bodyResult)` when the body
    // ran without any `require*` complaint, else `Left(Map[field,
    // reason])` capturing every problem in document order.  `require*`
    // inside the block returns a safe default on miss/invalid so the
    // body keeps running and accumulates errors in one pass.
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val buf = mutable.LinkedHashMap.empty[String, String]
      interp.validationStack.push(buf)
      val bodyComp = try eval(bodyArgClause.values.head, env, interp)
                     finally ()  // never pop in the try — see below
      bodyComp.flatMap { result =>
        interp.validationStack.pop()
        if buf.nonEmpty then
          val errMap = Value.MapV(
            scala.collection.immutable.ListMap.from(
              buf.map { (k, v) => Value.StringV(k) -> Value.StringV(v) }
            )
          )
          Pure(Value.InstanceV("Left", Map("value" -> errMap)))
        else
          Pure(Value.InstanceV("Right", Map("value" -> result)))
      }

    // `receive { case … }` — special form so we can pull interp.out the AST
    // cases at dispatch time.  Stashes (cases, env) and emits a Perform
    // whose payload is the integer token into that side table.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive", List(Value.intV(id)))
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // `receive(timeout = N) { case … }` — same dispatch, but the
    // driver also tracks a deadline.  On timeout the receive value
    // is None; on match it's Some(body-value).
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutClause.values.size == 1 =>
      val timeoutTerm = timeoutClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      val timeoutMs = Computation.run(eval(timeoutTerm, env, interp)) match
        case Value.IntV(n) => n
        case _ => throw InterpretError("receive timeout must be an Int (milliseconds)")
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive_t", List(Value.intV(id), Value.intV(timeoutMs)))
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // Special forms: `computed { ... }` / `effect { ... }` — by-name
    // bodies wrapped as zero-arg closures so the reactive machinery can
    // re-run them when dependencies change.  Without the special form
    // the block would be evaluated eagerly at call time and the runtime
    // would get a plain value instead of a thunk.
    case Term.Apply.After_4_6_0(Term.Name("computed"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      Pure(SignalRuntime.makeComputed(interp, thunk))
    case Term.Apply.After_4_6_0(Term.Name("effect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      SignalRuntime.makeEffect(interp, thunk)
      Computation.PureUnit

    // ── v1.16 restartable { handlers } { body } ───────────────────────────
    // Common Lisp condition-system style handler: the body runs in a virtual
    // thread; when `throw e` fires inside it, the error is handed to the
    // handler partial function which returns a `Restart` decision:
    //   Restart.resume(v)   — body continues with `v` as the throw-expression result
    //   Restart.useDefault  — body continues with Value.UnitV (null/unit)
    //   Restart.rethrow     — exception propagates normally from interp restartable frame
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("restartable"), pfArgClause),
        bodyArgClause)
        if pfArgClause.values.size == 1 && bodyArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          EffectsRuntime.evalRestartable(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp)
        case _ =>
          interp.located("restartable expects a partial function { case Error(…) => Restart.… }")

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      app.fun match
        // ── .copy(field = value, ...) on an InstanceV ────────────────
        // Named args arrive as Term.Assign(Term.Name(field), rhs); we have
        // to intercept BEFORE the generic eval path, otherwise Term.Assign
        // would fall into the var-assignment case and mutate interp.globals.
        case Term.Select(qual, Term.Name("copy")) =>
          OpticsRuntime.evalCopy(qual, app.argClause.values, env, interp)
        // ── Focus[T](_.a.b) / Focus(_.a.b) — Monocle-style lens ──────
        // Inspect the lambda body at AST level to extract a field-access
        // chain, then synthesise a Lens value with get / set / modify /
        // andThen. Done at AST level because the placeholder lambda is
        // otherwise erased to an opaque NativeFnV.
        case ta: Term.ApplyType if OpticsRuntime.isFocusName(ta.fun) =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        case Term.Name("Focus") =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        // ── direct[M] { stmts } — v1.8 do-notation sugar ─────────────
        case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) =>
          val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
          DirectTypeUtils.validateDirectTypeArg(typeArg)
          val tag = BlockRuntime.extractDirectMonadTag(typeArgClause.values)
          app.argClause.values match
            case List(block: Term.Block) => BlockRuntime.evalDirectBlock(block.stats, env, tag, interp)
            case List(single: Term)      => eval(single, env, interp)
            case _                       => interp.located("direct[M] expects a single block argument")
        // Db.query[T](...) — the interpreter intrinsic returns row maps;
        // preserve the erased type argument here and project each row map into
        // the registered case-class runtime shape so `ssc run` mirrors JvmGen.
        case Term.ApplyType.After_4_6_0(
              Term.Select(Term.Name("Db"), Term.Name("query")),
              typeArgClause
            ) if typeArgClause.values.nonEmpty =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val qualC = eval(Term.Name("Db"), env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsQ = extractPureValues(argComps)
          qualC match
            case Pure(qualV) if argVsQ != null =>
              DispatchRuntime.dispatch(qualV, "query", argVsQ, env, interp).map(projectTypedRows(typeName, _, interp))
            case _ =>
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals =>
                  DispatchRuntime.dispatch(qualV, "query", argVals, env, interp).map(projectTypedRows(typeName, _, interp))
                )
              )
        case Term.Select(qual, Term.Name(method)) =>
          val qualC    = eval(qual, env, interp)
          // Named args (Term.Assign) must evaluate only the RHS; the full
          // Term.Assign path at line 2338 treats them as var-assignments and
          // returns UnitV, destroying the actual value.
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsS = extractPureValues(argComps)
          qualC match
            case Pure(qualV) if argVsS != null =>
              DispatchRuntime.dispatch(qualV, method, argVsS, env, interp)
            case _ =>
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        // ── obj.method[T](args) — type args erased, dispatch with actual args
        // Mirrors the bare Term.Select(qual, method) path above so that
        // type-parameterised method calls like `Dataset.of[Int]()` reach
        // the dispatcher with the right argument list (otherwise the
        // standalone `Dataset.of` would auto-call as a no-arg NativeFnV
        // and then the outer `()` would fail on the resulting value).
        case Term.ApplyType.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
          val qualC    = eval(qual, env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsT = extractPureValues(argComps)
          qualC match
            case Pure(qualV) if argVsT != null =>
              DispatchRuntime.dispatch(qualV, method, argVsT, env, interp)
            case _ =>
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        case _ =>
          // Flatten nested Apply nodes so that curried calls like `f(a)(using b)`
          // are collected into a single `interp.callValue(f, [a, b])` invocation.
          // This is needed so that explicitly-supplied `using` arguments are
          // combined with regular arguments before `callFun` decides whether to
          // auto-resolve given instances.
          val (baseFun, allArgTerms) = collectApplyArgs(app)
          val hasNamedArgs = allArgTerms.exists(_.isInstanceOf[Term.Assign])
          val funC     = eval(baseFun, env, interp)
          // Collect (Option[argName], Computation) pairs to preserve name info
          // when any named arg is present.  Pure positional calls take the fast path.
          if hasNamedArgs then
            val namedComps = allArgTerms.map {
              case Term.Assign(Term.Name(n), rhs) => (Some(n), eval(rhs, env, interp))
              case other                          => (None,    eval(other, env, interp))
            }
            val comps = namedComps.map(_._2)
            funC match
              case Pure(fv) if comps.forall(_.isInstanceOf[Pure]) =>
                val namedVals = namedComps.map { case (k, Pure(v)) => (k, v); case (k, _) => (k, Value.UnitV) }
                CallRuntime.callValueNamed(fv, namedVals, env, interp)
              case _ =>
                FlatMap(funC, fv =>
                  interp.threadValues(comps)(argVals =>
                    CallRuntime.callValueNamed(fv, namedComps.map(_._1).zip(argVals), env, interp)))
          else if allArgTerms.lengthCompare(1) == 0 then
            // Single-arg fast path: avoid extractPureValues ArrayBuffer+toList for the
            // most common call shape: f(x) with one pure arg.
            val arg1C = eval(allArgTerms.head, env, interp)
            (funC, arg1C) match
              case (Pure(fv), Pure(av)) => interp.callValue1(fv, av, env)
              case (Pure(fv), _)        => FlatMap(arg1C, av => interp.callValue1(fv, av, env))
              case (_, Pure(av))        => FlatMap(funC, fv => interp.callValue1(fv, av, env))
              case _                    => FlatMap(funC, fv => FlatMap(arg1C, av => interp.callValue1(fv, av, env)))
          else
            val argComps = allArgTerms.map(eval(_, env, interp))
            val argVsPos = extractPureValues(argComps)
            funC match
              case Pure(fv) if argVsPos != null =>
                interp.callValue(fv, argVsPos, env)
              case _ =>
                FlatMap(funC, fv =>
                  interp.threadValues(argComps)(argVals => interp.callValue(fv, argVals, env)))

    // Compound assignment: x += e, x -= e, x *= e, x /= e, x %= e
    // Desugar: read current value, apply base-op, write back to interp.globals.
    case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
        if op.value.lengthIs > 1 && op.value.last == '=' &&
           !Set(">=", "<=", "!=", "==").contains(op.value) =>
      val baseOp = op.value.init
      if argClause.values.lengthCompare(1) == 0 then
        val rhsC = eval(argClause.values.head, env, interp)
        eval(lhs, env, interp).flatMap { lhsV =>
          rhsC.flatMap { rv =>
            interp.infix2(lhsV, baseOp, rv, env).flatMap { newV =>
              interp.globals(lhs.value) = newV; Computation.PureUnit } } }
      else
        eval(lhs, env, interp).flatMap { lhsV =>
          val argComps = argClause.values.map(eval(_, env, interp))
          interp.threadValues(argComps) { argVs =>
            interp.infix(lhsV, baseOp, argVs, env).flatMap { newV =>
              interp.globals(lhs.value) = newV
              Computation.PureUnit
            }
          }
        }

    // Infix operators: a op b
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      // Binary fast path: single rhs arg (covers all arithmetic/comparison).
      // Avoids argComps list, extractPureValues ArrayBuffer, and infix List wrap.
      if argClause.values.lengthCompare(1) == 0 then
        val lhsC = eval(lhs, env, interp)
        val rhsC = eval(argClause.values.head, env, interp)
        (lhsC, rhsC) match
          case (Pure(lv), Pure(rv)) => interp.infix2(lv, op.value, rv, env)
          case (Pure(lv), _)        => FlatMap(rhsC, rv => interp.infix2(lv, op.value, rv, env))
          case (_, Pure(rv))        => FlatMap(lhsC, lv => interp.infix2(lv, op.value, rv, env))
          case _                    => FlatMap(lhsC, lv => FlatMap(rhsC, rv => interp.infix2(lv, op.value, rv, env)))
      else
        val lhsC     = eval(lhs, env, interp)
        val argComps = argClause.values.map(eval(_, env, interp))
        val argVsInfix = extractPureValues(argComps)
        lhsC match
          case Pure(lhsV) if argVsInfix != null =>
            interp.infix(lhsV, op.value, argVsInfix, env)
          case _ =>
            FlatMap(lhsC, lhsV =>
              interp.threadValues(argComps)(argVs => interp.infix(lhsV, op.value, argVs, env)))

    // '.!' outside a direct block (or inside a lambda/block in a direct block) — error
    case Term.Select(_, Term.Name("!")) =>
      interp.located("'.!' can only appear in expression position directly inside a direct[M] block body; not inside lambdas or nested blocks")

    // Field / method selection: a.b  (no-arg call)
    case Term.Select(qual, name) =>
      eval(qual, env, interp) match
        case Pure(qualV) => DispatchRuntime.dispatch(qualV, name.value, Nil, env, interp)
        case qualC       => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, name.value, Nil, env, interp))

    // Block { stmts; expr }
    case Term.Block(stats) =>
      BlockRuntime.evalBlock(stats, env, interp)

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env, interp) match
        // Fast path: cond evaluated eagerly to a pure BoolV (the typical
        // case after pure-value shortcuts kick in). Skip the FlatMap.
        case Pure(Value.BoolV(true))  => eval(t.thenp, env, interp)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env, interp)
        case Pure(other)              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => condC.flatMap {
          case Value.BoolV(true)  => eval(t.thenp, env, interp)
          case Value.BoolV(false) => eval(t.elsep, env, interp)
          case other              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        }

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val sb = StringBuilder()
        // f"..." semantics: the literal part following each ${} can begin
        // with a Java/printf-style format spec applied to the preceding arg.
        //   `f"${pi}%.2f"` →  parts = ["", "%.2f"], spec consumed off parts(1)
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        for i <- parts.indices do
          val partStr = parts(i).asInstanceOf[Lit.String].value
          // The first part precedes any interpolation; never consumes a spec.
          val (consumedSpec, partRest) =
            if i > 0 && prefix == "f" then
              fmtRe.findFirstIn(partStr) match
                case Some(spec) => (Some(spec), partStr.substring(spec.length))
                case None       => (None, partStr)
            else (None, partStr)
          // Emit the interpolated value for index (i - 1) before interp part's text.
          if i > 0 then
            val v = argVs(i - 1)
            val rendered = consumedSpec match
              case Some(spec) =>
                val boxed: AnyRef = v match
                  case Value.IntV(n)    => java.lang.Long.valueOf(n)
                  case Value.DoubleV(d) => java.lang.Double.valueOf(d)
                  case Value.BoolV(b)   => java.lang.Boolean.valueOf(b)
                  case Value.CharV(c)   => java.lang.Character.valueOf(c)
                  case Value.StringV(s) => s
                  case other            => Value.show(other)
                try String.format(spec, boxed)
                catch case _: java.util.IllegalFormatException => Value.show(v)
              case None =>
                Value.show(v)
            sb ++= (if prefix == "html" then interp.htmlEscapeUnlessRaw(v, rendered) else rendered)
          sb ++= partRest
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then interp.stripIndent(raw) else raw))
      }

    // User-defined interpolator — build StringContext instance and call prefix fn.
    // Vararg `args: Any*` is packed into a single ListV so the body sees it as
    // an indexed list (args(0), args.length, etc.).
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val partVals = parts.map(p => Value.StringV(p.asInstanceOf[Lit.String].value))
        val sc = Value.InstanceV("StringContext", Map("parts" -> Value.ListV(partVals)))
        val fn: Value = interp.extensions.get(("StringContext", prefix))
          .orElse(env.get(prefix))
          .orElse(interp.globals.get(prefix))
          .getOrElse(interp.located(s"Unknown interpolator '$prefix': not in scope"))
        interp.callValue(fn, List(sc, Value.ListV(argVs)), env)
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = interp._phIdxTL.get()
        interp._phIdxTL.set(0)
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv, interp)
        finally interp._phIdxTL.set(saved)
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = interp._phIdxTL.get()
      interp._phIdxTL.set(interp._phIdxTL.get() + 1)
      Pure(env.getOrElse(s"_$$${i}", interp.located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    case Term.Function.After_4_6_0(paramClause, body) =>
      // Drop only the keys whose env value still matches the live interp.globals —
      // those are top-level bindings that the lambda should re-read from
      // `interp.globals` at call time (so a `var` reassigned later is visible).
      // Genuine closure captures (outer def params, block-local vals) have
      // a different value in env than in interp.globals and must be kept; otherwise
      // a param like `a` for `def adder(a)` is stripped because interp.globals also
      // hold the `<a>` HTML tag under that name, and the inner `b => a + b`
      // would resolve `a` to the tag instead of the captured Int.
      val closure    = env.filter { case (k, v) => !interp.globals.get(k).contains(v) }
      val paramNames = paramClause.values.map(_.name.value)
      // Extract declared type annotations so TypedHandlerWrapper can detect
      // typed route handlers at mount time.  Empty string for unannotated params.
      val paramTypes = paramClause.values.map(p => p.decltpe.fold("")(interp.typeToString))
      Pure(Value.FunV(paramNames, body, closure, paramTypes = paramTypes))

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    // Compiled and cached per AST node so matchPat/Option allocations happen once, not per call.
    case t: Term.PartialFunction =>
      var compiled = interp.pfCache.get(t)
      if compiled == null then
        compiled = PatternRuntime.compilePF(t, interp)
        interp.pfCache.put(t, compiled)
      val compiledPF = compiled
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        compiledPF.run(arg, env, interp)
      }))

    // Match / pattern match — compiled and cached per AST identity to avoid
    // per-call Option/List allocations in the matchPat hot path.
    case t: Term.Match =>
      eval(t.expr, env, interp).flatMap { scrutV =>
        var compiled = interp.matchCache.get(t)
        if compiled == null then
          compiled = PatternRuntime.compileMatch(t, interp)
          interp.matchCache.put(t, compiled)
        compiled.run(scrutV, env, interp)
      }

    // Tuple  (a, b, ...)
    case Term.Tuple(elems) =>
      evalArgs(elems, env, interp)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env, interp) { argVals =>
        env.getOrElse(typeName, interp.globals.getOrElse(typeName,
          interp.located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => interp.callFun(f, argVals)
            case _ => interp.located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      PatternRuntime.evalForYield(t.enumsBlock.enums, t.body, env, interp)

    // for x <- xs do f(x)
    case t: Term.For =>
      PatternRuntime.evalForDo(t.enumsBlock.enums, t.body, env, Map.empty, interp).flatMap(Computation.discardToUnit)

    // while cond do body  — refresh env from interp.globals each iteration so mutations are visible.
    // Snapshot interp.globals at loop entry: only update a key on subsequent iterations if its
    // interp.globals value CHANGED since entry (i.e., was written by Term.Assign).  This prevents
    // a pre-existing interp.globals entry (e.g. the HTML `<a>` tag) from clobbering a local
    // `var a = 0` that happens to shadow it.
    //
    // Hot path: instead of creating a new Map each iteration, maintain a mutable frame
    // that is updated in-place and exposed via MutableEnvView.  O(K) lookup per iteration
    // where K = keys that changed in globals (typically 0 or 1); no Map allocation.
    //
    // Frame size optimization: only copy env entries that are absent from (or differ from)
    // interp.globals.  These are the locally-declared vars that the loop can mutate.
    // All other entries (builtins, global defs) are already in interp.globals and
    // EvalRuntime.Term.Name's globals fallback makes them visible without copying.
    // This shrinks frame from O(N_globals) to O(N_local_vars) — typically 2-5 entries.
    case t: Term.While =>
      val frame = scala.collection.mutable.HashMap.from(env.iterator.filter { case (k, v) =>
        !interp.globals.get(k).contains(v)
      })
      val entrySnap: Map[String, Value] = frame.toMap
      val frameView = new MutableEnvView(frame)
      def loop: Computation =
        // Refresh mutable frame: only overwrite a key if globals changed since entry.
        frame.foreachEntry { (k, _) =>
          interp.globals.get(k) match
            case Some(gv) if entrySnap.get(k).forall(_ != gv) => frame(k) = gv
            case _                                             =>
        }
        eval(t.expr, frameView, interp).flatMap {
          case Value.BoolV(true) => eval(t.body, frameView, interp).flatMap(_ => loop)
          case _                 => Computation.PureUnit
        }
      loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env, interp).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env, interp) match
        case Pure(v) => interp.globals(name) = v; Computation.PureUnit
        case c       => c.flatMap { v => interp.globals(name) = v; Computation.PureUnit }

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          // 1. Direct lookup in env / interp.globals
          val direct = env.get(key).orElse(interp.globals.get(key))
          val found = direct.orElse {
            // 2. For generic keys like "Show[A]" try:
            //    a) resolveGiven (infers concrete type from regular args if any)
            //    b) scan env for a synthetic context-bound param "A$TC"
            GivenRuntime.resolveGiven(key, Nil, env, interp).orElse {
              // key shape: "TC[A]" — look for env entry "A$TC"
              val tcEnd = key.indexOf('[')
              if tcEnd > 0 then
                val tc     = key.substring(0, tcEnd)
                val typeArg = key.substring(tcEnd + 1, key.length - 1).trim
                val syntheticName = s"${typeArg}$$${tc}"
                env.get(syntheticName)
              else None
            }
          }
          Pure(found.getOrElse(interp.located(s"No given instance for '$key'")))

        // Prism[Outer, Variant] — focus on a single sum-type variant.
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => interp.located("Prism[Outer, Variant]: Variant must be a simple type name")
          Pure(OpticsRuntime.buildPrism(variantName, interp))

        // compiletime.constValue[T] — return the compile-time constant value of type T
        case (Term.Select(Term.Name("compiletime"), Term.Name("constValue")), List(typeArg)) =>
          Pure(CallRuntime.constValueOfType(typeArg.asInstanceOf[scala.meta.Type]))

        // compiletime.summonInline[TC[T]] — look up a given instance
        case (Term.Select(Term.Name("compiletime"), Term.Name("summonInline")), List(typeArg)) =>
          val key = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          val found = env.get(key).orElse(interp.globals.get(key)).orElse(GivenRuntime.resolveGiven(key, Nil, env, interp))
          Pure(found.getOrElse(interp.located(s"No given instance for '$key' (summonInline)")))

        case _ => eval(t.fun, env, interp)  // other type applications — erase type args

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      eval(t.arg, env, interp).flatMap { v =>
        (t.op.value, v) match
          case ("!", Value.BoolV(b))   => Computation.pureBool(!b)
          case ("-", Value.IntV(n))    => Computation.pureIntV(-n)
          case ("-", Value.DoubleV(d)) => Pure(Value.doubleV(-d))
          case ("+", n: Value.IntV)    => Pure(n)
          case ("+", d: Value.DoubleV) => Pure(d)
          case ("~", Value.IntV(n))    => Computation.pureIntV(~n)
          case (op, other)             => interp.located(s"Cannot apply unary $op to ${Value.show(other)}")
      }

    case t: Term.Throw =>
      eval(t.expr, env, interp).flatMap { v =>
        if interp._insideDirectBlock.get() then Pure(Value.InstanceV("Left", Map("value" -> v)))
        else
          // v1.16: check for an active restartable frame on interp thread.
          // If present, suspend the body and let the handler decide.
          val rsStack = interp.restartableStack()
          val rsHandle = rsStack.peekFirst()
          if rsHandle != null then
            rsHandle.errorQ.put(v)   // send error to handler thread
            rsHandle.resumeQ.take() match  // wait for handler's decision
              case Right(resumeVal) => Pure(resumeVal)   // resume with replacement
              case Left(rethrowVal) =>
                // Throw RestartableRethrow (not ScriptException) so the
                // body thread's catch wrapper knows interp is a terminal rethrow
                // and doesn't route it back through the handler again.
                throw RestartableRethrow(rethrowVal)
          else
            val isNoTrace = v match
              case Value.InstanceV(typeName, _) => interp.noTraceTypes.contains(typeName)
              case _ => false
            if isNoTrace then throw ScriptExceptionNoTrace(v)
            else throw ScriptException(v)
      }

    case t: Term.Try =>
      @annotation.nowarn("msg=deprecated")
      def tryCatch(thrownVal: Value, cause: Throwable): Value =
        t.catchp.iterator.flatMap { c =>
          PatternRuntime.matchPat(c.pat, thrownVal, Map.empty, interp).map(bound => (c, bound))
        }.nextOption() match
          case Some((matchedCase, bound)) => Computation.run(eval(matchedCase.body, env ++ bound, interp))
          case None                       => throw cause
      val tryResult: Value =
        try Computation.run(eval(t.expr, env, interp))
        catch
          case rr: RestartableRethrow => throw rr  // v1.16: let terminal rethrows pass through
          case se: ScriptException  => tryCatch(se.value, se)
          case th: Throwable =>
            // Convert any JVM exception (NumberFormatException, InterpretError, etc.)
            // into a ScalaScript InstanceV so catch patterns can match it.
            val exTypeName = th.getClass.getSimpleName
            val msg = Option(th.getMessage).getOrElse(exTypeName)
            tryCatch(Value.InstanceV(exTypeName, Map("message" -> Value.StringV(msg))), th)
      t.finallyp.foreach(f => Computation.run(eval(f, env, interp)))
      Pure(tryResult)

    case t: Term.Ascribe =>
      eval(t.expr, env, interp)

    case other => interp.located(s"Cannot eval: ${other.productPrefix}")

  // ─── Lenses / Optics — see OpticsRuntime.scala ─────────────────────

    /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After interp call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */
  private def evalArgs(args: List[Term], env: Env, interp: Interpreter)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env, interp))
    interp.threadValues(argComps)(k)

  /** Extract values from a list of Pure computations in one pass.
   *  Returns null if any computation is non-Pure (caller falls back to threadValues). */
  private[interpreter] def extractPureValues(comps: List[Computation]): List[Value] | Null =
    if comps.isEmpty then return Nil
    val buf  = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
    var rest = comps
    while rest.nonEmpty do
      rest.head match
        case Pure(v) => buf += v; rest = rest.tail
        case _       => return null
    buf.toList

  private def projectTypedRows(typeName: String, value: Value, interp: Interpreter): Value = value match
    case Value.ListV(rows) =>
      Value.ListV(rows.map(projectTypedRow(typeName, _, interp)))
    case other => other

  private def projectTypedRow(typeName: String, row: Value, interp: Interpreter): Value = row match
    case Value.MapV(entries) =>
      val fields = interp.typeFieldOrder.get(typeName) match
        case Some(order) =>
          val schemas = interp.typeFieldSchemas.getOrElse(typeName,
            order.map(name => TypeFieldSchema(name, name, Nil, None, key = false)))
          if interp.rejectUnknownTypes.contains(typeName) then
            val known = schemas.iterator.flatMap(_.storageNames).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
            entries.keys.collectFirst {
              case Value.StringV(k) if !known.contains(k.toLowerCase(java.util.Locale.ROOT)) => k
            }.foreach(name => throw InterpretError(s"$$.$name: unknown column '$name'"))
          schemas.map { schema =>
            schema.fieldName -> lookupRowField(entries, schema)
          }.toMap
        case None =>
          entries.collect { case (Value.StringV(k), v) => k -> v }
      Value.InstanceV(typeName, fields)
    case other => other

  private def lookupRowField(entries: Map[Value, Value], schema: TypeFieldSchema): Value =
    schema.storageNames.iterator
      .flatMap(name =>
        entries.get(Value.StringV(name))
          .orElse(entries.collectFirst { case (Value.StringV(k), v) if k.equalsIgnoreCase(name) => v })
      )
      .toSeq
      .headOption
      .orElse(schema.default)
      .getOrElse(Value.NullV)

  private def restoreHttpClientState(
      interp: Interpreter,
      priorBase: Option[Any],
      priorTimeout: Option[Any],
      priorRetries: Option[Any],
      priorRetryDelay: Option[Any]
  ): Unit =
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpBaseUrl, priorBase)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpTimeoutMs, priorTimeout)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpMaxRetries, priorRetries)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpRetryDelayMs, priorRetryDelay)

  private def restoreFeatureLocal(interp: Interpreter, key: String, value: Option[Any]): Unit =
    value match
      case Some(v) => interp.nativeFeatureLocalSet(key, v)
      case None    => interp.nativeFeatureLocalRemove(key)

  /** Peel nested `Apply` nodes to collect all argument lists for a curried call.
   *  Only activates when the **outermost** `Apply` has a `using` argument clause
   *  (i.e. `mod = Some(Mod.Using())`).  In that case we collect all arg lists
   *  (regular + using) into a single flat list and return the base callee.
   *
   *  This handles `f(regularArgs)(using usingArgs)` without affecting ordinary
   *  curried calls like `onWebSocket(path) { handler }` which have no `using` mod.
   */
  def collectApplyArgs(app: Term.Apply): (Term, List[Term]) =
    // Only flatten when interp outer Apply carries `using` args
    if app.argClause.mod.isEmpty then (app.fun, app.argClause.values)
    else
      def peel(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => inner.fun match
          // Stop at select / type-apply / other complex funs
          case _: Term.Select | _: Term.ApplyType | _: Term.ApplyInfix =>
            (t, acc)
          case _ =>
            peel(inner.fun, inner.argClause.values ++ acc)
        case other => (other, acc)
      peel(app.fun, app.argClause.values)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    // Specialised fast paths for the most common small-arity cases (0–3 args).
    // Each pattern match avoids ArrayBuffer + toList + FlatMap chain overhead
    // when all arg evaluations are Pure — the common case for literal arguments.
    comps match
      case Nil => k(Nil)
      case List(Pure(v1)) => k(List(v1))
      case List(Pure(v1), Pure(v2)) => k(List(v1, v2))
      case List(Pure(v1), Pure(v2), Pure(v3)) => k(List(v1, v2, v3))
      case _ =>
        // General case: all-Pure check then FlatMap chain.
        var allPure = true
        var rest    = comps
        val buf     = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
        while allPure && rest.nonEmpty do
          rest.head match
            case Pure(v) => buf += v; rest = rest.tail
            case _       => allPure = false
        if allPure then k(buf.toList)
        else
          def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
            case Nil       => k(acc.reverse)
            case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
          chain(comps, Nil)

  // ─── Block eval + direct[M] — see BlockRuntime.scala ─────────────────

  // ─── Call helpers ─────────────────────────────────────────────────
