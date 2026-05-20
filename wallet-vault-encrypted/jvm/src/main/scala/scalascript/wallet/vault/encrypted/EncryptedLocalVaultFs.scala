package scalascript.wallet.vault.encrypted

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

/** Filesystem-backed convenience entry points for the cross-compiled
 *  [[EncryptedLocalVault]].  Wraps the shared core with `java.nio.file`
 *  read / write, preserving the pre-Stage-5 JVM API surface
 *  (`create(path, mnemonic, password, ...)`, `load(path)`).  All file
 *  I/O is JVM-only by construction — the Scala.js side calls the
 *  shared `EncryptedLocalVault.create / load` directly with its own
 *  IndexedDB-backed `save` callback (deferred). */
object EncryptedLocalVaultFs:

  def create(
    path:      Path,
    mnemonic:  Mnemonic,
    password:  String,
    accounts:  Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
    kdfM:      Int = EncryptedLocalVault.DefaultM,
    kdfT:      Int = EncryptedLocalVault.DefaultT,
    kdfP:      Int = EncryptedLocalVault.DefaultP,
  )(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    if path.getParent != null then Files.createDirectories(path.getParent)
    EncryptedLocalVault.create(
      mnemonic = mnemonic,
      password = password,
      save     = file => VaultFileIo.write(file, path),
      accounts = accounts,
      kdfM     = kdfM,
      kdfT     = kdfT,
      kdfP     = kdfP,
    )

  def load(path: Path)(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    Future {
      val file = VaultFileIo.read(path)
      EncryptedLocalVault.load(file, save = f => VaultFileIo.write(f, path))
    }

  def generate(
    path:     Path,
    password: String,
    accounts: Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
  )(using ec: ExecutionContext): Future[(EncryptedLocalVault, Mnemonic)] =
    val m = Bip39.generate()
    create(path, m, password, accounts).map(_ -> m)
