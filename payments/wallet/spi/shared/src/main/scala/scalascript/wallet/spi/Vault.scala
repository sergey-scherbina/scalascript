package scalascript.wallet.spi

import scala.concurrent.Future
import scalascript.crypto.{Curve, PublicKey}

/** Discriminator for the storage backing a vault — used by hosts for
 *  audit logging, UI affordances ("hardware key required"), and
 *  policy decisions (cf. mcp-wallet `Policy.allowedVaultKinds`). */
enum VaultKind:
  case EncryptedLocal
  case Mpc
  case Hardware
  case Passkey
  case InMemory

sealed trait UnlockCredential

object UnlockCredential:
  case class Password(value: String) extends UnlockCredential
  case object Biometric              extends UnlockCredential
  case object None                   extends UnlockCredential

/** UI- and policy-facing summary of a wallet account. */
case class AccountDescriptor(
  id:             String,
  label:          String,
  publicKeys:     Map[Curve, PublicKey],
  derivationPath: String,
)

/** Storage for one or more signing keys behind a uniform fetching
 *  interface. Implementations:
 *  - `RawPrivateKeyVault` — in-memory, for tests / CLI automation
 *    (lives in `wallet-strategy-eoa`)
 *  - `EncryptedLocalVault` — file / IndexedDB with Argon2id+AES-GCM
 *    (wallet-spi Phase 2)
 *  - `LedgerVault` — hardware (wallet-spi Phase 7); see also
 *    docs/specs/wallet-spi.md §5.1
 *  - `MpcVault` — remote threshold-signing provider (Phase 8)
 *  - `PasskeyVault` — WebAuthn (Phase 6 owner for ERC-4337)
 *
 *  See docs/specs/wallet-spi.md §5. */
trait Vault:
  def kind: VaultKind
  def id: String
  def isLocked: Boolean
  def unlock(credential: UnlockCredential): Future[Unit]
  def lock(): Unit

  def listAccounts(): Future[Seq[AccountDescriptor]]

  /** Sole factory for chain-agnostic signers. The Vault is responsible
   *  for routing `(curve, derivationPath)` to the right key material;
   *  hardware vaults additionally route to the right on-device app.
   *  Throws (or fails the Future) when the requested curve is not
   *  supported by the vault. */
  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner]
