package scalascript.crypto

/** Portable **X25519** (Curve25519 Diffie-Hellman, RFC 7748) — the Montgomery ladder over `BigInt` in the
 *  field `p = 2^255 - 19`, pure Scala, identical on JVM and Scala.js, no platform crypto. A correctness-
 *  first reference (BigInt, conditional swap — not constant-time); a hardened deployment should swap in a
 *  constant-time field backend. Pairs with [[ChaCha20Poly1305]] for Noise / `age` / hybrid encryption. */
object X25519:

  private val P    = (BigInt(1) << 255) - 19
  private val A24  = BigInt(121665)          // (486662 - 2) / 4
  private val Mask255 = (BigInt(1) << 255) - 1

  /** The base point u = 9. */
  val BasePoint: Array[Byte] = { val b = new Array[Byte](32); b(0) = 9; b }

  /** X25519(scalar, uCoordinate) — both 32-byte little-endian; returns the 32-byte little-endian result. */
  def scalarMult(scalar: Array[Byte], u: Array[Byte]): Array[Byte] =
    require(scalar.length == 32 && u.length == 32, "X25519 needs 32-byte scalar + u")
    val k  = decodeScalar(scalar)
    val x1 = leToBig(u) & Mask255                       // clear the top bit, per RFC 7748
    var x2 = BigInt(1); var z2 = BigInt(0)
    var x3 = x1;        var z3 = BigInt(1)
    var swap = 0
    var t = 254
    while t >= 0 do
      val kt = ((k >> t) & BigInt(1)).toInt
      swap ^= kt
      if swap == 1 then { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
      swap = kt
      val a  = mod(x2 + z2); val aa = mod(a * a)
      val b  = mod(x2 - z2); val bb = mod(b * b)
      val e  = mod(aa - bb)
      val c  = mod(x3 + z3)
      val d  = mod(x3 - z3)
      val da = mod(d * a)
      val cb = mod(c * b)
      x3 = mod(mod(da + cb) * mod(da + cb))
      z3 = mod(x1 * mod(mod(da - cb) * mod(da - cb)))
      x2 = mod(aa * bb)
      z2 = mod(e * mod(aa + mod(A24 * e)))
      t -= 1
    if swap == 1 then { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
    encodeU(mod(x2 * z2.modPow(P - 2, P)))

  /** Derive the 32-byte public key from a 32-byte private key: `X25519(sk, 9)`. */
  def derivePublicKey(privateKey: Array[Byte]): Array[Byte] = scalarMult(privateKey, BasePoint)

  /** The Diffie-Hellman shared secret `X25519(mySecret, theirPublic)`. */
  def sharedSecret(mySecret: Array[Byte], theirPublic: Array[Byte]): Array[Byte] =
    scalarMult(mySecret, theirPublic)

  // ── helpers ──────────────────────────────────────────────────────────────────

  private inline def mod(x: BigInt): BigInt = { val r = x % P; if r.signum < 0 then r + P else r }

  private def decodeScalar(s: Array[Byte]): BigInt =
    val e = s.clone()
    e(0)  = (e(0)  & 248).toByte
    e(31) = (e(31) & 127).toByte
    e(31) = (e(31) | 64).toByte
    leToBig(e)

  private def leToBig(b: Array[Byte]): BigInt =
    var n = BigInt(0); var i = b.length - 1
    while i >= 0 do { n = (n << 8) | BigInt(b(i) & 0xff); i -= 1 }
    n

  private def encodeU(n: BigInt): Array[Byte] =
    val out = new Array[Byte](32); var v = n; var i = 0
    while i < 32 do { out(i) = (v & 0xff).toInt.toByte; v = v >> 8; i += 1 }
    out
