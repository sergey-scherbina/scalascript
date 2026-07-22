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

  private final class Savable[S, A, Fx <: Effect, R](
      state: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S],
      candidate: Authority
  ) extends Continuation[A, Fx, R](candidate):
    // Ordinary local resume shares the current heap (control-interoperability §8.2).
    override def resume(value: A): Eff[Fx, R] = machine.resume(state, value)

    override def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]] =
      // The codec is the typed defunctionalized evidence §8.1 names. A codec may
      // declare its frame Unsavable (a raw foreign value with no durable codec, the
      // §8.3 FrameGate); save() then rejects with the typed CaptureFailure instead
      // of producing a SavedContinuation, never spilling into a capsule. Otherwise
      // snapshot the live state now so a later mutation of the original cannot change
      // the saved frame (§8.2); Nothing <: Save covariantly widens the success value.
      codec.captureBarrier match
        case Some(failure) => perform(Save.Rejected(failure))
        case None =>
          val frame = codec.snapshot(state)
          Eff.pure(SavedContinuation.reusable(frame, machine, codec))

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

  /**
   * Managed builder whose state carries durable evidence, so `save()` succeeds.
   * Mirrors [[local]] but additionally requires a [[DurableValue]] codec for the
   * state; that codec is what lets a saved run reconstruct an independent frame
   * (control-interoperability §8.1/§8.2). Unmanaged closures and codec-less
   * [[local]] continuations remain unsavable.
   */
  def savable[S, A, Fx <: Effect, R](
      state: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S]
  ): Continuation[A, Fx, R] =
    new Savable(state, machine, codec, authority)

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
  /** Library-owned successful save plans (post-X1; see durable-continuation-save-run). */
  private[control] final class Authority private[SavedContinuation] ()

  private val authority = new Authority()

  private[control] def requireAuthority(candidate: Authority): Unit =
    if (candidate eq null) || !(candidate eq authority) then
      throw new IllegalArgumentException(
        "invalid SavedContinuation authority"
      )

  /**
   * A reusable saved continuation produced from a managed savable state machine.
   * Immutable, copyable, and multi-shot: `save` does not consume the source and
   * `run` may be called zero or more times. Each admitted run reconstructs an
   * independent frame from the snapshot and begins directly at the capture point —
   * never replaying the prefix, module `main`, or initializers
   * (control-interoperability §8.1/§8.2).
   */
  private final class Reusable[S, A, Fx <: Effect, R](
      frame: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S],
      candidate: Authority
  ) extends SavedContinuation[A, R](candidate):
    type Effects = Fx

    def run(value: A): Eff[Fx | Restore, R] =
      Eff.defer[Fx | Restore, R] {
        val fresh = codec.snapshot(frame)
        machine.resume(fresh, value)
      }

  private[control] def reusable[S, A, Fx <: Effect, R](
      frame: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S]
  ): SavedContinuation.Aux[A, Fx, R] =
    new Reusable(frame, machine, codec, authority)

  type Aux[A, Fx <: Effect, R] = SavedContinuation[A, R] { type Effects = Fx }
