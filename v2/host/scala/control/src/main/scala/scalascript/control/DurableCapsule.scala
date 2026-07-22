package scalascript.control

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Typed rejection raised when a capsule cannot be admitted at restore. `kind` is a
 * stable admission-failure category — `FormatVersion`, `ResumePointMismatch`,
 * `FrameTampered`, `CodecMismatch`, `AbiMismatch`, or `MissingDependency`.
 */
final class CapsuleRejected(val kind: String, message: String)
    extends RuntimeException(message)

/**
 * The pinned ABI/dependency identity a capsule carries so admission can reject a
 * codec, artifact, or dependency mismatch before any user code runs
 * (control-interoperability §10, §12). `codecAbiVersion` pins the value/frame codec
 * ABI, `artifactAbiId` pins the ExactArtifact/control/descriptor identity, and
 * `requiredDependencies` names the target/toolchain/plugin ids the capsule needs.
 */
final case class ArtifactProfile(
    codecAbiVersion: Int,
    artifactAbiId: String,
    requiredDependencies: Set[String]
)

object ArtifactProfile:
  val default: ArtifactProfile = ArtifactProfile(1, "", Set.empty)

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
    private[control] val codecAbiVersion: Int,
    private[control] val artifactAbiId: String,
    private[control] val requiredDependencies: List[String],
    private[control] val frame: DurableBytes,
    private[control] val digest: DurableBytes
):
  def encode(): DurableBytes = DurableCapsule.codec.encode(this)

object DurableCapsule:
  /** Current envelope format version. */
  private[control] val FormatVersion: Int = 2

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
          DurableCodec.pair(
            DurableCodec.int,
            DurableCodec.pair(
              DurableCodec.string,
              DurableCodec.pair(
                DurableCodec.list(DurableCodec.string),
                DurableCodec.pair(DurableCodec.bytes, DurableCodec.bytes)
              )
            )
          )
        )
      )
    ) {
      case (
            version,
            (id, (codecAbi, (artifactAbi, (deps, (frame, digest)))))
          ) =>
        new DurableCapsule(version, id, codecAbi, artifactAbi, deps, frame, digest)
    } { capsule =>
      (
        capsule.formatVersion,
        (
          capsule.resumePointId,
          (
            capsule.codecAbiVersion,
            (
              capsule.artifactAbiId,
              (capsule.requiredDependencies, (capsule.frame, capsule.digest))
            )
          )
        )
      )
    }

  /** Parse the transport envelope. Pure, bounded, and inert — no verification. */
  def decode(bytes: DurableBytes): DurableCapsule = codec.decode(bytes)

  private[control] def digestFrame(frame: DurableBytes): DurableBytes =
    val message = MessageDigest.getInstance("SHA-256")
    message.update(FrameDigestDomain)
    message.update(frame.view)
    new DurableBytes(message.digest())

  private[control] def create(
      id: String,
      frame: DurableBytes,
      profile: ArtifactProfile
  ): DurableCapsule =
    new DurableCapsule(
      FormatVersion,
      id,
      profile.codecAbiVersion,
      profile.artifactAbiId,
      profile.requiredDependencies.toList.sorted,
      frame,
      digestFrame(frame)
    )

  /** Admission: reject a stale version, a cross-point capsule, or a tampered frame. */
  private[control] def verify(capsule: DurableCapsule, expectedId: String): Unit =
    if capsule.formatVersion != FormatVersion then
      throw new CapsuleRejected(
        "FormatVersion",
        s"unsupported capsule format version ${capsule.formatVersion}"
      )
    if capsule.resumePointId != expectedId then
      throw new CapsuleRejected(
        "ResumePointMismatch",
        s"capsule resume point '${capsule.resumePointId}' does not match '$expectedId'"
      )
    if DurableCapsule.digestFrame(capsule.frame) != capsule.digest then
      throw new CapsuleRejected("FrameTampered", "capsule frame digest mismatch")

/**
 * A named binding of a resume program (`ResumeStateMachine`) and its frame codec.
 * The `id` labels every capsule this point freezes and is checked on restore, so a
 * capsule can only be admitted by a resume point that already holds the matching
 * program (the `ExactArtifact` code mode; the machine never travels as bytes).
 */
final class ResumePoint[S, A, Fx <: Effect, R] private[control] (
    val id: String,
    machine: ResumeStateMachine[S, A, Fx, R],
    codec: DurableCodec[S],
    val requiredResolvers: Set[String],
    val profile: ArtifactProfile
):
  /** An in-process savable continuation bound to this resume point (Part 1). */
  def savable(state: S): Continuation[A, Fx, R] =
    Continuation.savable(state, machine, codec)

  /** Freeze a state into a durable capsule pinning this resume point's ABI profile. */
  def freeze(state: S): DurableCapsule =
    DurableCapsule.create(id, codec.encode(state), profile)

  /**
   * Admit a capsule and rebind it to this resume point's program, running every
   * admission check atomically before any frame is decoded or run
   * (control-interoperability §9.2, §11, §12). Each failure is a distinct typed
   * `CapsuleRejected` kind:
   *   - `FormatVersion` / `ResumePointMismatch` / `FrameTampered` (capsule integrity),
   *   - `CodecMismatch` — the capsule's pinned value/frame codec ABI differs,
   *   - `AbiMismatch` — the capsule's pinned ExactArtifact/control identity differs,
   *   - `MissingDependency` — a required `DurableRef` resolver (`availableResolvers`) or a
   *     capsule-declared target/toolchain dependency (`availableDependencies`) is absent.
   * A present resolver whose resource fails later is a mid-run residual failure, not an
   * admission reject.
   */
  def restore(
      capsule: DurableCapsule,
      availableResolvers: Set[String] = Set.empty,
      availableDependencies: Set[String] = Set.empty
  ): SavedContinuation.Aux[A, Fx, R] =
    DurableCapsule.verify(capsule, id)
    if capsule.codecAbiVersion != profile.codecAbiVersion then
      throw new CapsuleRejected(
        "CodecMismatch",
        s"capsule codec ABI v${capsule.codecAbiVersion} is incompatible with runtime v${profile.codecAbiVersion}"
      )
    if capsule.artifactAbiId != profile.artifactAbiId then
      throw new CapsuleRejected(
        "AbiMismatch",
        s"capsule artifact ABI '${capsule.artifactAbiId}' differs from runtime '${profile.artifactAbiId}'"
      )
    val missing =
      requiredResolvers.diff(availableResolvers) ++
        capsule.requiredDependencies.toSet.diff(availableDependencies)
    if missing.nonEmpty then
      throw new CapsuleRejected(
        "MissingDependency",
        s"capsule requires unavailable dependency/resolver(s): ${missing.toVector.sorted.mkString(", ")}"
      )
    SavedContinuation.reusable(codec.decode(capsule.frame), machine, codec)

object ResumePoint:
  def define[S, A, Fx <: Effect, R](
      id: String,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableCodec[S],
      requiredResolvers: Set[String] = Set.empty,
      profile: ArtifactProfile = ArtifactProfile.default
  ): ResumePoint[S, A, Fx, R] =
    if id == null then throw new NullPointerException("resume point id")
    if id.isEmpty then
      throw new IllegalArgumentException("resume point id must be non-empty")
    if machine == null then throw new NullPointerException("resume machine")
    if codec == null then throw new NullPointerException("resume frame codec")
    if requiredResolvers == null then
      throw new NullPointerException("required resolvers")
    if profile == null then throw new NullPointerException("artifact profile")
    new ResumePoint(id, machine, codec, requiredResolvers, profile)
