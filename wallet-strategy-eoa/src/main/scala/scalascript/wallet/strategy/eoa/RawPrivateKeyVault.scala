package scalascript.wallet.strategy.eoa

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** In-memory `Vault` holding raw private keys (one per curve). For
 *  tests and trusted CLI automation. Production code should use
 *  `wallet-vault-encrypted` (Phase 2) or a hardware vault (Phase 7).
 *
 *  The vault is always unlocked — there is no credential to gate
 *  access. `unlock` is a no-op; `lock` discards the keys. */
class RawPrivateKeyVault(
  val id:         String,
  private var keys: Map[Curve, Array[Byte]],
)(using ec: ExecutionContext) extends Vault:

  @volatile private var locked = false

  def kind: VaultKind = VaultKind.InMemory

  def isLocked: Boolean = locked

  def unlock(credential: UnlockCredential): Future[Unit] =
    locked = false
    Future.successful(())

  def lock(): Unit =
    keys   = Map.empty
    locked = true

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    if locked then Future.successful(Nil)
    else Future {
      val backend = CryptoBackend.get()
      keys.map { case (curve, priv) =>
        val pubBytes = backend.derivePublic(curve, priv)
        AccountDescriptor(
          id             = s"$id:${curve}",
          label          = s"$id (${curve})",
          publicKeys     = Map(curve -> PublicKey(curve, pubBytes)),
          derivationPath = "raw",
        )
      }.toSeq
    }

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if locked then
      Future.failed(new IllegalStateException(s"Vault $id is locked"))
    else
      keys.get(curve) match
        case None =>
          Future.failed(new IllegalArgumentException(s"Vault $id has no key for $curve"))
        case Some(privKey) =>
          Future {
            val backend  = CryptoBackend.get()
            val pubBytes = backend.derivePublic(curve, privKey)
            new RawPrivateKeySigner(backend, curve, privKey, PublicKey(curve, pubBytes))
          }

private class RawPrivateKeySigner(
  backend:        CryptoBackend,
  val curve:      Curve,
  privKey:        Array[Byte],
  val publicKey:  PublicKey,
)(using ec: ExecutionContext) extends RawSigner:

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    Future(backend.sign(curve, privKey, msg, hash))

object RawPrivateKeyVault:
  /** Construct a vault from a single hex-encoded private key, defaulted
   *  to the secp256k1 curve. Convenient for x402 / EVM tests. */
  def fromHex(id: String, hex: String, curve: Curve = Curve.Secp256k1)(using ExecutionContext): RawPrivateKeyVault =
    val bytes = decodeHex(hex)
    new RawPrivateKeyVault(id, Map(curve -> bytes))

  private def decodeHex(s: String): Array[Byte] =
    val clean = if s.startsWith("0x") || s.startsWith("0X") then s.substring(2) else s
    require(clean.length % 2 == 0, s"Hex string has odd length: ${clean.length}")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
