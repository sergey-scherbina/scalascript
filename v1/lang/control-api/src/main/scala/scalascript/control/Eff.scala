package scalascript.control

/** A stackless, reusable description of a computation with effect row `Fx`. */
sealed trait Eff[+Fx <: Effect, +A]:
  final def flatMap[Fx2 >: Fx <: Effect, B](
      next: A => Eff[Fx2, B]
  ): Eff[Fx2, B] =
    Eff.bind(this, next)

  final def map[B](f: A => B): Eff[Fx, B] =
    flatMap[Fx, B](value => Eff.pure(f(value)))

  final def step: Eff.Step[Fx, A] = Eff.step(this)

object Eff:
  /** JVM-visible only as an unforgeable cross-file kernel parameter. */
  private[control] final class Authority private[Eff] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException("invalid Eff authority")

  sealed trait Step[+Fx <: Effect, +A]

  object Step:
    final case class Done[A](value: A) extends Step[Nothing, A]

    sealed trait Request[+Fx <: Effect, +A] extends Step[Fx, A]:
      type OpFx <: Effect
      type Result

      def operation: Operation[OpFx, Result]

      def resumption: Resumption[Result, Fx, A]

      private[control] def snapshotKey: EffectKey[OpFx]

  private type Frame = Any => Eff[Effect, Any]

  // This is the JVM reference model's private host representation. It is not a
  // CoreIR inventory, a durable frame, or a serialization format.
  private final class Pure[A](val value: A) extends Eff[Nothing, A]

  private final class Deferred[Fx <: Effect, A](
      thunk: () => Eff[Fx, A]
  ) extends Eff[Fx, A]:
    def forceErased(): Eff[Effect, Any] = thunk()

  private final class Bind[Fx <: Effect, A, B](
      source: Eff[Fx, A],
      next: A => Eff[Fx, B]
  ) extends Eff[Fx, B]:
    def sourceErased: Eff[Effect, Any] = source
    def frameErased: Frame = next.asInstanceOf[Frame]

  private final class Attached(
      source: Eff[Effect, Any],
      val frames: List[Frame]
  ) extends Eff[Effect, Any]:
    def sourceErased: Eff[Effect, Any] = source

  private sealed abstract class PendingNode[+Fx <: Effect, +A]
      extends Eff[Fx, A]:
    type OpFx <: Effect
    type Result

    def operation: Operation[OpFx, Result]
    def operationKey: EffectKey[OpFx]
    def resumption: Resumption[Result, Fx, A]

  private final class Pending[
      Fx <: Effect,
      A,
      OpFx0 <: Effect,
      Result0
  ](
      val operation: Operation[OpFx0, Result0],
      val operationKey: EffectKey[OpFx0],
      val resumption: Resumption[Result0, Fx, A],
      candidate: Authority
  ) extends PendingNode[Fx, A]:
    Eff.requireAuthority(candidate)

    type OpFx = OpFx0
    type Result = Result0

  private final class RequestImpl[
      Fx <: Effect,
      A,
      OpFx0 <: Effect,
      Result0
  ](
      val operation: Operation[OpFx0, Result0],
      val snapshotKey: EffectKey[OpFx0],
      val resumption: Resumption[Result0, Fx, A]
  ) extends Step.Request[Fx, A]:
    type OpFx = OpFx0
    type Result = Result0

  def pure[A](value: A): Eff[Nothing, A] = new Pure(value)

  def defer[Fx <: Effect, A](body: => Eff[Fx, A]): Eff[Fx, A] =
    new Deferred(() => body)

  def runPure[A](body: Eff[Nothing, A]): A =
    step(body) match
      case Step.Done(value) => value
      case request: Step.Request[?, ?] =>
        throw new IllegalStateException(
          s"empty effect row produced request ${request.operation.id}"
        )

  private def pending[
      Fx <: Effect,
      A,
      OpFx <: Effect,
      Result
  ](
      operation: Operation[OpFx, Result],
      operationKey: EffectKey[OpFx],
      resumption: Resumption[Result, Fx, A]
  ): Eff[Fx, A] =
    new Pending(operation, operationKey, resumption, authority)

  /** Safe kernel entry: an operation can only enter its own declared row. */
  private[control] def performOperation[Fx <: Effect, A](
      operation: Operation[Fx, A]
  ): Eff[Fx, A] =
    if operation == null then throw new NullPointerException("operation")
    val operationKey = operation.effect
    if operationKey == null then
      throw new NullPointerException("operation effect key")
    val operationId = operation.id
    if operationId == null then
      throw new NullPointerException("operation id")
    if operationId.name == null then
      throw new NullPointerException("operation name")
    if operationId.effect != operationKey.id then
      throw new IllegalArgumentException(
        s"operation $operationId does not belong to ${operationKey.id}"
      )
    val multiplicity = operation.multiplicity
    if multiplicity == null then
      throw new NullPointerException("operation multiplicity")

    multiplicity match
      case ResumeMultiplicity.Reusable =>
        val continuation: Continuation[A, Fx, A] =
          Continuation.runtime(authority, operationId.toString) { value =>
            Eff.pure(value)
          }
        pending(
          operation,
          operationKey,
          Resumption.Reusable(continuation)
        )
      case ResumeMultiplicity.OneShot =>
        val continuation: OneShotContinuation[A, Fx, A] =
          OneShotContinuation.runtime(authority, operationId) { value =>
            Eff.pure(value)
          }
        pending(
          operation,
          operationKey,
          Resumption.OneShot(continuation)
        )

  private def bind[Fx <: Effect, A, Fx2 >: Fx <: Effect, B](
      source: Eff[Fx, A],
      next: A => Eff[Fx2, B]
  ): Eff[Fx2, B] =
    new Bind[Fx2, A, B](source, next)

  private def attach(
      source: Eff[Effect, Any],
      frames: List[Frame]
  ): Eff[Effect, Any] =
    if frames.isEmpty then source else new Attached(source, frames)

  private def mapResumption[X](
      source: Resumption[X, Effect, Any],
      frames: List[Frame]
  ): Resumption[X, Effect, Any] =
    source match
      case Resumption.Reusable(continuation) =>
        Resumption.Reusable(
          Continuation.runtime(authority, "Eff.step") { value =>
            attach(continuation.resume(value), frames)
          }
        )
      case Resumption.OneShot(continuation) =>
        Resumption.OneShot(
          OneShotContinuation.delegate(authority, continuation) { next =>
            attach(next, frames)
          }
        )

  private def deepResumption[
      Handled <: Effect,
      Residual <: Effect,
      A,
      B,
      X
  ](
      source: Resumption[X, Handled | Residual, A],
      handler: Handler[Handled, Residual, A, B],
      handledKey: EffectKey[Handled]
  ): Resumption[X, Residual, B] =
    source match
      case Resumption.Reusable(continuation) =>
        Resumption.Reusable[X, Residual, B](
          Continuation.runtime[X, Residual, B](authority, "handle") {
            value =>
              val resumed: Eff[Handled | Residual, A] =
                continuation.resume(value)
              handleWithKey[Handled, Residual, A, B](
                resumed,
                handler,
                handledKey
              )
          }
        )
      case Resumption.OneShot(continuation) =>
        val widened: OneShotContinuation[
          X,
          Handled | Residual,
          A
        ] = continuation
        Resumption.OneShot[X, Residual, B](
          OneShotContinuation.delegate[
            X,
            Handled | Residual,
            A,
            Residual,
            B
          ](authority, widened) { next =>
            handleWithKey[Handled, Residual, A, B](
              next,
              handler,
              handledKey
            )
          }
        )

  private def handleRequest[
      Handled <: Effect,
      Residual <: Effect,
      A,
      B
  ](
      request: Step.Request[Handled | Residual, A],
      handler: Handler[Handled, Residual, A, B],
      handledKey: EffectKey[Handled]
  ): Eff[Residual, B] =
    val next: Resumption[request.Result, Residual, B] =
      deepResumption[Handled, Residual, A, B, request.Result](
        request.resumption,
        handler,
        handledKey
      )
    if handledKey.sameRuntime(request.snapshotKey) then
      val operation =
        request.operation
          .asInstanceOf[Operation[Handled, request.Result]]
      handler.onOperation(operation, next)
    else
      // Owner mismatch proves that this existential operation belongs to the
      // residual side of Handled | Residual. The narrowing stays private.
      pending[Residual, B, request.OpFx, request.Result](
        request.operation,
        request.snapshotKey,
        next
      )

  private def handleWithKey[
      Handled <: Effect,
      Residual <: Effect,
      A,
      B
  ](
      body: Eff[Handled | Residual, A],
      handler: Handler[Handled, Residual, A, B],
      handledKey: EffectKey[Handled]
  ): Eff[Residual, B] =
    Eff.defer {
      body.step match
        case Step.Done(value) => handler.onReturn(value)
        case request: Step.Request[?, ?] =>
          handleRequest[Handled, Residual, A, B](
            request.asInstanceOf[
              Step.Request[Handled | Residual, A]
            ],
            handler,
            handledKey
          )
    }

  /** Safe kernel entry for deep handling; raw forwarding remains private. */
  private[control] def handleKernel[
      Handled <: Effect,
      Residual <: Effect,
      A,
      B
  ](
      body: Eff[Handled | Residual, A],
      handler: Handler[Handled, Residual, A, B]
  ): Eff[Residual, B] =
    if handler == null then throw new NullPointerException("handler")
    val handledKey = handler.effect
    if handledKey == null then
      throw new NullPointerException("handler effect key")
    handleWithKey[Handled, Residual, A, B](body, handler, handledKey)

  private def requestStep(
      pending: PendingNode[Effect, Any],
      frames: List[Frame]
  ): Step[Effect, Any] =
    val next =
      if frames.isEmpty then pending.resumption
      else mapResumption(pending.resumption, frames)
    new RequestImpl[
      Effect,
      Any,
      pending.OpFx,
      pending.Result
    ](pending.operation, pending.operationKey, next)

  private def step[Fx <: Effect, A](body: Eff[Fx, A]): Step[Fx, A] =
    var current: Eff[Effect, Any] = body
    var frames: List[Frame] = Nil

    while true do
      current match
        case pure: Pure[?] =>
          frames match
            case frame :: rest =>
              frames = rest
              current = frame(pure.value)
            case Nil =>
              return Step.Done(pure.value).asInstanceOf[Step[Fx, A]]
        case deferred: Deferred[?, ?] =>
          current = deferred.forceErased()
        case bind: Bind[?, ?, ?] =>
          frames = bind.frameErased :: frames
          current = bind.sourceErased
        case attached: Attached =>
          current = attached.sourceErased
          if attached.frames.nonEmpty then
            frames =
              if frames.isEmpty then attached.frames
              else attached.frames ::: frames
        case pending: PendingNode[?, ?] =>
          val widened =
            pending.asInstanceOf[PendingNode[Effect, Any]]
          return requestStep(widened, frames).asInstanceOf[Step[Fx, A]]

    throw new AssertionError("unreachable Eff.step loop exit")

/** Lift one typed operation into its effect row. */
def perform[Fx <: Effect, A](operation: Operation[Fx, A]): Eff[Fx, A] =
  Eff.performOperation(operation)
