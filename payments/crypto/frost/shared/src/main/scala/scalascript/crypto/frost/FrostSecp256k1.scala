package scalascript.crypto.frost

import java.math.BigInteger
import scalascript.crypto.{Secp256k1Group, Secp256k1Schnorr, Sha256}

/** FROST threshold Schnorr on **secp256k1**, producing a **standard BIP-340** signature (FROST-secp256k1).
 *
 *  Mirrors the FROST-Ed25519 design ([[FrostKeygen]] / [[FrostSign]]) on the portable secp256k1 group
 *  ([[Secp256k1Group]]) + BIP-340 challenge ([[Secp256k1Schnorr]]). Any `t`-of-`n` quorum produces a 64-byte
 *  signature `r ‖ s` that verifies under a plain BIP-340 verifier against the x-only group public key — so the
 *  same threshold custody now reaches **Bitcoin Taproot key-path spends and Ethereum** (Schnorr).
 *
 *  Two BIP-340 specifics beyond the Ed25519 case are handled: the group public key is forced even-`y` at keygen
 *  (negate the secret if `Y` is odd), and the aggregate nonce `R` is forced even-`y` at signing (each signer
 *  flips its effective nonce when `R` is odd). The internal per-signer binding hash is implementation-defined
 *  (SHA-256 here) — only the final aggregate signature is constrained, and it is exactly BIP-340.
 *
 *  This quorum is **in-process** (all shares in one process — the trusted-dealer + single-node simulation,
 *  matching `FrostSign`); a networked transport keeping shares non-co-located is a separate slice
 *  (`frost-distributed-transport`). Correctness-first reference (BigInteger, not constant-time). */
