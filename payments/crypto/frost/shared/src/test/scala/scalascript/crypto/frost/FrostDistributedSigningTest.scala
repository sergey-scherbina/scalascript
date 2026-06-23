package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger
import scalascript.crypto.{Secp256k1Schnorr, Sha256}
import FrostDistributedSigning.{Participant, LocalTransport, Transport, coordinate, localSign}
import FrostSecp256k1.{Commitment, SigningPackage}

/** Distributed FROST-secp256k1: a `t`-of-`n` quorum where each share lives on its own [[Participant]] produces a
 *  valid BIP-340 signature, byte-identical to the in-process path for the same nonces, exchanging only public
 *  data. Runs on JVM + Scala.js. */
class FrostDistributedSigningTest extends AnyFunSuite:

  private def big(n: Long): BigInteger = BigInteger.valueOf(n)
  private val msg = Sha256.digest("distributed taproot custody".getBytes("UTF-8"))

  /** Deterministic, distinct nonces per signer (each in [1, n)). */
  private def fixedNonces(ids: List[Int]): Map[Int, (BigInteger, BigInteger)] =
    ids.map(id => id -> (big(id.toLong * 1000 + 1), big(id.toLong * 1000 + 2))).toMap

  /** Reference "in-process FrostQuorum" computation with explicit nonces — one process, all shares. */
  private def inProcessReference(ks: FrostSecp256k1.KeyShares, signers: List[Int],
                                 nonces: Map[Int, (BigInteger, BigInteger)]): Array[Byte] =
    val states      = signers.map(id => FrostSecp256k1.commit(id, Some(nonces(id))))
    val commitments = states.map(_._2)
    val pkg         = FrostSecp256k1.prepare(ks.groupPublicKeyXonly, commitments, msg)
    val shareOf     = ks.shares.map(s => s.id -> s.value).toMap
    val partials    = states.map((st, _) => FrostSecp256k1.partial(st, shareOf(st.id), signers, pkg))
    FrostSecp256k1.aggregate(pkg, partials)

  test("distributed t-of-n produces a valid BIP-340 signature (LocalTransport)") {
    for (t, n, signers) <- List((2, 3, List(1, 2)), (2, 3, List(2, 3)), (3, 5, List(1, 3, 5)), (5, 5, (1 to 5).toList)) do
      val ks  = FrostSecp256k1.generate(t, n)
      val sig = localSign(ks, signers, msg)
      assert(sig.length == 64)
      assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig), s"$t-of-$n $signers must verify")
  }

  test("distributed signature is byte-identical to the in-process path for the same nonces (the gate)") {
    val ks      = FrostSecp256k1.generateFrom(Array(big(987654321L), big(13)), 4)
    val signers = List(1, 3)
    val nonces  = fixedNonces(signers)
    val dist    = localSign(ks, signers, msg, nonces)
    val inproc  = inProcessReference(ks, signers, nonces)
    assert(java.util.Arrays.equals(dist, inproc), "distributed must equal in-process for identical nonces")
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, dist))
  }

  test("same nonces → deterministic signature; fresh nonces → still valid") {
    val ks      = FrostSecp256k1.generate(2, 3)
    val signers = List(1, 2)
    val nonces  = fixedNonces(signers)
    assert(java.util.Arrays.equals(localSign(ks, signers, msg, nonces), localSign(ks, signers, msg, nonces)))
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, localSign(ks, signers, msg)))  // random nonces
  }

  test("only public data crosses the transport — no share is ever sent") {
    val ks      = FrostSecp256k1.generate(2, 3)
    val signers = List(1, 2)
    val shareValues = ks.shares.map(_.value).toSet
    // A transport that records every message and asserts none of them is a secret share.
    val inner = new LocalTransport(Participant.fromShares(ks).filter((id, _) => signers.contains(id)), signers)
    class RecordingTransport extends Transport:
      var commitments: List[Commitment] = Nil
      var partials: List[BigInteger]     = Nil
      def signerIds = signers
      def round1(nonces: Map[Int, (BigInteger, BigInteger)]) =
        commitments = inner.round1(nonces); commitments
      def round2(pkg: SigningPackage) =
        partials = inner.round2(pkg); partials
    val recording = new RecordingTransport
    val sig = coordinate(ks.groupPublicKeyXonly, recording, msg)
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig))
    // round-1 messages are 33-byte compressed points, not shares; round-2 are challenge-bound partials, not shares.
    assert(recording.commitments.forall(c => c.dCommit.length == 33 && c.eCommit.length == 33))
    assert(recording.partials.nonEmpty && recording.partials.forall(p => !shareValues.contains(p)))
  }

  test("a Participant holds only its own share and signs from the public package") {
    val ks  = FrostSecp256k1.generateFrom(Array(big(42), big(99)), 3)
    val p1  = new Participant(1, ks.shares.head.value)
    val p3  = new Participant(3, ks.shares(2).value)
    val signers = List(1, 3)
    val c1  = p1.round1(); val c3 = p3.round1()
    val pkg = FrostSecp256k1.prepare(ks.groupPublicKeyXonly, List(c1, c3), msg)
    val sig = FrostSecp256k1.aggregate(pkg, List(p1.round2(signers, pkg), p3.round2(signers, pkg)))
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig))
  }
