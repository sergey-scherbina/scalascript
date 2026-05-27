package scalascript.wallet.vault.ledger.bitcoin

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.vault.ledger.{AppSwitchRequired, CurveAppRouting, Dashboard, LedgerTransport}

/** A [[Vault]] backed by a Ledger device running the on-device
 *  Bitcoin app (new protocol, v2+).  Limited to `secp256k1` — the
 *  Bitcoin app only manages secp256k1 keys.
 *
 *  Lifecycle:
 *  - `unlock` — opens the underlying transport.  The device's PIN is
 *    entered out-of-band; the vault is "locked" only in the sense that
 *    the host has not yet opened the USB / WebHID handle.
 *  - `getSigner` — probes `Dashboard.getAppName` first; raises
 *    [[AppSwitchRequired]] if the Bitcoin app is not active.  Otherwise
 *    returns a [[LedgerBitcoinRawSigner]] bound to the given path.
 *  - `lock` — closes the transport (best-effort, returns Unit). */
class LedgerBitcoinVault(
  transport: LedgerTransport,
  val id:    String = "ledger-bitcoin",
)(using ec: ExecutionContext) extends Vault:

  @volatile private var unlocked: Boolean = false

  def kind: VaultKind   = VaultKind.Hardware
  def isLocked: Boolean = !unlocked

  def unlock(credential: UnlockCredential): Future[Unit] =
    // Ledger devices unlock via on-device PIN; the host only opens the transport.
    val _ = credential
    transport.open().map(_ => unlocked = true)

  def lock(): Unit =
    unlocked = false
    val _ = transport.close()

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    Future.successful(Seq(
      AccountDescriptor(
        id             = "ledger-btc-0",
        label          = "Ledger Bitcoin #0",
        publicKeys     = Map.empty,
        derivationPath = BitcoinApp.DefaultPath,
      )
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if curve != Curve.Secp256k1 then
      Future.failed(new UnsupportedOperationException(
        s"LedgerBitcoinVault only supports Secp256k1 (got $curve). " +
        s"Use a curve-specific Ledger vault for $curve."
      ))
    else
      ensureUnlocked()
        .flatMap(_ => ensureBitcoinApp())
        .map(_ => new LedgerBitcoinRawSigner(transport, derivationPath))

  private def ensureUnlocked(): Future[Unit] =
    if unlocked then Future.successful(())
    else Future.failed(new IllegalStateException(s"LedgerBitcoinVault[$id] is locked"))

  private def ensureBitcoinApp(): Future[Unit] =
    Dashboard.getAppName(transport).flatMap { info =>
      if info.name == CurveAppRouting.BitcoinApp then Future.successful(())
      else Future.failed(AppSwitchRequired(info.name, CurveAppRouting.BitcoinApp))
    }

/** [[RawSigner]] backed by the Ledger Bitcoin app.
 *
 *  The `sign(payload, hashAlgo)` method interprets `payload` as raw
 *  PSBT bytes and dispatches to [[BitcoinApp.signPsbt]].  The raw
 *  signer use-case concatenates all returned input signatures so that
 *  the caller receives a flat byte stream: each signature is prefixed
 *  by a single length byte `[sig_len(1) || sig(sig_len)]*`.
 *
 *  A synthetic zero-length [[PublicKey]] is used here because the
 *  Bitcoin app returns per-input partial signatures rather than a
 *  single public key at signer-construction time.  Callers that need
 *  the extended public key should call [[BitcoinApp.getExtendedPubkey]]
 *  directly. */
class LedgerBitcoinRawSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Secp256k1

  /** Synthetic empty public key — the Bitcoin app does not return a
   *  single pubkey at signer construction time; use
   *  [[BitcoinApp.getExtendedPubkey]] for xpub retrieval. */
  val publicKey: PublicKey = PublicKey(Curve.Secp256k1, Array.emptyByteArray)

  /** Interpret `msg` as raw PSBT bytes.  Calls [[BitcoinApp.signPsbt]]
   *  and returns all input signatures concatenated as
   *  `[sig_len(1) || sig(sig_len)]*`. The `hash` parameter is ignored
   *  (the on-device Bitcoin app handles its own digest internally). */
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    BitcoinApp.signPsbt(transport, msg).map { sigsMap =>
      if sigsMap.isEmpty then Array.emptyByteArray
      else
        val buf = new java.io.ByteArrayOutputStream()
        val sorted = sigsMap.toSeq.sortBy(_._1)
        for (_, sig) <- sorted do
          buf.write(sig.length & 0xff)
          buf.write(sig)
        buf.toByteArray
    }
