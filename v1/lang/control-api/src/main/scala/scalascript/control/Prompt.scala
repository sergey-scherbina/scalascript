package scalascript.control

/** A generative, answer-type-indexed delimiter. */
final class Prompt[P, R] private (candidate: Prompt.Authority):
  Prompt.requireAuthority(candidate)

  private val effectKey: EffectKey[Control[P]] =
    Control.freshKey[P](candidate)

  private final class ShiftOperation[A, Base <: Effect](
      val body: ShiftBody[P, A, Base, R],
      val effect: EffectKey[Control[P]]
  ) extends Operation[Control[P], A]:
    val id: OperationId = OperationId(effect.id, "shift")

  private def invokeShift[X, Fx <: Effect](
      operation: Operation[Control[P], X],
      continuation: Continuation[X, Fx, R]
  ): Eff[Fx | Control[P], R] =
    // A ShiftOperation enters the computation at Base | Control[P]. Eff
    // covariance and flatMap may only widen Base, so the matching reset's Fx
    // is a valid instantiation of the operation's rank-2 body.
    val shifted =
      operation.asInstanceOf[ShiftOperation[X, Fx]]
    shifted.body[Fx](continuation)

  private[control] def resetBody[Fx <: Effect](
      body: => Eff[Fx | Control[P], R]
  ): Eff[Fx, R] =
    val handledKey = effectKey
    handle[Control[P], Fx, R, R](Eff.defer(body))(
      new Handler[Control[P], Fx, R, R]:
        val effect: EffectKey[Control[P]] = handledKey

        def onReturn(value: R): Eff[Fx, R] = Eff.pure(value)

        def onOperation[X](
            operation: Operation[Control[P], X],
            resumption: Resumption[X, Fx, R]
        ): Eff[Fx, R] =
          resumption match
            case Resumption.Reusable(continuation) =>
              resetBody(
                invokeShift(operation, continuation)
              )
            case Resumption.OneShot(_) =>
              throw new IllegalStateException(
                "shift operation unexpectedly exposed a one-shot resumption"
              )
    )

  private[control] def shiftBody[A, Fx <: Effect](
      body: ShiftBody[P, A, Fx, R]
  ): Eff[Fx | Control[P], A] =
    perform(new ShiftOperation[A, Fx](body, effectKey))

object Prompt:
  /** JVM-visible only as an unforgeable constructor parameter. */
  private[control] final class Authority private[Prompt] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException("invalid Prompt authority")

  private final class Scoped[R](candidate: Authority)
      extends ScopedPrompt[R]:
    type Key = this.type
    val prompt: Prompt[Key, R] = new Prompt[Key, R](candidate)

  private[control] def scoped[R]: ScopedPrompt[R] =
    new Scoped[R](authority)

trait ScopedPrompt[R]:
  type Key
  val prompt: Prompt[Key, R]

type ShiftBody[P, A, Fx <: Effect, R] =
  [Residual >: Fx <: Effect] =>
    Continuation[A, Residual, R] => Eff[Residual | Control[P], R]

def freshPrompt[R]: ScopedPrompt[R] =
  Prompt.scoped[R]

/** Delimit and deeply handle shifts for exactly `prompt`. */
def reset[P, Fx <: Effect, R](prompt: Prompt[P, R])(
    body: => Eff[Fx | Control[P], R]
): Eff[Fx, R] =
  prompt.resetBody(body)

/** Capture up to `prompt`; the body remains under the same reset (`shift`). */
def shift[P, A, Fx <: Effect, R](prompt: Prompt[P, R])(
    body: ShiftBody[P, A, Fx, R]
): Eff[Fx | Control[P], A] =
  prompt.shiftBody(body)
