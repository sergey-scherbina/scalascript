package scalascript.crypto

import java.math.BigInteger

/** Pure NIST **P-256** (secp256r1 / prime256v1) group arithmetic — the short-Weierstrass curve
 *  `y² = x³ - 3x + b` over `Fp`, `java.math.BigInteger` backed, no platform crypto. Identical on JVM and
 *  Scala.js, so ES256 / WebAuthn (P-256) verification runs in a browser wallet. Mirrors
 *  [[Secp256k1Group]]; the only structural difference is `a = -3` (vs `a = 0`), which changes point
 *  doubling and the curve equation.
 *
 *  NOT constant-time (BigInteger, double-and-add): a correctness-first reference, cross-checked
 *  byte-for-byte against the BouncyCastle P-256 backend. */
object P256Group:

  private val ZERO  = BigInteger.ZERO
  private val ONE   = BigInteger.ONE
  private val TWO   = BigInteger.valueOf(2)
  private val THREE = BigInteger.valueOf(3)
  private val FOUR  = BigInteger.valueOf(4)
  private val EIGHT = BigInteger.valueOf(8)

  /** Field prime `p = 2^256 - 2^224 + 2^192 + 2^96 - 1`. */
  val P: BigInteger = new BigInteger(
    "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)

  /** Prime group order `n`. */
  val N: BigInteger = new BigInteger(
    "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

  /** Curve coefficient `b` (a = -3 is applied directly in the formulas). */
  val B: BigInteger = new BigInteger(
    "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

  /** Base point `G`. */
  val Gx: BigInteger = new BigInteger(
    "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
  val Gy: BigInteger = new BigInteger(
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

  private inline def modP(a: BigInteger): BigInteger = a.mod(P)
  private inline def fInv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)

  /** A point in Jacobian coordinates; `Z == 0` is the identity (point at infinity). */
  final case class JPoint(X: BigInteger, Y: BigInteger, Z: BigInteger)

  val Identity: JPoint = JPoint(ONE, ONE, ZERO)
  val G: JPoint        = JPoint(Gx, Gy, ONE)

  def isIdentity(p: JPoint): Boolean = p.Z.signum() == 0

  /** Point doubling with `a = -3` (the "dbl-2001-b" Jacobian formula:
   *  `alpha = 3·(X-Z²)·(X+Z²)` exploits `3X² + a·Z⁴ = 3X² - 3Z⁴`). */
  def double(p: JPoint): JPoint =
    if isIdentity(p) || p.Y.signum() == 0 then Identity
    else
      val delta = modP(p.Z.multiply(p.Z))                                  // Z²
      val gamma = modP(p.Y.multiply(p.Y))                                  // Y²
      val beta  = modP(p.X.multiply(gamma))                                // X·Y²
      val alpha = modP(THREE.multiply(modP(p.X.subtract(delta))).multiply(modP(p.X.add(delta))))
      val x3 = modP(alpha.multiply(alpha).subtract(EIGHT.multiply(beta)))  // α² - 8β
      val yz = modP(p.Y.add(p.Z))
      val z3 = modP(modP(yz.multiply(yz)).subtract(gamma).subtract(delta)) // (Y+Z)² - γ - δ
      val y3 = modP(alpha.multiply(modP(FOUR.multiply(beta).subtract(x3)))
                         .subtract(EIGHT.multiply(modP(gamma.multiply(gamma)))))  // α(4β - X3) - 8γ²
      JPoint(x3, y3, z3)

  /** Jacobian point addition (curve-independent group law). */
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

  def mulG(k: BigInteger): JPoint = mul(k, G)

  def toAffine(p: JPoint): Option[(BigInteger, BigInteger)] =
    if isIdentity(p) then None
    else
      val zInv  = fInv(p.Z)
      val zInv2 = modP(zInv.multiply(zInv))
      val x = modP(p.X.multiply(zInv2))
      val y = modP(p.Y.multiply(zInv2).multiply(zInv))
      Some((x, y))

  def negate(p: JPoint): JPoint =
    if isIdentity(p) then p else JPoint(p.X, modP(p.Y.negate()), p.Z)

  // ── encoding (SEC1) ─────────────────────────────────────────────────────────

  /** 33-byte compressed SEC1 encoding `0x02/0x03 || x`. */
  def compress(p: JPoint): Array[Byte] =
    toAffine(p) match
      case None => throw new IllegalArgumentException("cannot compress the point at infinity")
      case Some((x, y)) =>
        val prefix = if y.testBit(0) then 0x03.toByte else 0x02.toByte
        Array(prefix) ++ to32(x)

  /** 65-byte uncompressed SEC1 encoding `0x04 || x || y`. */
  def encodeUncompressed(p: JPoint): Array[Byte] =
    toAffine(p) match
      case None => throw new IllegalArgumentException("cannot encode the point at infinity")
      case Some((x, y)) => Array(0x04.toByte) ++ to32(x) ++ to32(y)

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

  /** `sqrt(x³ - 3x + b) mod p`, or `None` if not a quadratic residue. p ≡ 3 (mod 4) → `^((p+1)/4)`. */
  private def ySquaredRoot(x: BigInteger): Option[BigInteger] =
    val rhs = rhsCurve(x)
    val y   = rhs.modPow(P.add(ONE).divide(FOUR), P)
    if modP(y.multiply(y)).equals(rhs) then Some(y) else None

  private def rhsCurve(x: BigInteger): BigInteger =
    modP(modP(modP(x.multiply(x).multiply(x)).subtract(THREE.multiply(x))).add(B))   // x³ - 3x + b

  private def isOnCurveAffine(x: BigInteger, y: BigInteger): Boolean =
    modP(y.multiply(y)).equals(rhsCurve(x))

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
