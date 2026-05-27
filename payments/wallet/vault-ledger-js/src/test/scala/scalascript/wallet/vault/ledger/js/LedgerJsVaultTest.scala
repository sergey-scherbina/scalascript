package scalascript.wallet.vault.ledger.js

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scalascript.crypto.Curve
import scalascript.wallet.spi.{UnlockCredential, VaultKind}
import scalascript.wallet.vault.ledger.Bip32Path

class LedgerJsVaultTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  private def ok(payload: Array[Byte]): Array[Byte] = payload ++ Array[Byte](0x90.toByte, 0x00)

  private def appInfo(name: String): Array[Byte] =
    val n = name.getBytes("UTF-8")
    val v = "1.0.0".getBytes("UTF-8")
    val p = Array[Byte](1, n.length.toByte) ++ n ++ Array[Byte](v.length.toByte) ++ v
    ok(p)

  private def pubKeyPayload(): Array[Byte] =
    val key = Array.tabulate[Byte](65)(_.toByte)
    val addr = "0x0000000000000000000000000000000000000001".getBytes("US-ASCII")
    ok(Array[Byte](65) ++ key ++ Array[Byte](addr.length.toByte) ++ addr)

  test("LedgerJsVault implements hardware Vault lifecycle"):
    val device = MockWebHidDevice()
    val vault = LedgerJsVault(WebHidLedgerTransport(device))
    for
      _ <- vault.unlock(UnlockCredential.None)
      _ = assert(vault.kind == VaultKind.Hardware)
      _ = assert(!vault.isLocked)
      accounts <- vault.listAccounts()
      _ = vault.lock()
    yield
      assert(accounts.head.derivationPath == Bip32Path.DefaultEthereum)
      assert(vault.isLocked)

  test("LedgerJsVault gets an Ethereum signer through WebHID transport"):
    val device = MockWebHidDevice()
    device.queueApdu(appInfo("Ethereum"))
    device.queueApdu(pubKeyPayload())
    val vault = LedgerJsVault(WebHidLedgerTransport(device))
    for
      _ <- vault.unlock(UnlockCredential.None)
      signer <- vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum)
    yield
      assert(signer.curve == Curve.Secp256k1)
      assert(signer.publicKey.bytes.length == 65)
      assert(device.sentReports.nonEmpty)
