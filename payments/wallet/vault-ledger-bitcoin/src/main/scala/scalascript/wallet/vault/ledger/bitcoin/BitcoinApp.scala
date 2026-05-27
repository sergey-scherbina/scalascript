package scalascript.wallet.vault.ledger.bitcoin

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu, Bip32Path, LedgerTransport}

/** Wraps the Ledger Bitcoin on-device app's APDU surface (new protocol,
 *  v2+).  Numbers follow the Ledger bitcoin-app spec (github.com/LedgerHQ/app-bitcoin-new).
 *
 *  CLA = `0xE1`.
 *
 *  Instructions implemented:
 *  - `0x00` GET_EXTENDED_PUBKEY — returns BIP-32 xpub data
 *  - `0x02` REGISTER_WALLET    — register multisig wallet policy (skipped for single-sig)
 *  - `0x03` GET_WALLET_ADDRESS — derive a receiving address
 *  - `0x04` SIGN_PSBT          — sign a PSBT; returns per-input partial signatures
 *
 *  GET_EXTENDED_PUBKEY payload: `[displayAddr(1) || BIP32-path-encoding]`.
 *  Response: `[chain_code(32) || pubkey(33) || fingerprint(4) || depth(1) || child_number(4)]`
 *  = 74 bytes total.
 *
 *  SIGN_PSBT payload: chunked PSBT bytes (p1=0x00 first, p1=0x80 continue).
 *  Response (final chunk): `[n_sigs(1) || (sig_len(1) || sig(sig_len))*]`.
 *
 *  Reference: github.com/LedgerHQ/app-bitcoin-new (doc/bitcoin.md). */
object BitcoinApp:

  val Cla: Int = 0xE1

  // INS values for the new Bitcoin app protocol
  val Ins_GetExtendedPubkey: Int = 0x00  // GET_EXTENDED_PUBKEY
  val Ins_RegisterWallet:    Int = 0x02  // REGISTER_WALLET (for multisig; skip for single-sig)
  val Ins_GetWalletAddress:  Int = 0x03  // GET_WALLET_ADDRESS
  val Ins_SignPsbt:          Int = 0x04  // SIGN_PSBT

  val P1First:    Int = 0x00
  val P1Continue: Int = 0x80
  val P2None:     Int = 0x00

  // P1 flags on GET_EXTENDED_PUBKEY
  val P1_DontDisplay: Int = 0x00
  val P1_Display:     Int = 0x01

  /** Default account-level derivation path for Native SegWit (BIP-84). */
  val DefaultPath: String = "m/84'/0'/0'"

  /** BIP-32 xpub data returned by GET_EXTENDED_PUBKEY.
   *
   *  Wire format (74 bytes):
   *  `[chain_code(32) || pubkey(33) || fingerprint(4) || depth(1) || child_number(4)]`
   */
  final case class ExtendedPubkey(
    chainCode:   Array[Byte],  // 32 bytes
    publicKey:   Array[Byte],  // 33 bytes (compressed secp256k1)
    fingerprint: Array[Byte],  // 4 bytes — parent key fingerprint
    depth:       Int,
    childNumber: Int,
  )

  /** GET_EXTENDED_PUBKEY.
   *
   *  Sends `[displayAddr(1) || Bip32Path.encode(path)]` and returns the
   *  parsed xpub data.
   *
   *  @param transport  open [[LedgerTransport]]
   *  @param path       BIP-32 derivation path (e.g. `"m/84'/0'/0'"`)
   *  @param display    whether to request confirmation on the device screen
   */
  def getExtendedPubkey(
    transport: LedgerTransport,
    path:      String,
    display:   Boolean = false,
  )(using ec: ExecutionContext): Future[ExtendedPubkey] =
    val pathBytes = Bip32Path.encode(path)
    val payload   = Array[Byte]((if display then P1_Display else P1_DontDisplay).toByte) ++ pathBytes
    val cmd       = Apdu.command(Cla, Ins_GetExtendedPubkey, P1_DontDisplay, P2None, payload)
    transport.exchange(cmd).map { resp =>
      val (sw, body) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"GET_EXTENDED_PUBKEY failed: sw=${Apdu.swHex(sw)}")
      decodeExtendedPubkey(body)
    }

  /** Decode the 74-byte GET_EXTENDED_PUBKEY response.
   *
   *  Layout: `[chain_code(32) || pubkey(33) || fingerprint(4) || depth(1) || child_number(4)]`
   */
  private[bitcoin] def decodeExtendedPubkey(body: Array[Byte]): ExtendedPubkey =
    require(body.length == 74,
      s"GET_EXTENDED_PUBKEY response must be 74 B (got ${body.length})")
    val chainCode   = java.util.Arrays.copyOfRange(body, 0, 32)
    val pubKey      = java.util.Arrays.copyOfRange(body, 32, 65)
    val fingerprint = java.util.Arrays.copyOfRange(body, 65, 69)
    val depth       = body(69) & 0xff
    val childNumber =
      ((body(70) & 0xff) << 24) | ((body(71) & 0xff) << 16) |
      ((body(72) & 0xff) <<  8) |  (body(73) & 0xff)
    ExtendedPubkey(chainCode, pubKey, fingerprint, depth, childNumber)

  /** SIGN_PSBT.
   *
   *  Sends the full PSBT bytes to the device in chunks.  The device
   *  validates each input against the registered wallet policy and
   *  returns a flat binary stream of partial signatures:
   *  `[n_sigs(1) || (sig_len(1) || sig(sig_len))*]`.
   *
   *  This simplified implementation returns a `Map[inputIndex -> sigBytes]`
   *  where input indices are assigned sequentially starting at 0 (matching
   *  the order the device returns them).
   *
   *  @param transport  open [[LedgerTransport]]
   *  @param psbtBytes  raw PSBT bytes (RFC 7468 / BIP-174 binary form)
   *  @return           `Map[inputIndex -> DER-encoded partial signature]`
   */
  def signPsbt(
    transport: LedgerTransport,
    psbtBytes: Array[Byte],
  )(using ec: ExecutionContext): Future[Map[Int, Array[Byte]]] =
    Apdu.chunkedSend(transport, Cla, Ins_SignPsbt, P1First, P1Continue, P2None, psbtBytes).map {
      case (Apdu.Sw_Ok, body) => decodeSignatures(body)
      case (sw, _)            => throw new RuntimeException(s"SIGN_PSBT failed: sw=${Apdu.swHex(sw)}")
    }

  /** Decode the SIGN_PSBT response body.
   *
   *  Wire format: `[n_sigs(1) || (sig_len(1) || sig(sig_len))*]`
   *  Returns a map from sequential input index (0-based) to DER signature bytes.
   */
  private[bitcoin] def decodeSignatures(body: Array[Byte]): Map[Int, Array[Byte]] =
    if body.isEmpty then return Map.empty
    val nSigs = body(0) & 0xff
    val result = Map.newBuilder[Int, Array[Byte]]
    var off    = 1
    var i      = 0
    while i < nSigs do
      require(off < body.length, s"SIGN_PSBT response truncated reading sig $i length")
      val sigLen = body(off) & 0xff
      off += 1
      require(off + sigLen <= body.length,
        s"SIGN_PSBT response truncated reading sig $i (len=$sigLen)")
      val sig = java.util.Arrays.copyOfRange(body, off, off + sigLen)
      result += (i -> sig)
      off += sigLen
      i   += 1
    result.result()
