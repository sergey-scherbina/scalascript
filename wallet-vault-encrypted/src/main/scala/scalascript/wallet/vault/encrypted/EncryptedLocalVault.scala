package scalascript.wallet.vault.encrypted

import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** File-system-backed vault using BIP-39 + Argon2id + AES-256-GCM.
 *
 *  Lifecycle:
 *  {{{
 *  val v = EncryptedLocalVault.create(path, mnemonic, "hunter2")
 *  // later:
 *  val v2 = EncryptedLocalVault.load(path)
 *  v2.unlock(UnlockCredential.Password("hunter2"))
 *  val signer = v2.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0")
 *  }}}
 *
 *  The vault file contains the encrypted 64-byte BIP-39 seed.
 *  Individual signing keys are derived on demand via HD derivation.
 *
 *  KDF parameters (conservative defaults):
 *  - Argon2id: m=65536 KiB (64 MiB), t=3, p=1
 *  - AES-256-GCM: 12-byte random IV per write */
class EncryptedLocalVault private (
  private var file: VaultFile,
  private val path: Path,
)(using ec: ExecutionContext) extends Vault:

  @volatile private var _seed:   Option[Array[Byte]] = None
  @volatile private var _locked: Boolean             = true

  def kind: VaultKind = VaultKind.EncryptedLocal
  def id:   String    = file.id
  def isLocked: Boolean = _locked

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

  /** Add a new account entry (does not re-encrypt; call `save()` after). */
  def addAccount(account: VaultAccount): Unit =
    file = file.copy(accounts = file.accounts :+ account)

  /** Persist the current account list to disk (seed stays encrypted as-is). */
  def saveAccounts(): Unit = VaultFile.write(file, path)

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

  /** Create a new encrypted vault from a BIP-39 mnemonic and write it to `path`. */
  def create(
    path:      Path,
    mnemonic:  Mnemonic,
    password:  String,
    accounts:  Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
    kdfM:      Int = DefaultM,
    kdfT:      Int = DefaultT,
    kdfP:      Int = DefaultP,
  )(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    Future {
      val rng     = new SecureRandom()
      val salt    = randomBytes(rng, 16)
      val iv      = randomBytes(rng, 12)
      val vaultId = UUID.randomUUID().toString
      val aad     = vaultId.getBytes("UTF-8")
      val backend = CryptoBackend.get()
      val seed    = mnemonic.toSeed()
      val key     = backend.argon2id(password.getBytes("UTF-8"), salt, kdfM, kdfT, kdfP, 32)
      val ct      = backend.aesGcmEncrypt(key, iv, seed, aad)
      val file    = VaultFile(1, vaultId, accounts, kdfM, kdfT, kdfP, salt, iv, aad, ct)
      if path.getParent != null then Files.createDirectories(path.getParent)
      VaultFile.write(file, path)
      val vault   = new EncryptedLocalVault(file, path)
      vault._seed   = Some(seed)
      vault._locked = false
      vault
    }

  /** Load a vault from disk (locked; call `unlock` before use). */
  def load(path: Path)(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    Future {
      val file = VaultFile.read(path)
      new EncryptedLocalVault(file, path)
    }

  /** Generate a fresh mnemonic and create the vault in one step. */
  def generate(
    path:     Path,
    password: String,
    accounts: Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
  )(using ec: ExecutionContext): Future[(EncryptedLocalVault, Mnemonic)] =
    val m = Bip39.generate()
    create(path, m, password, accounts).map(_ -> m)

  private def randomBytes(rng: SecureRandom, n: Int): Array[Byte] =
    val buf = new Array[Byte](n)
    rng.nextBytes(buf)
    buf

private class HdRawSigner(
  backend:       CryptoBackend,
  val curve:     Curve,
  privKey:       Array[Byte],
  val publicKey: PublicKey,
)(using ec: ExecutionContext) extends RawSigner:
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    Future(backend.sign(curve, privKey, msg, hash))
