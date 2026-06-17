package scalascript.payments.caeft

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Canada Interac e-Transfer + EFT webhook receiver.
 *
 *  Verification: HMAC-SHA256 over raw body.
 *  Signature header: `X-Interac-Signature: sha256=<hex>`.
 *
 *  Supported event types (JSON `"type"` field):
 *
 *    interac.transfer.sent       → CaInteracSent
 *      JSON: { "type": "...", "transferId": "...", "recipient": "...", "amount": "..." }
 *      Fired when the recipient deposits (or auto-deposits) the Interac e-Transfer.
 *
 *    interac.transfer.reclaimed  → CaInteracReclaimed
 *      JSON: { "type": "...", "transferId": "...", "reason": "..." }
 *      Fired when the sender recalls an un-deposited transfer.
 *
 *    interac.transfer.expired    → CaInteracExpired
 *      JSON: { "type": "...", "transferId": "..." }
 *      Fired when the transfer expires (recipient did not deposit within 30 days).
 *
 *    eft.debit.returned          → CaEftReturned
 *      JSON: { "type": "...", "transferId": "...", "returnCode": "...", "description": "..." }
 *      Fired when an EFT AFT debit is returned (NSF, account closed, etc.).
 *
 *  See specs/international-bank-rails.md §CA_INTERAC for webhook taxonomy.
 */
class CaEftWebhookReceiver(
    override val config:   WebhookConfig = WebhookConfig(),
    override val seenKeys: SeenKeyStore  = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-Interac-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected signature format in $SignatureHeader: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature(s"$SignatureHeader HMAC-SHA256 mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"CA EFT/Interac event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.CaInteracSent(transferId, recipient, _)       => s"ca.interac.sent.$transferId.$recipient"
    case BankRailsEvent.CaInteracReclaimed(transferId, _)             => s"ca.interac.reclaimed.$transferId"
    case BankRailsEvent.CaInteracExpired(transferId)                  => s"ca.interac.expired.$transferId"
    case BankRailsEvent.CaEftReturned(transferId, returnCode, _)      => s"ca.eft.returned.$transferId.$returnCode"
    case other                                                         => s"ca.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private[caeft] def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in CA EFT/Interac webhook event")
    )
    eventType match
      case "interac.transfer.sent" =>
        val transferId = extractJsonString(body, "transferId").getOrElse("unknown")
        val recipient  = extractJsonString(body, "recipient").getOrElse("unknown")
        val amount     = extractJsonString(body, "amount").getOrElse("0")
        BankRailsEvent.CaInteracSent(transferId, recipient, amount)

      case "interac.transfer.reclaimed" =>
        val transferId = extractJsonString(body, "transferId").getOrElse("unknown")
        val reason     = extractJsonString(body, "reason").getOrElse("reclaimed")
        BankRailsEvent.CaInteracReclaimed(transferId, reason)

      case "interac.transfer.expired" =>
        val transferId = extractJsonString(body, "transferId").getOrElse("unknown")
        BankRailsEvent.CaInteracExpired(transferId)

      case "eft.debit.returned" =>
        val transferId  = extractJsonString(body, "transferId").getOrElse("unknown")
        val returnCode  = extractJsonString(body, "returnCode").getOrElse("900")
        val description = extractJsonString(body, "description")
                            .getOrElse(EftReturnCode.description(returnCode))
        BankRailsEvent.CaEftReturned(transferId, returnCode, description)

      case other =>
        throw new IllegalArgumentException(s"Unknown CA EFT/Interac event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[caeft] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[caeft] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0


/** CPA Standard 005 EFT return codes.
 *  See CPA AFT return reason codes for full list. */
object EftReturnCode:
  def description(code: String): String = code match
    case "900" => "Edit reject — invalid data in the AFT item"
    case "901" => "Invalid account number"
    case "902" => "Invalid transit/routing number"
    case "903" => "No account or wrong account type"
    case "904" => "Account closed"
    case "905" => "Insufficient funds (NSF)"
    case "906" => "Payment stopped"
    case "907" => "Customer deceased"
    case "908" => "Customer revoked authorization"
    case "909" => "Account frozen"
    case "910" => "Currency/bank not applicable for AFT"
    case other  => s"CPA EFT return code $other"
