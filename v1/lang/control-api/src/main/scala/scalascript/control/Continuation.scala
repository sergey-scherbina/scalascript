package scalascript.control

import java.util.concurrent.atomic.AtomicBoolean

sealed trait Resumption[-A, +Fx <: Effect, +R]

object Resumption:
  final case class Reusable[A, Fx <: Effect, R] private[control] (
      continuation: Continuation[A, Fx, R]
  ) extends Resumption[A, Fx, R]

  final case class OneShot[A, Fx <: Effect, R] private[control] (
      continuation: OneShotContinuation[A, Fx, R]
  ) extends Resumption[A, Fx, R]

sealed abstract class Continuation[-A, Fx <: Effect, +R] private (
    candidate: Continuation.Authority
):
  Continuation.requireAuthority(candidate)

  def resume(value: A): Eff[Fx, R]

  def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]]

object Continuation:
  /** JVM-visible only as an unforgeable constructor parameter. */
  private[control] final class Authority private[Continuation] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException("invalid Continuation authority")

  private final class Runtime[A, Fx <: Effect, R](
      site: String,
      resumeBody: A => Eff[Fx, R],
      candidate: Authority
  ) extends Continuation[A, Fx, R](candidate):
    override def resume(value: A): Eff[Fx, R] = resumeBody(value)

    override def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]] =
      perform(Save.Rejected(CaptureFailure.UnmanagedCapture(site)))

  private final class Local[S, A, Fx <: Effect, R](
      state: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      candidate: Authority
  ) extends Continuation[A, Fx, R](candidate):
    override def resume(value: A): Eff[Fx, R] = machine.resume(state, value)

    override def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]] =
      perform(
        Save.Rejected(
          CaptureFailure.UnmanagedCapture("Continuation.local")
        )
      )

  private[control] def runtime[A, Fx <: Effect, R](
      kernel: Eff.Authority,
      site: String
  )(
      resume: A => Eff[Fx, R]
  ): Continuation[A, Fx, R] =
    Eff.requireAuthority(kernel)
    new Runtime(site, resume, authority)

  def local[S, A, Fx <: Effect, R](
      state: S,
      machine: ResumeStateMachine[S, A, Fx, R]
  ): Continuation[A, Fx, R] =
    new Local(state, machine, authority)

sealed abstract class OneShotContinuation[-A, +Fx <: Effect, +R] private (
    candidate: OneShotContinuation.Authority
):
  OneShotContinuation.requireAuthority(candidate)

  def tryResume(value: A): Either[ResumeRejected, Eff[Fx, R]]

object OneShotContinuation:
  /** JVM-visible only as an unforgeable constructor parameter. */
  private[control] final class Authority private[OneShotContinuation] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException(
        "invalid OneShotContinuation authority"
      )

  private final class Runtime[A, Fx <: Effect, R](
      operation: OperationId,
      resumeBody: A => Eff[Fx, R],
      candidate: Authority
  ) extends OneShotContinuation[A, Fx, R](candidate):
    private val claimed = new AtomicBoolean(false)

    override def tryResume(
        value: A
    ): Either[ResumeRejected, Eff[Fx, R]] =
      if claimed.compareAndSet(false, true) then Right(resumeBody(value))
      else Left(ResumeRejected.AlreadyResumed(operation))

  private final class Delegated[A, Fx <: Effect, R, Fx2 <: Effect, R2](
      source: OneShotContinuation[A, Fx, R],
      transform: Eff[Fx, R] => Eff[Fx2, R2],
      candidate: Authority
  ) extends OneShotContinuation[A, Fx2, R2](candidate):
    override def tryResume(
        value: A
    ): Either[ResumeRejected, Eff[Fx2, R2]] =
      source.tryResume(value) match
        case Left(rejected) => Left(rejected)
        case Right(next)    => Right(transform(next))

  private[control] def runtime[A, Fx <: Effect, R](
      kernel: Eff.Authority,
      operation: OperationId
  )(
      resume: A => Eff[Fx, R]
  ): OneShotContinuation[A, Fx, R] =
    Eff.requireAuthority(kernel)
    new Runtime(operation, resume, authority)

  private[control] def runtime[A, Fx <: Effect, R](
      kernel: Eff.Authority,
      operation: OperationId,
      continuation: Continuation[A, Fx, R]
  ): OneShotContinuation[A, Fx, R] =
    runtime(kernel, operation)(continuation.resume)

  /** Transform a resumed computation while retaining the source gate. */
  private[control] def delegate[
      A,
      Fx <: Effect,
      R,
      Fx2 <: Effect,
      R2
  ](
      kernel: Eff.Authority,
      source: OneShotContinuation[A, Fx, R]
  )(
      transform: Eff[Fx, R] => Eff[Fx2, R2]
  ): OneShotContinuation[A, Fx2, R2] =
    Eff.requireAuthority(kernel)
    new Delegated(source, transform, authority)

sealed abstract class SavedContinuation[-A, +R] private (
    candidate: SavedContinuation.Authority
):
  SavedContinuation.requireAuthority(candidate)

  type Effects <: Effect

  def run(value: A): Eff[Effects | Restore, R]

object SavedContinuation:
  /** Reserved for post-X1 library-owned successful save plans. */
  private[control] final class Authority private[SavedContinuation] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException(
        "invalid SavedContinuation authority"
      )

  type Aux[A, Fx <: Effect, R] = SavedContinuation[A, R] { type Effects = Fx }
