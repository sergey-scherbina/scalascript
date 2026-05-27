package scalascript.wallet.vault.ledger.solana

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.vault.ledger.{AppSwitchRequired, Bip32Path, CurveAppRouting, Dashboard, LedgerTransport}

/** A [[Vault]] backed by a Ledger device running the on-device
 *  Solana app. Only `Curve.Ed25519` is supported — for other curves
 *  use a curve-specific vault (`LedgerEthereumVault`, etc.).
 *
 *  Lifecycle:
 *  - `unlock` — opens the underlying transport. The device PIN is
 *    entered out-of-band.
 *  - `getSigner` — probes `Dashboard.getAppName` first; raises
 *    [[AppSwitchRequired]] if the Solana app is not active.
 *    Returns a [[LedgerSolanaRawSigner]] bound to the given path.
 *  - `lock` — closes the transport (best-effort). */
class LedgerSolanaVault(
  transport: LedgerTransport,
  val id:    String = "ledger-solana",
)(using ec: ExecutionContext) extends Vault:

  @volatile private var unlocked: Boolean = false

  def kind: VaultKind   = VaultKind.Hardware
  def isLocked: Boolean = !unlocked

  def unlock(credential: UnlockCredential): Future[Unit] =
    val _ = credential
    transport.open().map(_ => unlocked = true)

  def lock(): Unit =
    unlocked = false
    val _ = transport.close()

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    Future.successful(Seq(
      AccountDescriptor(
        id             = "ledger-solana-0",
        label          = "Ledger Solana #0",
        publicKeys     = Map.empty,   // populated lazily by getSigner
        derivationPath = Bip32Path.DefaultSolana,
      )
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if curve != Curve.Ed25519 then
      Future.failed(new UnsupportedOperationException(
        s"LedgerSolanaVault only supports Ed25519 (got $curve). " +
        s"Use a curve-specific Ledger vault (Ethereum, Bitcoin, …) for $curve."
      ))
    else
      ensureUnlocked()
        .flatMap(_ => ensureSolanaApp())
        .flatMap(_ => SolanaApp.getPublicKey(transport, derivationPath))
        .map { pubKey =>
          new LedgerSolanaRawSigner(
            transport      = transport,
            derivationPath = derivationPath,
            publicKey      = PublicKey(Curve.Ed25519, pubKey.bytes),
          )
        }

  private def ensureUnlocked(): Future[Unit] =
    if unlocked then Future.successful(())
    else Future.failed(new IllegalStateException(s"LedgerSolanaVault[$id] is locked"))

  private def ensureSolanaApp(): Future[Unit] =
    Dashboard.getAppName(transport).flatMap { info =>
      if info.name == CurveAppRouting.SolanaApp then Future.successful(())
      else Future.failed(AppSwitchRequired(info.name, CurveAppRouting.SolanaApp))
    }

/** [[RawSigner]] backed by the Ledger Solana app.
 *
 *  Solana transactions are signed raw (the on-device app hashes them
 *  internally), so `hashAlgo` is intentionally ignored. This matches
 *  the Solana chain-layer convention where the host passes the
 *  serialised transaction bytes and the device returns a 64-byte
 *  ed25519 signature. */
class LedgerSolanaRawSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
  val publicKey:       PublicKey,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Ed25519

  /** Sign `payload` bytes. `hashAlgo` is ignored — the Solana app
   *  always operates on the raw serialised transaction / message
   *  and hashes internally on the device. */
  def sign(payload: Array[Byte], hashAlgo: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    SolanaApp.signTransaction(transport, derivationPath, payload)
