package scalascript.crypto.bouncycastle

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECAlgorithms, ECCurve, ECPoint, FixedPointCombMultiplier}

import scalascript.crypto.HashAlgo

/** secp256k1 primitives: ECDSA sign/verify with RFC-6979 deterministic
 *  `k`, public-key derivation, and SEC1 §4.1.6 public-key recovery
 *  (ecrecover).
 *
 *  Signature encoding: a 65-byte concatenation `r(32) || s(32) ||
 *  recId(1)` where `recId ∈ {0, 1}`. `s` is always normalised to the
 *  low half of the curve order per BIP-62 / EIP-2. The blockchain-evm
 *  adapter is responsible for translating `recId` into Ethereum's
 *  `v` (27 + recId, or EIP-155 35 + chainId*2 + recId). */
private[bouncycastle] object Secp256k1:

  private val params = SECNamedCurves.getByName("secp256k1")
  private val domain = new ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)
  private val curve: ECCurve = domain.getCurve
  private val n      = domain.getN
  private val halfN  = n.shiftRight(1)
  // secp256k1 field prime (constant; cheaper than reflecting via BC's curve API):
  private val curvePrime = new BigInteger(
    "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
    16,
  )

  /** Derive the uncompressed public key (64 bytes, no 0x04 prefix). */
  def derivePublic(privKey: Array[Byte]): Array[Byte] =
    val d = new BigInteger(1, privKey)
    require(d.signum > 0 && d.compareTo(n) < 0, "secp256k1 private key out of range")
    val point = new FixedPointCombMultiplier().multiply(domain.getG, d).normalize()
    encodeUncompressed(point)

  def sign(privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    val digest = digestFor(msg, hash)
    val d      = new BigInteger(1, privKey)
    require(d.signum > 0 && d.compareTo(n) < 0, "secp256k1 private key out of range")
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r0, s0) = signer.generateSignature(digest)
    val (r, s)        = normaliseLowS(r0, s0)
    val pub           = derivePublic(privKey)
    val recId         = computeRecoveryId(r, s, digest, pub)
    encodeSig(r, s, recId)

  def verify(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean =
    if sig.length != 65 then false
    else
      val digest = digestFor(msg, hash)
      val (r, s, _) = decodeSig(sig)
      val pubPoint  = decodePublic(pubKey)
      val signer    = new ECDSASigner()
      signer.init(false, new ECPublicKeyParameters(pubPoint, domain))
      signer.verifySignature(digest, r, s)

  /** Recover the public key from `sig` over `digest`. Caller supplies
   *  the recovery id explicitly (extracted from the chain's `v` byte).
   *  Returns the 64-byte uncompressed public key (no 0x04 prefix). */
  def recoverPublic(digest: Array[Byte], sig: Array[Byte], recId: Int): Array[Byte] =
    if sig.length < 64 then
      throw new IllegalArgumentException("secp256k1 signature must be at least 64 bytes")
    val r = new BigInteger(1, sig.slice(0, 32))
    val s = new BigInteger(1, sig.slice(32, 64))
    recoverFromRS(digest, r, s, recId) match
      case Some(point) => encodeUncompressed(point)
      case None        =>
        throw new IllegalArgumentException(s"Could not recover public key (recId=$recId)")

  // ── internals ───────────────────────────────────────────────────────────

  private def digestFor(msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    hash match
      case HashAlgo.None      => requireLen32(msg, "msg")
      case HashAlgo.Sha256    => Hashes.hash(HashAlgo.Sha256, msg)
      case HashAlgo.Keccak256 => Hashes.hash(HashAlgo.Keccak256, msg)
      case other              => throw new IllegalArgumentException(s"Unsupported hash for secp256k1: $other")

  private def requireLen32(b: Array[Byte], name: String): Array[Byte] =
    if b.length == 32 then b
    else throw new IllegalArgumentException(s"$name must be 32 bytes for HashAlgo.None, got ${b.length}")

  private def normaliseLowS(r: BigInteger, s: BigInteger): (BigInteger, BigInteger) =
    if s.compareTo(halfN) > 0 then (r, n.subtract(s)) else (r, s)

  private def computeRecoveryId(r: BigInteger, s: BigInteger, digest: Array[Byte], expectedPub: Array[Byte]): Int =
    var i = 0
    while i < 4 do
      recoverFromRS(digest, r, s, i) match
        case Some(point) =>
          val encoded = encodeUncompressed(point)
          if java.util.Arrays.equals(encoded, expectedPub) then return i
        case None => ()
      i += 1
    throw new IllegalStateException("could not compute recovery id — invalid signature?")

  private def recoverFromRS(digest: Array[Byte], r: BigInteger, s: BigInteger, recId: Int): Option[ECPoint] =
    if r.signum <= 0 || s.signum <= 0 then return None
    val j      = recId / 2
    val x      = r.add(BigInteger.valueOf(j.toLong).multiply(n))
    if x.compareTo(curvePrime) >= 0 then return None
    decompressKey(x, (recId & 1) == 1) match
      case None         => None
      case Some(bigR)   =>
        // Check that n*R = O (point at infinity)
        if !bigR.multiply(n).isInfinity then return None
        val e        = new BigInteger(1, digest)
        val rInv     = r.modInverse(n)
        val eInv     = BigInteger.ZERO.subtract(e).mod(n)
        val srInv    = s.multiply(rInv).mod(n)
        val eInvRInv = eInv.multiply(rInv).mod(n)
        val q        = ECAlgorithms.sumOfTwoMultiplies(domain.getG, eInvRInv, bigR, srInv).normalize()
        if q.isInfinity then None else Some(q)

  private def decompressKey(xBN: BigInteger, yBit: Boolean): Option[ECPoint] =
    try
      // Encode the X coordinate as a 33-byte compressed-point header + X bytes.
      val compEnc = new Array[Byte](33)
      compEnc(0) = (if yBit then 0x03 else 0x02).toByte
      val xBytes  = toUnsigned32(xBN)
      System.arraycopy(xBytes, 0, compEnc, 1, 32)
      Some(curve.decodePoint(compEnc))
    catch
      case _: Exception => None

  private def encodeUncompressed(point: ECPoint): Array[Byte] =
    val normalised = point.normalize()
    val x = toUnsigned32(normalised.getAffineXCoord.toBigInteger)
    val y = toUnsigned32(normalised.getAffineYCoord.toBigInteger)
    x ++ y

  private def decodePublic(pubKey: Array[Byte]): ECPoint =
    val withPrefix = pubKey.length match
      case 64 => Array[Byte](0x04) ++ pubKey
      case 65 if pubKey(0) == 0x04 => pubKey
      case 33 if pubKey(0) == 0x02 || pubKey(0) == 0x03 => pubKey
      case _ =>
        throw new IllegalArgumentException(s"Invalid secp256k1 public key length: ${pubKey.length}")
    curve.decodePoint(withPrefix)

  private def encodeSig(r: BigInteger, s: BigInteger, recId: Int): Array[Byte] =
    val out = new Array[Byte](65)
    System.arraycopy(toUnsigned32(r), 0, out, 0, 32)
    System.arraycopy(toUnsigned32(s), 0, out, 32, 32)
    out(64) = recId.toByte
    out

  private def decodeSig(sig: Array[Byte]): (BigInteger, BigInteger, Int) =
    val r = new BigInteger(1, sig.slice(0, 32))
    val s = new BigInteger(1, sig.slice(32, 64))
    val v = if sig.length > 64 then sig(64).toInt & 0xff else 0
    (r, s, v)

  private def toUnsigned32(x: BigInteger): Array[Byte] =
    val raw = x.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val padded = new Array[Byte](32)
      System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length)
      padded
    else
      throw new IllegalArgumentException(s"BigInteger too large for 32-byte unsigned encoding: ${raw.length} bytes")
