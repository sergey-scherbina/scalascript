package scalascript.wallet.walletconnect

import java.nio.charset.StandardCharsets.UTF_8
import org.bouncycastle.crypto.params.{Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters}
import org.bouncycastle.crypto.signers.Ed25519Signer

/** WalletConnect v2 relay JWT auth.
 *
 *  Connecting to `wss://relay.walletconnect.com` requires an EdDSA-
 *  signed JWT carried as a query parameter (`auth=<jwt>`). The
 *  relay reads the JWT's `iss` claim to identify the client and the
 *  signature proves possession of the corresponding ed25519 private
 *  key.
 *
 *  JWT shape:
 *
 *      header   = { "alg": "EdDSA", "typ": "JWT" }
 *      payload  = {
 *        "iss": "did:key:z6Mk...",            // ed25519 did:key
 *        "aud": "wss://relay.walletconnect.com",
 *        "iat": <unix seconds>,
 *        "exp": <unix seconds + ttl>,
 *        "sub": "" (irrelevant to the relay; some impls set the
 *                  pairing topic — we leave it blank by default),
 *      }
 *      signature = Ed25519(b64u(header).b64u(payload))
 *      token     = b64u(header) "." b64u(payload) "." b64u(signature)
 *
 *  `b64u` here is base64url *without* padding (RFC 7515). */
object RelayJwt:

  val DefaultAud:    String = "wss://relay.walletconnect.com"
  val DefaultTtlSec: Long   = 24 * 60 * 60   // 1 day; relay max is 7 days

  /** Sign a relay-auth JWT. The 32-byte ed25519 keypair is the
   *  wallet's stable client identity — it should be persisted (the
   *  relay correlates rate-limits and accounting against it). */
  def sign(
    ed25519Priv: Array[Byte],
    ed25519Pub:  Array[Byte],
    aud:         String   = DefaultAud,
    nowSeconds:  Long     = System.currentTimeMillis() / 1000,
    ttlSeconds:  Long     = DefaultTtlSec,
    subject:     String   = "",
  ): String =
    require(ed25519Priv.length == 32, s"ed25519 priv must be 32 B, got ${ed25519Priv.length}")
    require(ed25519Pub.length  == 32, s"ed25519 pub must be 32 B, got ${ed25519Pub.length}")
    val header  = """{"alg":"EdDSA","typ":"JWT"}"""
    val payload = ujson.Obj(
      "iss" -> ujson.Str(didKey(ed25519Pub)),
      "sub" -> ujson.Str(subject),
      "aud" -> ujson.Str(aud),
      "iat" -> ujson.Num(nowSeconds.toDouble),
      "exp" -> ujson.Num((nowSeconds + ttlSeconds).toDouble),
    ).render()
    val headerB64  = base64UrlNoPad(header.getBytes(UTF_8))
    val payloadB64 = base64UrlNoPad(payload.getBytes(UTF_8))
    val unsigned   = s"$headerB64.$payloadB64"
    val sig        = signEd25519(ed25519Priv, unsigned.getBytes(UTF_8))
    val sigB64     = base64UrlNoPad(sig)
    s"$unsigned.$sigB64"

  /** Encode an ed25519 public key as a `did:key:z6Mk…` identifier.
   *  Per W3C did:key spec: multicodec prefix `0xed01` for ed25519,
   *  the bytes are then base58btc-encoded and prefixed with `z`. */
  def didKey(ed25519Pub: Array[Byte]): String =
    val multicodec = Array(0xed.toByte, 0x01.toByte) ++ ed25519Pub
    s"did:key:z${base58btcEncode(multicodec)}"

  // ── ed25519 + base encodings ──────────────────────────────────────────

  private def signEd25519(priv32: Array[Byte], msg: Array[Byte]): Array[Byte] =
    val signer = new Ed25519Signer()
    signer.init(true, new Ed25519PrivateKeyParameters(priv32, 0))
    signer.update(msg, 0, msg.length)
    signer.generateSignature()

  /** Public-key reconstruction from a 32-byte ed25519 private key.
   *  Useful for callers that store only the private half. */
  def publicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    new Ed25519PrivateKeyParameters(priv32, 0).generatePublicKey().getEncoded

  /** Sanity-check that `signature` covers `unsignedJwt` under
   *  `publicKey`. Used by the relay's JWT verifier in real life;
   *  exposed here for round-trip tests. */
  def verify(unsignedJwt: String, signature: Array[Byte], publicKey: Array[Byte]): Boolean =
    val v = new Ed25519Signer()
    v.init(false, new Ed25519PublicKeyParameters(publicKey, 0))
    val data = unsignedJwt.getBytes(UTF_8)
    v.update(data, 0, data.length)
    v.verifySignature(signature)

  private def base64UrlNoPad(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private val Base58Alphabet: Array[Char] =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray

  /** base58btc encode — identical algorithm to Bitcoin Base58; we
   *  inline a small implementation here to avoid cross-dependencies. */
  private def base58btcEncode(bytes: Array[Byte]): String =
    if bytes.isEmpty then return ""
    val leadingZeros = bytes.takeWhile(_ == 0).length
    val n = BigInt(1, bytes)
    val sb = new java.lang.StringBuilder
    var rem = n
    while rem > 0 do
      val (q, r) = rem /% BigInt(58)
      sb.append(Base58Alphabet(r.toInt))
      rem = q
    var i = 0
    while i < leadingZeros do
      sb.append(Base58Alphabet(0))
      i += 1
    sb.reverse.toString
