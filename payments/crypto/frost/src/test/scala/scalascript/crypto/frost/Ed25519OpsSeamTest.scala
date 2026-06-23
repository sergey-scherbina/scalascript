package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger
import java.security.SecureRandom

class Ed25519OpsSeamTest extends AnyFunSuite:

  /** A backend that delegates to the reference but records that FROST exercised it — proves the calls go
   *  through the seam (a real native backend substitutes the same way, transparently). */
  private class SpyOps extends Ed25519Ops:
    var mulBaseCalls = 0
    var sha512Calls  = 0
    private val r = Ed25519Ops.Reference
    def id = "spy"
    def base     = r.base
    def identity = r.identity
    def add(a: Ed25519Group.Point, b: Ed25519Group.Point) = r.add(a, b)
    def mul(s: BigInteger, p: Ed25519Group.Point)         = r.mul(s, p)
    def mulBase(s: BigInteger)                            = { mulBaseCalls += 1; r.mulBase(s) }
    def encode(p: Ed25519Group.Point)                     = r.encode(p)
    def decode(bytes: Array[Byte])                        = r.decode(bytes)
    def samePoint(a: Ed25519Group.Point, b: Ed25519Group.Point) = r.samePoint(a, b)
    def L = r.L
    def scalarAdd(a: BigInteger, b: BigInteger) = r.scalarAdd(a, b)
    def scalarMul(a: BigInteger, b: BigInteger) = r.scalarMul(a, b)
    def scalarInv(a: BigInteger)                = r.scalarInv(a)
    def scalarReduce(a: BigInteger)             = r.scalarReduce(a)
    def secretScalar(seed: Array[Byte])         = r.secretScalar(seed)
    def sha512(parts: Array[Byte]*)             = { sha512Calls += 1; r.sha512(parts*) }

  test("default backend is the pure reference"):
    assert(Ed25519Ops.current.id == "reference-bigint")

  test("FROST transparently uses a registered backend; reset restores the reference"):
    val spy = new SpyOps
    try
      Ed25519Ops.register(spy)
      assert(Ed25519Ops.current.id == "spy")
      // a full keygen+sign must flow through the registered backend
      val rng = new SecureRandom(); rng.setSeed(5L)
      val ks  = FrostKeygen.generate(2, 3, rng)
      val msg = "via spy".getBytes("UTF-8")
      val signers = ks.shares.take(2)
      val r1 = signers.map(s => s.id -> FrostSign.round1(s.id, rng)).toMap
      val commitments = signers.map(s => r1(s.id)._2)
      val partials = signers.map(s =>
        FrostSign.partialSign(r1(s.id)._1, s, msg, commitments, ks.groupPublicKey))
      val sig = FrostSign.aggregate(msg, commitments, partials)
      assert(sig.length == 64)
      assert(spy.mulBaseCalls > 0, "FROST did not route point ops through the registered backend")
      assert(spy.sha512Calls > 0,  "FROST did not route hashing through the registered backend")
    finally Ed25519Ops.reset()
    assert(Ed25519Ops.current.id == "reference-bigint")
