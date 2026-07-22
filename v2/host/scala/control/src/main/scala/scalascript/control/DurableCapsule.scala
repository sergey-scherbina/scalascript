package scalascript.control

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Typed rejection raised when a capsule cannot be admitted at restore. `kind` is a
 * stable admission-failure category — `FormatVersion`, `ResumePointMismatch`,
 * `FrameTampered`, `TamperedCapsule`, `ResourceLimit`, `CodecMismatch`,
 * `AbiMismatch`, or `MissingDependency`.
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
 * The admission-security policy a resume point binds into every capsule it freezes
 * and enforces on restore (control-interoperability §11.1 step 2, §12). For an
 * untrusted network capsule these checks are mandatory, not optional:
 *   - `signingKey` — the symmetric HMAC-SHA256 key. When non-empty, `freeze` signs the
 *     canonical capsule body and `restore` rejects a missing/forged/tampered signature
 *     as `TamperedCapsule`. An empty key means the trusted in-process path (unsigned).
 *   - `audience` / `tenant` — the runner this capsule is addressed to; a capsule whose
 *     bound audience or tenant differs from the admitting runner is `TamperedCapsule`.
 *   - `requiredBudget` — the execution/decode budget the capsule demands; admission
 *     rejects it as `ResourceLimit` when it exceeds the runner's available budget.
 *
 * The key never travels in the capsule and is never part of the serialized envelope.
 */
final case class AdmissionPolicy(
    audience: String,
    tenant: String,
    requiredBudget: Long,
    signingKey: DurableBytes
)

object AdmissionPolicy:
  /** The trusted in-process default: no audience/tenant binding, no budget, unsigned. */
  val open: AdmissionPolicy =
    AdmissionPolicy("", "", 0L, DurableBytes.fromArray(Array.emptyByteArray))

/**
 * A versioned, digest-verified durable capsule: the transport form of a saved
 * continuation's frame, bound to a named resume point
 * (`specs/durable-capsule-envelope.md`). Decoding is inert (§9.2) — it never
 * verifies the digest or signature or contacts a resume program; admission happens
 * at `ResumePoint.restore`. `audience`/`tenant`/`requiredBudget` are the untrusted
 * security envelope; `signature` is the keyed HMAC over the rest of the capsule.
 */
final class DurableCapsule private[control] (
    val formatVersion: Int,
    val resumePointId: String,
    private[control] val codecAbiVersion: Int,
    private[control] val artifactAbiId: String,
    private[control] val requiredDependencies: List[String],
    private[control] val frame: DurableBytes,
    private[control] val digest: DurableBytes,
    private[control] val audience: String,
    private[control] val tenant: String,
    private[control] val requiredBudget: Long,
    private[control] val signature: DurableBytes
):
  def encode(): DurableBytes = DurableCapsule.codec.encode(this)

  private[control] def withSignature(replacement: DurableBytes): DurableCapsule =
    new DurableCapsule(
      formatVersion,
      resumePointId,
      codecAbiVersion,
      artifactAbiId,
      requiredDependencies,
      frame,
      digest,
      audience,
      tenant,
      requiredBudget,
      replacement
    )