object FrostSecp256k1:

  import Secp256k1Group.{N, mulG, mul, add, negate, toAffine, to32}

  // ── scalar field (mod n) ──
  private def sInv(a: BigInteger): BigInteger = a.modPow(N.subtract(BigInteger.valueOf(2)), N)
  private def randomScalar(): BigInteger =
    var s = BigInteger.ZERO
    while s.signum() == 0 do s = new BigInteger(1, PlatformEntropy.bytes(48)).mod(N)
    s

  private def xOf(p: Secp256k1Group.JPoint): BigInteger =
    toAffine(p).getOrElse(sys.error("point at infinity"))._1
  private def yIsOdd(p: Secp256k1Group.JPoint): Boolean =
    toAffine(p).getOrElse(sys.error("point at infinity"))._2.testBit(0)

  // ── key generation (trusted dealer) ──────────────────────────────────────────

  /** One participant's secret share `f(id)` over the secp256k1 scalar field (`id` is 1-based). */
  final case class Share(id: Int, value: BigInteger)

  /** Group x-only public key (32 bytes, even-`y`), the `n` shares, and the threshold `t`. */
  final case class KeyShares(groupPublicKeyXonly: Array[Byte], shares: List[Share], threshold: Int)

  /** Trusted-dealer split: fresh random secret, degree-`(t-1)` polynomial, shares `f(1..n)`. The secret is
   *  negated if needed so the group key `Y = secret·G` has even `y` (BIP-340). */
  def generate(threshold: Int, total: Int): KeyShares =
    require(threshold >= 1 && threshold <= total, s"need 1 <= t($threshold) <= n($total)")
    val coeffs = Array.fill(threshold)(randomScalar())
    generateFrom(coeffs, total)

  /** Like [[generate]] but with explicit polynomial coefficients (`coeffs(0)` = secret) — for deterministic
   *  tests. The secret (and thus the whole polynomial constant term) is negated for even-`y` `Y`. */
  def generateFrom(coeffs: Array[BigInteger], total: Int): KeyShares =
    val t = coeffs.length
    require(t >= 1 && t <= total, s"need 1 <= t($t) <= n($total)")
    val sk0 = coeffs(0).mod(N)
    val yOdd = yIsOdd(mulG(sk0))
    // Force even-y Y: negating the secret negates only the constant term; negate the whole polynomial so the
    // shares stay consistent points on f with f(0) = the (possibly negated) secret.
    val c = if yOdd then coeffs.map(a => N.subtract(a.mod(N)).mod(N)) else coeffs.map(_.mod(N))
    val sk = c(0)
    val shares = (1 to total).map(id => Share(id, evalPoly(c, BigInteger.valueOf(id.toLong)))).toList
    KeyShares(to32(xOf(mulG(sk))), shares, t)

  private def evalPoly(coeffs: Array[BigInteger], x: BigInteger): BigInteger =
    var acc = BigInteger.ZERO
    var j = coeffs.length - 1
    while j >= 0 do { acc = acc.multiply(x).add(coeffs(j)).mod(N); j -= 1 }
    acc

  /** Lagrange coefficient `λ_i(0)` over the signing set `ids`, mod `n`. */
  def lagrangeAtZero(i: Int, ids: List[Int]): BigInteger =
    val xi = BigInteger.valueOf(i.toLong)
    ids.filter(_ != i).foldLeft(BigInteger.ONE) { (acc, j) =>
      val xj = BigInteger.valueOf(j.toLong)
      acc.multiply(xj).multiply(sInv(xj.subtract(xi).mod(N))).mod(N)
    }

  /** Reconstruct the secret scalar from `>= t` shares (Lagrange at `x=0`) — for tests / recovery. */
  def reconstruct(shares: List[Share]): BigInteger =
    require(shares.map(_.id).distinct.size == shares.size, "duplicate share ids")
    val ids = shares.map(_.id)
    shares.foldLeft(BigInteger.ZERO)((acc, s) =>
      acc.add(s.value.multiply(lagrangeAtZero(s.id, ids))).mod(N))

  // ── two-round signing (composable rounds — in-process OR distributed) ─────────

  /** A participant's PUBLIC round-1 commitment `(D_i, E_i)` as compressed points — broadcast to the
   *  coordinator. Carries no secret. */
  final case class Commitment(id: Int, dCommit: Array[Byte], eCommit: Array[Byte])

  /** A participant's PRIVATE round-1 state — the secret nonces. Never leaves the participant's host. */
  final case class NonceState(id: Int, d: BigInteger, e: BigInteger)

  /** The public round-2 package every party derives from the broadcast commitments: the even-`y` group-nonce
   *  x-coordinate `rx`, the BIP-340 challenge, whether `R` was negated, and the per-signer binding factors. */
  final case class SigningPackage(rx: Array[Byte], challenge: BigInteger, negR: Boolean, binding: Map[Int, BigInteger])

  /** Round 1: sample fresh nonces. Returns the private state (kept local) and the public commitment (broadcast).
   *  `nonces` may be supplied for deterministic runs (e.g. to match an in-process reference). */
  def commit(id: Int, nonces: Option[(BigInteger, BigInteger)] = None): (NonceState, Commitment) =
    val (d, e) = nonces.getOrElse((randomScalar(), randomScalar()))
    (NonceState(id, d, e), Commitment(id, Secp256k1Group.compress(mulG(d)), Secp256k1Group.compress(mulG(e))))

  /** Round-2 prep from the broadcast commitments — fully public, computed identically by every party. */
  def prepare(groupPublicKeyXonly: Array[Byte], commitments: List[Commitment], msg: Array[Byte]): SigningPackage =
    val commitsEnc = encodeCommitments(commitments)
    val binding = commitments.map(c => c.id -> bindingFactor(c.id, msg, commitsEnc)).toMap
    val rFull = commitments.map { c =>
      val dP = Secp256k1Group.decode(c.dCommit).getOrElse(sys.error("bad D commitment"))
      val eP = Secp256k1Group.decode(c.eCommit).getOrElse(sys.error("bad E commitment"))
      add(dP, mul(binding(c.id), eP))
    }.reduce(add)
    val negR  = yIsOdd(rFull)
    val rEven = if negR then negate(rFull) else rFull
    val rx    = to32(xOf(rEven))
    val ch    = new BigInteger(1, Secp256k1Schnorr.taggedHash("BIP0340/challenge", rx ++ groupPublicKeyXonly ++ msg)).mod(N)
    SigningPackage(rx, ch, negR, binding)

  /** Round 2: a participant's partial signature, from its OWN secret share + private nonce state + the public
   *  package. The share and the secret nonces never leave the participant. */
  def partial(state: NonceState, share: BigInteger, signerIds: List[Int], pkg: SigningPackage): BigInteger =
    val kappa0 = state.d.add(pkg.binding(state.id).multiply(state.e)).mod(N)   // d_i + ρ_i·e_i
    val kappa  = if pkg.negR then N.subtract(kappa0).mod(N) else kappa0
    kappa.add(pkg.challenge.multiply(lagrangeAtZero(state.id, signerIds)).multiply(share)).mod(N)

  /** Coordinator: assemble the final 64-byte BIP-340 signature `r ‖ s` from the partials. */
  def aggregate(pkg: SigningPackage, partials: List[BigInteger]): Array[Byte] =
    pkg.rx ++ to32(partials.foldLeft(BigInteger.ZERO)((acc, z) => acc.add(z).mod(N)))

  /** Per-signer binding factor `ρ_i = SHA256("FROSTsecp256k1/rho" ‖ id ‖ msg ‖ commitments) mod n`. */
  private def bindingFactor(id: Int, msg: Array[Byte], commitsEnc: Array[Byte]): BigInteger =
    val pre = "FROSTsecp256k1/rho".getBytes("UTF-8") ++ to32(BigInteger.valueOf(id.toLong)) ++ msg ++ commitsEnc
    new BigInteger(1, Sha256.digest(pre)).mod(N)

  private def encodeCommitments(cs: List[Commitment]): Array[Byte] =
    cs.sortBy(_.id).foldLeft(Array.emptyByteArray) { (acc, c) =>
      acc ++ to32(BigInteger.valueOf(c.id.toLong)) ++ c.dCommit ++ c.eCommit
    }

  /** Produce a BIP-340 signature for `msg` from an in-process `t`-of-`n` quorum of the given `signerIds` — all
   *  shares in one process (single-node simulation). A thin orchestration over the round API above, so the
   *  in-process and distributed ([[FrostDistributedSigning]]) paths are byte-identical for the same nonces. */
  def thresholdSign(ks: KeyShares, signerIds: List[Int], msg: Array[Byte]): Array[Byte] =
    require(signerIds.distinct.size == signerIds.size, "duplicate signer ids")
    require(signerIds.size >= ks.threshold, s"need >= ${ks.threshold} signers, got ${signerIds.size}")
    val shareOf = ks.shares.map(s => s.id -> s.value).toMap
    require(signerIds.forall(shareOf.contains), "unknown signer id")
    val states      = signerIds.map(id => commit(id))
    val commitments = states.map(_._2)
    val pkg         = prepare(ks.groupPublicKeyXonly, commitments, msg)
    val partials    = states.map((st, _) => partial(st, shareOf(st.id), signerIds, pkg))
    aggregate(pkg, partials)
