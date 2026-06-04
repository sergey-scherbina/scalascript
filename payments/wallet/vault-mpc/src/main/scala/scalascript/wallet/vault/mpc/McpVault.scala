package scalascript.wallet.vault.mpc

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** `Vault` impl that delegates every signing operation to an external
 *  MPC / threshold-signing provider over the `RemoteSigningClient`
 *  abstraction.
 *
 *  Unlike `RawPrivateKeyVault` / `EncryptedLocalVault`, this vault
 *  never holds full private keys. Each signature requires a network
 *  round-trip: the provider runs the actual threshold protocol
 *  (TSS / GG18 / CMP for ECDSA, FROST for Ed25519) across its node
 *  set and returns the assembled signature.
 *
 *  "Unlocked" semantics: the vault is unlocked iff the underlying
 *  client reports `health() == true`. `unlock` just runs the probe;
 *  `lock` flips the cached flag — callers should drop and recreate
 *  the underlying client to force re-authentication.
 *
 *  See docs/specs/wallet-spi.md §5 / §10 Phase 8. */
class McpVault(
  val id:    String,
  client:    RemoteSigningClient,
)(using ec: ExecutionContext) extends Vault:

  @volatile private var locked = true

  def kind: VaultKind = VaultKind.Mpc

  def isLocked: Boolean = locked

  def unlock(credential: UnlockCredential): Future[Unit] =
    client.health().map { ok =>
      locked = !ok
      if !ok then
        throw new IllegalStateException(
          s"MPC vault $id health check failed; provider unreachable or credentials invalid"
        )
    }

  def lock(): Unit =
    locked = true

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    if locked then Future.successful(Nil)
    else
      client.listAccounts().map { mpcAccounts =>
        mpcAccounts.map { acc =>
          AccountDescriptor(
            id             = acc.id,
            label          = acc.label,
            publicKeys     = acc.publicKeys.map { case (curve, bytes) =>
                               curve -> PublicKey(curve, bytes)
                             },
            // MPC vaults don't expose HD paths to the client — the
            // provider may use a derivation tree internally but it is
            // not visible here. Use the conventional "mpc:" placeholder
            // mirroring the in-memory vault's "raw:" / encrypted vault's
            // BIP-44 paths.
            derivationPath = s"mpc/${acc.id}",
          )
        }
      }

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if locked then
      Future.failed(new IllegalStateException(s"MPC vault $id is locked"))
    else
      // The vault routes a (curve, path) request to the account whose
      // recorded public keys include that curve. In production deployments
      // a vault is typically tied to a single accountId per `McpVault`
      // instance — the listAccounts call lets that be discovered at
      // unlock time without hard-coding it in client config.
      client.listAccounts().flatMap { accounts =>
        accounts.find(_.publicKeys.contains(curve)) match
          case None =>
            Future.failed(new IllegalArgumentException(
              s"MPC vault $id has no account with a $curve public key"
            ))
          case Some(acc) =>
            val pub = PublicKey(curve, acc.publicKeys(curve))
            Future.successful(
              new McpRemoteSigner(client, acc.id, curve, derivationPath, pub)
            )
      }

/** `RawSigner` that, on every call, asks the remote provider to run a
 *  threshold-signing round for `(accountId, curve, derivationPath)`. */
class McpRemoteSigner(
  client:        RemoteSigningClient,
  accountId:     String,
  val curve:     Curve,
  derivationPath: String,
  val publicKey: PublicKey,
) extends RawSigner:

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    client.sign(accountId, curve, derivationPath, msg, hash)
