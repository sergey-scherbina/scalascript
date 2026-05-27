package scalascript.blockchain.cosmos

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.{SHA256Digest, RIPEMD160Digest}
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters, Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator, Ed25519Signer}
import org.bouncycastle.math.ec.FixedPointCombMultiplier

/** Cosmos cryptographic primitives.
 *
 *  - secp256k1 ECDSA with RFC-6979 deterministic k (sign + verify), DER-encoded
 *  - ed25519 signing via BouncyCastle Ed25519Signer (64-byte raw signature)
 *  - SHA256, RIPEMD160, hash160 = ripemd160(sha256(x)) */
object CosmosCrypto:

  // ── secp256k1 domain ───────────────────────────────────────────────────────

  private val params = SECNamedCurves.getByName("secp256k1")
  private val domain = new ECDomainParameters(
    params.getCurve, params.getG, params.getN, params.getH)
  private val curveN = domain.getN
  private val halfN  = curveN.shiftRight(1)
  private val curveG = domain.getG

  // ── secp256k1 sign / verify ────────────────────────────────────────────────

  /** Sign a 32-byte hash with secp256k1. Returns DER-encoded signature.
   *  Uses RFC-6979 deterministic k. */
  def sign(privateKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    require(hash.length == 32, s"hash must be 32 bytes, got ${hash.length}")
    val d = new BigInteger(1, privateKey)
    require(d.signum > 0 && d.compareTo(curveN) < 0, "secp256k1 private key out of range")
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r0, s0) = signer.generateSignature(hash)
    val s = if s0.compareTo(halfN) > 0 then curveN.subtract(s0) else s0
    encodeDer(r0, s)

  /** Verify a DER-encoded secp256k1 ECDSA signature.
   *  `pubKey` may be 33-byte compressed or 65-byte uncompressed. */
  def verify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    require(hash.length == 32, s"hash must be 32 bytes, got ${hash.length}")
    try
      val (r, s) = decodeDer(sig)
      val point  = domain.getCurve.decodePoint(pubKey match
        case b if b.length == 33 => b
        case b if b.length == 64 => Array[Byte](0x04.toByte) ++ b
        case b if b.length == 65 => b
        case b => throw new IllegalArgumentException(s"Invalid pubkey length: ${b.length}")
      )
      val v = new ECDSASigner()
      v.init(false, new ECPublicKeyParameters(point, domain))
      v.verifySignature(hash, r, s)
    catch case _: Exception => false

  /** Derive a 33-byte compressed secp256k1 public key from a 32-byte private key. */
  def deriveCompressedPublicKey(privateKey: Array[Byte]): Array[Byte] =
    val d     = new BigInteger(1, privateKey)
    val point = new FixedPointCombMultiplier().multiply(curveG, d).normalize()
    val x     = toUnsigned32(point.getAffineXCoord.toBigInteger)
    val pref  = if point.getAffineYCoord.toBigInteger.testBit(0) then 0x03.toByte else 0x02.toByte
    Array(pref) ++ x

  // ── ed25519 sign / verify ──────────────────────────────────────────────────

  /** Sign `msg` (arbitrary bytes) with an Ed25519 private key.
   *  `privateKey` may be 32 bytes (seed) or 64 bytes (expanded).
   *  Returns a 64-byte raw signature. */
  def signEd25519(privateKey: Array[Byte], msg: Array[Byte]): Array[Byte] =
    val priv = new Ed25519PrivateKeyParameters(privateKey.take(32))
    val signer = new Ed25519Signer()
    signer.init(true, priv)
    signer.update(msg, 0, msg.length)
    signer.generateSignature()

  /** Verify an Ed25519 signature.
   *  `pubKey` must be 32 bytes. `sig` must be 64 bytes. */
  def verifyEd25519(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    try
      require(pubKey.length == 32, s"Ed25519 pubkey must be 32 bytes, got ${pubKey.length}")
      require(sig.length == 64, s"Ed25519 signature must be 64 bytes, got ${sig.length}")
      val pub    = new Ed25519PublicKeyParameters(pubKey)
      val signer = new Ed25519Signer()
      signer.init(false, pub)
      signer.update(msg, 0, msg.length)
      signer.verifySignature(sig)
    catch case _: Exception => false

  // ── Hash utilities ─────────────────────────────────────────────────────────

  def sha256(data: Array[Byte]): Array[Byte] =
    val d = new SHA256Digest()
    d.update(data, 0, data.length)
    val out = new Array[Byte](32)
    d.doFinal(out, 0)
    out

  def ripemd160(data: Array[Byte]): Array[Byte] =
    val d = new RIPEMD160Digest()
    d.update(data, 0, data.length)
    val out = new Array[Byte](20)
    d.doFinal(out, 0)
    out

  /** RIPEMD160(SHA256(data)) — the standard Cosmos/Bitcoin key hash. */
  def hash160(data: Array[Byte]): Array[Byte] = ripemd160(sha256(data))

  // ── DER helpers ────────────────────────────────────────────────────────────

  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] =
    val rb   = r.toByteArray
    val sb   = s.toByteArray
    val body = Array[Byte](0x02.toByte) ++ Array(rb.length.toByte) ++ rb ++
               Array[Byte](0x02.toByte) ++ Array(sb.length.toByte) ++ sb
    Array[Byte](0x30.toByte, body.length.toByte) ++ body

  def decodeDer(sig: Array[Byte]): (BigInteger, BigInteger) =
    require(sig.length >= 8 && sig(0) == 0x30.toByte, "Not a DER sequence")
    var pos = 2
    require(sig(pos) == 0x02.toByte, "Expected INTEGER for r")
    val rLen = sig(pos + 1) & 0xff
    val r    = new BigInteger(1, sig.slice(pos + 2, pos + 2 + rLen))
    pos += 2 + rLen
    require(sig(pos) == 0x02.toByte, "Expected INTEGER for s")
    val sLen = sig(pos + 1) & 0xff
    val s    = new BigInteger(1, sig.slice(pos + 2, pos + 2 + sLen))
    (r, s)

  private def toUnsigned32(n: BigInteger): Array[Byte] =
    val raw = n.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val padded = new Array[Byte](32)
      System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length)
      padded
    else throw new IllegalArgumentException(s"BigInteger too large: ${raw.length} bytes")
