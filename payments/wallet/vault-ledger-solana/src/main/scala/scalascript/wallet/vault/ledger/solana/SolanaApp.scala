package scalascript.wallet.vault.ledger.solana

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu, Bip32Path, LedgerTransport}

/** Wraps the Ledger Solana on-device app's APDU surface.
 *
 *  CLA = `0xE0`.
 *
 *  Instructions used here:
 *  - `0x05` GET_PUBKEY           — `[derivPath]` → `[pubkey(32)]`
 *  - `0x04` SIGN_TRANSACTION     — `[derivPath || tx]` chunked → `[sig(64)]`
 *  - `0x07` SIGN_OFFCHAIN_MESSAGE — `[derivPath || msg]` chunked → `[sig(64)]`
 *
 *  Chunking follows the standard Ledger convention:
 *   - first chunk P1 = `0x00`
 *   - continuation chunks P1 = `0x80`
 *
 *  Solana uses ed25519; signatures are 64 bytes (r || s), no `v` byte.
 *
 *  Reference: github.com/LedgerHQ/app-solana */
object SolanaApp:

  val Cla: Int = 0xE0

  /** GET_PUBKEY: returns 32-byte ed25519 public key. */
  val Ins_GetPubKey: Int = 0x05

  /** SIGN_TRANSACTION: P1=0x00 first, P1=0x80 continue. */
  val Ins_SignTransaction: Int = 0x04

  /** SIGN_OFFCHAIN_MESSAGE: P1=0x00 first, P1=0x80 continue. */
  val Ins_SignOffchainMessage: Int = 0x07

  /** Default Solana BIP-44 derivation path. */
  val DefaultPath: String = "m/44'/501'/0'/0'"

  /** P1 for the first APDU chunk. */
  val P1First: Int = 0x00

  /** P1 for continuation APDU chunks. */
  val P1Continue: Int = 0x80

  /** P2 — unused in all standard Solana app flows. */
  val P2None: Int = 0x00

  /** The 32-byte ed25519 public key returned by the Solana app. */
  final case class PublicKey(bytes: Array[Byte]):
    require(bytes.length == 32, s"Solana PublicKey must be 32 B (got ${bytes.length})")

    /** Base58 representation as shown in Solana explorers / wallets. */
    def toBase58: String = Base58.encode(bytes)

  /** GET_PUBKEY — sends the BIP-32 derivation path and returns the
   *  corresponding 32-byte ed25519 public key. */
  def getPublicKey(
    transport:      LedgerTransport,
    derivationPath: String = DefaultPath,
  )(using ec: ExecutionContext): Future[PublicKey] =
    val path = Bip32Path.encode(derivationPath)
    val cmd  = Apdu.command(Cla, Ins_GetPubKey, P1First, P2None, path)
    transport.exchange(cmd).map { resp =>
      val (sw, payload) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"GET_PUBKEY failed: sw=${Apdu.swHex(sw)}")
      require(payload.length == 32,
        s"GET_PUBKEY response must be 32 B (got ${payload.length})")
      PublicKey(payload.clone())
    }

  /** SIGN_TRANSACTION — sends `[derivPath || txBytes]` chunked.
   *
   *  The Solana app hashes the raw transaction bytes internally (SHA-512
   *  / SHA-256 depending on firmware version); the host sends the full
   *  serialised transaction.
   *
   *  Returns a 64-byte ed25519 signature. */
  def signTransaction(
    transport:      LedgerTransport,
    derivationPath: String,
    txBytes:        Array[Byte],
  )(using ec: ExecutionContext): Future[Array[Byte]] =
    val path    = Bip32Path.encode(derivationPath)
    val payload = path ++ txBytes
    Apdu.chunkedSend(transport, Cla, Ins_SignTransaction, P1First, P1Continue, P2None, payload).map {
      case (Apdu.Sw_Ok, sig) => requireSig64(sig, "SIGN_TRANSACTION")
      case (sw, _)           => throw new RuntimeException(s"SIGN_TRANSACTION failed: sw=${Apdu.swHex(sw)}")
    }

  /** SIGN_OFFCHAIN_MESSAGE — sends `[derivPath || messageBytes]` chunked.
   *
   *  Used for Solana off-chain message signing (wallet authentication,
   *  human-readable messages shown on the device display).
   *
   *  Returns a 64-byte ed25519 signature. */
  def signOffchainMessage(
    transport:      LedgerTransport,
    derivationPath: String,
    messageBytes:   Array[Byte],
  )(using ec: ExecutionContext): Future[Array[Byte]] =
    val path    = Bip32Path.encode(derivationPath)
    val payload = path ++ messageBytes
    Apdu.chunkedSend(transport, Cla, Ins_SignOffchainMessage, P1First, P1Continue, P2None, payload).map {
      case (Apdu.Sw_Ok, sig) => requireSig64(sig, "SIGN_OFFCHAIN_MESSAGE")
      case (sw, _)           => throw new RuntimeException(s"SIGN_OFFCHAIN_MESSAGE failed: sw=${Apdu.swHex(sw)}")
    }

  /** Validate and return the 64-byte signature. */
  private def requireSig64(sig: Array[Byte], ins: String): Array[Byte] =
    require(sig.length == 64,
      s"$ins response must be 64 B ed25519 signature (got ${sig.length})")
    sig
