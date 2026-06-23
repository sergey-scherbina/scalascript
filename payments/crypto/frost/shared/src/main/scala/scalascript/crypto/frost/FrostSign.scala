package scalascript.crypto.frost

import java.math.BigInteger

/** FROST-Ed25519 two-round threshold signing (FROST-Ed25519 slices 3+4). Produces a **standard Ed25519
 *  signature** (`ops.encode(R) ‖ scalarLE(z)`, 64 bytes) verifiable by any RFC 8032 verifier against the group
 *  public key — so a `t`-of-`n` quorum signs without ever reconstructing the key.
 *
 *  Why it verifies under plain Ed25519: with group commitment `R = Σ(D_i + ρ_i·E_i)` and partials
 *  `z_i = d_i + ρ_i·e_i + λ_i·c·s_i`, the aggregate `z = Σ z_i = (Σ nonces) + c·sk`, so `B·z = R + c·A` —
 *  exactly the Ed25519 verification equation with `c = SHA-512(R ‖ A ‖ msg) mod L`. The per-signer binding
 *  factor `ρ_i` (a hash of the commitment set + message) binds `E_i` to this signing, thwarting the
 *  Drijvers/ROS attack. */
object FrostSign:

  import Ed25519Group.Point
  private def ops: Ed25519Ops = Ed25519Ops.current
  private def L: BigInteger = ops.L
  import FrostKeygen.{Share, lagrangeAtZero}

  /** Round-1 secret nonces `(d, e)` — kept private by the signer. */
  final case class Nonce(d: BigInteger, e: BigInteger)

  /** Round-1 public commitments `D = B·d`, `E = B·e` for signer `id` (broadcast to the quorum). */
  final case class Commitment(id: Int, D: Array[Byte], E: Array[Byte])

  /** Round 1: a signer draws fresh nonces and publishes their commitments. */
  def round1(id: Int): (Nonce, Commitment) =
    val d = ops.randomScalar()
    val e = ops.randomScalar()
    (Nonce(d, e), Commitment(id, ops.encode(ops.mulBase(d)), ops.encode(ops.mulBase(e))))

  private def sha512(parts: Array[Byte]*): Array[Byte] = ops.sha512(parts*)

  /** Stable serialization of the commitment set (sorted by id) for the binding-factor + group-commitment
   *  hashes — every signer must derive identical binding factors, so the input order is canonical. */
  private def encodeCommitments(commitments: List[Commitment]): Array[Byte] =
    commitments.sortBy(_.id).foldLeft(Array.emptyByteArray) { (acc, c) =>
      acc ++ Ed25519Group.toLE(BigInteger.valueOf(c.id.toLong), 4) ++ c.D ++ c.E
    }

  /** Per-signer binding factor `ρ_i = SHA-512("FROST-ed25519/bind" ‖ id ‖ msg ‖ commitments) mod L`. */
  def bindingFactor(id: Int, msg: Array[Byte], commitments: List[Commitment]): BigInteger =
    Ed25519Group.fromLE(sha512(
      "FROST-ed25519/bind".getBytes("UTF-8"),
      Ed25519Group.toLE(BigInteger.valueOf(id.toLong), 4),
      msg,
      encodeCommitments(commitments))).mod(L)

  /** Group commitment `R = Σ_i (D_i + ρ_i·E_i)`. */
  def groupCommitment(msg: Array[Byte], commitments: List[Commitment]): Point =
    commitments.foldLeft(ops.identity) { (acc, c) =>
      val di = ops.decode(c.D).getOrElse(sys.error(s"bad D for signer ${c.id}"))
      val ei = ops.decode(c.E).getOrElse(sys.error(s"bad E for signer ${c.id}"))
      val rho = bindingFactor(c.id, msg, commitments)
      ops.add(acc, ops.add(di, ops.mul(rho, ei)))
    }

  /** Ed25519 challenge `c = SHA-512(ops.encode(R) ‖ groupPublicKey ‖ msg) mod L`. */
  def challenge(rEnc: Array[Byte], groupPublicKey: Array[Byte], msg: Array[Byte]): BigInteger =
    Ed25519Group.fromLE(sha512(rEnc, groupPublicKey, msg)).mod(L)

  /** Round 2: signer `share.id`'s partial signature
   *  `z_i = d_i + ρ_i·e_i + λ_i·c·s_i  (mod L)` over the active signing set (= the ids in `commitments`). */
  def partialSign(
      nonce: Nonce, share: Share,
      msg: Array[Byte], commitments: List[Commitment], groupPublicKey: Array[Byte]): BigInteger =
    val rho = bindingFactor(share.id, msg, commitments)
    val r   = groupCommitment(msg, commitments)
    val c   = challenge(ops.encode(r), groupPublicKey, msg)
    val lam = lagrangeAtZero(share.id, commitments.map(_.id))
    nonce.d
      .add(rho.multiply(nonce.e))
      .add(lam.multiply(c).multiply(share.value))
      .mod(L)

  /** Aggregate the partials into a standard 64-byte Ed25519 signature `ops.encode(R) ‖ scalarLE(z)`. */
  def aggregate(
      msg: Array[Byte], commitments: List[Commitment], partials: List[BigInteger]): Array[Byte] =
    val r = groupCommitment(msg, commitments)
    val z = partials.foldLeft(BigInteger.ZERO)((acc, zi) => acc.add(zi)).mod(L)
    ops.encode(r) ++ Ed25519Group.toLE(z, 32)
