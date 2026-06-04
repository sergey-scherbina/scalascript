package scalascript.wire.compat

import java.security.MessageDigest
import scalascript.wire.{WireDecodeError, WireEnvelope}

/** Schema-id hashing and binary-compatibility evolution rules.
 *
 *  Every `WireCodec[A]` carries a `schemaId` string that identifies the
 *  structural layout of the encoded type.  Envelopes with `schemaId` set
 *  can be checked against a `WireSchemaRegistry` to decide whether binary
 *  traffic between two peers is safe.
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Phase 8 */
object WireSchemaId:

  /** Stable content-addressed schema id: `"sha256:<first-16-hex-chars>"`.
   *
   *  Input is a canonical schema-definition string such as:
   *  `"object:Point{x:int64,y:int64}"` — the caller constructs this from
   *  the type structure; `WireCodec.schemaId` can either use this helper
   *  or provide a hand-written stable string.
   *
   *  Truncated to 16 hex chars (64 bits) for readability; collision risk
   *  is negligible for schema space sizes. */
  def hash(definition: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val hex    = digest.digest(definition.getBytes("UTF-8"))
      .map(b => "%02x".format(b & 0xff)).mkString
    s"sha256:${hex.take(16)}"

  /** Check whether `id` was produced by `hash(definition)`. */
  def verify(definition: String, id: String): Boolean =
    hash(definition) == id

// ── Evolution rules ───────────────────────────────────────────────────────

/** Sealed set of evolution outcomes between two schema ids. */
enum CompatibilityResult:
  /** Identical schema ids — unconditionally compatible. */
  case Identical
  /** Different ids but declared compatible by the registry. */
  case Compatible(reason: String)
  /** Unknown relationship — compatibility not declared.
   *  Binary traffic is allowed only when `WireConfig.jsonFallback = true`. */
  case Unknown(localId: String, remoteId: String)
  /** Explicitly incompatible — binary traffic must be rejected. */
  case Incompatible(localId: String, remoteId: String, reason: String)

/** Registry of known-compatible schema-id pairs.
 *
 *  Two schema ids are compatible if they are identical, or if a prior
 *  evolution was explicitly registered (e.g. an additive field addition).
 *
 *  Not thread-safe for writes; build once at startup, read concurrently. */
class WireSchemaRegistry:
  private val compat = scala.collection.mutable.HashMap.empty[(String, String), String]

  /** Declare that messages encoded with `oldId` can be decoded by code
   *  compiled against `newId`.  The pair is directional: `(old → new)`. */
  def registerEvolution(oldId: String, newId: String, reason: String): Unit =
    compat((oldId, newId)) = reason

  /** Check compatibility of `remoteId` (sender) against `localId` (receiver). */
  def check(localId: String, remoteId: String): CompatibilityResult =
    if localId == remoteId then CompatibilityResult.Identical
    else compat.get((remoteId, localId)) match
      case Some(reason) => CompatibilityResult.Compatible(reason)
      case None         => CompatibilityResult.Unknown(localId, remoteId)

  /** Total number of registered evolution pairs. */
  def size: Int = compat.size

object WireSchemaRegistry:
  val empty: WireSchemaRegistry = new WireSchemaRegistry()

// ── Envelope schema-id guard ──────────────────────────────────────────────

object WireCompatibilityGuard:

  /** Verify that the `schemaId` carried by an inbound envelope is compatible
   *  with the local codec's `schemaId`.
   *
   *  - If the envelope has no `schemaId`, and `requireSchemaId = false`
   *    (default), the check passes.
   *  - If `requireSchemaId = true` and `schemaId` is missing, returns `Left`.
   *  - If `Incompatible`, always returns `Left`.
   *  - If `Unknown` and `allowUnknown = false` (default strict), returns `Left`.
   */
  def check(
    env:             WireEnvelope,
    localSchemaId:   String,
    registry:        WireSchemaRegistry = WireSchemaRegistry.empty,
    requireSchemaId: Boolean = false,
    allowUnknown:    Boolean = true,
  ): Either[WireDecodeError, CompatibilityResult] =
    env.schemaId match
      case None if requireSchemaId =>
        Left(WireDecodeError.MalformedInput(
          s"schemaId required but missing from envelope (local=$localSchemaId)"
        ))
      case None =>
        Right(CompatibilityResult.Unknown("<absent>", localSchemaId))
      case Some(remoteId) =>
        val result = registry.check(localSchemaId, remoteId)
        result match
          case CompatibilityResult.Incompatible(_, _, reason) =>
            Left(WireDecodeError.SchemaIdMismatch(localSchemaId, remoteId))
          case CompatibilityResult.Unknown(l, r) if !allowUnknown =>
            Left(WireDecodeError.SchemaIdMismatch(l, r))
          case other =>
            Right(other)

// ── Golden test vector registry ────────────────────────────────────────────

/** A single golden vector: name, encoded bytes in a format, and expected WireValue. */
case class WireGoldenVector(
  name:          String,
  format:        String,
  encodedBase64: String,
  schemaId:      String,
)

/** In-memory registry of golden test vectors for cross-version compat testing.
 *
 *  Old release encodes a value and stores the vector here.  New release
 *  must decode it correctly to pass the test. */
class WireGoldenVectorRegistry:
  private val vectors = scala.collection.mutable.ArrayBuffer.empty[WireGoldenVector]

  def register(v: WireGoldenVector): Unit = vectors += v
  def register(name: String, format: String, encoded: Array[Byte], schemaId: String): Unit =
    register(WireGoldenVector(name, format, java.util.Base64.getEncoder.encodeToString(encoded), schemaId))

  def all: Seq[WireGoldenVector] = vectors.toSeq
  def byFormat(format: String): Seq[WireGoldenVector] = vectors.filter(_.format == format).toSeq
  def decode(v: WireGoldenVector): Array[Byte] = java.util.Base64.getDecoder.decode(v.encodedBase64)
  def size: Int = vectors.size
