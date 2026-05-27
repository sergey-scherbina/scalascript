package scalascript.wallet.ledger

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu => SharedApdu, Bip32Path, LedgerTransport}

/** Cardano Ledger app (WebHID / Scala.js side).
 *
 *  CLA = `0xD7`.  Instruction bytes (per ledger-app-cardano-shelley spec):
 *   - `0x10` GET_EXTENDED_PUBLIC_KEY  — returns 64-byte extended pubkey
 *   - `0x21` SIGN_TX                  — CIP-8 CBOR framing; returns Ed25519 sig
 *
 *  The Cardano app uses CIP-8 CBOR transaction framing on-device.  For the
 *  signing instruction we build a minimal CBOR structure carrying the inputs,
 *  outputs, fee and TTL; the device signs the canonical CBOR hash (Blake2b-256)
 *  and returns the Ed25519 raw signature (64 B).
 *
 *  Reference: https://github.com/vacuumlabs/ledger-app-cardano-shelley */
object CardanoApp:

  /** Cardano app CLA. */
  val Cla: Int = 0xD7

  /** GET_EXTENDED_PUBLIC_KEY instruction. */
  val Ins_GetExtPublicKey: Int = 0x10

  /** SIGN_TX instruction. */
  val Ins_SignTx: Int = 0x21

  /** Default Cardano derivation path (Shelley account 0, address 0). */
  val DefaultCardanoPath: String = "m/1852'/1815'/0'/0/0"

  // ── Extended public key ──────────────────────────────────────────────────

  /** Result of `getExtendedPublicKey`: a 64-byte extended pubkey.
   *  Bytes 0..31 = 32-byte compressed Ed25519 public key.
   *  Bytes 32..63 = 32-byte chain code. */
  final case class ExtendedPublicKey(raw: Array[Byte]):
    require(raw.length == 64, s"Extended public key must be 64 B (got ${raw.length})")
    def publicKeyBytes: Array[Byte] = raw.slice(0, 32)
    def chainCode:      Array[Byte] = raw.slice(32, 64)

  /** GET_EXTENDED_PUBLIC_KEY (INS=0x10).
   *
   *  Encodes the BIP-32 path using the standard Ledger path wire format
   *  ([[Bip32Path.encode]]) and sends it as a single APDU. The device
   *  responds with the 64-byte extended public key. */
  def getExtendedPublicKey(
    transport: LedgerTransport,
    path:      String = DefaultCardanoPath,
  )(using ec: ExecutionContext): Future[ExtendedPublicKey] =
    val pathBytes = Bip32Path.encode(path)
    val cmd = SharedApdu.command(Cla, Ins_GetExtPublicKey, 0x00, 0x00, pathBytes)
    transport.exchange(cmd).map { resp =>
      val (sw, payload) = SharedApdu.parseResponse(resp)
      checkStatus(sw, "GET_EXTENDED_PUBLIC_KEY")
      require(payload.length == 64,
        s"GET_EXTENDED_PUBLIC_KEY expected 64 B payload (got ${payload.length})")
      ExtendedPublicKey(payload)
    }

  // ── Transaction signing ──────────────────────────────────────────────────

  /** Minimal UTXO input for SIGN_TX. */
  final case class TxInput(txId: Array[Byte], index: Int):
    require(txId.length == 32, s"txId must be 32 B (got ${txId.length})")

  /** Minimal output for SIGN_TX. */
  final case class TxOutput(addressBytes: Array[Byte], amountLovelace: Long)

  /** SIGN_TX result: a raw 64-byte Ed25519 signature. */
  final case class TxSignature(raw: Array[Byte]):
    require(raw.length == 64, s"Ed25519 signature must be 64 B (got ${raw.length})")

  /** SIGN_TX (INS=0x21) with CIP-8 CBOR framing.
   *
   *  Builds a minimal CBOR structure carrying `[inputs, outputs, fee, ttl]`
   *  (the mandatory fields of a Shelley transaction body) and sends it to the
   *  device via a chunked APDU sequence.  The device displays the transaction
   *  for user confirmation on-screen and returns a 64-byte Ed25519 signature.
   *
   *  The CBOR schema used here:
   *  {{{
   *    txBody = #6.24(bstr .cbor {
   *      0: [* [bytes .size 32, uint]],   ; inputs  (txId, index)
   *      1: [* [bytes,          uint]],   ; outputs (addr, lovelace)
   *      2: uint,                          ; fee
   *      3: uint,                          ; ttl
   *    })
   *  }}}
   *
   *  @param path     BIP-44 derivation path for the signing key
   *  @param inputs   UTXO inputs consumed by the transaction
   *  @param outputs  transaction outputs
   *  @param fee      transaction fee in lovelace
   *  @param ttl      time-to-live slot number */
  def signTx(
    transport: LedgerTransport,
    path:      String,
    inputs:    Seq[TxInput],
    outputs:   Seq[TxOutput],
    fee:       Long,
    ttl:       Long,
  )(using ec: ExecutionContext): Future[TxSignature] =
    val pathBytes = Bip32Path.encode(path)
    val txBody    = encodeCborTxBody(inputs, outputs, fee, ttl)
    // Payload for SIGN_TX: [pathBytes || txBodyCbor]
    val combined  = new Array[Byte](pathBytes.length + txBody.length)
    System.arraycopy(pathBytes, 0, combined, 0, pathBytes.length)
    System.arraycopy(txBody, 0, combined, pathBytes.length, txBody.length)
    SharedApdu.chunkedSend(
      transport,
      Cla,
      Ins_SignTx,
      p1First    = 0x01,  // first chunk
      p1Continue = 0x02,  // continuation
      p2         = 0x00,
      payload    = combined,
    ).map { case (sw, payload) =>
      checkStatus(sw, "SIGN_TX")
      require(payload.length == 64,
        s"SIGN_TX expected 64-byte Ed25519 signature (got ${payload.length})")
      TxSignature(payload)
    }

  // ── CBOR helpers ─────────────────────────────────────────────────────────

  /** Encode a minimal Shelley transaction body as CBOR.
   *  Only the four mandatory fields are included (map keys 0–3). */
  private[ledger] def encodeCborTxBody(
    inputs:  Seq[TxInput],
    outputs: Seq[TxOutput],
    fee:     Long,
    ttl:     Long,
  ): Array[Byte] =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    // Map of 4 entries: {0: inputs, 1: outputs, 2: fee, 3: ttl}
    cborHead(5, 4, buf)
    // key 0 → inputs array
    cborHead(0, 0, buf)
    cborHead(4, inputs.length, buf)
    inputs.foreach { inp =>
      cborHead(4, 2, buf)              // 2-element array [txId, index]
      cborHead(2, 32, buf); buf ++= inp.txId
      cborHead(0, inp.index, buf)
    }
    // key 1 → outputs array
    cborHead(0, 1, buf)
    cborHead(4, outputs.length, buf)
    outputs.foreach { out =>
      cborHead(4, 2, buf)
      cborHead(2, out.addressBytes.length, buf); buf ++= out.addressBytes
      cborUint(out.amountLovelace, buf)
    }
    // key 2 → fee
    cborHead(0, 2, buf)
    cborUint(fee, buf)
    // key 3 → ttl
    cborHead(0, 3, buf)
    cborUint(ttl, buf)
    buf.toArray

  private def cborHead(major: Int, arg: Long, buf: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    val mt = (major << 5).toByte
    if arg < 24 then buf += (mt | arg).toByte
    else if arg < 256 then { buf += (mt | 24).toByte; buf += arg.toByte }
    else if arg < 65536 then
      buf += (mt | 25).toByte
      buf += ((arg >> 8) & 0xff).toByte
      buf += ( arg       & 0xff).toByte
    else if arg < 0x100000000L then
      buf += (mt | 26).toByte
      (3 to 0 by -1).foreach(i => buf += ((arg >> (i * 8)) & 0xff).toByte)
    else
      buf += (mt | 27).toByte
      (7 to 0 by -1).foreach(i => buf += ((arg >> (i * 8)) & 0xff).toByte)

  private def cborUint(n: Long, buf: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    cborHead(0, n, buf)

  private def checkStatus(sw: Int, ctx: String): Unit =
    if sw == Apdu.SW_OK then ()
    else if sw == Apdu.SW_UserDeclined then
      throw LedgerUserDeclinedException(ctx)
    else if sw == Apdu.SW_InvalidLength then
      throw LedgerInvalidLengthException(ctx, sw)
    else
      throw new RuntimeException(s"$ctx failed: sw=${Apdu.swHex(sw)}")
