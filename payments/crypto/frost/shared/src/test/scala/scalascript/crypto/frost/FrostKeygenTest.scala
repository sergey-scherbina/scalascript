package scalascript.crypto.frost

import scalascript.crypto.Ed25519Group

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger

class FrostKeygenTest extends AnyFunSuite:
  import FrostKeygen.*

  private def hex(b: Array[Byte]): String = b.map("%02x".format(_)).mkString

  test("any t-of-n shares reconstruct the secret; the group key is B·sk"):
    // deterministic poly: sk = 7, a1 = 5  (t=2)
    val ks = generateFrom(Array(BigInteger.valueOf(7), BigInteger.valueOf(5)), total = 3)
    assert(ks.shares.size == 3 && ks.threshold == 2)
    // every 2-subset reconstructs sk = 7
    val subsets = ks.shares.combinations(2).toList
    assert(subsets.nonEmpty)
    subsets.foreach { sub =>
      assert(reconstruct(sub) == BigInteger.valueOf(7), s"subset ${sub.map(_.id)} did not recover sk")
    }
    // group public key = B·7
    assert(hex(ks.groupPublicKey) == hex(Ed25519Group.encode(Ed25519Group.mulBase(BigInteger.valueOf(7)))))

  test("fewer than t shares do NOT recover the secret"):
    val ks = generateFrom(Array(BigInteger.valueOf(7), BigInteger.valueOf(5)), total = 3)
    // a single share reconstructs to f(0)?? no — with one share, Lagrange over {id} gives the share value
    // itself (λ=1), which is f(id) ≠ sk for a non-constant poly.
    ks.shares.foreach { s =>
      assert(reconstruct(List(s)) != BigInteger.valueOf(7))
    }

  test("random t-of-n: reconstruct matches and B·reconstruct == group key (t=3, n=5)"):
    val ks  = generate(threshold = 3, total = 5)
    val sk  = reconstruct(ks.shares.take(3))
    // a different 3-subset agrees
    assert(reconstruct(ks.shares.drop(2).take(3)) == sk)
    // B·sk equals the published group public key
    assert(hex(Ed25519Group.encode(Ed25519Group.mulBase(sk))) == hex(ks.groupPublicKey))

  test("Feldman commitments verify each share (and reject a tampered share)"):
    val ks = generate(threshold = 2, total = 3)
    ks.shares.foreach(s => assert(verifyShare(s, ks.commitments), s"share ${s.id} failed VSS check"))
    val bad = ks.shares.head.copy(value = ks.shares.head.value.add(BigInteger.ONE))
    assert(!verifyShare(bad, ks.commitments), "tampered share should fail VSS check")
