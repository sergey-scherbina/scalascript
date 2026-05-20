package scalascript.wallet.vault.ledger

import scala.concurrent.{ExecutionContext, Future}

/** Wraps the Ledger dashboard-app APDU `B0 01 00 00` —
 *  `getAppAndVersion`. Returns the currently-active on-device app
 *  name + its version string. Critical for the Vault to detect when
 *  the user must switch apps before signing.
 *
 *  Response payload format (per BOLOS spec):
 *  {{{
 *  [ 01 | nameLen (1B) | name (nameLen B)
 *       | verLen  (1B) | ver  (verLen B) ]
 *  }}}
 *  The leading `0x01` is a format-version byte. */
object Dashboard:

  /** BOLOS dashboard CLA. */
  val Cla: Int = 0xB0

  /** `getAppAndVersion` INS. */
  val Ins_GetAppAndVersion: Int = 0x01

  /** Send `getAppAndVersion` and parse the result. Throws if the
   *  status word is non-zero. */
  def getAppName(t: LedgerTransport)(using ec: ExecutionContext): Future[AppInfo] =
    val cmd = Apdu.command(Cla, Ins_GetAppAndVersion, 0, 0, Array.emptyByteArray)
    t.exchange(cmd).map { resp =>
      val (sw, payload) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then
        throw new RuntimeException(s"getAppAndVersion failed: sw=${Apdu.swHex(sw)}")
      parse(payload)
    }

  /** Decode the dashboard response payload. */
  def parse(payload: Array[Byte]): AppInfo =
    require(payload.length >= 3, s"AppInfo payload too short: ${payload.length}")
    require(payload(0) == 0x01, s"Unexpected format byte: ${payload(0)}")
    val nameLen = payload(1) & 0xff
    require(payload.length >= 2 + nameLen + 1,
      s"AppInfo payload truncated: have ${payload.length}, need ${2 + nameLen + 1}+verLen")
    val name = new String(payload, 2, nameLen, java.nio.charset.StandardCharsets.UTF_8)
    val verLen = payload(2 + nameLen) & 0xff
    val verOff = 2 + nameLen + 1
    require(payload.length >= verOff + verLen,
      s"AppInfo payload truncated reading version (len=$verLen)")
    val version = new String(payload, verOff, verLen, java.nio.charset.StandardCharsets.UTF_8)
    AppInfo(name, version)

  /** Convenience: probe the device and assert the required app is
   *  the one currently open. Returns `Future.failed` with
   *  [[AppSwitchRequired]] otherwise. */
  def requireApp(t: LedgerTransport, requiredApp: String)(using ec: ExecutionContext): Future[AppInfo] =
    getAppName(t).flatMap { info =>
      if info.name == requiredApp then Future.successful(info)
      else Future.failed(AppSwitchRequired(info.name, requiredApp))
    }

/** Parsed `getAppAndVersion` response. */
final case class AppInfo(name: String, version: String)
