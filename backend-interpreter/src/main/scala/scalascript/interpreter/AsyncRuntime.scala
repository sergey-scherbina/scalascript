package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}

/** Async effect handlers — Free-Monad walkers for Async.delay/async/await/parallel/recvFrom.
 *
 *  asyncInterp    — single-threaded sequential driver (deterministic)
 *  asyncParInterp — virtual-thread parallel driver (Project Loom)
 */
private[interpreter] object AsyncRuntime:

  // ── Single-threaded sequential driver ──────────────────────────────

  def asyncInterp(initial: Computation, interp: Interpreter): Computation =
    var current: Computation = initial
    while true do
      current match
        case Pure(_) => return current
        case Perform("Async", op, args) =>
          current = asyncDispatch(op, args, v => Pure(v), interp)
        case Perform(_, _, _) => return current
        case FlatMap(sub, f) => sub match
          case Pure(v) =>
            current = f(v)
          case FlatMap(sub2, g) =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
          case Perform("Async", op, args) =>
            current = asyncDispatch(op, args, v => asyncInterp(f(v), interp), interp)
          case Perform(_, _, _) =>
            return FlatMap(sub, v => asyncInterp(f(v), interp))
    throw InterpretError("unreachable")

  private def asyncDispatch(
    op:     String,
    args:   List[Value],
    resume: Value => Computation,
    interp: Interpreter
  ): Computation = op match
    case "delay" => args match
      case List(Value.IntV(ms)) =>
        if ms > 0 then Thread.sleep(ms)
        resume(Value.UnitV)
      case _ => throw InterpretError("Async.delay(ms: Int)")
    case "async" => args match
      case List(thunk) =>
        asyncInterp(interp.callValue(thunk, Nil, Map.empty), interp) match
          case Pure(v) =>
            resume(Value.InstanceV("Future", Map("value" -> v)))
          case _ => throw InterpretError(
            "Async.async thunk leaked an unhandled non-Async effect")
      case _ => throw InterpretError("Async.async(thunk)")
    case "await" => args match
      case List(Value.InstanceV("Future", fields)) =>
        resume(fields.getOrElse("value", Value.UnitV))
      case _ => throw InterpretError("Async.await(future)")
    case "parallel" => args match
      case List(Value.ListV(thunks)) =>
        val results = thunks.map { t =>
          asyncInterp(interp.callValue(t, Nil, Map.empty), interp) match
            case Pure(v) => v
            case _ => throw InterpretError(
              "Async.parallel thunk leaked an unhandled non-Async effect")
        }
        resume(Value.ListV(results))
      case _ => throw InterpretError("Async.parallel(thunks: List[() => A])")
    case "recvFrom" => args match
      case List(ws) =>
        val recvFn = ws match
          case Value.InstanceV(_, fields) =>
            fields.getOrElse("recv", throw InterpretError("Async.recvFrom: ws has no recv"))
          case other => throw InterpretError(s"Async.recvFrom: expected ws, got $other")
        interp.callValue(recvFn, Nil, Map.empty) match
          case Pure(v) => resume(v)
          case comp    => FlatMap(comp, resume)
      case _ => throw InterpretError("Async.recvFrom(ws)")
    case _ => throw InterpretError(s"Unknown Async operation: $op")

  // ── Virtual-thread parallel driver (Project Loom) ───────────────────

  def asyncParInterp(initial: Computation, interp: Interpreter): Computation =
    val ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    try
      def driver(c: Computation): Computation =
        var current: Computation = c
        while true do
          current match
            case Pure(_) => return current
            case Perform("Async", op, args) =>
              current = asyncParDispatch(op, args, v => Pure(v), ex, driver, interp)
            case Perform(_, _, _) => return current
            case FlatMap(sub, f) => sub match
              case Pure(v)          => current = f(v)
              case FlatMap(s2, g)   => current = FlatMap(s2, x => FlatMap(g(x), f))
              case Perform("Async", op, args) =>
                current = asyncParDispatch(op, args, v => driver(f(v)), ex, driver, interp)
              case Perform(_, _, _) =>
                return FlatMap(sub, v => driver(f(v)))
        throw InterpretError("unreachable")
      driver(initial)
    finally ex.shutdown()

  private def asyncParDispatch(
    op:     String,
    args:   List[Value],
    resume: Value => Computation,
    ex:     java.util.concurrent.ExecutorService,
    driver: Computation => Computation,
    interp: Interpreter
  ): Computation = op match
    case "delay" => args match
      case List(Value.IntV(ms)) =>
        if ms > 0 then Thread.sleep(ms)
        resume(Value.UnitV)
      case _ => throw InterpretError("Async.delay(ms: Int)")
    case "async" => args match
      case List(thunk) =>
        val fut: java.util.concurrent.Future[Value] = ex.submit(
          new java.util.concurrent.Callable[Value]:
            def call(): Value = driver(interp.callValue(thunk, Nil, Map.empty)) match
              case Pure(v) => v
              case _ => throw InterpretError(
                "Async.async thunk leaked an unhandled non-Async effect")
        )
        val carrier = Value.NativeFnV("_futureRef", _ => {
          throw InterpretError("Future ref is opaque")
        })
        val fid = interp.freshFutureId()
        interp.parallelFutures.put(fid, fut)
        resume(Value.InstanceV("Future", Map(
          "_parId" -> Value.IntV(fid),
          "value"  -> carrier
        )))
      case _ => throw InterpretError("Async.async(thunk)")
    case "await" => args match
      case List(Value.InstanceV("Future", fields)) =>
        fields.get("_parId") match
          case Some(Value.IntV(fid)) =>
            val fut = interp.parallelFutures.remove(fid)
            if fut == null then throw InterpretError("Async.await: stale Future")
            resume(fut.get())
          case _ =>
            resume(fields.getOrElse("value", Value.UnitV))
      case _ => throw InterpretError("Async.await(future)")
    case "parallel" => args match
      case List(Value.ListV(thunks)) =>
        val futs = thunks.map { t =>
          ex.submit(new java.util.concurrent.Callable[Value]:
            def call(): Value = driver(interp.callValue(t, Nil, Map.empty)) match
              case Pure(v) => v
              case _ => throw InterpretError(
                "Async.parallel thunk leaked an unhandled non-Async effect")
          )
        }
        val results = futs.map(_.get())
        resume(Value.ListV(results))
      case _ => throw InterpretError("Async.parallel(thunks: List[() => A])")
    case "recvFrom" => args match
      case List(ws) =>
        val recvFn = ws match
          case Value.InstanceV(_, fields) =>
            fields.getOrElse("recv", throw InterpretError("Async.recvFrom: ws has no recv"))
          case other => throw InterpretError(s"Async.recvFrom: expected ws, got $other")
        interp.callValue(recvFn, Nil, Map.empty) match
          case Pure(v) => resume(v)
          case comp    => FlatMap(comp, resume)
      case _ => throw InterpretError("Async.recvFrom(ws)")
    case _ => throw InterpretError(s"Unknown Async operation: $op")
