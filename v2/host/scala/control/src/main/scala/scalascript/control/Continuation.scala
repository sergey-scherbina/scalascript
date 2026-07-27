package scalascript.control

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

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

  /**
   * Cancel this one-shot continuation. `cancel` and `resume` compete for the SAME atomic claim, so
   * the existing eager-claim law (§3.1) extends without a new race algebra: exactly one wins, and
   * the loser is told WHICH — a resume that lost gets `Cancelled`, a cancel that lost gets
   * `TooLateToCancel` (owner decision D1). Cancelling twice succeeds idempotently with the same
   * evidence; it is not an error to be sure.
   */
  def tryCancel(): Either[ResumeRejected, CancelAccepted]

object OneShotContinuation:
  // The one atomic claim, as three states rather than a boolean — see Runtime.tryResume/tryCancel.
  private final val ClaimFree = 0
  private final val ClaimResumed = 1
  private final val ClaimCancelled = 2

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
    // A single AtomicBoolean could say only "taken", never BY WHOM — which is exactly the
    // distinction D1 requires. Three states, one CAS: 0 free, 1 resumed, 2 cancelled. The winner
    // still wins atomically and the loser still learns before touching the suffix (§3.1); it now
    // also learns which side won.
    private val claim = new AtomicInteger(ClaimFree)

    override def tryResume(
        value: A
    ): Either[ResumeRejected, Eff[Fx, R]] =
      if claim.compareAndSet(ClaimFree, ClaimResumed) then Right(resumeBody(value))
      else if claim.get() == ClaimCancelled then Left(ResumeRejected.Cancelled(operation))
      else Left(ResumeRejected.AlreadyResumed(operation))

    override def tryCancel(): Either[ResumeRejected, CancelAccepted] =
      if claim.compareAndSet(ClaimFree, ClaimCancelled) then Right(CancelAccepted(operation))
      else if claim.get() == ClaimResumed then Left(ResumeRejected.TooLateToCancel(operation))
      else Right(CancelAccepted(operation)) // already cancelled — idempotent, never a failure

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

    // A delegated view shares the SOURCE's claim — cancelling through the view must cancel the one
    // continuation, not a copy of its state.
    override def tryCancel(): Either[ResumeRejected, CancelAccepted] = source.tryCancel()

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

  /**
   * Run this saved continuation. Enforces the cancellation latch: a cancelled value rejects every
   * NEW run. Prefer [[tryRun]] where the rejection should be a value rather than a throw — `run`
   * exists for the common path and delegates to it.
   */
  def run(value: A): Eff[Effects | Restore, R] =
    tryRun(value) match
      case Right(next)     => next
      case Left(rejection) => throw new RunRejected(rejection)

  /**
   * Admission-checked run. The cancellation check happens HERE — when `tryRun` is called, before
   * any frame is reconstructed and before an execution identity exists — which is what "rejected at
   * admission" means (§11.1). Owner decision D4 puts it FIRST, ahead of expiry/revocation: when a
   * value is both expired and cancelled, the caller's own action is the more informative answer to
   * "why did my run not happen", and leaving the tie unspecified would make it a per-lane accident.
   */
  def tryRun(value: A): Either[ResumeRejected, Eff[Effects | Restore, R]]

  /**
   * Latch this saved value cancelled — monotonic and idempotent. Blocks only NEW admissions:
   * runs already in flight are not killed (owner decision D2, because interrupting a running
   * suffix is irreducibly target-specific), which is why [[cancellationScope]] exists to say so
   * in machine-readable form rather than in a footnote.
   */
  def cancel(): CancelAccepted

  def isCancelled: Boolean

  /** What this lane's `cancel` actually promises. `BlocksNewAdmissions` is the portable base
   *  contract; a lane that can also interrupt a running suffix advertises otherwise. */
  def cancellationScope: CancellationScope = CancellationScope.BlocksNewAdmissions

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
      operation: OperationId,
      candidate: Authority
  ) extends SavedContinuation[A, R](candidate):
    type Effects = Fx

    private val cancelled = new AtomicBoolean(false)

    def tryRun(value: A): Either[ResumeRejected, Eff[Fx | Restore, R]] =
      // D4: checked FIRST, and before the defer — a cancelled value must not reconstruct a frame.
      if cancelled.get() then Left(ResumeRejected.Cancelled(operation))
      else
        Right(Eff.defer[Fx | Restore, R] {
          val fresh = codec.snapshot(frame)
          machine.resume(fresh, value)
        })

    def cancel(): CancelAccepted =
      cancelled.set(true) // monotonic latch: idempotent by construction, never a failure
      CancelAccepted(operation)

    def isCancelled: Boolean = cancelled.get()

  private[control] def reusable[S, A, Fx <: Effect, R](
      frame: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S],
      operation: OperationId = SavedOperation
  ): SavedContinuation.Aux[A, Fx, R] =
    new Reusable(frame, machine, codec, operation, authority)

  /** The identity a saved continuation reports in its rejections when the save site did not carry
   *  one — stable and target-neutral, never a synthesized per-instance name. PUBLIC because it is
   *  part of the observable contract: §13 says embedding goes through the structured OperationId
   *  and its constructor, never through message parsing, so a caller must be able to compare it. */
  val SavedOperation: OperationId =
    OperationId(EffectId("ssc.control.saved"), "run")

  type Aux[A, Fx <: Effect, R] = SavedContinuation[A, R] { type Effects = Fx }
