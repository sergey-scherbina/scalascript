package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}

/** Free-Monad effect handlers: Logger / Random / Clock / Env / Http /
 *  Retry / Cache / State.  Each `*Run` walks the computation tree,
 *  intercepts Perform nodes for its effect tag, and leaves all other
 *  Perform nodes propagating outward.
 */
private[interpreter] object EffectHandlers:

  /** Generic deep one-effect handler trampoline — the shared shape behind every `*Run`
   *  handler below. Walks `initial`, intercepts each `Perform(tag, op, args)` via
   *  `dispatch(op, args, resume)`, and leaves Performs of other effects propagating outward.
   *  This is also the seam the block-form/effect-handler plugin SPI plugs into: a plugin
   *  supplies only the `dispatch` reply (it never sees `Computation`). (polyglot-libraries §2d) */
  def runWithHandler(
    initial:  Computation,
    tag:      String,
    dispatch: (String, List[Value], Value => Computation) => Computation
  ): Computation =
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_)                  => return current
          case Perform(`tag`, op, args) => current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _)         => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                  => current = f(v)
            case FlatMap(s2, g)           => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform(`tag`, op, args) => current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)         => return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Logger ──────────────────────────────────────────────────────────
  // EXTRACTED to `logger-effect-plugin` (core-minimization, polyglot-libraries §2d).
  // The runners now live in the plugin and dispatch through `Backend.blockForms` +
  // `runWithHandler` above; nothing Logger-specific remains in interpreter core.

  // ── Random ──────────────────────────────────────────────────────────
  // EXTRACTED to `random-effect-plugin` (core-minimization, polyglot-libraries §2d).
  // The runners (`runRandom` / `runRandomSeeded(seed)`) now live in the plugin and dispatch
  // through `Backend.blockForms` + `runWithHandler` above; nothing Random-specific in core.

  // ── Clock ───────────────────────────────────────────────────────────
  // EXTRACTED to `clock-effect-plugin` (core-minimization, polyglot-libraries §2d).
  // runClock / runClockAt(t0) dispatch through `Backend.blockForms` + `runWithHandler`.

  // ── Env ─────────────────────────────────────────────────────────────
  // EXTRACTED to `env-effect-plugin` (core-minimization, polyglot-libraries §2d).
  // runEnv / runEnvWith(map) dispatch through `Backend.blockForms` + `runWithHandler`.

  // ── Stream (v1.51.6) ─────────────────────────────────────────────────
  // runStream { body } — canonical algebraic-effects form.
  // Collects Stream.emit(x) calls; handles complete()/error(msg)/request(n).
  // Returns (Source[A], R): a tuple of the emitted source and the body's final value.
  def streamRun(initial: Computation, interp: Interpreter): Computation =
    interp.ensurePluginsLoaded()
    val buf        = scala.collection.mutable.ListBuffer.empty[Value]
    var terminated = false
    var errorMsg   = Option.empty[String]

    def makeSource(): Value =
      val emitted = Value.ListV(buf.toList)
      if errorMsg.isDefined then
        // Return a Source that fails on first pull by wrapping in a failed source if available.
        val failedFn = interp.globals.getOrElse("Source.failed", null)
        if failedFn != null then
          try interp.invoke(failedFn, Value.StringV(errorMsg.get) :: Nil)
          catch case _: Throwable => emitted
        else emitted
      else
        val fromFn = interp.globals.getOrElse("Source.from", null)
        if fromFn != null then
          try interp.invoke(fromFn, emitted :: Nil)
          catch case _: Throwable => emitted
        else
          val nElems = emitted.asInstanceOf[Value.ListV].items.length
          Value.InstanceV("Source", Map(
            "runToList" -> Value.NativeFnV("Source.runToList", Computation.pureFn { _ => emitted }),
            "length"    -> Value.IntV(nElems)
          ))

    def finish(bodyResult: Value): Computation =
      Pure(Value.TupleV(makeSource() :: bodyResult :: Nil))

    def go(c0: Computation): Computation =
      var cur  = c0
      var done = false
      var result: Computation = Computation.PureUnit
      while !done do
        if terminated || errorMsg.isDefined then
          result = finish(Value.UnitV); done = true
        else cur match
          case Pure(v) =>
            result = finish(v); done = true
          case Perform("Stream", "emit", args) =>
            buf += args.headOption.getOrElse(Value.UnitV)
            cur = Computation.PureUnit
          case Perform("Stream", "complete", _) =>
            terminated = true; result = finish(Value.UnitV); done = true
          case Perform("Stream", "error", args) =>
            errorMsg = Some(args.headOption match
              case Some(Value.StringV(m)) => m
              case Some(v)                => v.toString
              case None                   => "Stream error")
            result = finish(Value.UnitV); done = true
          case Perform("Stream", "request", _) =>
            cur = Computation.PureUnit  // advisory no-op in v1.51.6
          case Perform(_, _, _) =>
            result = cur; done = true
          case FlatMap(sub, f) => sub match
            case Pure(v)                          => cur = f(v)
            case FlatMap(s2, g)                   => cur = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Stream", "emit", args)  =>
              buf += args.headOption.getOrElse(Value.UnitV)
              cur = f(Value.UnitV)
            case Perform("Stream", "complete", _) =>
              terminated = true; result = finish(Value.UnitV); done = true
            case Perform("Stream", "error", args) =>
              errorMsg = Some(args.headOption match
                case Some(Value.StringV(m)) => m
                case Some(v)                => v.toString
                case None                   => "Stream error")
              result = finish(Value.UnitV); done = true
            case Perform("Stream", "request", _)  =>
              cur = f(Value.UnitV)
            case Perform(_, _, _)                 =>
              result = FlatMap(sub, v => go(f(v))); done = true
      result
    go(initial)
