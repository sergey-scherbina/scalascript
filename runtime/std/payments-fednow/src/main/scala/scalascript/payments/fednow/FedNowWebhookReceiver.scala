package scalascript.payments.fednow

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** FedNow webhook receiver: verifies `X-FedNow-Signature: sha256=<hex>` header
 *  (HMAC-SHA256 over raw body) and parses the payload into typed `BankRailsEvent` values.
 *
 *  FedNow notifications are delivered over HTTPS with HMAC-SHA256 signing.
 *  The shared secret is provisioned during FedNow Connect onboarding.
 *
 *  Supported event types (JSON `"event"` field):
 *    fednow.credit.received  — positive pacs.002 acknowledgment (credit settled)
 *    fednow.credit.rejected  — negative pacs.002; reason codes AC01/CUST/DUPL/FOCR
 *    fednow.return.received  — return initiated by receiving FI
 *
 *  See specs/bank-rails.md §7.4 FedNow.
 */
class FedNowWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-FedNow-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"FedNow event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.FedNowCreditReceived(t)     => s"fednow.credit.received.${t.id.value}"
    case BankRailsEvent.FedNowRejected(t, _)        => s"fednow.credit.rejected.${t.id.value}"
    case other                                       => s"fednow.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "event").getOrElse(
      throw new IllegalArgumentException("Missing 'event' field in FedNow webhook payload")
    )
    eventType match
      case "fednow.credit.received" =>
        val t = parseTransfer(body)
        BankRailsEvent.FedNowCreditReceived(t.copy(status = BankTransferStatus.Settled))
      case "fednow.credit.rejected" =>
        val t    = parseTransfer(body)
        val code = extractJsonString(body, "reason_code").map(RejectCode(_))
                     .getOrElse(RejectCode("FOCR"))
        val desc = extractJsonString(body, "reason_desc").getOrElse("")
        BankRailsEvent.FedNowRejected(
          t.copy(status = BankTransferStatus.Rejected(code, desc)),
          code,
        )
      case "fednow.return.received" =>
        // FedNow return: map as FedNowCreditReceived with Returned status (no dedicated event yet)
        val t    = parseTransfer(body)
        val code = extractJsonString(body, "reason_code").map(ReturnCode(_))
                     .getOrElse(ReturnCode("FOCR"))
        val desc = extractJsonString(body, "reason_desc").getOrElse("")
        // Surface as a generic FedNowCreditReceived with Returned status so callers can inspect
        BankRailsEvent.FedNowCreditReceived(t.copy(status = BankTransferStatus.Returned(code, desc)))
      case other =>
        throw new IllegalArgumentException(s"Unknown FedNow event type: $other")

  private def parseTransfer(body: String): BankTransfer =
    val id        = extractJsonString(body, "instr_id")
                      .orElse(extractJsonString(body, "transfer_id"))
                      .getOrElse("unknown")
    val amountStr = extractJsonString(body, "amount").getOrElse("0")
    val reference = extractJsonString(body, "end_to_end_id")
                      .orElse(extractJsonString(body, "reference"))
                      .getOrElse("")
    val amount    = parseMoneyFromDecimal(amountStr, "USD")
    val dummy     = BankAccount(holderName = "", countryCode = "US")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.FEDNOW,
      amount    = amount,
      sender    = dummy,
      recipient = dummy,
      reference = reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )

  /** Parse a FedNow credit-received event into a FedNowCreditReceived with instrId and amount. */
  private[fednow] def parseFedNowCreditReceived(body: String): (String, Money) =
    val instrId = extractJsonString(body, "instr_id")
                    .orElse(extractJsonString(body, "transfer_id"))
                    .getOrElse("unknown")
    val amountStr = extractJsonString(body, "amount").getOrElse("0")
    val amount    = parseMoneyFromDecimal(amountStr, "USD")
    (instrId, amount)

  private def parseMoneyFromDecimal(decimalStr: String, currencyCode: String): Money =
    val ccy   = Currency(currencyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = BigDecimal(decimalStr)
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  /** Extract a string value for a key from a flat JSON object. */
  private[fednow] def extractJsonString(json: String, key: String): Option[String] =
    val pattern = s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[fednow] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
