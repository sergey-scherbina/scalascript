package scalascript.wallet.vault.ledger.cardano

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.vault.ledger.{AppSwitchRequired, CurveAppRouting, Dashboard, LedgerTransport}

/** A [[Vault]] backed by a Ledger device running the on-device
 *  Cardano app. Only `Curve.Ed25519` is supported.
 *
 *  Lifecycle:
 *  - `unlock` — opens the underlying transport. The device PIN is
 *    entered out-of-band.
 *  - `getSigner` — probes `Dashboard.getAppName` first; raises
 *    [[AppSwitchRequired]] if the Cardano app is not active.
 *    Returns a [[LedgerCardanoRawSigner]] bound to the given path.
 *  - `lock` — closes the transport (best-effort). */
class LedgerCardanoVault(
  transport: LedgerTransport,
  val id:    String = "ledger-cardano",
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
        id             = "ledger-cardano-0",
        label          = "Ledger Cardano #0",
        publicKeys     = Map.empty,  // populated lazily by getSigner
        derivationPath = CardanoApp.DefaultPath,
      )
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if curve != Curve.Ed25519 then
      Future.failed(new UnsupportedOperationException(
        s"LedgerCardanoVault only supports Ed25519 (got $curve). " +
        s"Use a curve-specific Ledger vault (Ethereum, Bitcoin, …) for $curve."
      ))
    else
      ensureUnlocked()
        .flatMap(_ => ensureCardanoApp())
        .flatMap(_ => CardanoApp.getExtendedPublicKey(transport, derivationPath))
        .map { extPub =>
          new LedgerCardanoRawSigner(
            transport      = transport,
            derivationPath = derivationPath,
            publicKey      = PublicKey(Curve.Ed25519, extPub.publicKey),
          )
        }

  private def ensureUnlocked(): Future[Unit] =
    if unlocked then Future.successful(())
    else Future.failed(new IllegalStateException(s"LedgerCardanoVault[$id] is locked"))

  private def ensureCardanoApp(): Future[Unit] =
    Dashboard.getAppName(transport).flatMap { info =>
      if info.name == CurveAppRouting.CardanoApp then Future.successful(())
      else Future.failed(AppSwitchRequired(info.name, CurveAppRouting.CardanoApp))
    }

/** [[RawSigner]] backed by the Ledger Cardano app.
 *
 *  Signs via the CIP-8 data sign flow: wraps `payload` in a
 *  COSE Sig_Structure and sends it to the device via `SIGN_TX`.
 *  The device returns a 64-byte Ed25519 signature. */
class LedgerCardanoRawSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
  val publicKey:       PublicKey,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Ed25519

  /** Sign `payload` bytes using the CIP-8 COSE Sig_Structure flow.
   *
   *  `hashAlgo` is ignored — the Cardano app signs the CIP-8
   *  Sig_Structure which already encodes the payload. The Ledger
   *  device hashes internally. */
  def sign(payload: Array[Byte], hashAlgo: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    val sigStruct = CardanoCip8.sigStructure(payload)
    CardanoApp.signTx(transport, derivationPath, sigStruct)
