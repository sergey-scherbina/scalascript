package scalascript.wallet.ledger

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.vault.ledger.{AppSwitchRequired, Bip32Path, CurveAppRouting, Dashboard, LedgerTransport}
import scalascript.wallet.vault.ledger.ethereum.EthereumApp

/** [[Vault]] SPI implementation backed by a Ledger device over WebHID.
 *
 *  The vault is intentionally multi-curve:
 *   - `Curve.Secp256k1` → routes to the Ethereum Ledger app
 *     (uses `EthereumApp.signPersonalMessage` for raw personal signing;
 *     Ethereum tx signing handled via `hash=Keccak256` in the signer)
 *   - `Curve.Ed25519`   → routes to the Cardano Ledger app
 *     (uses `CardanoApp.getExtendedPublicKey` / `CardanoApp.signTx`)
 *
 *  WebHID connection lifecycle:
 *   - `unlock` — calls `HidTransport.open()` which triggers
 *     `navigator.hid.requestDevice({ filters: [{ vendorId: 0x2C97 }] })`
 *     if no device is already connected, or `openExisting()` if a
 *     transport is supplied pre-connected.
 *   - `lock`   — calls `HidTransport.close()` (fire-and-forget).
 *   - `getSigner` — checks the app with `Dashboard.getAppName`; raises
 *     [[AppSwitchRequired]] if wrong app is open. */
class LedgerVault(
  transport:  LedgerTransport,
  val id:     String = "ledger-webhid",
)(using ec: ExecutionContext) extends Vault:

  @volatile private var unlocked: Boolean = false

  def kind:      VaultKind = VaultKind.Hardware
  def isLocked:  Boolean   = !unlocked

  def unlock(credential: UnlockCredential): Future[Unit] =
    // Ledger's PIN is entered out-of-band on the device; the host just
    // opens the WebHID handle.  Any credential value is accepted.
    val _ = credential
    transport.open().map(_ => unlocked = true)

  def lock(): Unit =
    unlocked = false
    val _ = transport.close()

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    Future.successful(Seq(
      AccountDescriptor(
        id             = "ledger-eth-0",
        label          = "Ledger Ethereum #0",
        publicKeys     = Map.empty,
        derivationPath = Bip32Path.DefaultEthereum,
      ),
      AccountDescriptor(
        id             = "ledger-cardano-0",
        label          = "Ledger Cardano #0",
        publicKeys     = Map.empty,
        derivationPath = CardanoApp.DefaultCardanoPath,
      ),
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    ensureUnlocked().flatMap { _ =>
      curve match
        case Curve.Secp256k1 =>
          ensureApp(CurveAppRouting.EthereumApp).flatMap { _ =>
            EthereumApp.getPublicKey(transport, derivationPath).map { info =>
              LedgerEthSigner(
                transport      = transport,
                derivationPath = derivationPath,
                publicKey      = PublicKey(Curve.Secp256k1, info.publicKey),
              )
            }
          }
        case Curve.Ed25519 =>
          ensureApp(CurveAppRouting.CardanoApp).flatMap { _ =>
            CardanoApp.getExtendedPublicKey(transport, derivationPath).map { extPk =>
              LedgerCardanoSigner(
                transport      = transport,
                derivationPath = derivationPath,
                publicKey      = PublicKey(Curve.Ed25519, extPk.publicKeyBytes),
              )
            }
          }
        case other =>
          Future.failed(new UnsupportedOperationException(
            s"LedgerVault does not support curve $other. " +
            s"Only Secp256k1 (Ethereum) and Ed25519 (Cardano) are supported."
          ))
    }

  // ── connect / disconnect helpers ─────────────────────────────────────────

  /** Alias for [[unlock]] — named for discoverability in the browser context. */
  def connect(): Future[Unit] = unlock(UnlockCredential.None)

  /** Alias for [[lock]] — named for discoverability in the browser context. */
  def disconnect(): Unit = lock()

  // ── internal ─────────────────────────────────────────────────────────────

  private def ensureUnlocked(): Future[Unit] =
    if unlocked then Future.successful(())
    else Future.failed(new IllegalStateException(s"LedgerVault[$id] is locked — call unlock() first"))

  private def ensureApp(requiredApp: String): Future[Unit] =
    Dashboard.getAppName(transport).flatMap { info =>
      if info.name == requiredApp then Future.successful(())
      else Future.failed(AppSwitchRequired(info.name, requiredApp))
    }

// ── Per-curve signers ─────────────────────────────────────────────────────

/** [[RawSigner]] backed by the Ethereum app (secp256k1). */
private final class LedgerEthSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
  val publicKey:       PublicKey,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Secp256k1

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    hash match
      case HashAlgo.Keccak256 =>
        // msg is the RLP-encoded unsigned transaction
        EthereumApp.signTransaction(transport, derivationPath, msg).map(_.toBytes65)
      case HashAlgo.None =>
        // msg is [domainSeparator(32) || messageHash(32)] — EIP-712 hashed flow
        require(msg.length == 64,
          s"Ledger Eth signer expects 64-byte [domain||msgHash] for hash=None (got ${msg.length})")
        val domain = java.util.Arrays.copyOfRange(msg, 0, 32)
        val mh     = java.util.Arrays.copyOfRange(msg, 32, 64)
        EthereumApp.signEip712Hashed(transport, derivationPath, domain, mh).map(_.toBytes65)
      case other =>
        Future.failed(new UnsupportedOperationException(
          s"Ledger Eth signer does not handle hash=$other"))

  def signPersonalMessage(message: Array[Byte]): Future[Array[Byte]] =
    EthereumApp.signPersonalMessage(transport, derivationPath, message).map(_.toBytes65)

/** [[RawSigner]] backed by the Cardano app (Ed25519). */
private final class LedgerCardanoSigner(
  transport:           LedgerTransport,
  val derivationPath:  String,
  val publicKey:       PublicKey,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve = Curve.Ed25519

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    // For Cardano the standard flow is signTx; here we expose a raw-bytes
    // personal signing path via the SIGN_TX instruction with a synthetic
    // single-input/single-output structure.  For production use the caller
    // should use `CardanoApp.signTx` directly with proper inputs/outputs.
    //
    // A practical Cardano RawSigner would accept the already-CBOR-encoded
    // transaction body as `msg` (i.e. hash=None) and send it as a SIGN_TX
    // payload.  We implement that contract here: msg is a raw CBOR tx body,
    // sent to the device for signing; the device returns a 64-byte Ed25519
    // signature.
    val _ = hash // Ed25519 signing is always deterministic; hash param ignored
    val pathBytes = Bip32Path.encode(derivationPath)
    val combined  = new Array[Byte](pathBytes.length + msg.length)
    System.arraycopy(pathBytes, 0, combined, 0, pathBytes.length)
    System.arraycopy(msg, 0, combined, pathBytes.length, msg.length)
    import scalascript.wallet.vault.ledger.{Apdu => SharedApdu}
    SharedApdu.chunkedSend(
      transport,
      CardanoApp.Cla,
      CardanoApp.Ins_SignTx,
      p1First    = 0x01,
      p1Continue = 0x02,
      p2         = 0x00,
      payload    = combined,
    ).map { case (sw, payload) =>
      if sw != Apdu.SW_OK then
        throw new RuntimeException(s"Cardano SIGN_TX failed: sw=${Apdu.swHex(sw)}")
      payload
    }
