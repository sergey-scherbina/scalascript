package scalascript.crypto.bouncycastle

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECPoint, FixedPointCombMultiplier}

import scalascript.crypto.HashAlgo

/** secp256r1 / P-256 primitives — WebAuthn / passkey signing curve.
 *
 *  Signature encoding: 64 bytes `r(32) || s(32)`. No recovery byte
 *  (WebAuthn ergonomics never use `ecrecover`-style recovery; if
 *  needed for an ERC-4337 smart account, the contract verifies via
 *  the full public key, not via recovery). `s` is low-s normalised. */
private[bouncycastle] object P256:

  private val params = SECNamedCurves.getByName("secp256r1")
  private val domain = new ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)
  private val n      = domain.getN
  private val halfN  = n.shiftRight(1)

  def derivePublic(privKey: Array[Byte]): Array[Byte] =
    val d = new BigInteger(1, privKey)
    require(d.signum > 0 && d.compareTo(n) < 0, "P-256 private key out of range")
    val point = new FixedPointCombMultiplier().multiply(domain.getG, d).normalize()
    encodeUncompressed(point)

  def sign(privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    val digest = hash match
      case HashAlgo.None   => requireLen32(msg)
      case HashAlgo.Sha256 => Hashes.hash(HashAlgo.Sha256, msg)
      case other           => throw new IllegalArgumentException(s"Unsupported hash for P-256: $other")
    val d      = new BigInteger(1, privKey)
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r0, s0) = signer.generateSignature(digest)
    val s             = if s0.compareTo(halfN) > 0 then n.subtract(s0) else s0
    val out           = new Array[Byte](64)
    System.arraycopy(toUnsigned32(r0), 0, out, 0,  32)
    System.arraycopy(toUnsigned32(s),  0, out, 32, 32)
    out

  def verify(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean =
    if sig.length != 64 then return false
    val digest = hash match
      case HashAlgo.None   => requireLen32(msg)
      case HashAlgo.Sha256 => Hashes.hash(HashAlgo.Sha256, msg)
      case _               => return false
    val r = new BigInteger(1, sig.slice(0, 32))
    val s = new BigInteger(1, sig.slice(32, 64))
    val pubPoint = decodePublic(pubKey)
    val signer   = new ECDSASigner()
    signer.init(false, new ECPublicKeyParameters(pubPoint, domain))
    signer.verifySignature(digest, r, s)

  private def requireLen32(b: Array[Byte]): Array[Byte] =
    if b.length == 32 then b
    else throw new IllegalArgumentException(s"msg must be 32 bytes for HashAlgo.None, got ${b.length}")

  private def encodeUncompressed(point: ECPoint): Array[Byte] =
    val normalised = point.normalize()
    toUnsigned32(normalised.getAffineXCoord.toBigInteger) ++
      toUnsigned32(normalised.getAffineYCoord.toBigInteger)

  private def decodePublic(pubKey: Array[Byte]): ECPoint =
    val withPrefix = pubKey.length match
      case 64 => Array[Byte](0x04) ++ pubKey
      case 65 if pubKey(0) == 0x04 => pubKey
      case 33 if pubKey(0) == 0x02 || pubKey(0) == 0x03 => pubKey
      case _ =>
        throw new IllegalArgumentException(s"Invalid P-256 public key length: ${pubKey.length}")
    domain.getCurve.decodePoint(withPrefix)

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
