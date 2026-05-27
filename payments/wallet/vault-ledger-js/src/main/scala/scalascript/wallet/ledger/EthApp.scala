package scalascript.wallet.ledger

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.LedgerTransport
import scalascript.wallet.vault.ledger.ethereum.{EthereumApp => SharedEthApp}

/** Ethereum Ledger app (WebHID / Scala.js side).
 *
 *  Thin facade that re-uses the shared `EthereumApp` implementation from
 *  `wallet-vault-ledger-ethereum` while exposing the same API shape as the
 *  task spec requires.  The JS-side code does not duplicate any framing or
 *  APDU encoding logic — that all lives in the shared/JVM-split modules.
 *
 *  CLA = `0xE0`.  Instruction bytes:
 *   - `0x02` GET_PUBLIC_KEY
 *   - `0x04` SIGN_TRANSACTION   (chunked)
 *   - `0x08` SIGN_PERSONAL_MESSAGE  (chunked)
 *
 *  References: github.com/LedgerHQ/app-ethereum (doc/ethapp.adoc). */
object EthApp:

  val Cla: Int = SharedEthApp.Cla      // 0xE0
  val Ins_GetAddress:     Int = SharedEthApp.Ins_GetPublicKey        // 0x02
  val Ins_SignTx:         Int = SharedEthApp.Ins_SignTransaction      // 0x04
  val Ins_SignPersonal:   Int = SharedEthApp.Ins_SignPersonalMessage  // 0x08

  val P1First:    Int = SharedEthApp.P1First    // 0x00
  val P1Continue: Int = SharedEthApp.P1Continue // 0x80
  val P2None:     Int = SharedEthApp.P2None     // 0x00

  // ── public key / address ────────────────────────────────────────────────

  /** Result of `getAddress`. */
  final case class AddressInfo(
    publicKey: Array[Byte],
    address:   String,
  )

  /** GET_PUBLIC_KEY (INS=0x02).
   *
   *  Encodes the BIP-32 path and sends to the device.  Returns the
   *  uncompressed 65-byte public key and the ASCII checksum address.
   *
   *  @param path       BIP-44 derivation path, e.g. `"m/44'/60'/0'/0/0"`
   *  @param display    if true sets P1=0x01 to show address on device */
  def getAddress(
    transport: LedgerTransport,
    path:      String,
    display:   Boolean = false,
  )(using ec: ExecutionContext): Future[AddressInfo] =
    SharedEthApp.getPublicKey(transport, path, confirmOnDevice = display).map { info =>
      AddressInfo(info.publicKey, info.address)
    }

  // ── transaction signing ─────────────────────────────────────────────────

  /** Result of any signing operation: the EVM `(v, r, s)` triple. */
  final case class Signature(v: Int, r: Array[Byte], s: Array[Byte]):
    require(r.length == 32 && s.length == 32,
      s"r,s must be 32 B (got ${r.length}/${s.length})")
    /** Canonical 65-byte EVM encoding: `r || s || v`. */
    def toBytes65: Array[Byte] =
      val out = new Array[Byte](65)
      var i = 0
      while i < 32 do { out(i)      = r(i); i += 1 }
      i = 0
      while i < 32 do { out(32 + i) = s(i); i += 1 }
      out(64) = (v & 0xff).toByte
      out

  /** SIGN_TRANSACTION (INS=0x04), chunked.
   *
   *  The Ethereum app expects the full RLP-encoded unsigned transaction;
   *  it computes the keccak256 digest on-device.
   *
   *  Payloads > 255 B are split into multiple APDUs with
   *  `p1=0x00` (first) / `p1=0x80` (continuation). */
  def signTransaction(
    transport: LedgerTransport,
    path:      String,
    rlpTx:     Array[Byte],
  )(using ec: ExecutionContext): Future[Signature] =
    SharedEthApp.signTransaction(transport, path, rlpTx).map { sig =>
      Signature(sig.v, sig.r, sig.s)
    }

  /** SIGN_PERSONAL_MESSAGE (INS=0x08), chunked (EIP-191 personal_sign).
   *
   *  Payload: `[ derivPath || msgLen(4 BE) || msg ]`. The device
   *  prepends the EIP-191 prefix internally. */
  def signPersonalMessage(
    transport: LedgerTransport,
    path:      String,
    msg:       Array[Byte],
  )(using ec: ExecutionContext): Future[Signature] =
    SharedEthApp.signPersonalMessage(transport, path, msg).map { sig =>
      Signature(sig.v, sig.r, sig.s)
    }
