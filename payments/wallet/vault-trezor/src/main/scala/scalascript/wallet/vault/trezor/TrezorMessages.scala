package scalascript.wallet.vault.trezor

import upickle.default.*

// ── Device info from /enumerate ──────────────────────────────────────────────

case class TrezorDeviceInfo(
  path:    String,
  session: Option[String],
  vendor:  String  = "",
  product: String  = "",
)

object TrezorDeviceInfo:
  given ReadWriter[TrezorDeviceInfo] = macroRW

// ── Bridge call request / response ────────────────────────────────────────────

case class TrezorCallRequest(
  `type`:  String,
  message: ujson.Value,
)

case class TrezorResponse(
  messageType: String,
  message:     ujson.Value,
)

// ── BIP-32 path helpers ───────────────────────────────────────────────────────

object Bip32:
  val Hardened: Int = 0x80000000.toInt

  /** Parse `"m/44'/60'/0'/0/0"` → `Array(0x8000002c, 0x8000003c, 0x80000000, 0, 0)`. */
  def parse(path: String): Array[Int] =
    path.stripPrefix("m/").split('/').map { segment =>
      if segment.endsWith("'") then
        segment.dropRight(1).toInt | Hardened
      else
        segment.toInt
    }

  val DefaultEthereum: String = "m/44'/60'/0'/0/0"

// ── Known message types ───────────────────────────────────────────────────────

object TrezorMessageType:
  val Initialize            = "Initialize"
  val Features              = "Features"
  val GetPublicKey          = "GetPublicKey"
  val PublicKey             = "PublicKey"
  val EthereumSignMessage   = "EthereumSignMessage"
  val EthereumMessageSig    = "EthereumMessageSignature"
  val EthereumGetAddress    = "EthereumGetAddress"
  val EthereumAddress       = "EthereumAddress"
  val ButtonRequest         = "ButtonRequest"
  val ButtonAck             = "ButtonAck"
  val PinMatrixRequest      = "PinMatrixRequest"
  val PassphraseRequest     = "PassphraseRequest"
  val Failure               = "Failure"
  val Success               = "Success"

// ── Failure message ───────────────────────────────────────────────────────────

class TrezorDeviceFailure(val code: Int, val message: String)
    extends RuntimeException(s"Trezor Failure($code): $message")

object TrezorDeviceFailure:
  def fromResponse(r: TrezorResponse): TrezorDeviceFailure =
    val code = r.message.obj.get("code").map(_.num.toInt).getOrElse(0)
    val msg  = r.message.obj.get("message").map(_.str).getOrElse("unknown error")
    new TrezorDeviceFailure(code, msg)
