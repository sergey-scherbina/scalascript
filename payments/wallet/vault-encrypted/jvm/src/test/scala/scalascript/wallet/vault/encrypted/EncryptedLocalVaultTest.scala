package scalascript.wallet.vault.encrypted

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.{UnlockCredential, VaultKind}
import java.nio.file.{Files, Path}

class EncryptedLocalVaultTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val TestPassword = "correct horse battery staple"
  private val TestMnemonic = Bip39.fromPhrase(
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
  ).getOrElse(sys.error("bad test mnemonic"))

  private def tmpPath(): Path =
    Files.createTempFile("vault-test-", ".json")

  // ── create + unlock ──────────────────────────────────────────────────────

  test("create: vault file is written to disk") {
    val path = tmpPath()
    Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    assert(Files.size(path) > 0)
  }

  test("create: vault starts unlocked after creation") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    assert(!vault.isLocked)
    assert(vault.kind == VaultKind.EncryptedLocal)
  }

  test("load + unlock with correct password") {
    val path = tmpPath()
    Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val v2 = Await.result(EncryptedLocalVaultFs.load(path), 5.seconds)
    assert(v2.isLocked)
    Await.result(v2.unlock(UnlockCredential.Password(TestPassword)), 30.seconds)
    assert(!v2.isLocked)
  }

  test("unlock with wrong password fails") {
    val path = tmpPath()
    Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val v2 = Await.result(EncryptedLocalVaultFs.load(path), 5.seconds)
    val ex = intercept[Exception] {
      Await.result(v2.unlock(UnlockCredential.Password("wrong")), 30.seconds)
    }
    assert(ex != null)
    assert(v2.isLocked)
  }

  test("lock clears the seed") {
    val path = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    vault.lock()
    assert(vault.isLocked)
    val ex = intercept[Exception] {
      Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    }
    assert(ex.getMessage.contains("locked"))
  }

  // ── key derivation ────────────────────────────────────────────────────────

  test("getSigner: secp256k1 key has 64-byte public key") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    assert(signer.curve == Curve.Secp256k1)
    assert(signer.publicKey.bytes.length == 64)
  }

  test("getSigner: ed25519 key has 32-byte public key") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'/0'"), 5.seconds)
    assert(signer.curve == Curve.Ed25519)
    assert(signer.publicKey.bytes.length == 32)
  }

  test("getSigner: deterministic — same path gives same public key") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val s1 = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    val s2 = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    assert(s1.publicKey.bytes.toSeq == s2.publicKey.bytes.toSeq)
  }

  test("getSigner: different paths give different keys") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val s0 = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    val s1 = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/1"), 5.seconds)
    assert(s0.publicKey.bytes.toSeq != s1.publicKey.bytes.toSeq)
  }

  test("getSigner: sign + verify round-trip") {
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    val msg    = "hello vault".getBytes("UTF-8")
    val sig    = Await.result(signer.sign(msg, HashAlgo.Sha256), 5.seconds)
    import scalascript.crypto.CryptoBackend
    val ok = CryptoBackend.get().verify(Curve.Secp256k1, signer.publicKey.bytes, msg, sig, HashAlgo.Sha256)
    assert(ok)
  }

  // ── persistence round-trip ───────────────────────────────────────────────

  test("key derivation is identical after load+unlock") {
    val path  = tmpPath()
    Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val v2 = Await.result(EncryptedLocalVaultFs.load(path), 5.seconds)
    Await.result(v2.unlock(UnlockCredential.Password(TestPassword)), 30.seconds)
    val signer = Await.result(v2.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    // Known secp256k1 pubkey from all-zeros mnemonic "abandon...about" at this path
    assert(signer.publicKey.bytes.length == 64)
    // Determinism: create a fresh vault with same mnemonic and compare
    val path2  = tmpPath()
    val vault2 = Await.result(EncryptedLocalVaultFs.create(path2, TestMnemonic, "differentpw",
      kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val s2 = Await.result(vault2.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    assert(signer.publicKey.bytes.toSeq == s2.publicKey.bytes.toSeq)
  }

  // ── accounts ─────────────────────────────────────────────────────────────

  test("listAccounts returns configured accounts") {
    val accs  = Seq(VaultAccount("a1", "Main", "m/44'/60'/0'/0/0"),
                    VaultAccount("a2", "Solana", "m/44'/501'/0'/0'"))
    val path  = tmpPath()
    val vault = Await.result(EncryptedLocalVaultFs.create(path, TestMnemonic, TestPassword,
      accounts = accs, kdfM = 256, kdfT = 1, kdfP = 1), 30.seconds)
    val listed = Await.result(vault.listAccounts(), 5.seconds)
    assert(listed.map(_.id).toSet == Set("a1", "a2"))
  }

  // ── generate helper ───────────────────────────────────────────────────────

  test("generate: produces a 24-word mnemonic and unlocked vault") {
    val path         = tmpPath()
    val (vault, mne) = Await.result(EncryptedLocalVaultFs.generate(path, TestPassword), 30.seconds)
    assert(mne.words.size == 24)
    assert(!vault.isLocked)
  }
