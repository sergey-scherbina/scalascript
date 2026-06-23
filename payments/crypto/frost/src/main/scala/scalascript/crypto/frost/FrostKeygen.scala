package scalascript.crypto.frost

import java.math.BigInteger
import java.security.SecureRandom

/** FROST key generation — **trusted-dealer** Shamir secret sharing over the Ed25519 scalar field (mod `L`)
 *  (FROST-Ed25519 slice 2). A signing scalar `sk` is split into `n` shares such that any `t` reconstruct it
 *  (Lagrange interpolation at `x=0`) and fewer than `t` learn nothing; the group public key is `B·sk`.
 *
 *  Trusted-dealer (one party builds the polynomial) is the simpler precursor to a full distributed key
 *  generation (DKG); the share/threshold shapes are identical, so the signing slices build on this unchanged.
 *  Shares carry Feldman VSS commitments (`B·a_j`) so a participant can verify its share against the public
 *  commitment (`B·share == Σ commitment_j · id^j`). */
object FrostKeygen:

  private def ops: Ed25519Ops = Ed25519Ops.current
  private def L: java.math.BigInteger = ops.L

  /** One participant's secret share: the polynomial evaluated at `id` (`id` is 1-based, never 0). */
  final case class Share(id: Int, value: BigInteger)

  /** Output of key generation: the group public key, the `n` secret shares, the threshold `t`, and the
   *  Feldman commitments `B·a_0 … B·a_{t-1}` (encoded points) for share verification. */
  final case class KeyShares(
      groupPublicKey: Array[Byte],
      shares: List[Share],
      threshold: Int,
      commitments: List[Array[Byte]])

  /** A uniform non-zero scalar in `[1, L)`. Draws 48 random bytes and reduces mod `L` (negligible bias). */
  def randomScalar(rng: SecureRandom): BigInteger =
    var s = BigInteger.ZERO
    while s.signum() == 0 do
      val b = new Array[Byte](48); rng.nextBytes(b)
      s = new BigInteger(1, b).mod(L)
    s

  /** Trusted-dealer split: fresh random `sk`, degree-`(t-1)` polynomial, shares `f(1..n)`. */
  def generate(threshold: Int, total: Int, rng: SecureRandom = new SecureRandom()): KeyShares =
    require(threshold >= 1 && threshold <= total, s"need 1 <= t($threshold) <= n($total)")
    val coeffs = Array.fill(threshold)(randomScalar(rng)) // coeffs(0) = sk, then a_1..a_{t-1}
    generateFrom(coeffs, total)

  /** Like [[generate]] but with explicit polynomial coefficients (`coeffs(0)` = secret). For deterministic
   *  tests + as the building block a real DKG sums per-party polynomials with. */
  def generateFrom(coeffs: Array[BigInteger], total: Int): KeyShares =
    val threshold = coeffs.length
    require(threshold >= 1 && threshold <= total, s"need 1 <= t($threshold) <= n($total)")
    val sk = coeffs(0).mod(L)
    val shares = (1 to total).map { id =>
      Share(id, evalPoly(coeffs, BigInteger.valueOf(id.toLong)))
    }.toList
    val commitments = coeffs.map(a => ops.encode(ops.mulBase(a.mod(L)))).toList
    KeyShares(ops.encode(ops.mulBase(sk)), shares, threshold, commitments)

  /** Horner evaluation of `Σ coeffs(j)·x^j` mod `L`. */
  private def evalPoly(coeffs: Array[BigInteger], x: BigInteger): BigInteger =
    var acc = BigInteger.ZERO
    var j = coeffs.length - 1
    while j >= 0 do
      acc = acc.multiply(x).add(coeffs(j)).mod(L)
      j -= 1
    acc

  /** Lagrange coefficient `λ_i(0) = Π_{j≠i} x_j/(x_j - x_i)` mod `L`, over the participant ids in `ids`. */
  def lagrangeAtZero(i: Int, ids: List[Int]): BigInteger =
    val xi = BigInteger.valueOf(i.toLong)
    ids.filter(_ != i).foldLeft(BigInteger.ONE) { (acc, j) =>
      val xj   = BigInteger.valueOf(j.toLong)
      val num  = xj
      val den  = xj.subtract(xi).mod(L)
      acc.multiply(num).multiply(ops.scalarInv(den)).mod(L)
    }

  /** Reconstruct the secret scalar from `>= t` shares via Lagrange interpolation at `x=0`. */
  def reconstruct(shares: List[Share]): BigInteger =
    require(shares.nonEmpty, "no shares")
    require(shares.map(_.id).distinct.size == shares.size, "duplicate share ids")
    val ids = shares.map(_.id)
    shares.foldLeft(BigInteger.ZERO) { (acc, s) =>
      acc.add(s.value.multiply(lagrangeAtZero(s.id, ids))).mod(L)
    }

  /** Verify a share against the public Feldman commitments: `B·value == Σ_j commitment_j · id^j`
   *  (fails if any commitment is undecodable). */
  def verifyShare(share: Share, commitments: List[Array[Byte]]): Boolean =
    val lhs = ops.mulBase(share.value.mod(L))
    val x   = BigInteger.valueOf(share.id.toLong)
    // Accumulate (Σ commitment_j·x^j, x^j); None as soon as a commitment fails to decode.
    val rhs = commitments.foldLeft[Option[(Ed25519Group.Point, BigInteger)]](
        Some((ops.identity, BigInteger.ONE))) { (acc, c) =>
      acc.flatMap { (sum, xp) =>
        ops.decode(c).map(cj =>
          (ops.add(sum, ops.mul(xp, cj)), xp.multiply(x).mod(L)))
      }
    }
    rhs.exists((sum, _) => ops.samePoint(lhs, sum))