object DurableCapsule:
  /** Current envelope format version. */
  private[control] val FormatVersion: Int = 3

  // "ssc-frame-v1\0" — NUL-terminated domain separator (§10), built as explicit
  // bytes so the canonical prefix is unambiguous and the source stays plain text.
  private val FrameDigestDomain: Array[Byte] =
    "ssc-frame-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  // "ssc-capsule-sig-v1\0" — domain separator for the keyed capsule signature.
  private val CapsuleSignatureDomain: Array[Byte] =
    "ssc-capsule-sig-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  private val emptySignature: DurableBytes =
    DurableBytes.fromArray(Array.emptyByteArray)

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
                DurableCodec.pair(
                  DurableCodec.bytes,
                  DurableCodec.pair(
                    DurableCodec.bytes,
                    DurableCodec.pair(
                      DurableCodec.string,
                      DurableCodec.pair(
                        DurableCodec.string,
                        DurableCodec.pair(DurableCodec.long, DurableCodec.bytes)
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    ) {
      case (
            version,
            (
              id,
              (
                codecAbi,
                (
                  artifactAbi,
                  (
                    deps,
                    (
                      frame,
                      (
                        digest,
                        (audience, (tenant, (requiredBudget, signature)))
                      )
                    )
                  )
                )
              )
            )
          ) =>
        new DurableCapsule(
          version,
          id,
          codecAbi,
          artifactAbi,
          deps,
          frame,
          digest,
          audience,
          tenant,
          requiredBudget,
          signature
        )
    } { capsule =>
      (
        capsule.formatVersion,
        (
          capsule.resumePointId,
          (
            capsule.codecAbiVersion,
            (
              capsule.artifactAbiId,
              (
                capsule.requiredDependencies,
                (
                  capsule.frame,
                  (
                    capsule.digest,
                    (
                      capsule.audience,
                      (capsule.tenant, (capsule.requiredBudget, capsule.signature))
                    )
                  )
                )
              )
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

  // --- Keyed HMAC-SHA256 (RFC 2104), hand-rolled over MessageDigest so it is
  // byte-identical to the import-free JS lane's hand-rolled implementation. ---

  private val HmacBlockSize: Int = 64

  private def sha256(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(data)

  private def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val normalized = if key.length > HmacBlockSize then sha256(key) else key
    val padded = java.util.Arrays.copyOf(normalized, HmacBlockSize)
    val inner = new Array[Byte](HmacBlockSize + message.length)
    val outer = new Array[Byte](HmacBlockSize + 32)
    var i = 0
    while i < HmacBlockSize do
      inner(i) = (padded(i) ^ 0x36).toByte
      outer(i) = (padded(i) ^ 0x5c).toByte
      i += 1
    System.arraycopy(message, 0, inner, HmacBlockSize, message.length)
    val innerHash = sha256(inner)
    System.arraycopy(innerHash, 0, outer, HmacBlockSize, innerHash.length)
    sha256(outer)

  // The signature covers the canonical body with an empty signature slot, so a
  // forged edit of any other field changes the message and breaks the HMAC.
  private def signingMessage(capsule: DurableCapsule): Array[Byte] =
    val body = codec.encode(capsule.withSignature(emptySignature)).view
    val message = new Array[Byte](CapsuleSignatureDomain.length + body.length)
    System.arraycopy(
      CapsuleSignatureDomain,
      0,
      message,
      0,
      CapsuleSignatureDomain.length
    )
    System.arraycopy(body, 0, message, CapsuleSignatureDomain.length, body.length)
    message

  private def signatureFor(
      capsule: DurableCapsule,
      key: DurableBytes
  ): DurableBytes =
    DurableBytes.fromArray(hmacSha256(key.view, signingMessage(capsule)))

  private[control] def create(
      id: String,
      frame: DurableBytes,
      profile: ArtifactProfile,
      policy: AdmissionPolicy
  ): DurableCapsule =
    val unsigned =
      new DurableCapsule(
        FormatVersion,
        id,
        profile.codecAbiVersion,
        profile.artifactAbiId,
        profile.requiredDependencies.toList.sorted,
        frame,
        digestFrame(frame),
        policy.audience,
        policy.tenant,
        policy.requiredBudget,
        emptySignature
      )
    if policy.signingKey.length == 0 then unsigned
    else unsigned.withSignature(signatureFor(unsigned, policy.signingKey))

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
   * Security admission (§11.1 step 2): the keyed signature, the audience/tenant
   * binding, and the resource budget, each a distinct typed rejection. Runs after the
   * capsule-integrity `verify` and before the codec/ABI/dependency checks.
   */
  private[control] def admitSecurity(
      capsule: DurableCapsule,
      policy: AdmissionPolicy,
      availableBudget: Long
  ): Unit =
    if policy.signingKey.length != 0 then
      if signatureFor(capsule, policy.signingKey) != capsule.signature then
        throw new CapsuleRejected(
          "TamperedCapsule",
          "capsule signature verification failed"
        )
    if capsule.audience != policy.audience then
      throw new CapsuleRejected(
        "TamperedCapsule",
        s"capsule audience '${capsule.audience}' is not admitted by this runner"
      )
    if capsule.tenant != policy.tenant then
      throw new CapsuleRejected(
        "TamperedCapsule",
        s"capsule tenant '${capsule.tenant}' is not admitted by this runner"
      )
    if capsule.requiredBudget > availableBudget then
      throw new CapsuleRejected(
        "ResourceLimit",
        s"capsule requires budget ${capsule.requiredBudget} exceeding available $availableBudget"
      )

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
    val profile: ArtifactProfile,
    val policy: AdmissionPolicy
):
  /** An in-process savable continuation bound to this resume point (Part 1). */
  def savable(state: S): Continuation[A, Fx, R] =
    Continuation.savable(state, machine, codec)

  /**
   * Freeze a state into a durable capsule pinning this resume point's ABI profile and
   * security policy. When the policy carries a signing key the capsule is signed.
   */
  def freeze(state: S): DurableCapsule =
    DurableCapsule.create(id, codec.encode(state), profile, policy)

  /**
   * Admit a capsule and rebind it to this resume point's program, running every
   * admission check atomically before any frame is decoded or run
   * (control-interoperability §9.2, §11, §12). Each failure is a distinct typed
   * `CapsuleRejected` kind:
   *   - `FormatVersion` / `ResumePointMismatch` / `FrameTampered` (capsule integrity),
   *   - `TamperedCapsule` — the keyed signature, audience, or tenant is wrong (§11.1 step 2),
   *   - `ResourceLimit` — the capsule's declared budget exceeds `availableBudget`,
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
      availableDependencies: Set[String] = Set.empty,
      availableBudget: Long = Long.MaxValue
  ): SavedContinuation.Aux[A, Fx, R] =
    DurableCapsule.verify(capsule, id)
    DurableCapsule.admitSecurity(capsule, policy, availableBudget)
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
      profile: ArtifactProfile = ArtifactProfile.default,
      policy: AdmissionPolicy = AdmissionPolicy.open
  ): ResumePoint[S, A, Fx, R] =
    if id == null then throw new NullPointerException("resume point id")
    if id.isEmpty then
      throw new IllegalArgumentException("resume point id must be non-empty")
    if machine == null then throw new NullPointerException("resume machine")
    if codec == null then throw new NullPointerException("resume frame codec")
    if requiredResolvers == null then
      throw new NullPointerException("required resolvers")
    if profile == null then throw new NullPointerException("artifact profile")
    if policy == null then throw new NullPointerException("admission policy")
    new ResumePoint(id, machine, codec, requiredResolvers, profile, policy)
