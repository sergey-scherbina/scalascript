package scalascript.control

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Typed rejection raised when a capsule cannot be admitted at restore. */
final class CapsuleRejected(reason: String) extends RuntimeException(reason)

/**
 * A versioned, digest-verified durable capsule: the transport form of a saved
 * continuation's frame, bound to a named resume point
 * (`specs/durable-capsule-envelope.md`). Decoding is inert (§9.2) — it never
 * verifies the digest or contacts a resume program; admission happens at
 * `ResumePoint.restore`.
 */
final class DurableCapsule private[control] (
    val formatVersion: Int,
    val resumePointId: String,
    private[control] val frame: DurableBytes,
    private[control] val digest: DurableBytes
):
  def encode(): DurableBytes = DurableCapsule.codec.encode(this)

object DurableCapsule:
  /** Current envelope format version. */
  private[control] val FormatVersion: Int = 1

  // "ssc-frame-v1\0" — NUL-terminated domain separator (§10), built as explicit
  // bytes so the canonical prefix is unambiguous and the source stays plain text.
  private val FrameDigestDomain: Array[Byte] =
    "ssc-frame-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  private val codec: DurableCodec[DurableCapsule] =
    DurableCodec.imap(
      DurableCodec.pair(
        DurableCodec.int,
        DurableCodec.pair(
          DurableCodec.string,
          DurableCodec.pair(DurableCodec.bytes, DurableCodec.bytes)
        )
      )
    ) { case (version, (id, (frame, digest))) =>
      new DurableCapsule(version, id, frame, digest)
    } { capsule =>
      (
        capsule.formatVersion,
        (capsule.resumePointId, (capsule.frame, capsule.digest))
      )
    }

  /** Parse the transport envelope. Pure, bounded, and inert — no verification. */
  def decode(bytes: DurableBytes): DurableCapsule = codec.decode(bytes)

  private[control] def digestFrame(frame: DurableBytes): DurableBytes =
    val message = MessageDigest.getInstance("SHA-256")
    message.update(FrameDigestDomain)
    message.update(frame.view)
    new DurableBytes(message.digest())

  private[control] def create(id: String, frame: DurableBytes): DurableCapsule =
    new DurableCapsule(FormatVersion, id, frame, digestFrame(frame))

  /** Admission: reject a stale version, a cross-point capsule, or a tampered frame. */
  private[control] def verify(capsule: DurableCapsule, expectedId: String): Unit =
    if capsule.formatVersion != FormatVersion then
      throw new CapsuleRejected(
        s"unsupported capsule format version ${capsule.formatVersion}"
      )
    if capsule.resumePointId != expectedId then
      throw new CapsuleRejected(
        s"capsule resume point '${capsule.resumePointId}' does not match '$expectedId'"
      )
    if DurableCapsule.digestFrame(capsule.frame) != capsule.digest then
      throw new CapsuleRejected("capsule frame digest mismatch")

/**
 * A named binding of a resume program (`ResumeStateMachine`) and its frame codec.
 * The `id` labels every capsule this point freezes and is checked on restore, so a
 * capsule can only be admitted by a resume point that already holds the matching
 * program (the `ExactArtifact` code mode; the machine never travels as bytes).
 */
final class ResumePoint[S, A, Fx <: Effect, R] private[control] (
    val id: String,
    machine: ResumeStateMachine[S, A, Fx, R],
    codec: DurableCodec[S]
):
  /** An in-process savable continuation bound to this resume point (Part 1). */
  def savable(state: S): Continuation[A, Fx, R] =
    Continuation.savable(state, machine, codec)

  /** Freeze a state into a durable, digest-verified capsule. */
  def freeze(state: S): DurableCapsule =
    DurableCapsule.create(id, codec.encode(state))

  /** Admit a capsule and rebind it to this resume point's program. */
  def restore(capsule: DurableCapsule): SavedContinuation.Aux[A, Fx, R] =
    DurableCapsule.verify(capsule, id)
    SavedContinuation.reusable(codec.decode(capsule.frame), machine, codec)

object ResumePoint:
  def define[S, A, Fx <: Effect, R](
      id: String,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableCodec[S]
  ): ResumePoint[S, A, Fx, R] =
    if id == null then throw new NullPointerException("resume point id")
    if id.isEmpty then
      throw new IllegalArgumentException("resume point id must be non-empty")
    if machine == null then throw new NullPointerException("resume machine")
    if codec == null then throw new NullPointerException("resume frame codec")
    new ResumePoint(id, machine, codec)
