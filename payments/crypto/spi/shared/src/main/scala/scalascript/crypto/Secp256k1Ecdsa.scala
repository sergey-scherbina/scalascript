package scalascript.crypto

import java.math.BigInteger

/** Pure secp256k1 ECDSA — RFC-6979 deterministic nonces (HMAC-SHA256), BIP-62 low-S normalisation and DER
 *  signature encoding, over [[Secp256k1Group]] + [[HmacSha256]]. Identical on JVM + Scala.js, no platform crypto
 *  dependency. Byte-for-byte compatible with the BouncyCastle `ECDSASigner(HMacDSAKCalculator(SHA256))` + low-S
 *  output that the Bitcoin / Cosmos chains previously produced. Correctness-first reference (not constant-time).
 */
object Secp256k1Ecdsa:

  import Secp256k1Group.{N, mulG, mul, add, toAffine, decode, compress, to32}

  private val ZERO = BigInteger.ZERO
  private val halfN = N.shiftRight(1)

  private def nInv(a: BigInteger): BigInteger = a.modPow(N.subtract(BigInteger.valueOf(2)), N)

  /** Sign a 32-byte `hash` with a 32-byte secp256k1 private key. Returns a DER-encoded `(r, s)` with `s`
   *  normalised to the low half (BIP-62), no sighash-type suffix. */
  def sign(privKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    require(hash.length == 32, s"ECDSA hash must be 32 bytes, got ${hash.length}")
    val d = new BigInteger(1, privKey)
    require(d.signum() > 0 && d.compareTo(N) < 0, "secp256k1 private key out of range")
    val z = new BigInteger(1, hash)
    val nonces = rfc6979(d, hash)
    var result: Array[Byte] = null
    while result == null do
      val k = nonces()
      val rPoint = toAffine(mulG(k))
      rPoint match
        case Some((rx, _)) =>
          val r = rx.mod(N)
          if r.signum() != 0 then
            val s0 = nInv(k).multiply(z.add(r.multiply(d))).mod(N)
            if s0.signum() != 0 then
              val s = if s0.compareTo(halfN) > 0 then N.subtract(s0) else s0
              result = encodeDer(r, s)
        case None => ()   // k·G = identity (vanishingly unlikely) → next nonce
    result

  /** Verify a DER-encoded secp256k1 ECDSA signature. Accepts both low-S and high-S signatures (only signing
   *  enforces low-S). `pubKey` is 33-byte compressed or 65-byte uncompressed. */
  def verify(pubKey: Array[Byte], hash: Array[Byte], derSig: Array[Byte]): Boolean =
    if hash.length != 32 then false
    else
      try
        val (r, s) = decodeDer(derSig)
        if r.signum() <= 0 || r.compareTo(N) >= 0 then false
        else if s.signum() <= 0 || s.compareTo(N) >= 0 then false
        else
          decode(pubKey) match
            case None => false
            case Some(q) =>
              val z  = new BigInteger(1, hash)
              val w  = nInv(s)
              val u1 = z.multiply(w).mod(N)
              val u2 = r.multiply(w).mod(N)
              val rp = add(mulG(u1), mul(u2, q))
              toAffine(rp) match
                case None         => false
                case Some((x, _)) => x.mod(N).equals(r)
      catch case _: Exception => false

  /** 33-byte compressed public key from a 32-byte private key. */
  def derivePublicCompressed(privKey: Array[Byte]): Array[Byte] =
    val d = new BigInteger(1, privKey)
    require(d.signum() > 0 && d.compareTo(N) < 0, "secp256k1 private key out of range")
    compress(mulG(d))

  /** 32-byte x-only public key (Taproot / Schnorr) from a private key. */
  def derivePublicXonly(privKey: Array[Byte]): Array[Byte] =
    derivePublicCompressed(privKey).drop(1)

  // ── RFC 6979 deterministic nonce generator ───────────────────────────────────

  /** Returns a generator that yields successive RFC-6979 candidate nonces `k` (HMAC-SHA256, secp256k1). Each
   *  call advances the internal HMAC state; the caller retries until `(r, s)` are both non-zero. */
  private def rfc6979(d: BigInteger, hash: Array[Byte]): () => BigInteger =
    val int2octets  = to32(d)
    val bits2octets = to32(new BigInteger(1, hash).mod(N))
    var v = Array.fill[Byte](32)(0x01.toByte)
    var k = Array.fill[Byte](32)(0x00.toByte)
    k = HmacSha256.mac(k, v ++ Array[Byte](0x00) ++ int2octets ++ bits2octets)
    v = HmacSha256.mac(k, v)
    k = HmacSha256.mac(k, v ++ Array[Byte](0x01) ++ int2octets ++ bits2octets)
    v = HmacSha256.mac(k, v)
    var first = true
    () =>
      if !first then
        k = HmacSha256.mac(k, v ++ Array[Byte](0x00))
        v = HmacSha256.mac(k, v)
      first = false
      // qlen = 256 → exactly one HMAC block (32 bytes) is bits2int(T)
      var candidate = ZERO
      var found = false
      while !found do
        v = HmacSha256.mac(k, v)
        candidate = new BigInteger(1, v)
        if candidate.signum() > 0 && candidate.compareTo(N) < 0 then found = true
        else
          k = HmacSha256.mac(k, v ++ Array[Byte](0x00))
          v = HmacSha256.mac(k, v)
      candidate

  // ── DER ───────────────────────────────────────────────────────────────────────

  /** Encode `(r, s)` as DER `SEQUENCE { INTEGER r, INTEGER s }`. Bodies are < 128 bytes so a single-byte length
   *  is always sufficient. */
  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] =
    val rb = r.toByteArray   // already minimal two's-complement (leading 0x00 when high bit set)
    val sb = s.toByteArray
    val body = Array[Byte](0x02.toByte, rb.length.toByte) ++ rb ++
               Array[Byte](0x02.toByte, sb.length.toByte) ++ sb
    Array[Byte](0x30.toByte, body.length.toByte) ++ body

  /** Decode a DER `(r, s)`. */
  def decodeDer(sig: Array[Byte]): (BigInteger, BigInteger) =
    require(sig.length >= 8 && sig(0) == 0x30.toByte, "not a DER sequence")
    var pos = 2
    require(sig(pos) == 0x02.toByte, "expected INTEGER for r")
    val rLen = sig(pos + 1) & 0xff
    val r = new BigInteger(sig.slice(pos + 2, pos + 2 + rLen))
    pos += 2 + rLen
    require(sig(pos) == 0x02.toByte, "expected INTEGER for s")
    val sLen = sig(pos + 1) & 0xff
    val s = new BigInteger(sig.slice(pos + 2, pos + 2 + sLen))
    (r, s)
