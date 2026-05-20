package scalascript.wallet.walletconnect

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

/** WalletConnect v2 envelope codec.
 *
 *  The relay shuttles base64-encoded byte arrays around — the envelope
 *  inside is the protocol-level cryptogram. Two layouts:
 *
 *  - **Type 0** (sealed message — both sides know the symKey):
 *        [1B type=0] ‖ [12B iv] ‖ [ciphertext+tag]
 *
 *  - **Type 1** (initial session response — proposer learns the
 *    responder's X25519 pubkey from the envelope itself):
 *        [1B type=1] ‖ [32B senderPubKey] ‖ [12B iv] ‖ [ciphertext+tag]
 *
 *  The cipher is ChaCha20-Poly1305 with a 32-byte key and a random
 *  12-byte IV. The tag is 16 bytes, appended to the ciphertext by
 *  JCE's `ChaCha20-Poly1305` provider. */
object WcEnvelope:

  val TagBytes:  Int = 16
  val IvBytes:   Int = 12
  val PubBytes:  Int = 32
  val Type0:     Byte = 0
  val Type1:     Byte = 1

  /** Decoded envelope payload. `senderPublicKey` is set iff the
   *  envelope was Type 1. */
  case class Opened(plaintext: Array[Byte], senderPublicKey: Option[Array[Byte]])

  /** Encrypt + frame a Type 0 envelope (both parties already share
   *  `symKey`). `iv` is generated from a CSPRNG when not supplied. */
  def sealType0(
    symKey:    Array[Byte],
    plaintext: Array[Byte],
    rng:       SecureRandom = new SecureRandom(),
    ivOverride: Option[Array[Byte]] = None,
  ): Array[Byte] =
    val iv = ivOverride.getOrElse {
      val b = new Array[Byte](IvBytes)
      rng.nextBytes(b)
      b
    }
    require(iv.length == IvBytes, s"iv must be $IvBytes B, got ${iv.length}")
    val ct = chachaEncrypt(symKey, iv, plaintext)
    val out = new Array[Byte](1 + IvBytes + ct.length)
    out(0) = Type0
    System.arraycopy(iv, 0, out, 1, IvBytes)
    System.arraycopy(ct, 0, out, 1 + IvBytes, ct.length)
    out

  /** Encrypt + frame a Type 1 envelope (the responder ships its own
   *  X25519 pubkey alongside the encrypted payload — used for the
   *  session-approval response on the pairing topic). */
  def sealType1(
    symKey:           Array[Byte],
    senderPublicKey:  Array[Byte],
    plaintext:        Array[Byte],
    rng:              SecureRandom = new SecureRandom(),
    ivOverride:       Option[Array[Byte]] = None,
  ): Array[Byte] =
    require(senderPublicKey.length == PubBytes, s"sender pub must be $PubBytes B")
    val iv = ivOverride.getOrElse {
      val b = new Array[Byte](IvBytes)
      rng.nextBytes(b)
      b
    }
    require(iv.length == IvBytes, s"iv must be $IvBytes B, got ${iv.length}")
    val ct = chachaEncrypt(symKey, iv, plaintext)
    val out = new Array[Byte](1 + PubBytes + IvBytes + ct.length)
    out(0) = Type1
    System.arraycopy(senderPublicKey, 0, out, 1, PubBytes)
    System.arraycopy(iv,              0, out, 1 + PubBytes, IvBytes)
    System.arraycopy(ct,              0, out, 1 + PubBytes + IvBytes, ct.length)
    out

  /** Decrypt either envelope shape. Picks the layout by the first
   *  byte; throws `IllegalArgumentException` on framing errors and
   *  `javax.crypto.AEADBadTagException` on integrity failures. */
  def open(symKey: Array[Byte], envelope: Array[Byte]): Opened =
    require(envelope.nonEmpty, "empty envelope")
    envelope(0) match
      case `Type0` =>
        require(envelope.length >= 1 + IvBytes + TagBytes, "type-0 envelope too short")
        val iv = envelope.slice(1, 1 + IvBytes)
        val ct = envelope.slice(1 + IvBytes, envelope.length)
        Opened(chachaDecrypt(symKey, iv, ct), None)
      case `Type1` =>
        require(envelope.length >= 1 + PubBytes + IvBytes + TagBytes, "type-1 envelope too short")
        val pub = envelope.slice(1, 1 + PubBytes)
        val iv  = envelope.slice(1 + PubBytes, 1 + PubBytes + IvBytes)
        val ct  = envelope.slice(1 + PubBytes + IvBytes, envelope.length)
        Opened(chachaDecrypt(symKey, iv, ct), Some(pub))
      case other =>
        throw new IllegalArgumentException(s"unknown envelope type: ${other & 0xff}")

  /** Base64 helpers — the relay carries envelopes as base64 strings. */
  def encodeBase64(envelope: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(envelope)

  def decodeBase64(s: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(s)

  // ── ChaCha20-Poly1305 ─────────────────────────────────────────────────

  private def chachaEncrypt(symKey: Array[Byte], iv: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    require(symKey.length == 32, s"ChaCha20 key must be 32 B, got ${symKey.length}")
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(symKey, "ChaCha20"), new IvParameterSpec(iv))
    cipher.doFinal(plaintext)

  private def chachaDecrypt(symKey: Array[Byte], iv: Array[Byte], ciphertextAndTag: Array[Byte]): Array[Byte] =
    require(symKey.length == 32, s"ChaCha20 key must be 32 B, got ${symKey.length}")
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symKey, "ChaCha20"), new IvParameterSpec(iv))
    cipher.doFinal(ciphertextAndTag)
