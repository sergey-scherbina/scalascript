package ssc

/** Portable explicit algebraic-effect runtime.
 *
 * Effects are ordinary closure values: Pure(v) or
 * Op(label, argument, continuation). Raw `effect.perform` continuations are
 * reusable; typed plain `.ssc effect` declarations use the explicitly gated
 * `effect.perform.oneshot` primitive. This object owns the target contract used
 * by the VM and generated backends. V2EffectContext is deliberately separate:
 * it adapts legacy JVM BlockForm plugins and is not compiled-target semantics.
 */
object PortableEffects:
  import Value.*

  val primitiveNames: Set[String] = Set(
    "effect.pure", "effect.perform", "effect.perform.oneshot", "effect.handle",
  )

  private def fail(message: String): Nothing =
    throw new RuntimeException(s"effect: $message")

  def pure(value: Value): Value = DataV("Pure", Vector(value))

  def packArgs(args: List[Value]): Value = args match
    case Nil       => UnitV
    case List(one) => one
    case many      => DataV("__EffArgs__", many.toVector)

  def perform(label: String, args: List[Value]): Value =
    val identity = ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    DataV("Op", Vector(StrV(label), packArgs(args), identity))

  private def operationLabel(effectId: String, operationName: String): String =
    s"$effectId.$operationName"

  private def rejected(operation: OperationId): Nothing =
    throw new ControlRunFailure(ResumeRejected.AlreadyResumed(operation))

  /** Add one linearizable claim to the continuation of the exact operation.
   * The identity is supplied separately and never reconstructed by splitting
   * the legacy display/dispatch label. Non-matching dispatch results (for
   * example an active plugin handler returning a value) pass through unchanged. */
  def guardOperation(effectId: String, operationName: String, value: Value): Value =
    val expectedLabel = operationLabel(effectId, operationName)
    val operation = OperationId(EffectId(effectId), operationName)
    value match
      case DataV("Op", IndexedSeq(StrV(label), argument, continuation: ClosV))
          if label == expectedLabel =>
        val claimed = new java.util.concurrent.atomic.AtomicBoolean(false)
        val guarded = ClosV(Array[Value](continuation), 1, env =>
          if claimed.compareAndSet(false, true) then
            Done(Prims.runClos1(env(0).asInstanceOf[ClosV], env.last))
          else rejected(operation)
        )
        DataV("Op", Vector(StrV(label), argument, guarded))
      case other => other

  def performOneShot(effectId: String, operationName: String, args: List[Value]): Value =
    guardOperation(effectId, operationName,
      perform(operationLabel(effectId, operationName), args))

  private def call1(value: Value, argument: Value, label: String): Value = value match
    case closure: ClosV => Prims.runClos1(closure, argument)
    case other => fail(s"$label must be a one-argument closure, got ${Show.show(other)}")

  private def dispatch1(value: Value, event: Value): HandlerDispatch = value match
    case closure: ClosV => Runtime.dispatchHandler1(closure, event)
    case other => fail(s"effect handler must be a one-argument closure, got ${Show.show(other)}")

  /** Resolve a possibly suspended handler-pattern decision. Guard-side effects
   *  remain ordinary public Ops; only their continuation is wrapped so the
   *  exact decision resumes and terminal Unhandled can rebuild the original
   *  residual computation. */
  private def foldDispatch(
      step: HandlerDispatch,
      onUnhandled: () => Value
  ): Value = step match
    case HandlerDispatch.Matched(value) => value
    case HandlerDispatch.Unhandled      => onUnhandled()
    case HandlerDispatch.Suspended(label, argument, continue) =>
      val resume = ClosV(Runtime.emptyEnv, 1, env =>
        Done(foldDispatch(continue(env.last), onUnhandled)))
      DataV("Op", Vector(label, argument, resume))

  private def operationName(label: String): String =
    val dot = label.lastIndexOf('.')
    if dot < 0 then label else label.substring(dot + 1)

  /** Fold an explicit computation through a user handler. The handler-facing
   * `resume` delegates to the operation's original continuation: raw/multi
   * continuations remain reusable while typed one-shot continuations retain
   * their single base claim through every deep wrapper. */
  def handle(computation: Value, handler: Value): Value = computation match
    case DataV("Pure", IndexedSeq(value)) => handle(value, handler)
    case DataV("Op", IndexedSeq(StrV(label), argument, continuation: ClosV)) =>
      val resume = ClosV(Array[Value](continuation, handler), 1, env => {
        val k = env(0)
        val h = env(1)
        val next = call1(k, env.last, "effect continuation")
        Done(handle(next, h))
      })
      val eventArgs = argument match
        case UnitV                         => List(resume)
        case DataV("__EffArgs__", fields) => fields.toList :+ resume
        case one                           => List(one, resume)
      foldDispatch(
        dispatch1(handler, DataV(operationName(label), eventArgs.toVector)),
        () =>
          // Forward the exact operation and the same deep resume wrapper. The
          // wrapper reaches the original continuation first, preserving its
          // base one-shot gate (or raw/multi reuse), then reinstalls this handler.
          DataV("Op", Vector(StrV(label), argument, resume)))
    case DataV("Op", fields) => fail(s"malformed Op with ${fields.length} field(s)")
    case value =>
      foldDispatch(
        dispatch1(handler, DataV("Return", Vector(value))),
        () => value)

  def eval(op: String, args: List[Value]): Value = op match
    case "effect.pure" => args match
      case List(value) => pure(value)
      case _ => fail(s"effect.pure expects 1 argument, got ${args.length}")
    case "effect.perform" => args match
      case StrV(label) :: operationArgs => perform(label, operationArgs)
      case _ => fail("effect.perform expects a String label followed by operation arguments")
    case "effect.perform.oneshot" => args match
      case StrV(effectId) :: StrV(operationName) :: operationArgs =>
        performOneShot(effectId, operationName, operationArgs)
      case _ => fail(
        "effect.perform.oneshot expects String effect and operation names followed by operation arguments")
    case "effect.handle" => args match
      case List(computation, handler) => handle(computation, handler)
      case _ => fail(s"effect.handle expects 2 arguments, got ${args.length}")
    case other => fail(s"unknown primitive '$other'")
