package scalascript.crypto

import java.math.BigInteger

/** Pure BIP-340 Schnorr signatures + BIP-341 Taproot key tweaking over [[Secp256k1Group]] + [[Sha256]] — the
 *  part of the Bitcoin crypto surface that no generic `sign`/`hash` SPI can express (it needs raw secp256k1
 *  point arithmetic and `lift_x`). Identical on JVM + Scala.js, no platform crypto dependency. Verified against
 *  the published BIP-340 test vectors. Correctness-first reference (not constant-time). */
object Secp256k1Schnorr:

  import Secp256k1Group.{N, P, mulG, mul, add, toAffine, liftX, compress, to32}

  /** BIP-340 tagged hash: `SHA256(SHA256(tag) || SHA256(tag) || data)`. */
  def taggedHash(tag: String, data: Array[Byte]): Array[Byte] =
    val t = Sha256.digest(tag.getBytes("UTF-8"))
    Sha256.digest(t ++ t ++ data)

  /** BIP-340 Schnorr signing. `msg` and `auxRand` are 32 bytes; returns the 64-byte signature `r || s`. */
  def sign(privKey: Array[Byte], msg: Array[Byte], auxRand: Array[Byte] = new Array[Byte](32)): Array[Byte] =
    require(msg.length == 32, "Schnorr message must be 32 bytes")
    require(auxRand.length == 32, "auxRand must be 32 bytes")
    val d0 = new BigInteger(1, privKey)
    require(d0.signum() > 0 && d0.compareTo(N) < 0, "Schnorr private key out of range")
    val (px0, py0) = toAffine(mulG(d0)).getOrElse(sys.error("d·G is identity"))
    val d  = if py0.testBit(0) then N.subtract(d0) else d0     // force even-y P
    val px = to32(px0)
    val t  = xorBytes(to32(d), taggedHash("BIP0340/aux", auxRand))
    val k0 = new BigInteger(1, taggedHash("BIP0340/nonce", t ++ px ++ msg)).mod(N)
    require(k0.signum() != 0, "Schnorr nonce is zero")
    val (rx0, ry0) = toAffine(mulG(k0)).getOrElse(sys.error("k·G is identity"))
    val k  = if ry0.testBit(0) then N.subtract(k0) else k0     // force even-y R
    val rx = to32(rx0)
    val e  = new BigInteger(1, taggedHash("BIP0340/challenge", rx ++ px ++ msg)).mod(N)
    val s  = k.add(e.multiply(d)).mod(N)
    rx ++ to32(s)

  /** BIP-340 Schnorr verification. `pubKey` is a 32-byte x-only key, `sig` is 64 bytes. */
  def verify(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    if pubKey.length != 32 || msg.length != 32 || sig.length != 64 then false
    else
      try
        val rx = new BigInteger(1, sig.slice(0, 32))
        val s  = new BigInteger(1, sig.slice(32, 64))
        if rx.compareTo(P) >= 0 || s.compareTo(N) >= 0 then false
        else
          liftX(new BigInteger(1, pubKey)) match
            case None => false
            case Some(pPoint) =>
              val e = new BigInteger(1, taggedHash("BIP0340/challenge", sig.slice(0, 32) ++ pubKey ++ msg)).mod(N)
              val r = add(mulG(s), mul(N.subtract(e), pPoint))    // s·G − e·P
              toAffine(r) match
                case None             => false
                case Some((rxR, ryR)) => !ryR.testBit(0) && rxR.equals(rx)
      catch case _: Exception => false

  // ── BIP-341 Taproot key tweaking ──────────────────────────────────────────────

  /** `taggedHash("TapTweak", xonly || merkleRoot)` — the tap-tweak commitment (empty merkle root = key-path
   *  only). */
  def tapTweakHash(xonlyPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    require(xonlyPubKey.length == 32, "x-only pubkey must be 32 bytes")
    taggedHash("TapTweak", xonlyPubKey ++ merkleRoot)

  /** Tweak an internal public key for Taproot key-path spending: `P + t·G`, `t = tapTweakHash(lift_x(P), root)`.
   *  Returns the 33-byte compressed tweaked output key. `internalPubKey` is 32 (x-only) or 33 (compressed). */
  def tweakedKey(internalPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    val xonly = if internalPubKey.length == 33 then internalPubKey.drop(1) else internalPubKey
    require(xonly.length == 32, "internal pubkey must be 32 or 33 bytes")
    val t = new BigInteger(1, tapTweakHash(xonly, merkleRoot))
    require(t.compareTo(N) < 0, "tweak scalar out of range")
    val lifted = liftX(new BigInteger(1, xonly)).getOrElse(throw new IllegalArgumentException("x not on curve"))
    compress(add(lifted, mulG(t)))

  /** Tweaked private key for Taproot key-path spending: negate `d` to even-y, then `d' + t mod n`. */
  def tweakedPrivateKey(privKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    val d0 = new BigInteger(1, privKey)
    val (x, y) = toAffine(mulG(d0)).getOrElse(sys.error("d·G is identity"))
    val d = if y.testBit(0) then N.subtract(d0) else d0
    val t = new BigInteger(1, tapTweakHash(to32(x), merkleRoot))
    to32(d.add(t).mod(N))

  private def xorBytes(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val out = new Array[Byte](a.length)
    var i = 0
    while i < a.length do { out(i) = (a(i) ^ b(i)).toByte; i += 1 }
    out
