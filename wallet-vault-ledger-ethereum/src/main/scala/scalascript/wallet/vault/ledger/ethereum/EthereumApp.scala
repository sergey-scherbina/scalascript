package scalascript.wallet.vault.ledger.ethereum

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu, Bip32Path, LedgerTransport}

/** Wraps the Ledger Ethereum on-device app's APDU surface. Numbers
 *  follow the published ledger-app-eth spec.
 *
 *  CLA = `0xE0`.
 *
 *  Instructions used here:
 *  - `0x02` GET_PUBLIC_KEY        — `[derivPath]` → `[keyLen|key|addrLen|ascii-addr|chainCode?]`
 *  - `0x04` SIGN_TRANSACTION      — `[derivPath || rlp]` chunked → `[v(1)|r(32)|s(32)]`
 *  - `0x06` GET_APP_CONFIGURATION — `[]` → `[flags(1)|major(1)|minor(1)|patch(1)]`
 *  - `0x08` SIGN_PERSONAL_MESSAGE — `[derivPath || msgLen(4) || msg]` chunked → `[v|r|s]`
 *  - `0x0C` SIGN_EIP712           — `[derivPath || domainHash(32) || msgHash(32)]` → `[v|r|s]`
 *
 *  For both SIGN_TRANSACTION and SIGN_PERSONAL_MESSAGE the *first*
 *  chunk carries the derivation path immediately followed by the
 *  beginning of the payload; continuation chunks carry only payload.
 *  P1 distinguishes first (`0x00`) from continuation (`0x80`); P2 is
 *  unused (always `0x00`) for the standard flows we implement here.
 *
 *  Reference: github.com/LedgerHQ/app-ethereum (doc/ethapp.adoc). */
