package scalascript.wallet.vault.ledger.cardano

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu, Bip32Path, LedgerTransport}

/** Wraps the Ledger Cardano on-device app's APDU surface.
 *
 *  CLA = `0xD7`.
 *
 *  Instructions:
 *  - `0x10` GET_EXTENDED_PUBLIC_KEY — `[derivPath]` → `[pubKey(32) || chainCode(32)]`
 *  - `0x21` SIGN_TX (CIP-8 data sign flow) — `[derivPath || sigStructure]` chunked → `[sig(64)]`
 *
 *  Chunking follows the standard Ledger convention:
 *   - first chunk P1 = `0x00`
 *   - continuation chunks P1 = `0x80`
 *
 *  Cardano uses Ed25519; signatures are 64 bytes.
 *
 *  Reference: github.com/LedgerHQ/app-cardano */
object CardanoApp:

  val Cla: Int = 0xD7

  /** GET_EXTENDED_PUBLIC_KEY: returns 32-byte public key + 32-byte chain code. */
  val Ins_GetExtendedPublicKey: Int = 0x10

  /** SIGN_TX (CIP-8 sign flow): P1=0x00 first, P1=0x80 continue. */
  val Ins_SignTx: Int = 0x21

  val P1First:    Int = 0x00
  val P1Continue: Int = 0x80
  val P2None:     Int = 0x00

  /** Default Cardano Shelley derivation path (CIP-1852). */
  val DefaultPath: String = "m/1852'/1815'/0'/0/0"

  /** The extended public key returned by the Cardano app. */
  final case class ExtendedPublicKey(
    publicKey: Array[Byte],  // 32 bytes ed25519 public key
    chainCode: Array[Byte],  // 32 bytes
  ):
    require(publicKey.length == 32, s"publicKey must be 32 B (got ${publicKey.length})")
    require(chainCode.length == 32, s"chainCode must be 32 B (got ${chainCode.length})")

  /** GET_EXTENDED_PUBLIC_KEY — sends the BIP-32 derivation path and returns
   *  the corresponding 32-byte ed25519 public key and 32-byte chain code. */
  def getExtendedPublicKey(
    transport:      LedgerTransport,
    derivationPath: String = DefaultPath,
  )(using ec: ExecutionContext): Future[ExtendedPublicKey] =
    val path = Bip32Path.encode(derivationPath)
    val cmd  = Apdu.command(Cla, Ins_GetExtendedPublicKey, P1First, P2None, path)
    transport.exchange(cmd).map { resp =>
      val (sw, payload) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"GET_EXTENDED_PUBLIC_KEY failed: sw=${Apdu.swHex(sw)}")
      require(payload.length >= 64,
        s"GET_EXTENDED_PUBLIC_KEY response must be at least 64 B (got ${payload.length})")
      ExtendedPublicKey(
        publicKey = java.util.Arrays.copyOfRange(payload, 0, 32),
        chainCode = java.util.Arrays.copyOfRange(payload, 32, 64),
      )
    }

  /** SIGN_TX (CIP-8 data sign flow).
   *
   *  Sends `[derivPath || txBodyBytes]` chunked. The CIP-8 signing payload
   *  is the raw transaction body bytes; the device signs them with the
   *  Ed25519 key at the given derivation path.
   *
   *  Returns a 64-byte ed25519 signature. */
  def signTx(
    transport:      LedgerTransport,
    derivationPath: String,
    txBodyBytes:    Array[Byte],
  )(using ec: ExecutionContext): Future[Array[Byte]] =
    val path    = Bip32Path.encode(derivationPath)
    val payload = path ++ txBodyBytes
    Apdu.chunkedSend(transport, Cla, Ins_SignTx, P1First, P1Continue, P2None, payload).map {
      case (Apdu.Sw_Ok, sig) =>
        require(sig.length == 64,
          s"SIGN_TX response must be 64 B ed25519 signature (got ${sig.length})")
        sig
      case (sw, _) =>
        throw new RuntimeException(s"SIGN_TX failed: sw=${Apdu.swHex(sw)}")
    }
