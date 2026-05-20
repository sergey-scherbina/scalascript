package scalascript.wallet.vault.encrypted

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** Encrypted vault using BIP-39 + Argon2id + AES-256-GCM, parameterised
 *  over a pluggable save sink.
 *
 *  Lifecycle:
 *  {{{
 *  val (vault, file) = EncryptedLocalVault.create(mnemonic, "hunter2", save = _ => ())
 *  // later:
 *  val v2 = EncryptedLocalVault.load(file, save = _ => ())
 *  v2.unlock(UnlockCredential.Password("hunter2"))
 *  val signer = v2.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0")
 *  }}}
 *
 *  The vault file contains the encrypted 64-byte BIP-39 seed.
 *  Individual signing keys are derived on demand via HD derivation.
 *
 *  KDF parameters (conservative defaults):
 *  - Argon2id: m=65536 KiB (64 MiB), t=3, p=1
 *  - AES-256-GCM: 12-byte random IV per write
 *
 *  Cross-compiled (JVM + Scala.js).  All crypto operations route through
 *  `CryptoBackend.get()`, which resolves to BouncyCastle on JVM and
 *  `crypto-noble-js` on Scala.js (both implementations registered
 *  through the shared `object CryptoBackend.register(...)` surface).
 *
 *  The JVM-only path-based variant lives next to this in `jvm/`
 *  (`EncryptedLocalVaultFs`); on Scala.js a future IndexedDB-backed
 *  helper will pass a `save` callback that persists JSON to the
 *  browser's storage layer. */
class EncryptedLocalVault private (
  private var file: VaultFile,
  private val save: VaultFile => Unit,
)(using ec: ExecutionContext) extends Vault:

  @volatile private var _seed:   Option[Array[Byte]] = None
  @volatile private var _locked: Boolean             = true

  def kind: VaultKind = VaultKind.EncryptedLocal
  def id:   String    = file.id
  def isLocked: Boolean = _locked

  /** Inspect the underlying vault file (e.g. to re-serialise / persist
   *  via a custom store).  Read-only — call `addAccount` / `saveAccounts`
   *  to mutate. */
  def vaultFile: VaultFile = file

  def unlock(credential: UnlockCredential): Future[Unit] =
    credential match
      case UnlockCredential.Password(pw) =>
        Future {
          val backend = CryptoBackend.get()
          val key     = backend.argon2id(pw.getBytes("UTF-8"), file.kdfSalt,
                          file.kdfM, file.kdfT, file.kdfP, 32)
          val seed    = backend.aesGcmDecrypt(key, file.iv, file.ciphertext, file.aad)
          _seed   = Some(seed)
          _locked = false
        }
      case _ =>
        Future.failed(new UnsupportedOperationException("EncryptedLocalVault only supports Password unlock"))

  def lock(): Unit =
    _seed   = None
    _locked = true

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    Future.successful(
      file.accounts.map { a =>
        AccountDescriptor(
          id             = a.id,
          label          = a.label,
          publicKeys     = Map.empty, // public keys computed lazily on getSigner
          derivationPath = a.derivationPath,
        )
      }
    )

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    _seed match
      case None =>
        Future.failed(new IllegalStateException(s"Vault ${file.id} is locked"))
      case Some(seed) =>
        Future {
          val backend  = CryptoBackend.get()
          val privKey  = deriveKey(backend, curve, seed, derivationPath)
          val pubBytes = backend.derivePublic(curve, privKey)
          new HdRawSigner(backend, curve, privKey, PublicKey(curve, pubBytes))
        }

  /** Add a new account entry (does not re-encrypt; call `saveAccounts()` after). */
  def addAccount(account: VaultAccount): Unit =
    file = file.copy(accounts = file.accounts :+ account)

  /** Persist the current account list through the configured `save`
   *  sink (seed stays encrypted as-is).  No-op for transient vaults
   *  that pass `save = _ => ()`. */
  def saveAccounts(): Unit = save(file)

  private def deriveKey(backend: CryptoBackend, curve: Curve, seed: Array[Byte], path: String): Array[Byte] =
    val segments = parsePath(path)
    val master   = backend.deriveMaster(curve, seed)
    val leaf     = segments.foldLeft(master) { case (node, (index, hardened)) =>
      backend.deriveChild(curve, node, index, hardened)
    }
    leaf.privateKey

  private def parsePath(path: String): Seq[(Long, Boolean)] =
    val clean = if path.startsWith("m/") then path.drop(2) else path
    if clean.isEmpty then Seq.empty
    else clean.split('/').map { seg =>
      if seg.endsWith("'") then (seg.dropRight(1).toLong, true)
      else                      (seg.toLong, false)
    }.toSeq

