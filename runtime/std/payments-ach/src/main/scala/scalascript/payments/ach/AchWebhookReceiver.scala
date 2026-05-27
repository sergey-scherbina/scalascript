package scalascript.payments.ach

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** ACH webhook receiver.
 *
 *  Verification: HMAC-SHA256 over raw body; signature header `X-ACH-Signature: sha256=<hex>`.
 *  Uses the same raw-body signing convention as SEPA and Stripe.
 *
 *  Supported event types (JSON `"type"` field):
 *    ach.transfer.settled  → AchTransferSettled
 *    ach.return            → AchReturn  (R-code in "r_code" field)
 *    ach.notification_of_change → AchNotificationOfChange  (C-code in "c_code" field)
 *
 *  See docs/bank-rails.md §7.2 for event taxonomy.
 */
class AchWebhookReceiver(
    override val config:   WebhookConfig = WebhookConfig(),
    override val seenKeys: SeenKeyStore  = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-ACH-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected signature format: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256Hex(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature("X-ACH-Signature HMAC-SHA256 mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"ACH event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.AchTransferSettled(t)               => s"ach.settled.${t.id.value}"
    case BankRailsEvent.AchReturn(t, rc, _)                 => s"ach.return.${t.id.value}.${rc.value}"
    case BankRailsEvent.AchNotificationOfChange(t, cc, _)   => s"ach.noc.${t.id.value}.${cc.value}"
    case other                                               => s"ach.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ──────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in ACH webhook event")
    )
    eventType match
      case "ach.transfer.settled" =>
        val t = parseTransfer(body)
        BankRailsEvent.AchTransferSettled(t.copy(status = BankTransferStatus.Settled))

      case "ach.return" =>
        val t     = parseTransfer(body)
        val rStr  = extractJsonString(body, "r_code").getOrElse("R00")
        val rCode = RCode(rStr)
        val desc  = extractJsonString(body, "description").getOrElse(rCode.description)
        BankRailsEvent.AchReturn(
          t.copy(status = BankTransferStatus.Returned(ReturnCode(rStr), desc)),
          rCode,
          desc,
        )

      case "ach.notification_of_change" =>
        val t       = parseTransfer(body)
        val cStr    = extractJsonString(body, "c_code").getOrElse("C01")
        val cCode   = CCode(cStr)
        val corrected = extractJsonString(body, "corrected_data").getOrElse("")
        BankRailsEvent.AchNotificationOfChange(t, cCode, corrected)

      case other =>
        throw new IllegalArgumentException(s"Unknown ACH event type: $other")

  private def parseTransfer(body: String): BankTransfer =
    val id        = extractJsonString(body, "transfer_id").getOrElse("unknown")
    val amountStr = extractJsonString(body, "amount").getOrElse("0")
    val ccyStr    = extractJsonString(body, "currency").getOrElse("USD")
    val reference = extractJsonString(body, "reference").getOrElse("")
    val amount    = parseMoneyFromDecimal(amountStr, ccyStr)
    val dummy     = BankAccount(holderName = "", countryCode = "US")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.ACH_CREDIT,
      amount    = amount,
      sender    = dummy,
      recipient = dummy,
      reference = reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )

  private def parseMoneyFromDecimal(decimalStr: String, currencyCode: String): Money =
    val ccy   = Currency(currencyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = scala.util.Try(BigDecimal(decimalStr)).getOrElse(BigDecimal(0))
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

  // ── Minimal JSON field extraction ────────────────────────────────────────

  private[ach] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ───────────────────────────────────────────────────────

  private[ach] def hmacSha256Hex(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
