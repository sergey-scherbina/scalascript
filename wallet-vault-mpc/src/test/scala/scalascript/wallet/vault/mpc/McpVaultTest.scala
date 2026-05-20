package scalascript.wallet.vault.mpc

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.*

class McpVaultTest extends AnyFunSuite with Matchers:

  private val dummyPub = Array.fill(33)(0x02.toByte)
  private val dummySig = Array.fill(64)(0x42.toByte)

  private val sampleAccount =
    McpAccount(
      id         = "acct-1",
      label      = "Treasury",
      publicKeys = Map(Curve.Secp256k1 -> dummyPub),
    )

  test("McpVault.kind is Mpc and starts locked"):
    val vault = new McpVault("v1", new MockRemoteSigningClient(Seq(sampleAccount), dummySig))
    vault.kind     shouldBe VaultKind.Mpc
    vault.isLocked shouldBe true

  test("unlock probes the client and flips the locked flag"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig, healthy = true)
    val vault  = new McpVault("v1", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked     shouldBe false
    client.healthCalls shouldBe 1

  test("unlock fails when the provider reports unhealthy"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig, healthy = false)
    val vault  = new McpVault("v1", client)
    val ex = intercept[IllegalStateException] {
      Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    }
    ex.getMessage should include ("health check failed")
    vault.isLocked shouldBe true

  test("listAccounts returns empty while locked"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    val accts  = Await.result(vault.listAccounts(), 2.seconds)
    accts shouldBe Nil
    client.listCalls shouldBe 0

  test("listAccounts surfaces provider accounts after unlock"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    val accts  = Await.result(vault.listAccounts(), 2.seconds)
    accts.size shouldBe 1
    val a = accts.head
    a.id                                shouldBe "acct-1"
    a.label                             shouldBe "Treasury"
    a.derivationPath                    shouldBe "mpc/acct-1"
    a.publicKeys(Curve.Secp256k1).bytes shouldBe dummyPub

  test("getSigner returns a signer that delegates to the client"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 2.seconds)
    signer.curve            shouldBe Curve.Secp256k1
    signer.publicKey.bytes  shouldBe dummyPub
    val msg = Array[Byte](1, 2, 3, 4)
    val sig = Await.result(signer.sign(msg, HashAlgo.Keccak256), 2.seconds)
    sig                  shouldBe dummySig
    client.signCalls     shouldBe 1
    client.lastAccountId shouldBe "acct-1"
    client.lastCurve     shouldBe Curve.Secp256k1
    client.lastPath      shouldBe "m/44'/60'/0'/0/0"
    client.lastPayload   shouldBe msg
    client.lastHashAlgo  shouldBe HashAlgo.Keccak256

  test("getSigner fails when no account has the requested curve"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    val ex = intercept[IllegalArgumentException] {
      Await.result(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'"), 2.seconds)
    }
    ex.getMessage should include ("no account with a Ed25519 public key")

  test("getSigner fails while locked"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    val ex = intercept[IllegalStateException] {
      Await.result(vault.getSigner(Curve.Secp256k1, "m"), 2.seconds)
    }
    ex.getMessage should include ("is locked")

  test("lock returns the vault to the locked state"):
    val client = new MockRemoteSigningClient(Seq(sampleAccount), dummySig)
    val vault  = new McpVault("v1", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked shouldBe false
    vault.lock()
    vault.isLocked shouldBe true
    // listAccounts respects the freshly-locked state.
    Await.result(vault.listAccounts(), 2.seconds) shouldBe Nil

  test("multi-curve account routes by curve match"):
    val ed25519Pub  = Array.fill(32)(0x07.toByte)
    val multiAccount = McpAccount(
      id         = "acct-2",
      label      = "Solana+EVM",
      publicKeys = Map(Curve.Secp256k1 -> dummyPub, Curve.Ed25519 -> ed25519Pub),
    )
    val client = new MockRemoteSigningClient(Seq(multiAccount), dummySig)
    val vault  = new McpVault("v2", client)
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    val sol = Await.result(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'"), 2.seconds)
    sol.curve           shouldBe Curve.Ed25519
    sol.publicKey.bytes shouldBe ed25519Pub
