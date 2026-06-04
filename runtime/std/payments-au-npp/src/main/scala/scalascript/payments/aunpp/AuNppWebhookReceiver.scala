package scalascript.payments.aunpp

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** Australia NPP webhook receiver: verifies `X-NPP-Signature` header (HMAC-SHA256 over raw body)
 *  and parses the JSON payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    npp.payment.credited  → AuNppCredited  (NPP settlement confirmed by receiving participant)
 *    npp.payment.returned  → AuNppReturned  (transfer returned with NPP return code)
 *
 *  Webhook auth: `X-NPP-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body,
 *  shared secret configured at aggregator on-boarding.
 *
 *  See docs/specs/payment-rails-apac.md §AU_NPP.
 */
class AuNppWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-NPP-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"NPP event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.AuNppCredited(transfer)            => s"au-npp.credited.${transfer.id.value}"
    case BankRailsEvent.AuNppReturned(transfer, returnCode) => s"au-npp.returned.${transfer.id.value}.$returnCode"
    case other                                              => s"au-npp.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private[aunpp] def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in NPP webhook event")
    )
    eventType match
      case "npp.payment.credited" =>
        val transferId = extractJsonString(body, "transferId")
                           .orElse(extractJsonString(body, "endToEndId"))
                           .orElse(extractJsonString(body, "id"))
                           .getOrElse("unknown")
        val amountStr  = extractJsonString(body, "amount").getOrElse("0")
        val reference  = extractJsonString(body, "remittanceInfo")
                           .orElse(extractJsonString(body, "reference"))
                           .getOrElse("")
        val creditorBsb  = extractJsonString(body, "creditorBsb").getOrElse("")
        val creditorAcct = extractJsonString(body, "creditorAccount").getOrElse("")
        val creditorName = extractJsonString(body, "creditorName").getOrElse("")
        val debtorBsb    = extractJsonString(body, "debtorBsb").getOrElse("")
        val debtorAcct   = extractJsonString(body, "debtorAccount").getOrElse("")
        val debtorName   = extractJsonString(body, "debtorName").getOrElse("")
        val transfer = BankTransfer(
          id        = TransferId(transferId),
          rail      = RailKind.AU_NPP,
          amount    = parseMoney(amountStr),
          sender    = BankAccount(
            holderName    = debtorName,
            countryCode   = "AU",
            accountNumber = Some(debtorAcct).filter(_.nonEmpty),
            bsbNumber     = Some(debtorBsb).filter(_.nonEmpty),
          ),
          recipient = BankAccount(
            holderName    = creditorName,
            countryCode   = "AU",
            accountNumber = Some(creditorAcct).filter(_.nonEmpty),
            bsbNumber     = Some(creditorBsb).filter(_.nonEmpty),
          ),
          reference = reference,
          status    = BankTransferStatus.Settled,
          createdAt = Instant.now(),
        )
        BankRailsEvent.AuNppCredited(transfer)

      case "npp.payment.returned" =>
        val transferId = extractJsonString(body, "transferId")
                           .orElse(extractJsonString(body, "endToEndId"))
                           .orElse(extractJsonString(body, "id"))
                           .getOrElse("unknown")
        val returnCode = extractJsonString(body, "returnCode")
                           .orElse(extractJsonString(body, "reason"))
                           .getOrElse("unknown")
        val amountStr  = extractJsonString(body, "amount").getOrElse("0")
        val reference  = extractJsonString(body, "remittanceInfo")
                           .orElse(extractJsonString(body, "reference"))
                           .getOrElse("")
        val dummy = BankAccount(holderName = "", countryCode = "AU")
        val transfer = BankTransfer(
          id        = TransferId(transferId),
          rail      = RailKind.AU_NPP,
          amount    = parseMoney(amountStr),
          sender    = dummy,
          recipient = dummy,
          reference = reference,
          status    = BankTransferStatus.Returned(ReturnCode(returnCode), returnCode),
          createdAt = Instant.now(),
        )
        BankRailsEvent.AuNppReturned(transfer, returnCode)

      case other =>
        throw new IllegalArgumentException(s"Unknown NPP event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[aunpp] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Money parsing ──────────────────────────────────────────────────────────

  private def parseMoney(amountStr: String): Money =
    val cents = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(cents, Currency("AUD"))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[aunpp] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
