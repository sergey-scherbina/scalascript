package scalascript.wallet.vault.encrypted

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/** Scala.js persistence helpers for [[EncryptedLocalVault]].
 *
 *  The encrypted vault core is shared across JVM and JS.  This file only
 *  supplies browser/Node storage adapters and convenience entry points that
 *  mirror the JVM `EncryptedLocalVaultFs` shape.
 */
trait VaultFileStore:
  def read(id: String)(using ExecutionContext): Future[Option[VaultFile]]
  def write(file: VaultFile)(using ExecutionContext): Future[Unit]
  def delete(id: String)(using ExecutionContext): Future[Unit]

/** In-memory store used by Node/Scala.js tests and as the final fallback when
 *  neither IndexedDB nor localStorage is present. */
final class MemoryVaultFileStore extends VaultFileStore:
  private val files = scala.collection.mutable.Map.empty[String, VaultFile]

  def read(id: String)(using ExecutionContext): Future[Option[VaultFile]] =
    Future.successful(files.get(id))

  def write(file: VaultFile)(using ExecutionContext): Future[Unit] =
    files.update(file.id, file)
    Future.successful(())

  def delete(id: String)(using ExecutionContext): Future[Unit] =
    files.remove(id)
    Future.successful(())

/** Synchronous localStorage-backed fallback.  Stores the same JSON shape as
 *  IndexedDB under `<prefix>:<vault-id>`. */
final class LocalStorageVaultFileStore(prefix: String = "ssc:vault") extends VaultFileStore:
  import LocalStorageVaultFileStore.storage

  def read(id: String)(using ExecutionContext): Future[Option[VaultFile]] =
    storage match
      case None => Future.successful(None)
      case Some(s) =>
        val raw = s.getItem(key(id)).asInstanceOf[String | Null]
        Future.successful(Option(raw).map(VaultFile.fromJson))

  def write(file: VaultFile)(using ExecutionContext): Future[Unit] =
    storage match
      case None => Future.failed(IllegalStateException("localStorage is not available"))
      case Some(s) =>
        s.setItem(key(file.id), VaultFile.toJson(file))
        Future.successful(())

  def delete(id: String)(using ExecutionContext): Future[Unit] =
    storage match
      case None => Future.successful(())
      case Some(s) =>
        s.removeItem(key(id))
        Future.successful(())

  private def key(id: String): String = s"$prefix:$id"

object LocalStorageVaultFileStore:
  def available: Boolean = storage.isDefined

  private def storage: Option[js.Dynamic] =
    val direct =
      if js.typeOf(js.Dynamic.global.localStorage) != "undefined" && js.Dynamic.global.localStorage != null then
        Some(js.Dynamic.global.localStorage)
      else None
    direct.orElse {
      if js.typeOf(js.Dynamic.global.window) != "undefined" && js.Dynamic.global.window != null &&
          js.typeOf(js.Dynamic.global.window.localStorage) != "undefined" && js.Dynamic.global.window.localStorage != null
      then Some(js.Dynamic.global.window.localStorage)
      else None
    }

/** IndexedDB-backed vault file store.  Values are stored as `{ id, json }`
 *  records so the durable representation stays the shared `VaultFile` JSON.
 */
