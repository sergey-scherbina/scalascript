package scalascript.wallet.vault.ledger.ethereum

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.vault.ledger.{AppSwitchRequired, Bip32Path, CurveAppRouting, Dashboard, LedgerTransport}

/** A [[Vault]] backed by a Ledger device running the on-device
 *  Ethereum app. Limited to `secp256k1` — for other curves point a
 *  separate vault at the device with the matching app
 *  (`LedgerSolanaVault`, `LedgerBitcoinVault`, …).
 *
 *  Lifecycle:
 *  - `unlock` — opens the underlying transport. The device's PIN is
 *    entered out-of-band; the vault is "locked" only in the sense
 *    that the host has not yet opened the USB / WebHID handle.
 *  - `getSigner` — probes `getAppName` first; raises
 *    [[AppSwitchRequired]] if a different app is active. Otherwise
 *    returns a [[LedgerEthereumSigner]] bound to the given path.
 *  - `lock` — closes the transport (best-effort, no Future). */
class LedgerEthereumVault(
  transport: LedgerTransport,
  val id:    String = "ledger-ethereum",
)(using ec: ExecutionContext) extends Vault:

  @volatile private var unlocked: Boolean = false

  def kind: VaultKind   = VaultKind.Hardware
  def isLocked: Boolean = !unlocked

  def unlock(credential: UnlockCredential): Future[Unit] =
    // Ledger devices unlock via on-device PIN; the host only needs
    // to open the transport. Any credential value is accepted.
    val _ = credential
    transport.open().map(_ => unlocked = true)

  def lock(): Unit =
    unlocked = false
    // Fire-and-forget; the trait signature returns Unit so we don't
    // expose the close future. Best-effort.
    val _ = transport.close()

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    // The device doesn't enumerate accounts — the host picks
    // derivation paths. Return the default Ethereum account; richer
    // listings are a host concern.
    Future.successful(Seq(
      AccountDescriptor(
        id             = "ledger-eth-0",
        label          = "Ledger Ethereum #0",
        publicKeys     = Map.empty,            // populated lazily by getSigner
        derivationPath = Bip32Path.DefaultEthereum,
      )
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if curve != Curve.Secp256k1 then
      Future.failed(new UnsupportedOperationException(
        s"LedgerEthereumVault only supports Secp256k1 (got $curve). " +
        s"Use a curve-specific Ledger vault (Solana, Cardano, …) for $curve."
      ))
    else
      ensureUnlocked()
        .flatMap(_ => ensureEthereumApp())
        .flatMap(_ => EthereumApp.getPublicKey(transport, derivationPath))
        .map { info =>
          new LedgerEthereumSigner(
            transport      = transport,
            derivationPath = derivationPath,
            publicKey      = PublicKey(Curve.Secp256k1, info.publicKey),
            address        = info.address,
          )
        }

  private def ensureUnlocked(): Future[Unit] =
    if unlocked then Future.successful(())
    else Future.failed(new IllegalStateException(s"LedgerEthereumVault[$id] is locked"))

  private def ensureEthereumApp(): Future[Unit] =
    Dashboard.getAppName(transport).flatMap { info =>
      if info.name == CurveAppRouting.EthereumApp then Future.successful(())
      else Future.failed(AppSwitchRequired(info.name, CurveAppRouting.EthereumApp))
    }

/** [[RawSigner]] backed by the Ledger Ethereum app.
 *
 *  The signer knows only its derivation path and curve — choosing
 *  which signing INS to issue (transaction vs personal vs EIP-712)
 *  is the responsibility of the chain layer (`EvmChainAdapter`) via
 *  the `hash` argument:
 *  - `HashAlgo.None`     — caller supplies a pre-computed EIP-712
 *                          digest pair `[domain(32) || msg(32)]` =
 *                          64 B; we route to SIGN_EIP712.
 *  - `HashAlgo.Keccak256`— caller supplies the *full RLP-encoded
 *                          unsigned transaction*; we route to
 *                          SIGN_TRANSACTION (the device computes
 *                          keccak256 itself).
 *
 *  This convention follows the way blockchain-evm's `EvmChainAdapter`
 *  treats the wallet-spi `sign` call today: `hash=Keccak256` ⇒ "this
 *  is an unsigned-tx payload"; `hash=None` ⇒ "this is a pre-digested
 *  hash". For arbitrary EIP-191 personal messages, use
 *  [[signPersonalMessage]] directly. */
class LedgerEthereumSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
  val publicKey:       PublicKey,
  val address:         String,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Secp256k1

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    hash match
      case HashAlgo.Keccak256 =>
        // Treat `msg` as the RLP-encoded unsigned transaction.
        EthereumApp.signTransaction(transport, derivationPath, msg).map(_.toBytes65)
      case HashAlgo.None =>
        // Treat `msg` as `[domainSeparator(32) || messageHash(32)]`
        // — the EIP-712 hashed flow.
        require(msg.length == 64,
          s"Ledger Ethereum signer expects 64-byte [domain||msgHash] for hash=None (got ${msg.length})")
        val domain = java.util.Arrays.copyOfRange(msg, 0, 32)
        val mh     = java.util.Arrays.copyOfRange(msg, 32, 64)
        EthereumApp.signEip712Hashed(transport, derivationPath, domain, mh).map(_.toBytes65)
      case other =>
        Future.failed(new UnsupportedOperationException(
          s"Ledger Ethereum signer does not handle hash=$other; " +
          s"use HashAlgo.Keccak256 (raw RLP tx) or HashAlgo.None ([domain||msgHash])."
        ))

  /** Direct access to EIP-191 personal_sign. Not routed via
   *  [[sign]] because the chain-layer convention there is already
   *  occupied by tx / EIP-712. */
  def signPersonalMessage(message: Array[Byte]): Future[Array[Byte]] =
    EthereumApp.signPersonalMessage(transport, derivationPath, message).map(_.toBytes65)
