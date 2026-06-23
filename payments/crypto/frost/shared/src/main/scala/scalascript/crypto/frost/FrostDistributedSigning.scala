package scalascript.crypto.frost

import java.math.BigInteger
import FrostSecp256k1.{Commitment, NonceState, SigningPackage, KeyShares}

/** Distributed FROST-secp256k1 signing — the production counterpart of the in-process
 *  [[FrostSecp256k1.thresholdSign]]: each share lives on its own [[Participant]] (host), and the coordinator
 *  exchanges only PUBLIC data — round-1 commitments and round-2 partial scalars — never a share.
 *
 *  The protocol is transport-agnostic: a [[Transport]] is how the coordinator reaches the participants. The
 *  bundled [[LocalTransport]] runs them in-process (the no-co-location SIMULATION used by the tests); an HTTP/WS
 *  or actor-cluster transport is a drop-in that sends each `round1`/`round2` request over the wire (the request
 *  bodies are exactly the public `Commitment` / partial-`BigInteger` values — a share is never serialized).
 *
 *  Because every party derives the round-2 package deterministically from the public commitments, the
 *  distributed signature is byte-identical to the in-process one for the same nonces (gate). */
object FrostDistributedSigning:

  /** A signing participant — holds exactly ONE secret share and runs the two rounds locally. The share is
   *  `private`: no accessor exposes it, so it never leaves this object / host. */
  final class Participant(val id: Int, private val share: BigInteger):
    private var state: Option[NonceState] = None

    /** Round 1: this participant's public commitment. Secret nonces are kept internally. `nonces` may be
     *  supplied for deterministic runs. */
    def round1(nonces: Option[(BigInteger, BigInteger)] = None): Commitment =
      val (st, commitment) = FrostSecp256k1.commit(id, nonces)
      state = Some(st)
      commitment

    /** Round 2: this participant's partial, computed from the LOCAL share + nonce state + the public package. */
    def round2(signerIds: List[Int], pkg: SigningPackage): BigInteger =
      FrostSecp256k1.partial(state.getOrElse(sys.error(s"participant $id: round1 not run")), share, signerIds, pkg)

  object Participant:
    /** One participant per share (in a real deployment, one per host). */
    def fromShares(ks: KeyShares): Map[Int, Participant] =
      ks.shares.map(s => s.id -> new Participant(s.id, s.value)).toMap

  /** How the coordinator reaches the signing participants. Each method carries only public data. */
  trait Transport:
    def signerIds: List[Int]
    /** Round 1: ask every signer for its commitment (optionally seeding nonces for deterministic runs). */
    def round1(nonces: Map[Int, (BigInteger, BigInteger)]): List[Commitment]
    /** Round 2: send the public package to every signer and collect their partials. */
    def round2(pkg: SigningPackage): List[BigInteger]

  /** In-process transport over independent [[Participant]] objects — the no-co-location simulation. It never
   *  reads a participant's share; it only relays the public round-1/round-2 messages. */
  final class LocalTransport(participants: Map[Int, Participant], val signerIds: List[Int]) extends Transport:
    require(signerIds.forall(participants.contains), "transport: unknown signer id")
    def round1(nonces: Map[Int, (BigInteger, BigInteger)]): List[Commitment] =
      signerIds.map(id => participants(id).round1(nonces.get(id)))
    def round2(pkg: SigningPackage): List[BigInteger] =
      signerIds.map(id => participants(id).round2(signerIds, pkg))

  /** The coordinator — holds the group public key + signer set, **no shares**. Drives the two rounds over the
   *  transport and assembles the BIP-340 signature. */
  def coordinate(groupPublicKeyXonly: Array[Byte], transport: Transport, msg: Array[Byte],
                 nonces: Map[Int, (BigInteger, BigInteger)] = Map.empty): Array[Byte] =
    val commitments = transport.round1(nonces)                                  // round 1 (public)
    val pkg         = FrostSecp256k1.prepare(groupPublicKeyXonly, commitments, msg)   // public derivation
    val partials    = transport.round2(pkg)                                     // round 2 (public partials)
    FrostSecp256k1.aggregate(pkg, partials)                                     // coordinator assembles r‖s

  /** Convenience: distributed `t`-of-`n` sign over an in-process [[LocalTransport]] built from `ks`. */
  def localSign(ks: KeyShares, signerIds: List[Int], msg: Array[Byte],
                nonces: Map[Int, (BigInteger, BigInteger)] = Map.empty): Array[Byte] =
    require(signerIds.size >= ks.threshold, s"need >= ${ks.threshold} signers, got ${signerIds.size}")
    val participants = Participant.fromShares(ks).filter((id, _) => signerIds.contains(id))
    coordinate(ks.groupPublicKeyXonly, new LocalTransport(participants, signerIds), msg, nonces)
