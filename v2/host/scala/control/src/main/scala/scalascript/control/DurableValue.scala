package scalascript.control

/**
 * Typed durable-frame evidence for a captured state value.
 *
 * The in-process keystone needs only an independent snapshot so the save
 * snapshot law (`specs/control-interoperability.md` §8.2) holds: mutation of the
 * original after save, and mutation inside one admitted run, must be invisible to
 * any other run. A later slice extends this to the §9.1 canonical byte codec; the
 * snapshot method stays the in-memory projection of that codec (`decode ∘ encode`).
 * Instances never reflect over `S`.
 */
trait DurableValue[S]:
  def snapshot(value: S): S

  /**
   * If defined, the frame this evidence describes cannot cross the save boundary —
   * it holds a raw foreign value (a live object/socket/lock/closure) with no durable
   * codec. `save()` then rejects with this typed `CaptureFailure` instead of
   * producing a `SavedContinuation`, the `Unsavable` side of the §8.3 FrameGate.
   */
  def captureBarrier: Option[CaptureFailure] = None

object DurableValue:
  private final class Immutable[S] extends DurableValue[S]:
    def snapshot(value: S): S = value

  private final class Copying[S](copy: S => S) extends DurableValue[S]:
    def snapshot(value: S): S = copy(value)

  private final class Unsavable[S](failure: CaptureFailure)
      extends DurableValue[S]:
    def snapshot(value: S): S =
      throw new IllegalStateException("an unsavable frame has no snapshot")
    override def captureBarrier: Option[CaptureFailure] = Some(failure)

  /** For an immutable value type an independent copy is the value itself. */
  def immutable[S]: DurableValue[S] = new Immutable[S]

  /** Evidence built from an explicit deep copy for a mutable or aliased `S`. */
  def copying[S](copy: S => S): DurableValue[S] = new Copying[S](copy)

  /**
   * Evidence that a frame is NOT savable — its captured value is a raw foreign
   * value with no durable codec. A `savable` built with it resumes in-process, but
   * `save()` rejects with `failure` (the §8.3 FrameGate discriminator).
   */
  def unsavable[S](failure: CaptureFailure): DurableValue[S] =
    new Unsavable[S](failure)