final class IndexedDbVaultFileStore(
    dbName:    String = "scalascript-wallet-vaults",
    storeName: String = "vaults",
) extends VaultFileStore:

  def read(id: String)(using ec: ExecutionContext): Future[Option[VaultFile]] =
    withStore("readonly") { store =>
      requestFuture(store.get(id)) { result =>
        if js.isUndefined(result) || result == null then None
        else Some(VaultFile.fromJson(result.asInstanceOf[js.Dynamic].json.asInstanceOf[String]))
      }
    }.flatten

  def write(file: VaultFile)(using ec: ExecutionContext): Future[Unit] =
    withStore("readwrite") { store =>
      val record = js.Dynamic.literal("id" -> file.id, "json" -> VaultFile.toJson(file))
      requestFuture(store.put(record))(_ => ())
    }.flatten

  def delete(id: String)(using ec: ExecutionContext): Future[Unit] =
    withStore("readwrite") { store =>
      requestFuture(store.delete(id))(_ => ())
    }.flatten

  private def withStore[A](mode: String)(body: js.Dynamic => Future[A])(using ec: ExecutionContext): Future[Future[A]] =
    openDb().map { db =>
      val tx    = db.transaction(storeName, mode)
      val store = tx.objectStore(storeName)
      body(store).andThen { case _ => try db.close() catch case _: Throwable => () }
    }

  private def openDb(): Future[js.Dynamic] =
    val indexedDb = js.Dynamic.global.indexedDB
    val p = Promise[js.Dynamic]()
    val req = indexedDb.open(dbName, 1)
    req.onupgradeneeded = { (_: js.Dynamic) =>
      val db = req.result
      val names = db.objectStoreNames
      val exists =
        js.typeOf(names.contains) != "undefined" && names.contains(storeName).asInstanceOf[Boolean]
      if !exists then db.createObjectStore(storeName, js.Dynamic.literal("keyPath" -> "id"))
    }
    req.onsuccess = { (_: js.Dynamic) => p.success(req.result) }
    req.onerror = { (_: js.Dynamic) => p.failure(requestError(req, "IndexedDB open failed")) }
    p.future

  private def requestFuture[A](req: js.Dynamic)(read: js.Dynamic => A): Future[A] =
    val p = Promise[A]()
    req.onsuccess = { (_: js.Dynamic) => p.success(read(req.result)) }
    req.onerror = { (_: js.Dynamic) => p.failure(requestError(req, "IndexedDB request failed")) }
    p.future

  private def requestError(req: js.Dynamic, fallback: String): RuntimeException =
    val err = req.error
    val msg =
      if js.isUndefined(err) || err == null then fallback
      else
        val m = err.message
        if js.isUndefined(m) || m == null then fallback else m.toString
    RuntimeException(msg)

object IndexedDbVaultFileStore:
  def available: Boolean =
    js.typeOf(js.Dynamic.global.indexedDB) != "undefined" && js.Dynamic.global.indexedDB != null &&
      js.typeOf(js.Dynamic.global.indexedDB.open) == "function"

object VaultFileStores:
  private lazy val memoryFallback = MemoryVaultFileStore()

  def memory: MemoryVaultFileStore = memoryFallback

  def default(): VaultFileStore =
    if IndexedDbVaultFileStore.available then IndexedDbVaultFileStore()
    else if LocalStorageVaultFileStore.available then LocalStorageVaultFileStore()
    else memoryFallback

object EncryptedLocalVaultJs:
  def create(
    mnemonic: Mnemonic,
    password: String,
    store:    VaultFileStore = VaultFileStores.default(),
    accounts: Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
    kdfM:     Int = EncryptedLocalVault.DefaultM,
    kdfT:     Int = EncryptedLocalVault.DefaultT,
    kdfP:     Int = EncryptedLocalVault.DefaultP,
  )(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    EncryptedLocalVault
      .create(mnemonic, password, save = f => writeAndForget(store, f), accounts, kdfM, kdfT, kdfP)
      .flatMap(v => store.write(v.vaultFile).map(_ => v))

  def load(id: String, store: VaultFileStore = VaultFileStores.default())(using ec: ExecutionContext): Future[EncryptedLocalVault] =
    store.read(id).map {
      case Some(file) => EncryptedLocalVault.load(file, save = f => writeAndForget(store, f))
      case None       => throw new java.util.NoSuchElementException(s"Vault not found: $id")
    }

  def generate(
    password: String,
    store:    VaultFileStore = VaultFileStores.default(),
    accounts: Seq[VaultAccount] = Seq(VaultAccount("default", "Default", "m/44'/60'/0'/0/0")),
  )(using ec: ExecutionContext): Future[(EncryptedLocalVault, Mnemonic)] =
    val mnemonic = Bip39.generate()
    create(mnemonic, password, store, accounts).map(_ -> mnemonic)

  def delete(id: String, store: VaultFileStore = VaultFileStores.default())(using ec: ExecutionContext): Future[Unit] =
    store.delete(id)

  def save(vault: EncryptedLocalVault, store: VaultFileStore = VaultFileStores.default())(using ec: ExecutionContext): Future[Unit] =
    store.write(vault.vaultFile)

  private def writeAndForget(store: VaultFileStore, file: VaultFile)(using ec: ExecutionContext): Unit =
    store.write(file).failed.foreach(_ => ())
