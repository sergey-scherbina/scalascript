package scalascript.wallet.vault.mpc.frost

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

import org.scalatest.funsuite.AnyFunSuite

import scalascript.crypto.{Curve, HashAlgo}
import scalascript.crypto.frost.FrostKeygen
import scalascript.wallet.spi.{UnlockCredential, VaultKind}
import scalascript.wallet.vault.mpc.McpVault

/** Slice 8: FROST-Ed25519 wired into the wallet stack as an in-house
 *  `RemoteSigningClient`, signing through a stock `McpVault`. The signature
 *  the vault produces must verify under standard (BouncyCastle) Ed25519. */
class FrostSigningClientTest extends AnyFunSuite:

  private given ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 5.seconds)

  /** Reference verify with BouncyCastle Ed25519 — the authoritative gate. */
  private def bcVerify(pub: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    val signer = new org.bouncycastle.crypto.signers.Ed25519Signer()
    signer.init(false, new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pub, 0))
    signer.update(msg, 0, msg.length)
    signer.verifySignature(sig)

  private def quorum2of3(account: String): (FrostKeygen.KeyShares, FrostQuorum) =
    val ks = FrostKeygen.generate(threshold = 2, total = 3)
    (ks, new FrostQuorum(account, "Treasury", ks, signerIds = List(1, 2)))

  test("McpVault backed by FrostSigningClient signs an Ed25519 message that verifies"):
    val (ks, q) = quorum2of3("treasury")
    val client  = new FrostSigningClient(Seq(q))
    val vault   = new McpVault("frost-treasury", client)

    assert(vault.kind == VaultKind.Mpc)
    assert(vault.isLocked)
    await(vault.unlock(UnlockCredential.None))
    assert(!vault.isLocked)

    val accounts = await(vault.listAccounts())
    assert(accounts.size == 1)
    assert(accounts.head.publicKeys.contains(Curve.Ed25519))
    assert(accounts.head.publicKeys(Curve.Ed25519).bytes.sameElements(ks.groupPublicKey))

    val signer = await(vault.getSigner(Curve.Ed25519, accounts.head.derivationPath))
    assert(signer.curve == Curve.Ed25519)

    val msg = "transfer 100 to alice".getBytes("UTF-8")
    val sig = await(signer.sign(msg, HashAlgo.None))
    assert(sig.length == 64)
    assert(bcVerify(ks.groupPublicKey, msg, sig), "FROST vault signature failed standard Ed25519 verify")

    // a different signing subset of the same group key also verifies
    val q13   = new FrostQuorum("treasury", "Treasury", ks, signerIds = List(1, 3))
    val sig13 = await(new FrostSigningClient(Seq(q13)).sign("treasury", Curve.Ed25519, "mpc/treasury", msg, HashAlgo.None))
    assert(bcVerify(ks.groupPublicKey, msg, sig13), "subset {1,3} failed to verify")

  test("FrostSigningClient rejects non-Ed25519 curves and unknown accounts"):
    val (_, q) = quorum2of3("treasury")
    val client = new FrostSigningClient(Seq(q))
    val msg    = "x".getBytes("UTF-8")

    val wrongCurve = intercept[IllegalArgumentException](
      await(client.sign("treasury", Curve.Secp256k1, "mpc/treasury", msg, HashAlgo.None)))
    assert(wrongCurve.getMessage.contains("Ed25519"))

    val unknown = intercept[IllegalArgumentException](
      await(client.sign("nope", Curve.Ed25519, "mpc/nope", msg, HashAlgo.None)))
    assert(unknown.getMessage.contains("nope"))

  test("a FrostQuorum needs at least t signers"):
    val ks = FrostKeygen.generate(threshold = 2, total = 3)
    intercept[IllegalArgumentException](new FrostQuorum("treasury", "Treasury", ks, signerIds = List(1)))