object EncryptedLocalVault:

  val DefaultM = 65536
  val DefaultT = 3
  val DefaultP = 1

  /** Build a new encrypted vault from a BIP-39 mnemonic + password and
   *  hand the resulting `VaultFile` to `save` for persistence (file I/O
   *  on JVM, IndexedDB on JS, in-memory in tests).  Vault is returned
   *  already unlocked. */
  def create(
    mnemonic:  Mnemonic,
    password:  String,
    save:      VaultFile => Unit,
    accounts:  Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
    kdfM:      Int = DefaultM,
    kdfT:      Int = DefaultT,
    kdfP:      Int = DefaultP,
  )(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    Future {
      val backend = CryptoBackend.get()
      val salt    = backend.randomBytes(16)
      val iv      = backend.randomBytes(12)
      val vaultId = randomVaultId(backend)
      val aad     = vaultId.getBytes("UTF-8")
      val seed    = mnemonic.toSeed()
      val key     = backend.argon2id(password.getBytes("UTF-8"), salt, kdfM, kdfT, kdfP, 32)
      val ct      = backend.aesGcmEncrypt(key, iv, seed, aad)
      val file    = VaultFile(1, vaultId, accounts, kdfM, kdfT, kdfP, salt, iv, aad, ct)
      save(file)
      val vault   = new EncryptedLocalVault(file, save)
      vault._seed   = Some(seed)
      vault._locked = false
      vault
    }

  /** Wrap an already-deserialised [[VaultFile]] in a locked vault.
   *  Call `unlock(...)` before signing. */
  def load(file: VaultFile, save: VaultFile => Unit = _ => ())(using ec: ExecutionContext): EncryptedLocalVault =
    new EncryptedLocalVault(file, save)

  /** Generate a fresh mnemonic + create the vault in one step.  Returns
   *  the unlocked vault and the generated mnemonic (caller is
   *  responsible for surfacing the mnemonic to the user once). */
  def generate(
    password: String,
    save:     VaultFile => Unit,
    accounts: Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
  )(using ec: ExecutionContext): Future[(EncryptedLocalVault, Mnemonic)] =
    val m = Bip39.generate()
    create(m, password, save, accounts).map(_ -> m)

  /** Generate a UUID-shaped vault id from 16 secure-random bytes —
   *  avoids `java.util.UUID.randomUUID()`, which on Scala.js reaches
   *  into `java.security.SecureRandom` (not shimmed).  Uses RFC 4122
   *  version-4 / variant-1 bit twiddling for layout-compatible output. */
  private def randomVaultId(backend: CryptoBackend): String =
    val b = backend.randomBytes(16)
    // RFC 4122 §4.4: set version 4 (random) + variant 10xx.
    b(6) = ((b(6) & 0x0f) | 0x40).toByte
    b(8) = ((b(8) & 0x3f) | 0x80).toByte
    val hex = b.map(x => f"${x & 0xff}%02x").mkString
    s"${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"

private class HdRawSigner(
  backend:       CryptoBackend,
  val curve:     Curve,
  privKey:       Array[Byte],
  val publicKey: PublicKey,
)(using ec: ExecutionContext) extends RawSigner:
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    Future(backend.sign(curve, privKey, msg, hash))
