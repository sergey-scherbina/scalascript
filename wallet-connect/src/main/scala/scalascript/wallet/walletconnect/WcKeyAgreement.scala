package scalascript.wallet.walletconnect

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.{HKDFBytesGenerator, X25519KeyPairGenerator}
import org.bouncycastle.crypto.params.{HKDFParameters, X25519KeyGenerationParameters, X25519PrivateKeyParameters, X25519PublicKeyParameters}

/** WalletConnect v2 key-agreement primitives.
 *
 *  WC sessions are derived from a pair of X25519 keys:
 *
 *      sharedSecret = X25519(self.priv, peer.pub)
 *      symKey       = HKDF-SHA256(ikm = sharedSecret, salt = empty,
 *                                 info = empty, length = 32)
 *      topic        = sha256(symKey)
 *
 *  Pairing topics (initial proposal channel) use a simpler scheme —
 *  the symKey is shipped in the `wc:` URI itself, so both sides
 *  derive the pairing topic from the URI's symKey directly. The
 *  follow-on session key agreement uses X25519 so the wallet and
 *  dApp can establish a fresh symKey independent of any party who
 *  saw the URI. */
object WcKeyAgreement:

  /** A freshly-generated X25519 keypair. Bytes are 32 each. */
  case class Keypair(privateKey: Array[Byte], publicKey: Array[Byte])

  /** Generate a new X25519 keypair via a CSPRNG. */
  def generateKeypair(rng: SecureRandom = new SecureRandom()): Keypair =
    val gen = new X25519KeyPairGenerator()
    gen.init(new X25519KeyGenerationParameters(rng))
    val kp     = gen.generateKeyPair()
    val priv32 = kp.getPrivate.asInstanceOf[X25519PrivateKeyParameters].getEncoded
    val pub32  = kp.getPublic.asInstanceOf[X25519PublicKeyParameters].getEncoded
    Keypair(priv32, pub32)

  /** Reconstruct the public half of an X25519 keypair from its
   *  private bytes — needed when we persist sessions across restarts
   *  but only stored the private key. */
  def publicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    require(priv32.length == 32, s"X25519 private key must be 32 B, got ${priv32.length}")
    new X25519PrivateKeyParameters(priv32, 0).generatePublicKey().getEncoded

  /** Diffie-Hellman shared secret. The 32-byte raw output of X25519;
   *  feed it to `deriveSymKey` rather than using it directly. */
  def deriveSharedSecret(selfPriv32: Array[Byte], peerPub32: Array[Byte]): Array[Byte] =
    require(selfPriv32.length == 32, s"X25519 priv must be 32 B, got ${selfPriv32.length}")
    require(peerPub32.length  == 32, s"X25519 pub must be 32 B, got ${peerPub32.length}")
    val agreement = new X25519Agreement()
    agreement.init(new X25519PrivateKeyParameters(selfPriv32, 0))
    val out = new Array[Byte](32)
    agreement.calculateAgreement(new X25519PublicKeyParameters(peerPub32, 0), out, 0)
    out

  /** Derive the WC symKey from an ECDH shared secret.
   *  HKDF-SHA256 with empty salt + empty info, 32-byte output —
   *  the exact parameters @walletconnect/utils ships. */
  def deriveSymKey(sharedSecret: Array[Byte]): Array[Byte] =
    val gen = new HKDFBytesGenerator(new SHA256Digest())
    gen.init(new HKDFParameters(sharedSecret, Array.emptyByteArray, Array.emptyByteArray))
    val out = new Array[Byte](32)
    gen.generateBytes(out, 0, 32)
    out

  /** WC topic identifier — `sha256(symKey)`. Used as the relay
   *  subscription key for both pairings and sessions. */
  def topicFromSymKey(symKey: Array[Byte]): Array[Byte] =
    val d = new SHA256Digest()
    d.update(symKey, 0, symKey.length)
    val out = new Array[Byte](32)
    d.doFinal(out, 0)
    out

  /** Parsed `wc:` pairing URI: topic, symKey, relay protocol.
   *
   *  Example:
   *    `wc:7e8f...@2?relay-protocol=irn&symKey=0a1b...`
   *
   *  - `topic` is hex (64 chars) and equals `sha256(symKey)`.
   *  - `symKey` is hex (64 chars).
   *  - Relay protocol is informational — we always speak `irn`.
   *
   *  Returns `None` on any framing or hex-decoding failure. */
  case class PairingUri(
    topic:         Array[Byte],
    symKey:        Array[Byte],
    relayProtocol: String,
    relayData:     Option[String],
  )

  def parsePairingUri(uri: String): Option[PairingUri] =
    try
      require(uri.startsWith("wc:"), s"not a wc: URI")
      val (path, query) =
        uri.stripPrefix("wc:").split('?').toList match
          case path :: query :: Nil => (path, query)
          case path :: Nil          => (path, "")
          case _                    => throw new IllegalArgumentException("malformed URI")
      val (topicHex, version) = path.split('@').toList match
        case t :: v :: Nil => (t, v)
        case _             => throw new IllegalArgumentException("missing @version")
      require(version == "2", s"only WC v2 supported, got $version")
      val params = query.split('&').toSeq.flatMap { kv =>
        kv.split('=').toList match
          case k :: v :: Nil => Some(k -> v)
          case _             => None
      }.toMap
      val symKeyHex = params.getOrElse("symKey",
        throw new IllegalArgumentException("missing symKey"))
      val topic  = hexDecode(topicHex)
      val symKey = hexDecode(symKeyHex)
      require(symKey.length == 32, s"symKey must be 32 B, got ${symKey.length}")
      require(topic.toSeq  == topicFromSymKey(symKey).toSeq,
        "URI topic does not match sha256(symKey)")
      Some(PairingUri(
        topic         = topic,
        symKey        = symKey,
        relayProtocol = params.getOrElse("relay-protocol", "irn"),
        relayData     = params.get("relay-data"),
      ))
    catch case _: Throwable => None

  private[walletconnect] def hexDecode(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    require(clean.length % 2 == 0, s"hex string of odd length: ${clean.length}")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  private[walletconnect] def hexEncode(b: Array[Byte]): String =
    val sb = new java.lang.StringBuilder(b.length * 2)
    var i = 0
    while i < b.length do
      sb.append(f"${b(i) & 0xff}%02x")
      i += 1
    sb.toString
