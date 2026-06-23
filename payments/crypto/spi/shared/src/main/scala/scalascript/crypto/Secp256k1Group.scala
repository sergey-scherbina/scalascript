package scalascript.crypto

import java.math.BigInteger

/** Pure secp256k1 group arithmetic — the short-Weierstrass curve `y² = x³ + 7` over `Fp`, `java.math.BigInteger`
 *  backed, with NO platform crypto dependency. Works identically on the JVM and Scala.js, so the Bitcoin /
 *  Cosmos ECDSA + BIP-340 Schnorr stacks can run in a browser wallet. Mirrors the FROST `Ed25519Group`
 *  reference.
 *
 *  NOT constant-time (BigInteger, double-and-add): a correctness-first reference, gated against published
 *  secp256k1 / BIP-340 vectors and cross-checked byte-for-byte against the BouncyCastle backend. A
 *  side-channel-hardened deployment should swap in a constant-time field backend.
 *
 *  Internally points use Jacobian coordinates `(X:Y:Z)` with affine `x = X/Z²`, `y = Y/Z³` (so addition and
 *  doubling need no per-step modular inverse); the affine boundary inverts `Z` once via Fermat. */
object Secp256k1Group:

  private val ZERO = BigInteger.ZERO
  private val ONE  = BigInteger.ONE
  private val TWO  = BigInteger.valueOf(2)
  private val THREE = BigInteger.valueOf(3)
  private val SEVEN = BigInteger.valueOf(7)
  private val EIGHT = BigInteger.valueOf(8)

  /** Field prime `p = 2^256 - 2^32 - 977`. */
  val P: BigInteger = new BigInteger(
    "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)

  /** Prime group order `n`. */
  val N: BigInteger = new BigInteger(
    "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

  /** Base point `G`. */
  val Gx: BigInteger = new BigInteger(
    "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
  val Gy: BigInteger = new BigInteger(
    "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

  private inline def modP(a: BigInteger): BigInteger = a.mod(P)
  private inline def fInv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)

  /** A point in Jacobian coordinates; `Z == 0` is the identity (point at infinity). */
  final case class JPoint(X: BigInteger, Y: BigInteger, Z: BigInteger)

  val Identity: JPoint = JPoint(ONE, ONE, ZERO)
  val G: JPoint        = JPoint(Gx, Gy, ONE)

  def isIdentity(p: JPoint): Boolean = p.Z.signum() == 0

  /** Point doubling (a = 0 simplification for secp256k1). */
  def double(p: JPoint): JPoint =
    if isIdentity(p) || p.Y.signum() == 0 then Identity
    else
      val a  = modP(p.X.multiply(p.X))                                  // X²
      val b  = modP(p.Y.multiply(p.Y))                                  // Y²
      val c  = modP(b.multiply(b))                                      // B²
      val xb = modP(p.X.add(b))
      val d  = modP(TWO.multiply(modP(xb.multiply(xb)).subtract(a).subtract(c)))
      val e  = modP(THREE.multiply(a))                                  // 3X²  (a=0)
      val f  = modP(e.multiply(e))
      val x3 = modP(f.subtract(TWO.multiply(d)))
      val y3 = modP(e.multiply(d.subtract(x3)).subtract(EIGHT.multiply(c)))
      val z3 = modP(TWO.multiply(p.Y).multiply(p.Z))
      JPoint(x3, y3, z3)

  /** Jacobian point addition. */
  def add(p: JPoint, q: JPoint): JPoint =
    if isIdentity(p) then q
    else if isIdentity(q) then p
    else
      val z1z1 = modP(p.Z.multiply(p.Z))
      val z2z2 = modP(q.Z.multiply(q.Z))
      val u1 = modP(p.X.multiply(z2z2))
      val u2 = modP(q.X.multiply(z1z1))
      val s1 = modP(p.Y.multiply(q.Z).multiply(z2z2))
      val s2 = modP(q.Y.multiply(p.Z).multiply(z1z1))
      if u1.equals(u2) then
        if !s1.equals(s2) then Identity      // P = -Q
        else double(p)                       // P = Q
      else
        val h  = modP(u2.subtract(u1))
        val i  = modP(modP(TWO.multiply(h)).multiply(modP(TWO.multiply(h))))
        val j  = modP(h.multiply(i))
        val rr = modP(TWO.multiply(s2.subtract(s1)))
        val v  = modP(u1.multiply(i))
        val x3 = modP(rr.multiply(rr).subtract(j).subtract(TWO.multiply(v)))
        val y3 = modP(rr.multiply(v.subtract(x3)).subtract(TWO.multiply(s1).multiply(j)))
        val zz = modP(p.Z.add(q.Z))
        val z3 = modP(modP(zz.multiply(zz)).subtract(z1z1).subtract(z2z2).multiply(h))
        JPoint(x3, y3, z3)

  /** Scalar multiplication `k·P` (double-and-add over the bits of a non-negative `k`). */
  def mul(k: BigInteger, p: JPoint): JPoint =
    require(k.signum() >= 0, "scalar must be non-negative")
    var n = k
    var d = p
    var r = Identity
    while n.signum() > 0 do
      if n.testBit(0) then r = add(r, d)
      d = double(d)
      n = n.shiftRight(1)
    r

  /** `k·G`. */
  def mulG(k: BigInteger): JPoint = mul(k, G)

  /** Affine `(x, y)` of a point, or `None` for the identity. */
  def toAffine(p: JPoint): Option[(BigInteger, BigInteger)] =
    if isIdentity(p) then None
    else
      val zInv  = fInv(p.Z)
      val zInv2 = modP(zInv.multiply(zInv))
      val x = modP(p.X.multiply(zInv2))
      val y = modP(p.Y.multiply(zInv2).multiply(zInv))
      Some((x, y))

  /** Negate a point. */
  def negate(p: JPoint): JPoint =
    if isIdentity(p) then p else JPoint(p.X, modP(p.Y.negate()), p.Z)

  // ── encoding ────────────────────────────────────────────────────────────────

  /** 33-byte compressed SEC1 encoding `0x02/0x03 || x`. */
  def compress(p: JPoint): Array[Byte] =
    toAffine(p) match
      case None => throw new IllegalArgumentException("cannot compress the point at infinity")
      case Some((x, y)) =>
        val prefix = if y.testBit(0) then 0x03.toByte else 0x02.toByte
        Array(prefix) ++ to32(x)

  /** Decode a 33-byte compressed or 65-byte uncompressed SEC1 point. `None` if malformed / off-curve. */
  def decode(bytes: Array[Byte]): Option[JPoint] =
    bytes.length match
      case 33 if bytes(0) == 0x02 || bytes(0) == 0x03 =>
        val x = new BigInteger(1, bytes.slice(1, 33))
        ySquaredRoot(x).map { y0 =>
          val wantOdd = bytes(0) == 0x03
          val y = if y0.testBit(0) == wantOdd then y0 else modP(P.subtract(y0))
          JPoint(x, y, ONE)
        }
      case 65 if bytes(0) == 0x04 =>
        val x = new BigInteger(1, bytes.slice(1, 33))
        val y = new BigInteger(1, bytes.slice(33, 65))
        if isOnCurveAffine(x, y) then Some(JPoint(x, y, ONE)) else None
      case _ => None

  /** BIP-340 `lift_x`: the point with x-coordinate `x` and EVEN y. `None` if `x ≥ p` or no square root. */
  def liftX(x: BigInteger): Option[JPoint] =
    if x.compareTo(P) >= 0 then None
    else ySquaredRoot(x).map { y0 =>
      val y = if y0.testBit(0) then modP(P.subtract(y0)) else y0   // even y
      JPoint(x, y, ONE)
    }

  /** `sqrt(x³ + 7) mod p`, or `None` if not a quadratic residue. p ≡ 3 (mod 4) → `^((p+1)/4)`. */
  private def ySquaredRoot(x: BigInteger): Option[BigInteger] =
    val rhs = modP(modP(x.multiply(x).multiply(x)).add(SEVEN))
    val y   = rhs.modPow(P.add(ONE).divide(BigInteger.valueOf(4)), P)
    if modP(y.multiply(y)).equals(rhs) then Some(y) else None

  private def isOnCurveAffine(x: BigInteger, y: BigInteger): Boolean =
    modP(y.multiply(y)).equals(modP(modP(x.multiply(x).multiply(x)).add(SEVEN)))

  /** 32-byte big-endian, zero-padded / high-byte-trimmed unsigned encoding. */
  def to32(n: BigInteger): Array[Byte] =
    val raw = n.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val out = new Array[Byte](32)
      System.arraycopy(raw, 0, out, 32 - raw.length, raw.length)
      out
    else throw new IllegalArgumentException(s"value too large for 32 bytes: ${raw.length}")
