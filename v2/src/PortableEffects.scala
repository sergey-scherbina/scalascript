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

  /** Runtime-only hand-off from a handler-facing resume call to the iterative
   * driver. The label merely lets the ordinary Op-threading machinery carry
   * the handler suffix; authority comes from both this private class and the
   * process-local capability identity, neither of which portable code can
   * construct. */
  private val resumeCapability: AnyRef = new Object()
  private val resumeLabel = "ssc.control.__resume__"
  private final class ResumeRequest(
      val capability: AnyRef,
      val nextComputation: Value,
      val handler: Value,
  )
  private sealed trait DriverFrame
  private final case class ApplySuffix(afterResume: ClosV) extends DriverFrame
  private final case class Rehandle(handler: Value) extends DriverFrame

  private val resumeIdentity = ClosV(Runtime.emptyEnv, 1, env => Done(env.last))

  private def deferredResume(nextComputation: Value, handler: Value): Value =
    DataV("Op", Vector(
      StrV(resumeLabel),
      ForeignV(new ResumeRequest(resumeCapability, nextComputation, handler)),
      resumeIdentity,
    ))

  private def privateResume(value: Value): Option[(ResumeRequest, ClosV)] = value match
    case DataV("Op", IndexedSeq(
          StrV(label),
          ForeignV(request: ResumeRequest),
          afterResume: ClosV,
        ))
        if label == resumeLabel && (request.capability eq resumeCapability) =>
      Some(request -> afterResume)
    case _ => None

  /** Fold an explicit computation through a user handler. The handler-facing
   * `resume` eagerly invokes the operation's original continuation, so the
   * one-shot claim still happens at the resume call. Only recursive handling
   * of the obtained computation is deferred onto a heap-frame driver. */
  def handle(computation: Value, handler: Value): Value =
    runDriver(computation, handler, handlingComputationInitially = true)

  /** Complete a value crossing a managed program/call boundary. Ordinary
   * values — including public Op/3 computations — are returned by identity;
   * only an exact capability-backed resume request enters the private driver. */
  def completeManaged(value: Value): Value =
    privateResume(value) match
      case Some(_) => runDriver(value, UnitV, handlingComputationInitially = false)
      case None    => value

  private def runDriver(
      computation: Value,
      handler: Value,
      handlingComputationInitially: Boolean,
  ): Value =
    var current = computation
    var currentHandler = handler
    var frames: List[DriverFrame] = Nil
    var handlingComputation = handlingComputationInitially

    while true do
      privateResume(current) match
        case Some((request, afterResume)) =>
          if handlingComputation then
            frames = ApplySuffix(afterResume) :: Rehandle(currentHandler) :: frames
          else
            frames = ApplySuffix(afterResume) :: frames
          current = request.nextComputation
          currentHandler = request.handler
          handlingComputation = true
        case None =>
          if handlingComputation then
            current match
              case DataV("Pure", IndexedSeq(value)) =>
                current = value
              case DataV("Op", IndexedSeq(StrV(label), argument, continuation: ClosV)) =>
                val resume = ClosV(Array[Value](continuation, currentHandler), 1, env => {
                  val next = call1(env(0), env.last, "effect continuation")
                  Done(deferredResume(next, env(1)))
                })
                val eventArgs = argument match
                  case UnitV                         => List(resume)
                  case DataV("__EffArgs__", fields) => fields.toList :+ resume
                  case one                           => List(one, resume)
                current = foldDispatch(
                  dispatch1(
                    currentHandler,
                    DataV(operationName(label), eventArgs.toVector),
                  ),
                  () =>
                    // Preserve the exact argument and base continuation/gate.
                    // The deep resume wrapper reaches that continuation first,
                    // then reinstalls this handler through the private driver.
                    DataV("Op", Vector(StrV(label), argument, resume)),
                )
                handlingComputation = false
              case DataV("Op", fields) =>
                fail(s"malformed Op with ${fields.length} field(s)")
              case value =>
                current = foldDispatch(
                  dispatch1(currentHandler, DataV("Return", Vector(value))),
                  () => value,
                )
                handlingComputation = false
          else
            frames match
              case ApplySuffix(afterResume) :: remaining =>
                frames = remaining
                current = call1(afterResume, current, "effect resume suffix")
              case Rehandle(outerHandler) :: remaining =>
                frames = remaining
                currentHandler = outerHandler
                handlingComputation = true
              case Nil => return current

    fail("unreachable handler driver")

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
