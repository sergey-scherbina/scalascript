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

object DurableValue:
  private final class Immutable[S] extends DurableValue[S]:
    def snapshot(value: S): S = value

  private final class Copying[S](copy: S => S) extends DurableValue[S]:
    def snapshot(value: S): S = copy(value)

  /** For an immutable value type an independent copy is the value itself. */
  def immutable[S]: DurableValue[S] = new Immutable[S]

  /** Evidence built from an explicit deep copy for a mutable or aliased `S`. */
  def copying[S](copy: S => S): DurableValue[S] = new Copying[S](copy)