object EthereumApp:

  val Cla: Int = 0xE0

  val Ins_GetPublicKey:         Int = 0x02
  val Ins_SignTransaction:      Int = 0x04
  val Ins_GetAppConfiguration:  Int = 0x06
  val Ins_SignPersonalMessage:  Int = 0x08
  val Ins_SignEip712:           Int = 0x0C

  val P1First:        Int = 0x00
  val P1Continue:     Int = 0x80
  val P2None:         Int = 0x00

  // P1 flags on GET_PUBLIC_KEY:
  val P1_DontConfirm: Int = 0x00
  val P1_Confirm:     Int = 0x01

  // P2 flags on GET_PUBLIC_KEY:
  val P2_NoChainCode: Int = 0x00
  val P2_ChainCode:   Int = 0x01

  /** Result of `getPublicKey`. */
  final case class PublicKeyInfo(
    publicKey:   Array[Byte],
    address:     String,
    chainCode:   Option[Array[Byte]],
  )

  /** Result of `signTransaction` / `signPersonalMessage` / `signEip712`:
   *  the EVM `(v, r, s)` triple. `v` here is the raw byte the device
   *  returned — for legacy txs the host may need to add an offset for
   *  EIP-155; the Vault layer is intentionally agnostic about that
   *  transformation. */
  final case class Signature(v: Int, r: Array[Byte], s: Array[Byte]):
    require(r.length == 32 && s.length == 32, s"r,s must be 32 B (got ${r.length}/${s.length})")

    /** Concatenate `r || s || v` into the canonical 65-byte EVM
     *  signature serialisation. */
    def toBytes65: Array[Byte] =
      val out = new Array[Byte](65)
      System.arraycopy(r, 0, out, 0,  32)
      System.arraycopy(s, 0, out, 32, 32)
      out(64) = (v & 0xff).toByte
      out

  /** GET_PUBLIC_KEY. Returns the uncompressed public key (65 B with
   *  leading 0x04), the device-formatted ASCII checksum address, and
   *  optionally the BIP-32 chain code if `requestChainCode=true`. */
  def getPublicKey(
    transport:         LedgerTransport,
    derivationPath:    String,
    confirmOnDevice:   Boolean = false,
    requestChainCode:  Boolean = false,
  )(using ec: ExecutionContext): Future[PublicKeyInfo] =
    val path = Bip32Path.encode(derivationPath)
    val p1   = if confirmOnDevice  then P1_Confirm     else P1_DontConfirm
    val p2   = if requestChainCode then P2_ChainCode    else P2_NoChainCode
    val cmd  = Apdu.command(Cla, Ins_GetPublicKey, p1, p2, path)
    transport.exchange(cmd).map { resp =>
      val (sw, payload) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"GET_PUBLIC_KEY failed: sw=${Apdu.swHex(sw)}")
      decodePublicKeyResponse(payload, withChainCode = requestChainCode)
    }

  /** Decode the GET_PUBLIC_KEY response:
   *  `[keyLen(1) | key (keyLen)] [addrLen(1) | addr (addrLen)] [chainCode(32)?]` */
  private[ethereum] def decodePublicKeyResponse(
    payload:        Array[Byte],
    withChainCode:  Boolean,
  ): PublicKeyInfo =
    require(payload.length >= 1, "GET_PUBLIC_KEY response is empty")
    val keyLen = payload(0) & 0xff
    require(payload.length >= 1 + keyLen + 1,
      s"GET_PUBLIC_KEY response truncated reading public key (len=$keyLen)")
    val key     = java.util.Arrays.copyOfRange(payload, 1, 1 + keyLen)
    val addrOff = 1 + keyLen
    val addrLen = payload(addrOff) & 0xff
    require(payload.length >= addrOff + 1 + addrLen,
      s"GET_PUBLIC_KEY response truncated reading address (len=$addrLen)")
    val addrBytes = java.util.Arrays.copyOfRange(payload, addrOff + 1, addrOff + 1 + addrLen)
    val address   = new String(addrBytes, java.nio.charset.StandardCharsets.US_ASCII)
    val chainCode =
      if withChainCode then
        val ccOff = addrOff + 1 + addrLen
        require(payload.length >= ccOff + 32,
          s"GET_PUBLIC_KEY response truncated reading chain code")
        Some(java.util.Arrays.copyOfRange(payload, ccOff, ccOff + 32))
      else None
    PublicKeyInfo(key, withFormalPrefix(address), chainCode)

  /** Some firmware versions return the address with or without the
   *  leading `0x`; normalise to canonical EIP-55 lower-prefix form. */
  private def withFormalPrefix(s: String): String =
    if s.startsWith("0x") || s.startsWith("0X") then s else "0x" + s

  /** SIGN_TRANSACTION. Chunked send of `[derivPath || rlp(unsignedTx)]`.
   *  The Ethereum app expects the *full* RLP-encoded unsigned tx in
   *  the payload — it computes its own keccak256 digest on-device.
   *
   *  Returns the device's `(v, r, s)` triple. */
  def signTransaction(
    transport:      LedgerTransport,
    derivationPath: String,
    rlpUnsigned:    Array[Byte],
  )(using ec: ExecutionContext): Future[Signature] =
    val path    = Bip32Path.encode(derivationPath)
    val payload = path ++ rlpUnsigned
    Apdu.chunkedSend(transport, Cla, Ins_SignTransaction, P1First, P1Continue, P2None, payload).map {
      case (Apdu.Sw_Ok, resp) => parseSignature(resp)
      case (sw, _)            => throw new RuntimeException(s"SIGN_TRANSACTION failed: sw=${Apdu.swHex(sw)}")
    }

  /** SIGN_PERSONAL_MESSAGE (EIP-191 personal_sign).
   *
   *  Payload: `[ derivPath || msgLen(4 BE) || msg ]`.
   *  The device prepends `"\x19Ethereum Signed Message:\n<len>"`
   *  internally. */
  def signPersonalMessage(
    transport:      LedgerTransport,
    derivationPath: String,
    message:        Array[Byte],
  )(using ec: ExecutionContext): Future[Signature] =
    val path     = Bip32Path.encode(derivationPath)
    val lenBe    = Array[Byte](
      ((message.length >>> 24) & 0xff).toByte,
      ((message.length >>> 16) & 0xff).toByte,
      ((message.length >>>  8) & 0xff).toByte,
      ( message.length         & 0xff).toByte,
    )
    val payload  = path ++ lenBe ++ message
    Apdu.chunkedSend(transport, Cla, Ins_SignPersonalMessage, P1First, P1Continue, P2None, payload).map {
      case (Apdu.Sw_Ok, resp) => parseSignature(resp)
      case (sw, _)            => throw new RuntimeException(s"SIGN_PERSONAL_MESSAGE failed: sw=${Apdu.swHex(sw)}")
    }

  /** SIGN_EIP712 (hashed flow). Sends `[derivPath || domainSeparator(32) || messageHash(32)]`
   *  in a single APDU and returns the `(v, r, s)` triple.
   *
   *  Compatible with every Ethereum app version ≥ 1.6. For the
   *  full structured-data flow (filter descriptors, on-device field
   *  rendering) use `SIGN_EIP712_FULL` — not implemented here. */
  def signEip712Hashed(
    transport:        LedgerTransport,
    derivationPath:   String,
    domainSeparator:  Array[Byte],
    messageHash:      Array[Byte],
  )(using ec: ExecutionContext): Future[Signature] =
    require(domainSeparator.length == 32, s"domainSeparator must be 32 B (got ${domainSeparator.length})")
    require(messageHash.length     == 32, s"messageHash must be 32 B (got ${messageHash.length})")
    val path    = Bip32Path.encode(derivationPath)
    val payload = path ++ domainSeparator ++ messageHash
    val cmd     = Apdu.command(Cla, Ins_SignEip712, P1First, P2None, payload)
    transport.exchange(cmd).map { resp =>
      val (sw, body) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"SIGN_EIP712 failed: sw=${Apdu.swHex(sw)}")
      parseSignature(body)
    }

  /** Parse the `[v(1) | r(32) | s(32)]` triple returned by every
   *  signing INS in the Ethereum app. */
  private[ethereum] def parseSignature(resp: Array[Byte]): Signature =
    require(resp.length == 65,
      s"Ethereum signature must be 65 B (got ${resp.length})")
    Signature(
      v = resp(0) & 0xff,
      r = java.util.Arrays.copyOfRange(resp, 1, 33),
      s = java.util.Arrays.copyOfRange(resp, 33, 65),
    )
