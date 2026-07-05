package scalascript.crypto

/** Portable **Noise Protocol** `Noise_XX_25519_ChaChaPoly_SHA256` handshake, over the from-scratch
 *  [[X25519]] + [[ChaCha20Poly1305]] + [[HkdfSha256]] + [[Sha256]] — pure Scala, identical on JVM and
 *  Scala.js, no platform crypto.
 *
 *  XX is the mutual-authentication interactive pattern (both sides learn each other's static key):
 *  {{{ -> e ; <- e, ee, s, es ; -> s, se }}}
 *  After the three messages both parties call [[HandshakeState.split]] to get the two transport
 *  [[CipherState]]s. Ephemeral keys are supplied by the caller (`useEphemeral`) so the portable layer
 *  stays free of any RNG dependency; a real deployment passes 32 random bytes. */
object NoiseXX:

  private val ProtocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
  private val HashLen      = 32
  private val TagLen       = 16
  private val empty        = Array.emptyByteArray

  final case class KeyPair(priv: Array[Byte], pub: Array[Byte])
  def keyPair(priv: Array[Byte]): KeyPair = KeyPair(priv, X25519.derivePublicKey(priv))

  /** Nonce-versioned AEAD key (Noise §5.1). A `null` key means "no key set". */
  final class CipherState(var k: Array[Byte], var n: Long):
    def hasKey: Boolean = k != null
    private def nonce: Array[Byte] =
      val b = new Array[Byte](12); var v = n; var i = 4
      while i < 12 do { b(i) = (v & 0xff).toByte; v >>>= 8; i += 1 }   // 32 zero bits ‖ LE64(n)
      b
    def encryptWithAd(ad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
      if !hasKey then plaintext
      else { val (ct, tag) = ChaCha20Poly1305.seal(k, nonce, plaintext, ad); n += 1; ct ++ tag }
    def decryptWithAd(ad: Array[Byte], ciphertext: Array[Byte]): Array[Byte] =
      if !hasKey then ciphertext
      else
        val ct  = java.util.Arrays.copyOfRange(ciphertext, 0, ciphertext.length - TagLen)
        val tag = java.util.Arrays.copyOfRange(ciphertext, ciphertext.length - TagLen, ciphertext.length)
        val pt  = ChaCha20Poly1305.open(k, nonce, ct, tag, ad).getOrElse(throw new IllegalStateException("Noise: AEAD auth failed"))
        n += 1; pt

  /** Chaining key + transcript hash + the current [[CipherState]] (Noise §5.2). */
  private final class SymmetricState:
    // initializeSymmetric(protocolName): protocol name ≤ HASHLEN → zero-padded, else hashed.
    private val pn = ProtocolName.getBytes("UTF-8")
    var h:  Array[Byte] = if pn.length <= HashLen then pn ++ new Array[Byte](HashLen - pn.length) else Sha256.digest(pn)
    var ck: Array[Byte] = h
    val cs = new CipherState(null, 0)
    def mixHash(data: Array[Byte]): Unit = h = Sha256.digest(h ++ data)
    def mixKey(input: Array[Byte]): Unit =
      val out = HkdfSha256.derive(ck, input, empty, 2 * HashLen)     // Noise HKDF(ck, ikm, 2)
      ck   = java.util.Arrays.copyOfRange(out, 0, HashLen)
      cs.k = java.util.Arrays.copyOfRange(out, HashLen, 2 * HashLen)
      cs.n = 0
    def encryptAndHash(pt: Array[Byte]): Array[Byte] = { val ct = cs.encryptWithAd(h, pt); mixHash(ct); ct }
    def decryptAndHash(ct: Array[Byte]): Array[Byte] = { val pt = cs.decryptWithAd(h, ct); mixHash(ct); pt }
    def split(): (CipherState, CipherState) =
      val out = HkdfSha256.derive(ck, empty, empty, 2 * HashLen)
      (new CipherState(java.util.Arrays.copyOfRange(out, 0, HashLen), 0),
       new CipherState(java.util.Arrays.copyOfRange(out, HashLen, 2 * HashLen), 0))

  /** One party's handshake state. `s` is the local static key pair. */
  final class HandshakeState(val initiator: Boolean, s: KeyPair):
    private val sym = new SymmetricState()
    private var e: KeyPair       = null
    private var rs: Array[Byte]  = null
    private var re: Array[Byte]  = null
    sym.mixHash(empty)                                   // XX has an empty prologue and no pre-messages

    /** The remote party's static public key, learned during the handshake (XX mutual auth). */
    def remoteStatic: Array[Byte] = rs

    /** Supply the ephemeral key for the next `e` token (the caller owns the RNG). */
    def useEphemeral(priv: Array[Byte]): Unit = e = keyPair(priv)

    private def dh(tok: String): Array[Byte] = tok match
      case "ee" => X25519.sharedSecret(e.priv, re)
      case "es" => if initiator then X25519.sharedSecret(e.priv, rs) else X25519.sharedSecret(s.priv, re)
      case "se" => if initiator then X25519.sharedSecret(s.priv, re) else X25519.sharedSecret(e.priv, rs)

    def writeMessage(tokens: Seq[String], payload: Array[Byte]): Array[Byte] =
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      for tok <- tokens do tok match
        case "e"                  => buf ++= e.pub; sym.mixHash(e.pub)
        case "s"                  => buf ++= sym.encryptAndHash(s.pub)
        case t @ ("ee"|"es"|"se") => sym.mixKey(dh(t))
      buf ++= sym.encryptAndHash(payload)
      buf.toArray

    def readMessage(tokens: Seq[String], message: Array[Byte]): Array[Byte] =
      var i = 0
      for tok <- tokens do tok match
        case "e" => re = java.util.Arrays.copyOfRange(message, i, i + 32); i += 32; sym.mixHash(re)
        case "s" =>
          val len = if sym.cs.hasKey then 32 + TagLen else 32
          rs = sym.decryptAndHash(java.util.Arrays.copyOfRange(message, i, i + len)); i += len
        case t @ ("ee"|"es"|"se") => sym.mixKey(dh(t))
      sym.decryptAndHash(java.util.Arrays.copyOfRange(message, i, message.length))

    /** After the final handshake message, derive the two transport cipher states `(c→, c←)`. */
    def split(): (CipherState, CipherState) = sym.split()

  /** The XX message patterns, in order. */
  val Msg1: Seq[String] = Seq("e")
  val Msg2: Seq[String] = Seq("e", "ee", "s", "es")
  val Msg3: Seq[String] = Seq("s", "se")
