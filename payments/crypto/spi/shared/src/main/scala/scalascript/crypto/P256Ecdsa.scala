package scalascript.crypto

import java.math.BigInteger

/** Pure NIST **P-256** ECDSA — RFC-6979 deterministic nonces (HMAC-SHA256), BIP-62-style low-S
 *  normalisation and DER signature encoding, over [[P256Group]] + [[HmacSha256]]. Identical on JVM +
 *  Scala.js, no platform crypto. This is the `ES256` / WebAuthn signature primitive. Byte-for-byte
 *  compatible with a deterministic (RFC-6979) BouncyCastle `ECDSASigner` + low-S. Correctness-first
 *  reference (not constant-time). Mirrors [[Secp256k1Ecdsa]]. */
object P256Ecdsa:

  import P256Group.{N, mulG, mul, add, toAffine, decode, compress, to32}

  private val ZERO  = BigInteger.ZERO
  private val halfN = N.shiftRight(1)

  private def nInv(a: BigInteger): BigInteger = a.modPow(N.subtract(BigInteger.valueOf(2)), N)

  /** Sign a 32-byte `hash` (typically SHA-256 of the message) with a 32-byte P-256 private key.
   *  Returns a DER `(r, s)` with `s` normalised to the low half. */
  def sign(privKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    require(hash.length == 32, s"ECDSA hash must be 32 bytes, got ${hash.length}")
    val d = new BigInteger(1, privKey)
    require(d.signum() > 0 && d.compareTo(N) < 0, "P-256 private key out of range")
    val z = new BigInteger(1, hash)
    val nonces = rfc6979(d, hash)
    var result: Array[Byte] = null
    while result == null do
      val k = nonces()
      toAffine(mulG(k)) match
        case Some((rx, _)) =>
          val r = rx.mod(N)
          if r.signum() != 0 then
            val s0 = nInv(k).multiply(z.add(r.multiply(d))).mod(N)
            if s0.signum() != 0 then
              val s = if s0.compareTo(halfN) > 0 then N.subtract(s0) else s0
              result = encodeDer(r, s)
        case None => ()   // k·G = identity (vanishingly unlikely) → next nonce
    result

  /** Verify a DER-encoded P-256 ECDSA signature. Accepts both low-S and high-S. `pubKey` is 33-byte
   *  compressed or 65-byte uncompressed SEC1. */
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
              toAffine(add(mulG(u1), mul(u2, q))) match
                case None         => false
                case Some((x, _)) => x.mod(N).equals(r)
      catch case _: Exception => false

  /** 33-byte compressed public key from a 32-byte private key. */
  def derivePublicCompressed(privKey: Array[Byte]): Array[Byte] =
    val d = new BigInteger(1, privKey)
    require(d.signum() > 0 && d.compareTo(N) < 0, "P-256 private key out of range")
    compress(mulG(d))

  /** 65-byte uncompressed public key from a 32-byte private key. */
  def derivePublicUncompressed(privKey: Array[Byte]): Array[Byte] =
    val d = new BigInteger(1, privKey)
    require(d.signum() > 0 && d.compareTo(N) < 0, "P-256 private key out of range")
    P256Group.encodeUncompressed(mulG(d))

  // ── ES256/COSE fixed-length signature encoding ──────────────────────────────────

  /** Convert a DER `(r, s)` to the fixed **64-byte R‖S** form used by JOSE (ES256) and COSE. */
  def derToRaw(der: Array[Byte]): Array[Byte] =
    val (r, s) = decodeDer(der)
    to32(r) ++ to32(s)

  /** Convert a 64-byte R‖S back to DER for [[verify]]. */
  def rawToDer(raw: Array[Byte]): Array[Byte] =
    require(raw.length == 64, s"ES256 raw signature must be 64 bytes, got ${raw.length}")
    encodeDer(new BigInteger(1, raw.slice(0, 32)), new BigInteger(1, raw.slice(32, 64)))

  // ── RFC 6979 deterministic nonce generator (HMAC-SHA256, qlen 256) ──────────────

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

  // ── DER ─────────────────────────────────────────────────────────────────────────

  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] =
    val rb = r.toByteArray
    val sb = s.toByteArray
    val body = Array[Byte](0x02.toByte, rb.length.toByte) ++ rb ++
               Array[Byte](0x02.toByte, sb.length.toByte) ++ sb
    Array[Byte](0x30.toByte, body.length.toByte) ++ body

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
