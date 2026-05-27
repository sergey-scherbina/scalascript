package scalascript.wallet.vault.encrypted

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AsyncFunSuite

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend
import scalascript.wallet.spi.UnlockCredential

class EncryptedLocalVaultJsTest extends AsyncFunSuite with BeforeAndAfterAll:
  override implicit def executionContext = scala.concurrent.ExecutionContext.global

  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)

  private def mnemonic: Mnemonic =
    Bip39.fromPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
      .getOrElse(sys.error("parse failed"))

  test("MemoryVaultFileStore: create persists, load reopens, unlock succeeds"):
    val store = MemoryVaultFileStore()
    for
      vault <- EncryptedLocalVaultJs.create(
                 mnemonic = mnemonic,
                 password = "swordfish",
                 store    = store,
                 kdfM     = 256,
                 kdfT     = 1,
                 kdfP     = 1,
               )
      id       = vault.id
      reopened <- EncryptedLocalVaultJs.load(id, store)
      _        = assert(reopened.isLocked)
      _       <- reopened.unlock(UnlockCredential.Password("swordfish"))
    yield assert(!reopened.isLocked)

  test("saveAccounts persists account metadata through the JS store callback"):
    val store = MemoryVaultFileStore()
    val extra = VaultAccount("eth-1", "Ethereum 1", "m/44'/60'/0'/0/1")
    for
      vault <- EncryptedLocalVaultJs.create(
                 mnemonic = mnemonic,
                 password = "swordfish",
                 store    = store,
                 kdfM     = 256,
                 kdfT     = 1,
                 kdfP     = 1,
               )
      _ = vault.addAccount(extra)
      _ = vault.saveAccounts()
      reopened <- EncryptedLocalVaultJs.load(vault.id, store)
      accounts <- reopened.listAccounts()
    yield assert(accounts.exists(a => a.id == "eth-1" && a.derivationPath == extra.derivationPath))

  test("delete removes a persisted JS vault"):
    val store = MemoryVaultFileStore()
    for
      vault <- EncryptedLocalVaultJs.create(
                 mnemonic = mnemonic,
                 password = "swordfish",
                 store    = store,
                 kdfM     = 256,
                 kdfT     = 1,
                 kdfP     = 1,
               )
      _      <- EncryptedLocalVaultJs.delete(vault.id, store)
      loaded <- EncryptedLocalVaultJs.load(vault.id, store).map(_ => "found").recover { case _ => "missing" }
    yield assert(loaded == "missing")
