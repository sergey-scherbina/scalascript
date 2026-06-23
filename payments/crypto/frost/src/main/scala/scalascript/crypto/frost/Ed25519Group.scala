package scalascript.crypto.frost

import java.math.BigInteger

/** Pure Ed25519 group arithmetic — the RFC 8032 reference algorithm, `java.math.BigInteger`-backed, with NO
 *  external dependency (FROST-Ed25519 slice 1, the foundation the threshold scheme is built on).
 *
 *  NOT constant-time (BigInteger): this is a correctness-first reference for FROST threshold signing, where the
 *  signing key is already split across parties; a side-channel-hardened deployment should swap in a
 *  constant-time field backend. Correctness is gated by cross-checking generated public keys against a
 *  reference Ed25519 implementation (see `Ed25519GroupTest`). */
object Ed25519Group:

  private val ZERO = BigInteger.ZERO
  private val ONE  = BigInteger.ONE
  private val TWO  = BigInteger.valueOf(2)

  /** Field prime `p = 2^255 - 19`. */
  val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

  /** Prime-order subgroup order `L = 2^252 + 27742317777372353535851937790883648493`. */
  val L: BigInteger = TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"))

  private def modP(a: BigInteger): BigInteger = a.mod(P)            // always non-negative
  private def fInv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)  // a^(p-2) mod p

  /** Curve constant `d = -121665 / 121666 mod p`. */
  private val D: BigInteger =
    modP(BigInteger.valueOf(-121665).multiply(fInv(BigInteger.valueOf(121666))))

  /** `sqrt(-1) = 2^((p-1)/4) mod p` — used in the square-root step of point decompression. */
  private val ModpSqrtM1: BigInteger =
    TWO.modPow(P.subtract(ONE).divide(BigInteger.valueOf(4)), P)

  /** A curve point in extended homogeneous coordinates `(X:Y:Z:T)` with `x = X/Z`, `y = Y/Z`, `x·y = T/Z`. */
  final case class Point(X: BigInteger, Y: BigInteger, Z: BigInteger, T: BigInteger)

  /** Neutral element. */
  val Identity: Point = Point(ZERO, ONE, ONE, ZERO)

  /** Base point `B` (`y = 4/5`, x recovered with an even low bit). */
  val B: Point =
    val by = modP(BigInteger.valueOf(4).multiply(fInv(BigInteger.valueOf(5))))
    val bx = recoverX(by, 0).getOrElse(sys.error("Ed25519 base point: no x"))
    Point(bx, by, ONE, modP(bx.multiply(by)))

  /** Unified twisted-Edwards point addition (RFC 8032 §5.1.4, extended coordinates). */
  def add(p: Point, q: Point): Point =
    val a  = modP(p.Y.subtract(p.X).multiply(q.Y.subtract(q.X)))
    val b  = modP(p.Y.add(p.X).multiply(q.Y.add(q.X)))
    val c  = modP(TWO.multiply(p.T).multiply(q.T).multiply(D))
    val dd = modP(TWO.multiply(p.Z).multiply(q.Z))
    val e  = b.subtract(a)
    val f  = dd.subtract(c)
    val g  = dd.add(c)
    val h  = b.add(a)
    Point(modP(e.multiply(f)), modP(g.multiply(h)), modP(f.multiply(g)), modP(e.multiply(h)))

  /** Scalar multiplication `s·P` (double-and-add over the bits of a non-negative `s`). */
  def mul(s: BigInteger, p: Point): Point =
    require(s.signum() >= 0, "scalar must be non-negative")
    var n = s
    var d = p
    var q = Identity
    while n.signum() > 0 do
      if n.testBit(0) then q = add(q, d)
      d = add(d, d)
      n = n.shiftRight(1)
    q

  /** `B·s`. */
  def mulBase(s: BigInteger): Point = mul(s, B)

  /** Project to affine and compare (handles differing Z). */
  def samePoint(p: Point, q: Point): Boolean =
    modP(p.X.multiply(q.Z)).equals(modP(q.X.multiply(p.Z))) &&
    modP(p.Y.multiply(q.Z)).equals(modP(q.Y.multiply(p.Z)))

  /** Recover `x` from `y` and the wanted low bit `sign` (0/1), per RFC 8032 §5.1.3. `None` if `y` is out of
   *  range or no square root exists. */
  private def recoverX(y: BigInteger, sign: Int): Option[BigInteger] =
    if y.compareTo(P) >= 0 then None
    else
      val y2 = modP(y.multiply(y))
      val x2 = modP(y2.subtract(ONE).multiply(fInv(modP(D.multiply(y2).add(ONE)))))
      if x2.signum() == 0 then
        if sign == 1 then None else Some(ZERO)
      else
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if modP(x.multiply(x).subtract(x2)).signum() != 0 then x = modP(x.multiply(ModpSqrtM1))
        if modP(x.multiply(x).subtract(x2)).signum() != 0 then None
        else
          val lowBit = if x.testBit(0) then 1 else 0
          if lowBit != sign then x = modP(P.subtract(x))
          Some(x)

  /** Encode a point to its 32-byte compressed form: little-endian `y` with `x`'s low bit in the top bit. */
  def encode(p: Point): Array[Byte] =
    val zinv = fInv(p.Z)
    val x = modP(p.X.multiply(zinv))
    val y = modP(p.Y.multiply(zinv))
    toLE(y.or(if x.testBit(0) then ONE.shiftLeft(255) else ZERO), 32)

  /** Decode a 32-byte compressed point. `None` if malformed. */
  def decode(bytes: Array[Byte]): Option[Point] =
    if bytes.length != 32 then None
    else
      val v    = fromLE(bytes)
      val sign = v.shiftRight(255).intValue() & 1
      val y    = v.and(ONE.shiftLeft(255).subtract(ONE))
      recoverX(y, sign).map(x => Point(x, y, ONE, modP(x.multiply(y))))

  // ── scalar field (mod L) ──
  def scalarReduce(a: BigInteger): BigInteger              = a.mod(L)
  def scalarAdd(a: BigInteger, b: BigInteger): BigInteger  = a.add(b).mod(L)
  def scalarMul(a: BigInteger, b: BigInteger): BigInteger  = a.multiply(b).mod(L)
  def scalarInv(a: BigInteger): BigInteger                 = a.mod(L).modPow(L.subtract(TWO), L)

  /** Ed25519 secret scalar from a 32-byte seed: `clamp(SHA-512(seed)[0:32])` read little-endian
   *  (RFC 8032 §5.1.5); the public key is `encode(B · scalar)`. Used to gate the group ops against a
   *  reference Ed25519 keygen. */
  def secretScalar(seed: Array[Byte]): BigInteger =
    val h = Sha512.digest(seed)
    val a = h.take(32)
    a(0)  = (a(0) & 0xF8).toByte
    a(31) = ((a(31) & 0x7F) | 0x40).toByte
    fromLE(a)

  // ── little-endian ↔ BigInteger ──
  def toLE(v: BigInteger, len: Int): Array[Byte] =
    val out  = new Array[Byte](len)
    val mask = BigInteger.valueOf(0xff)
    var x = v
    var i = 0
    while i < len do
      out(i) = x.and(mask).intValue().toByte
      x = x.shiftRight(8)
      i += 1
    out

  def fromLE(bytes: Array[Byte]): BigInteger =
    var v = ZERO
    var i = bytes.length - 1
    while i >= 0 do
      v = v.shiftLeft(8).or(BigInteger.valueOf((bytes(i) & 0xff).toLong))
      i -= 1
    v
