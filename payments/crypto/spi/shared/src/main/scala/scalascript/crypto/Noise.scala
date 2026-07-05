package scalascript.crypto

/** Portable **Noise Protocol Framework** handshakes over the from-scratch [[X25519]] +
 *  [[ChaCha20Poly1305]] + [[HkdfSha256]] + [[Sha256]] — pure Scala, identical on JVM and Scala.js, no
 *  platform crypto. A pattern-driven engine ([[CipherState]] / [[SymmetricState]] / [[HandshakeState]]
 *  per Noise §5) with the DH suite `25519`, cipher `ChaChaPoly`, hash `SHA256`.
 *
 *  Built-in [[Pattern]]s: [[NN]] (unauthenticated), [[XX]] (mutual auth, no prior knowledge), [[IK]]
 *  (initiator knows the responder's static up front — WireGuard / Lightning style). Ephemeral keys are
 *  supplied by the caller (`useEphemeral`) so the layer stays RNG-free. */
object Noise:

  final case class KeyPair(priv: Array[Byte], pub: Array[Byte])
  def keyPair(priv: Array[Byte]): KeyPair = KeyPair(priv, X25519.derivePublicKey(priv))

  /** A handshake pattern: the two pre-message token lists (initiator's / responder's) + the message
   *  token lists in order. Tokens: `e s ee es se ss`. */
  final case class Pattern(name: String, preIni: Seq[String], preRes: Seq[String], messages: Seq[Seq[String]])

  val NN: Pattern = Pattern("NN", Nil, Nil,      Seq(Seq("e"), Seq("e", "ee")))
  val XX: Pattern = Pattern("XX", Nil, Nil,      Seq(Seq("e"), Seq("e", "ee", "s", "es"), Seq("s", "se")))
  val IK: Pattern = Pattern("IK", Nil, Seq("s"), Seq(Seq("e", "es", "s", "ss"), Seq("e", "ee", "se")))
  /** One-way "sealed box": anonymous sender → known recipient, a single message. */
  val N:  Pattern = Pattern("N",  Nil, Seq("s"), Seq(Seq("e", "es")))
  /** Interactive, responder pre-known + authenticated; the initiator stays anonymous. */
  val NK: Pattern = Pattern("NK", Nil, Seq("s"), Seq(Seq("e", "es"), Seq("e", "ee")))
  /** Interactive, responder pre-known; the initiator transmits + proves its static (mutual auth). */
  val XK: Pattern = Pattern("XK", Nil, Seq("s"), Seq(Seq("e", "es"), Seq("e", "ee"), Seq("s", "se")))
  /** Interactive, responder transmits its static; anonymous initiator. */
  val NX: Pattern = Pattern("NX", Nil, Nil,      Seq(Seq("e"), Seq("e", "ee", "s", "es")))
  /** Interactive, initiator transmits its static in the last message; anonymous responder. */
  val XN: Pattern = Pattern("XN", Nil, Nil,      Seq(Seq("e"), Seq("e", "ee"), Seq("s", "se")))
  /** Interactive, both statics pre-known (mutual auth, no static transmitted). */
  val KK: Pattern = Pattern("KK", Seq("s"), Seq("s"), Seq(Seq("e", "es", "ss"), Seq("e", "ee", "se")))
  /** Interactive, initiator transmits its static immediately (in the clear); anonymous responder. */
  val IN: Pattern = Pattern("IN", Nil, Nil,      Seq(Seq("e", "s"), Seq("e", "ee", "se")))
  /** Interactive, initiator transmits its static immediately; responder transmits its static (mutual auth). */
  val IX: Pattern = Pattern("IX", Nil, Nil,      Seq(Seq("e", "s"), Seq("e", "ee", "se", "s", "es")))

  private val HashLen = 32
  private val TagLen  = 16
  private val empty   = Array.emptyByteArray

  final class CipherState(var k: Array[Byte], var n: Long):
    def hasKey: Boolean = k != null
    private def nonce: Array[Byte] =
      val b = new Array[Byte](12); var v = n; var i = 4
      while i < 12 do { b(i) = (v & 0xff).toByte; v >>>= 8; i += 1 }
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

  private final class SymmetricState(protocolName: String):
    private val pn = protocolName.getBytes("UTF-8")
    var h:  Array[Byte] = if pn.length <= HashLen then pn ++ new Array[Byte](HashLen - pn.length) else Sha256.digest(pn)
    var ck: Array[Byte] = h
    val cs = new CipherState(null, 0)
    def mixHash(data: Array[Byte]): Unit = h = Sha256.digest(h ++ data)
    def mixKey(input: Array[Byte]): Unit =
      val out = HkdfSha256.derive(ck, input, empty, 2 * HashLen)
      ck   = java.util.Arrays.copyOfRange(out, 0, HashLen)
      cs.k = java.util.Arrays.copyOfRange(out, HashLen, 2 * HashLen)
      cs.n = 0
    def encryptAndHash(pt: Array[Byte]): Array[Byte] = { val ct = cs.encryptWithAd(h, pt); mixHash(ct); ct }
    def decryptAndHash(ct: Array[Byte]): Array[Byte] = { val pt = cs.decryptWithAd(h, ct); mixHash(ct); pt }
    def split(): (CipherState, CipherState) =
      val out = HkdfSha256.derive(ck, empty, empty, 2 * HashLen)
      (new CipherState(java.util.Arrays.copyOfRange(out, 0, HashLen), 0),
       new CipherState(java.util.Arrays.copyOfRange(out, HashLen, 2 * HashLen), 0))

  /** One party's handshake state. `s` is the local static key pair (may be `null` for patterns with no
   *  local static, e.g. the initiator in NN/NK); `rsKnown` is a pre-shared remote static (IK/NK). */
  final class HandshakeState(pattern: Pattern, val initiator: Boolean, s: KeyPair,
                             rsKnown: Array[Byte] = null, prologue: Array[Byte] = Array.emptyByteArray):
    private val sym = new SymmetricState(s"Noise_${pattern.name}_25519_ChaChaPoly_SHA256")
    private var e: KeyPair      = null
    private var rs: Array[Byte] = rsKnown
    private var re: Array[Byte] = null
    private var msgIndex        = 0
    sym.mixHash(prologue)
    // Pre-messages (§7): mix the pre-shared public keys, initiator's then responder's.
    for tok <- pattern.preIni do sym.mixHash(preMessagePub(tok, ownerIsInitiator = true))
    for tok <- pattern.preRes do sym.mixHash(preMessagePub(tok, ownerIsInitiator = false))

    /** The remote party's static public key (set once learned / pre-shared). */
    def remoteStatic: Array[Byte] = rs

    /** Supply the ephemeral key for the next `e` token (the caller owns the RNG). */
    def useEphemeral(priv: Array[Byte]): Unit = e = keyPair(priv)

    private def preMessagePub(token: String, ownerIsInitiator: Boolean): Array[Byte] =
      val mine = ownerIsInitiator == initiator
      token match
        case "s" => if mine then s.pub else rs
        case "e" => if mine then e.pub else re
        case _   => throw new IllegalArgumentException(s"bad pre-message token: $token")

    private def dh(tok: String): Array[Byte] = tok match
      case "ee" => X25519.sharedSecret(e.priv, re)
      case "es" => if initiator then X25519.sharedSecret(e.priv, rs) else X25519.sharedSecret(s.priv, re)
      case "se" => if initiator then X25519.sharedSecret(s.priv, re) else X25519.sharedSecret(e.priv, rs)
      case "ss" => X25519.sharedSecret(s.priv, rs)

    /** Write the next handshake message carrying `payload`; advances the pattern. */
    def writeMessage(payload: Array[Byte]): Array[Byte] =
      val tokens = pattern.messages(msgIndex); msgIndex += 1
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      for tok <- tokens do tok match
        case "e"                       => buf ++= e.pub; sym.mixHash(e.pub)
        case "s"                       => buf ++= sym.encryptAndHash(s.pub)
        case t @ ("ee"|"es"|"se"|"ss") => sym.mixKey(dh(t))
      buf ++= sym.encryptAndHash(payload)
      buf.toArray

    /** Read the next handshake message; returns the decrypted payload; advances the pattern. */
    def readMessage(message: Array[Byte]): Array[Byte] =
      val tokens = pattern.messages(msgIndex); msgIndex += 1
      var i = 0
      for tok <- tokens do tok match
        case "e" => re = java.util.Arrays.copyOfRange(message, i, i + 32); i += 32; sym.mixHash(re)
        case "s" =>
          val len = if sym.cs.hasKey then 32 + TagLen else 32
          rs = sym.decryptAndHash(java.util.Arrays.copyOfRange(message, i, i + len)); i += len
        case t @ ("ee"|"es"|"se"|"ss") => sym.mixKey(dh(t))
      sym.decryptAndHash(java.util.Arrays.copyOfRange(message, i, message.length))

    /** After the final message, the two transport cipher states `(initiator→responder, responder→initiator)`. */
    def split(): (CipherState, CipherState) = sym.split()
