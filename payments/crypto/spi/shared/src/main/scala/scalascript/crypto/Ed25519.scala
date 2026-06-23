package scalascript.crypto

import java.math.BigInteger

/** Pure standard Ed25519 (RFC 8032) signing / verification / public-key derivation over [[Ed25519Group]] +
 *  [[Sha512]] — no platform crypto dependency, identical on JVM + Scala.js. Lets the Cosmos / Solana chains
 *  (and any Ed25519 caller) sign in a browser wallet with byte-identical results to BouncyCastle's
 *  `Ed25519Signer`. Correctness-first reference (not constant-time); verified against the RFC 8032 vectors. */
object Ed25519:

  import Ed25519Group.{mulBase, add, mul, encode, decode, samePoint, L, toLE, fromLE}

  /** Derive the 32-byte public key from a 32-byte seed (the Ed25519 "secret key"). */
  def derivePublicKey(seed: Array[Byte]): Array[Byte] =
    require(seed.length == 32, "Ed25519 seed must be 32 bytes")
    encode(mulBase(secretScalar(Sha512.digest(seed))))

  /** Sign `msg` with a 32-byte seed. Returns the 64-byte signature `R || S` (RFC 8032 §5.1.6). */
  def sign(seed: Array[Byte], msg: Array[Byte]): Array[Byte] =
    require(seed.length == 32, "Ed25519 seed must be 32 bytes")
    val h      = Sha512.digest(seed)
    val a      = secretScalar(h)
    val prefix = java.util.Arrays.copyOfRange(h, 32, 64)
    val aEnc   = encode(mulBase(a))
    val r      = fromLE(Sha512.digest(prefix ++ msg)).mod(L)
    val rEnc   = encode(mulBase(r))
    val k      = fromLE(Sha512.digest(rEnc ++ aEnc ++ msg)).mod(L)
    val s      = r.add(k.multiply(a)).mod(L)
    rEnc ++ toLE(s, 32)

  /** Verify a 64-byte Ed25519 signature `R || S` against a 32-byte public key (non-cofactored check
   *  `[S]B == R + [k]A`, rejecting non-canonical `S ≥ L`). */
  def verify(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    if pubKey.length != 32 || sig.length != 64 then false
    else
      try
        val rEnc = java.util.Arrays.copyOfRange(sig, 0, 32)
        val s    = fromLE(java.util.Arrays.copyOfRange(sig, 32, 64))
        if s.compareTo(L) >= 0 then false
        else
          (decode(rEnc), decode(pubKey)) match
            case (Some(rPoint), Some(aPoint)) =>
              val k = fromLE(Sha512.digest(rEnc ++ pubKey ++ msg)).mod(L)
              samePoint(mulBase(s), add(rPoint, mul(k, aPoint)))
            case _ => false
      catch case _: Exception => false

  /** RFC 8032 secret scalar: clamp the low 32 bytes of `SHA-512(seed)`, read little-endian. */
  private def secretScalar(h: Array[Byte]): BigInteger =
    val a = java.util.Arrays.copyOfRange(h, 0, 32)
    a(0)  = (a(0) & 0xF8).toByte
    a(31) = ((a(31) & 0x7F) | 0x40).toByte
    fromLE(a)
