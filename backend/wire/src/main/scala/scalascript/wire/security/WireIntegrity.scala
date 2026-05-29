package scalascript.wire.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import scalascript.wire.{WireDecodeError, WireEnvelope}

/** HMAC-SHA256 frame integrity for wire envelopes.
 *
 *  When `integrity = "hmac-sha256"` is configured, every outbound frame is
 *  signed before sending.  The HMAC covers the serialised payload bytes and
 *  is stored as a `"hmac"` header.  Inbound frames must carry a valid HMAC
 *  or they are rejected.
 *
 *  Spec: docs/distributed-wire-protocol.md §Frame Integrity */
object WireIntegrity:

  val Algorithm = "HmacSHA256"

  /** Compute a Base64-encoded HMAC-SHA256 of `data` using `keyBytes`. */
  def sign(data: Array[Byte], keyBytes: Array[Byte]): String =
    val mac = Mac.getInstance(Algorithm)
    mac.init(SecretKeySpec(keyBytes, Algorithm))
    Base64.getEncoder.encodeToString(mac.doFinal(data))

  /** Verify that `expected` matches the HMAC of `data`.
   *  Constant-time comparison to resist timing attacks. */
  def verify(data: Array[Byte], keyBytes: Array[Byte], expected: String): Boolean =
    val computed = sign(data, keyBytes)
    computed.length == expected.length &&
      (computed zip expected).foldLeft(0) { case (acc, (a, b)) => acc | (a ^ b) } == 0

  /** Attach an HMAC header to an envelope given the serialised payload bytes. */
  def attachHmac(env: WireEnvelope, payloadBytes: Array[Byte], keyBytes: Array[Byte]): WireEnvelope =
    val hmac = sign(payloadBytes, keyBytes)
    env.copy(
      flags   = env.flags + "hmac",
      headers = env.headers + ("hmac-sha256" -> hmac),
    )

  /** Verify the HMAC header of an envelope against `payloadBytes`.
   *  Returns `Left` if the flag/header is absent or the signature is invalid. */
  def verifyEnvelope(
    env:          WireEnvelope,
    payloadBytes: Array[Byte],
    keyBytes:     Array[Byte],
  ): Either[WireDecodeError, Unit] =
    if !env.flags.contains("hmac") then
      Left(WireDecodeError.MalformedInput("HMAC flag required but not present in envelope"))
    else
      env.headers.get("hmac-sha256") match
        case None =>
          Left(WireDecodeError.MalformedInput("hmac-sha256 header missing from envelope"))
        case Some(sig) if verify(payloadBytes, keyBytes, sig) =>
          Right(())
        case _ =>
          Left(WireDecodeError.MalformedInput("HMAC-SHA256 signature verification failed"))
