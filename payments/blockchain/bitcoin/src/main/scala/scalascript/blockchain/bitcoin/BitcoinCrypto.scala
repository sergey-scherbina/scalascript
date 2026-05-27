package scalascript.blockchain.bitcoin

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.{SHA256Digest, RIPEMD160Digest}
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECPoint, FixedPointCombMultiplier}

/** Bitcoin cryptographic primitives:
 *
 *  - secp256k1 ECDSA with RFC-6979 deterministic k (sign + verify),
 *    DER-encoded signature output
 *  - Sighash variants: SIGHASH_ALL, SIGHASH_NONE, SIGHASH_SINGLE,
 *    ANYONECANPAY modifier; SegWit BIP-143 sighash for P2WPKH inputs
 *  - Hash utilities: SHA256, hash160 (SHA256 then RIPEMD160),
 *    double-SHA256 (hash256), tagged hash (BIP-340)
 *  - Taproot BIP-341/BIP-340: Schnorr signing via secp256k1,
 *    tapTweakHash, tweakedKey */
object BitcoinCrypto:

  // ── secp256k1 domain ────────────────────────────────────────────────────────

  private val params = SECNamedCurves.getByName("secp256k1")
  private val domain = new ECDomainParameters(
    params.getCurve, params.getG, params.getN, params.getH)
  private val curveN  = domain.getN
  private val halfN   = curveN.shiftRight(1)
  private val curveG  = domain.getG

  // ── Sighash type constants ──────────────────────────────────────────────────

  val SIGHASH_ALL         = 0x01
  val SIGHASH_NONE        = 0x02
  val SIGHASH_SINGLE      = 0x03
  val SIGHASH_ANYONECANPAY = 0x80

  // ── ECDSA sign / verify ─────────────────────────────────────────────────────

  /** Sign a 32-byte hash with a secp256k1 private key.
   *  Returns a DER-encoded signature (without sighash type suffix).
   *  Uses RFC-6979 deterministic k via BouncyCastle `HMacDSAKCalculator`. */
  def sign(privateKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    require(hash.length == 32, s"Bitcoin sign: hash must be 32 bytes, got ${hash.length}")
    val d      = new BigInteger(1, privateKey)
    require(d.signum > 0 && d.compareTo(curveN) < 0, "secp256k1 private key out of range")
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r0, s0) = signer.generateSignature(hash)
    // Normalise s to low half (BIP-62 low-s rule)
    val s = if s0.compareTo(halfN) > 0 then curveN.subtract(s0) else s0
    encodeDer(r0, s)

  /** Verify a DER-encoded secp256k1 ECDSA signature.
   *  `pubKey` may be 33-byte compressed or 65-byte uncompressed. */
  def verify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    require(hash.length == 32, s"Bitcoin verify: hash must be 32 bytes, got ${hash.length}")
    try
      val (r, s) = decodeDer(sig)
      val point  = decodePublicKey(pubKey)
      val signer = new ECDSASigner()
      signer.init(false, new ECPublicKeyParameters(point, domain))
      signer.verifySignature(hash, r, s)
    catch case _: Exception => false

  // ── DER encoding ────────────────────────────────────────────────────────────

  /** Encode (r, s) as DER-encoded SEQUENCE { INTEGER r, INTEGER s }. */
  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] =
    val rb = derInt(r)
    val sb = derInt(s)
    val body = Array[Byte](0x02.toByte) ++ Array(rb.length.toByte) ++ rb ++
               Array[Byte](0x02.toByte) ++ Array(sb.length.toByte) ++ sb
    Array[Byte](0x30.toByte, body.length.toByte) ++ body

  def decodeDer(sig: Array[Byte]): (BigInteger, BigInteger) =
    require(sig.length >= 8 && sig(0) == 0x30.toByte, "Not a DER sequence")
    var pos = 2  // skip tag+length
    require(sig(pos) == 0x02.toByte, "Expected INTEGER for r")
    val rLen = sig(pos + 1) & 0xff
    val r    = new BigInteger(1, sig.slice(pos + 2, pos + 2 + rLen))
    pos += 2 + rLen
    require(sig(pos) == 0x02.toByte, "Expected INTEGER for s")
    val sLen = sig(pos + 1) & 0xff
    val s    = new BigInteger(1, sig.slice(pos + 2, pos + 2 + sLen))
    (r, s)

  // positive minimal-length DER integer encoding (with leading 0x00 if high bit set)
  private def derInt(n: BigInteger): Array[Byte] =
    val raw = n.toByteArray
    // BigInteger.toByteArray already adds a leading 0x00 when needed for sign; use as-is
    raw

  // ── Public key derivation ───────────────────────────────────────────────────

  /** Derive a 33-byte compressed public key from a 32-byte private key. */
  def deriveCompressedPublicKey(privateKey: Array[Byte]): Array[Byte] =
    val d     = new BigInteger(1, privateKey)
    val point = new FixedPointCombMultiplier().multiply(curveG, d).normalize()
    encodeCompressed(point)

  /** Derive the x-only (32-byte) public key for Taproot / Schnorr. */
  def deriveXonlyPublicKey(privateKey: Array[Byte]): Array[Byte] =
    val compressed = deriveCompressedPublicKey(privateKey)
    compressed.drop(1)  // drop 0x02 / 0x03 prefix

  // ── Hash utilities ──────────────────────────────────────────────────────────

  /** SHA256(data) */
  def sha256(data: Array[Byte]): Array[Byte] =
    val d = new SHA256Digest()
    d.update(data, 0, data.length)
    val out = new Array[Byte](32)
    d.doFinal(out, 0)
    out

  /** SHA256(SHA256(data)) — Bitcoin's hash256 / double-SHA256 */
  def hash256(data: Array[Byte]): Array[Byte] = sha256(sha256(data))

  /** RIPEMD160(SHA256(data)) — Bitcoin's hash160 */
  def hash160(data: Array[Byte]): Array[Byte] =
    val sha = sha256(data)
    val r   = new RIPEMD160Digest()
    r.update(sha, 0, sha.length)
    val out = new Array[Byte](20)
    r.doFinal(out, 0)
    out

  /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data) */
  def taggedHash(tag: String, data: Array[Byte]): Array[Byte] =
    val tagHash = sha256(tag.getBytes("UTF-8"))
    sha256(tagHash ++ tagHash ++ data)

  // ── BIP-143 SegWit sighash for P2WPKH ─────────────────────────────────────

  /** Compute the BIP-143 sighash for a P2WPKH input.
   *
   *  For P2WPKH the scriptCode is: `OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG`
   *  which is: `76 a9 14 <hash160(pubkey)> 88 ac`
   *
   *  @param txVersion     4-byte little-endian tx version
   *  @param hashPrevouts  double-SHA256 of all outpoints (32 bytes) or zeros for ANYONECANPAY
   *  @param hashSequence  double-SHA256 of all sequences (32 bytes) or zeros
   *  @param outpointTxid  32-byte txid (internal byte order, i.e. reversed from display)
   *  @param outpointVout  4-byte little-endian vout
   *  @param pubKeyHash    20-byte hash160 of the input's public key
   *  @param value         8-byte little-endian satoshi value of the input's UTXO
   *  @param sequence      4-byte little-endian nSequence of this input
   *  @param hashOutputs   double-SHA256 of all outputs (32 bytes), zeros for NONE, or single output for SINGLE
   *  @param locktime      4-byte little-endian nLocktime
   *  @param sighashType   4-byte little-endian sighash type */
  def bip143Sighash(
    txVersion:    Array[Byte],
    hashPrevouts: Array[Byte],
    hashSequence: Array[Byte],
    outpointTxid: Array[Byte],
    outpointVout: Array[Byte],
    pubKeyHash:   Array[Byte],
    value:        Array[Byte],
    sequence:     Array[Byte],
    hashOutputs:  Array[Byte],
    locktime:     Array[Byte],
    sighashType:  Array[Byte],
  ): Array[Byte] =
    val scriptCode = p2wpkhScriptCode(pubKeyHash)
    val preimage =
      txVersion ++
      hashPrevouts ++
      hashSequence ++
      outpointTxid ++
      outpointVout ++
      scriptCode ++
      value ++
      sequence ++
      hashOutputs ++
      locktime ++
      sighashType
    hash256(preimage)

  /** Build the BIP-143 scriptCode for P2WPKH: `OP_DUP OP_HASH160 <hash160> OP_EQUALVERIFY OP_CHECKSIG` */
  def p2wpkhScriptCode(hash160Bytes: Array[Byte]): Array[Byte] =
    require(hash160Bytes.length == 20, "P2WPKH hash160 must be 20 bytes")
    // varint-length-prefixed for BIP-143
    val script = Array[Byte](0x76.toByte, 0xa9.toByte, 0x14.toByte) ++
                 hash160Bytes ++
                 Array[Byte](0x88.toByte, 0xac.toByte)
    // Prefix with compact size (always 0x19 = 25 here)
    Array[Byte](0x19.toByte) ++ script

  // ── Taproot BIP-341 / BIP-340 ──────────────────────────────────────────────

  /** Compute the tap-tweak commitment: `taggedHash("TapTweak", <x-only-pubkey> || <merkle-root>)`.
   *  For key-path spending with no scripts pass an empty merkle root. */
  def tapTweakHash(xonlyPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    require(xonlyPubKey.length == 32, "x-only pubkey must be 32 bytes")
    taggedHash("TapTweak", xonlyPubKey ++ merkleRoot)

  /** Tweak an internal public key for Taproot key-path spending.
   *
   *  Per BIP-341: `P + t*G` where `t = tapTweakHash(lift_x(P), merkleRoot)` as a scalar.
   *  Returns the 33-byte compressed tweaked key. */
  def tweakedKey(internalPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    val xonly   = if internalPubKey.length == 33 then internalPubKey.drop(1) else internalPubKey
    require(xonly.length == 32, "Internal pubkey must be 32 or 33 bytes")
    val tweak   = tapTweakHash(xonly, merkleRoot)
    val t       = new BigInteger(1, tweak)
    require(t.compareTo(curveN) < 0, "Tweak scalar out of range")
    // lift_x: the even-y point with the given x coordinate
    val liftedPoint = liftX(xonly)
    val tweakPoint  = curveG.multiply(t).normalize()
    val resultPoint = liftedPoint.add(tweakPoint).normalize()
    encodeCompressed(resultPoint)

  /** Tweaked private key for Taproot key-path spending.
   *
   *  Per BIP-341: if P = d*G has odd y, use -d (curveN - d). Then tpriv = d' + t. */
  def tweakedPrivateKey(privateKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    val d0      = new BigInteger(1, privateKey)
    val pubPoint = new FixedPointCombMultiplier().multiply(curveG, d0).normalize()
    // Negate if y is odd (BIP-340 convention)
    val d = if pubPoint.getAffineYCoord.toBigInteger.testBit(0) then curveN.subtract(d0) else d0
    val xonly   = toUnsigned32(pubPoint.getAffineXCoord.toBigInteger)
    val tweak   = tapTweakHash(xonly, merkleRoot)
    val t       = new BigInteger(1, tweak)
    val tpriv   = d.add(t).mod(curveN)
    toUnsigned32(tpriv)

  /** BIP-340 Schnorr signing (for Taproot key-path spends).
   *
   *  `hash` must be exactly 32 bytes (the sighash from BIP-341).
   *  Returns a 64-byte Schnorr signature `r || s`. */
  def schnorrSign(privateKey: Array[Byte], hash: Array[Byte], auxRand: Array[Byte] = Array.fill(32)(0.toByte)): Array[Byte] =
    require(hash.length == 32, "Schnorr sighash must be 32 bytes")
    require(auxRand.length == 32, "auxRand must be 32 bytes")
    val d0  = new BigInteger(1, privateKey)
    require(d0.signum > 0 && d0.compareTo(curveN) < 0, "Schnorr private key out of range")
    // Ensure d has even Y (negate if needed)
    val P   = new FixedPointCombMultiplier().multiply(curveG, d0).normalize()
    val d   = if P.getAffineYCoord.toBigInteger.testBit(0) then curveN.subtract(d0) else d0
    val px  = toUnsigned32(P.getAffineXCoord.toBigInteger)
    // BIP-340: t = xor(bytes(d), hashBIP340Aux(a))
    val t   = xorBytes(toUnsigned32(d), taggedHash("BIP0340/aux", auxRand))
    // k0 = int(hashBIP340Nonce(t || bytes(P) || msg)) mod n
    val k0  = new BigInteger(1, taggedHash("BIP0340/nonce", t ++ px ++ hash)).mod(curveN)
    require(k0.signum != 0, "Schnorr nonce is zero — bad key/hash combo")
    val R   = new FixedPointCombMultiplier().multiply(curveG, k0).normalize()
    val k   = if R.getAffineYCoord.toBigInteger.testBit(0) then curveN.subtract(k0) else k0
    val rx  = toUnsigned32(R.getAffineXCoord.toBigInteger)
    // e = int(hashBIP340Challenge(bytes(R) || bytes(P) || msg)) mod n
    val e   = new BigInteger(1, taggedHash("BIP0340/challenge", rx ++ px ++ hash)).mod(curveN)
    val sig = k.add(e.multiply(d)).mod(curveN)
    rx ++ toUnsigned32(sig)

  /** BIP-340 Schnorr verification. `pubKey` is a 32-byte x-only key. */
  def schnorrVerify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    require(pubKey.length == 32, "Schnorr pubkey must be 32 bytes")
    if hash.length != 32 || sig.length != 64 then return false
    try
      val rx = new BigInteger(1, sig.slice(0, 32))
      val s  = new BigInteger(1, sig.slice(32, 64))
      if rx.compareTo(domain.getCurve.getField.getCharacteristic) >= 0 then return false
      if s.compareTo(curveN) >= 0 then return false
      val P  = liftX(pubKey)
      val e  = new BigInteger(1, taggedHash("BIP0340/challenge",
        sig.slice(0, 32) ++ pubKey ++ hash)).mod(curveN)
      val R  = curveG.multiply(s).subtract(P.multiply(e)).normalize()
      if R.isInfinity then return false
      if R.getAffineYCoord.toBigInteger.testBit(0) then return false
      R.getAffineXCoord.toBigInteger.compareTo(rx) == 0
    catch case _: Exception => false

  // ── BIP-341 sighash for Taproot key-path spending ──────────────────────────

  /** Compute the BIP-341 sighash for Taproot key-path or script-path spending.
   *
   *  This is the simplified form: `taggedHash("TapSighash", 0x00 || sigMsg)` where
   *  sigMsg is assembled from transaction fields per BIP-341 §Common Signature Message.
   *
   *  For a minimal implementation the caller pre-assembles the sigMsg bytes. */
  def tapSighash(sigMsg: Array[Byte]): Array[Byte] =
    taggedHash("TapSighash", Array[Byte](0x00.toByte) ++ sigMsg)

  // ── internals ──────────────────────────────────────────────────────────────

  private def encodeCompressed(point: ECPoint): Array[Byte] =
    val x   = toUnsigned32(point.getAffineXCoord.toBigInteger)
    val y   = point.getAffineYCoord.toBigInteger
    val pref = if y.testBit(0) then 0x03.toByte else 0x02.toByte
    Array(pref) ++ x

  private def decodePublicKey(bytes: Array[Byte]): ECPoint =
    val withPrefix = bytes.length match
      case 33 => bytes
      case 64 => Array[Byte](0x04.toByte) ++ bytes
      case 65 if bytes(0) == 0x04 => bytes
      case _  => throw new IllegalArgumentException(s"Invalid pubkey length: ${bytes.length}")
    domain.getCurve.decodePoint(withPrefix)

  /** Lift an x-coordinate to the even-y point on secp256k1. */
  private def liftX(xBytes: Array[Byte]): ECPoint =
    val x     = new BigInteger(1, xBytes)
    val curve = domain.getCurve
    val p     = curve.getField.getCharacteristic
    val ySquared = x.modPow(BigInteger.valueOf(3), p)
      .add(BigInteger.valueOf(7)).mod(p)
    val y0 = ySquared.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
    val y  = if y0.testBit(0) then p.subtract(y0) else y0
    curve.createPoint(x, y)

  private def xorBytes(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    a.zip(b).map { case (x, y) => (x ^ y).toByte }

  private[bitcoin] def toUnsigned32(n: BigInteger): Array[Byte] =
    val raw = n.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val padded = new Array[Byte](32)
      System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length)
      padded
    else
      throw new IllegalArgumentException(s"BigInteger too large for 32-byte unsigned encoding: ${raw.length} bytes")
