package scalascript.wallet.strategy.eoa

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scalascript.crypto.*
import scalascript.wallet.spi.*

/** Sanity tests that exercise trait composition.  The CryptoBackend is
 *  a per-test double — real signing impl lands in `crypto-bouncycastle`
 *  on JVM and `crypto-noble-js` on Scala.js (Stage 2 of the cross-
 *  compile sprint).  This suite uses `AsyncFunSuite` because Scala.js
 *  cannot block on `Await.result`. */
class EoaStrategyTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  // Register a deterministic double once for this test suite.
  override def withFixture(test: NoArgAsyncTest) =
    CryptoBackend.resetForTests()
    CryptoBackend.register(new StubCryptoBackend)
    complete { super.withFixture(test) } lastly {
      CryptoBackend.resetForTests()
    }

  test("Curve / HashAlgo enum surface is reachable") {
    assert(Curve.values.toSet.contains(Curve.Secp256k1))
    assert(HashAlgo.values.toSet.contains(HashAlgo.Keccak256))
  }

  test("PublicKey equality compares bytes structurally") {
    val pk1 = PublicKey(Curve.Secp256k1, Array[Byte](1, 2, 3))
    val pk2 = PublicKey(Curve.Secp256k1, Array[Byte](1, 2, 3))
    assert(pk1 == pk2)
    assert(pk1.hashCode == pk2.hashCode)
  }

  test("RawPrivateKeyVault.fromHex builds and lists accounts") {
    val v = RawPrivateKeyVault.fromHex("test-1", "0x" + "ab" * 32)
    v.listAccounts().map { accounts =>
      assert(accounts.size == 1)
      assert(accounts.head.publicKeys.contains(Curve.Secp256k1))
    }
  }

  test("EoaStrategy.kind == \"eoa\"") {
    val v = RawPrivateKeyVault.fromHex("test-2", "0x" + "01" * 32)
    v.getSigner(Curve.Secp256k1, "raw").map { signer =>
      val s = new EoaStrategy(signer)
      assert(s.kind == "eoa")
      assert(s.signer eq signer)
    }
  }

  test("Vault.lock discards keys") {
    val v = RawPrivateKeyVault.fromHex("test-3", "0x" + "02" * 32)
    v.lock()
    assert(v.isLocked)
    v.getSigner(Curve.Secp256k1, "raw").transform { (t: Try[RawSigner]) =>
      t match
        case Failure(ex) => Success(assert(ex.getMessage.contains("locked")))
        case Success(_)  => Success(fail("getSigner on locked vault must fail"))
    }
  }

/** Deterministic stub: derivePublic returns the private key reversed
 *  (so we get a distinct, predictable "public" byte string).  sign /
 *  verify are not exercised in this trait-composition test. */
private class StubCryptoBackend extends CryptoBackend:
  def id = "stub-for-traits-test"
  def supports(c: Curve) = true
  def sign(c: Curve, p: Array[Byte], m: Array[Byte], h: HashAlgo) = m ++ p.take(8)
  def verify(c: Curve, pk: Array[Byte], m: Array[Byte], s: Array[Byte], h: HashAlgo) = true
  def derivePublic(c: Curve, p: Array[Byte]) = p.reverse
  def recoverPublic(c: Curve, mh: Array[Byte], s: Array[Byte], r: Int) = s.take(32)
  def hash(a: HashAlgo, d: Array[Byte]) = d
  def hmac(a: HashAlgo, k: Array[Byte], d: Array[Byte]) = d
  def deriveMaster(c: Curve, seed: Array[Byte]) = HdKey(seed.take(32), seed.drop(32).take(32))
  def deriveChild(c: Curve, p: HdKey, i: Long, h: Boolean) = p
  def pbkdf2(p: Array[Byte], s: Array[Byte], i: Int, l: Int, h: HashAlgo) = new Array[Byte](l)
  def argon2id(p: Array[Byte], s: Array[Byte], m: Int, i: Int, par: Int, l: Int) = new Array[Byte](l)
  def hkdf(i: Array[Byte], s: Array[Byte], info: Array[Byte], l: Int, h: HashAlgo) = new Array[Byte](l)
  def aesGcmEncrypt(k: Array[Byte], iv: Array[Byte], p: Array[Byte], a: Array[Byte]) = p
  def aesGcmDecrypt(k: Array[Byte], iv: Array[Byte], c: Array[Byte], a: Array[Byte]) = c
  def randomBytes(l: Int) = new Array[Byte](l)
