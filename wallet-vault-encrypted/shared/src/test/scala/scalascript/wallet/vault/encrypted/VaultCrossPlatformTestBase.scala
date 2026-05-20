package scalascript.wallet.vault.encrypted

import org.scalatest.funsuite.{AnyFunSuite, AsyncFunSuite}
import scala.concurrent.ExecutionContext
import scalascript.crypto.CryptoBackend

/** Cross-platform conformance for the encrypted-vault Stage-5 crypto
 *  flow: same `(password, mnemonic, salt, iv)` produces the same
 *  AES-GCM ciphertext on JVM (BouncyCastle) and JS (noble), and the
 *  resulting blob decrypts to the same 64-byte BIP-39 seed.
 *
 *  Lives in `shared/`; the platform-specific subclass registers its
 *  `CryptoBackend` impl in `beforeAll`.  Synchronous tests
 *  (`AnyFunSuite`) only — for `Future`-returning operations see
 *  `VaultCrossPlatformAsyncTestBase`. */
abstract class VaultCrossPlatformTestBase extends AnyFunSuite:

  protected def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  protected def fromHex(s: String): Array[Byte] =
    s.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray

  // Known BIP-39 test vector: "abandon"*11 + "about" / passphrase=""
  //   PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048, 64) =
  //   0x5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1
  //     9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4
  protected val KnownSeed: Array[Byte] = fromHex(
    "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
    "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
  )

  test("BIP-39 seed for 'abandon...about' empty passphrase matches Trezor vector"):
    val m = Bip39.fromPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
      .getOrElse(sys.error("parse failed"))
    val seed = m.toSeed("")
    assert(hex(seed) == hex(KnownSeed))

  test("AES-GCM encrypt of BIP-39 seed under Argon2id-derived key — fixed vector"):
    // Fixed inputs that exercise the full vault encrypt-path
    // (Argon2id KDF → AES-256-GCM AEAD).  Hex string is generated once
    // from the JVM (BouncyCastle) backend and asserted byte-identical
    // on both platforms — JVM via `cryptoBouncycastle`, JS via
    // `crypto-noble-js`.  Drift in any of (PBKDF2, Argon2id, AES-GCM)
    // would surface here.
    val backend  = CryptoBackend.get()
    val password = "stage-5-known-vector".getBytes("UTF-8")
    val salt     = fromHex("00000000000000000000000000000001")  // 16 B
    val iv       = fromHex("000000000000000000000002")          // 12 B
    val aad      = "vault-aad".getBytes("UTF-8")
    val key      = backend.argon2id(password, salt, 256, 1, 1, 32)
    val ct       = backend.aesGcmEncrypt(key, iv, KnownSeed, aad)
    assert(hex(ct) == VaultCrossPlatformTestBase.ExpectedCiphertextHex)
    val pt = backend.aesGcmDecrypt(key, iv, ct, aad)
    assert(java.util.Arrays.equals(pt, KnownSeed))

object VaultCrossPlatformTestBase:
  /** Generated once with BouncyCastle; both platforms must reproduce it
   *  byte-for-byte.  See the matching test in `VaultCrossPlatformTestBase`.  */
  val ExpectedCiphertextHex: String =
    "831b5fb88f923cedbc070874a3b46e9fd9606032091e6085a6f09b704dd5326e" +
    "853faed8673ccede07b517bc41a3fbe7204a2dbc65c537b52f528f99f41b2df9" +
    "2c3b19296fe2d07d7f3c9d5a56f15a92"

/** Async cross-platform tests for [[EncryptedLocalVault]] — the
 *  `Future`-returning lifecycle methods (`create` / `unlock` /
 *  `getSigner`).  Uses `AsyncFunSuite` so the same test code runs on
 *  Scala.js where `Await.result` is unavailable. */
abstract class VaultCrossPlatformAsyncTestBase extends AsyncFunSuite:

  override implicit def executionContext: ExecutionContext = ExecutionContext.global

  test("vault round-trip: create → JSON round-trip → reopen → unlock"):
    val mnemonic = Bip39.fromPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
      .getOrElse(sys.error("parse failed"))
    var saved: Option[VaultFile] = None
    for
      vault <- EncryptedLocalVault.create(
                 mnemonic = mnemonic,
                 password = "swordfish",
                 save     = f => saved = Some(f),
                 kdfM     = 256,
                 kdfT     = 1,
                 kdfP     = 1,
               )
      savedFile  = saved.getOrElse(sys.error("save callback not invoked"))
      // JSON round-trip preserves structure
      _          = assert(VaultFile.fromJson(VaultFile.toJson(savedFile)).id == savedFile.id)
      reopened   = EncryptedLocalVault.load(savedFile)
      _          = assert(reopened.isLocked)
      // Wrong password rejected
      wrongResult <- reopened.unlock(scalascript.wallet.spi.UnlockCredential.Password("wrong"))
                       .map(_ => "ok").recover { case _ => "rejected" }
      _ = assert(wrongResult == "rejected")
      _ = assert(reopened.isLocked)
      // Right password unlocks
      _ <- reopened.unlock(scalascript.wallet.spi.UnlockCredential.Password("swordfish"))
    yield assert(!reopened.isLocked && !vault.isLocked)
