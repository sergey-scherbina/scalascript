package scalascript.payments.sepa

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** SEPA webhook receiver: verifies `X-SEPA-Signature: sha256=<hex>` header
 *  (HMAC-SHA256 over raw body) and parses the payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    sepa.transfer.completed, sepa.transfer.rejected, sepa.transfer.returned,
 *    sepa.directdebit.completed, sepa.directdebit.returned,
 *    sepa.mandate.activated, sepa.mandate.cancelled,
 *    SCTInst.CreditTransfer.Settlement  (v1.55.2 SCT Inst settled — pacs.002 ACCC)
 *    SCTInst.CreditTransfer.Rejection   (v1.55.2 SCT Inst rejected — pacs.002 RJCT)
 */
class SepaWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-SEPA-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    // Format: "sha256=<hex>"
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected signature format in $SignatureHeader: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature(s"$SignatureHeader HMAC-SHA256 mismatch"))

    // Parse event
    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"SEPA event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.SepaTransferCompleted(t)         => s"sepa.transfer.completed.${t.id.value}"
    case BankRailsEvent.SepaTransferRejected(t, _)       => s"sepa.transfer.rejected.${t.id.value}"
    case BankRailsEvent.SepaTransferReturned(t, _)       => s"sepa.transfer.returned.${t.id.value}"
    case BankRailsEvent.SepaDirectDebitCompleted(t)      => s"sepa.dd.completed.${t.id.value}"
    case BankRailsEvent.SepaDirectDebitReturned(t, _)    => s"sepa.dd.returned.${t.id.value}"
    case BankRailsEvent.SepaMandateActivated(m)          => s"sepa.mandate.activated.${m.id.value}"
    case BankRailsEvent.SepaMandateCanceled(m)           => s"sepa.mandate.cancelled.${m.id.value}"
    case BankRailsEvent.SctInstSettled(e2eId, _, _)      => s"sctinst.settled.$e2eId"
    case BankRailsEvent.SctInstRejected(e2eId, _)        => s"sctinst.rejected.$e2eId"
    case other                                            => s"sepa.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    // Minimal JSON parsing without external library — extract "type" field
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in SEPA webhook event")
    )
    eventType match
      case "sepa.transfer.completed" =>
        val t = parseTransfer(body)
        BankRailsEvent.SepaTransferCompleted(t.copy(status = BankTransferStatus.Settled))
      case "sepa.transfer.rejected"  =>
        val t    = parseTransfer(body)
        val code = extractJsonString(body, "reason_code").map(RejectCode(_))
                     .getOrElse(RejectCode("MS02"))
        BankRailsEvent.SepaTransferRejected(t.copy(status = BankTransferStatus.Rejected(code, extractJsonString(body, "reason_desc").getOrElse(""))), code)
      case "sepa.transfer.returned"  =>
        val t    = parseTransfer(body)
        val code = extractJsonString(body, "reason_code").map(ReturnCode(_))
                     .getOrElse(ReturnCode("MS02"))
        BankRailsEvent.SepaTransferReturned(t.copy(status = BankTransferStatus.Returned(code, extractJsonString(body, "reason_desc").getOrElse(""))), code)
      case "sepa.directdebit.completed" =>
        val t = parseTransfer(body)
        BankRailsEvent.SepaDirectDebitCompleted(t.copy(status = BankTransferStatus.Settled))
      case "sepa.directdebit.returned"  =>
        val t    = parseTransfer(body)
        val code = extractJsonString(body, "reason_code").map(ReturnCode(_))
                     .getOrElse(ReturnCode("MS02"))
        BankRailsEvent.SepaDirectDebitReturned(t, code)
      case "sepa.mandate.activated"  =>
        BankRailsEvent.SepaMandateActivated(parseMandate(body, MandateStatus.Active))
      case "sepa.mandate.cancelled"  =>
        BankRailsEvent.SepaMandateCanceled(parseMandate(body, MandateStatus.Canceled))
      // SCT Inst events: aggregator delivers pacs.002 ACCC/RJCT notifications
      // using the SCTInst.CreditTransfer.* naming convention from the EBA RT1/TIPS scheme
      case "SCTInst.CreditTransfer.Settlement" =>
        // pacs.002 ACCC — settlement confirmed by TIPS/RT1 within the 10-second window
        val e2eId    = extractJsonString(body, "end_to_end_id").getOrElse(
                         extractJsonString(body, "endToEndId").getOrElse("unknown"))
        val amount   = extractJsonString(body, "amount").getOrElse("0")
        val currency = extractJsonString(body, "currency").getOrElse("EUR")
        BankRailsEvent.SctInstSettled(e2eId, amount, currency)
      case "SCTInst.CreditTransfer.Rejection" =>
        // pacs.002 RJCT — rejection within the 10-second SCT Inst window
        val e2eId  = extractJsonString(body, "end_to_end_id").getOrElse(
                       extractJsonString(body, "endToEndId").getOrElse("unknown"))
        val reason = extractJsonString(body, "reason").orElse(
                       extractJsonString(body, "reason_code")).getOrElse("UNKNOWN")
        BankRailsEvent.SctInstRejected(e2eId, reason)
      case other =>
        throw new IllegalArgumentException(s"Unknown SEPA event type: $other")

  private def parseTransfer(body: String): BankTransfer =
    val id        = extractJsonString(body, "transfer_id").getOrElse("unknown")
    val amountStr = extractJsonString(body, "amount").getOrElse("0")
    val ccyStr    = extractJsonString(body, "currency").getOrElse("EUR")
    val reference = extractJsonString(body, "reference").getOrElse("")
    val amount    = parseMoneyFromDecimal(amountStr, ccyStr)
    val dummy     = BankAccount(iban = None, holderName = "", countryCode = "")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.SEPA_CT,
      amount    = amount,
      sender    = dummy,
      recipient = dummy,
      reference = reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )

  private def parseMandate(body: String, status: MandateStatus): DirectDebitMandate =
    val id  = extractJsonString(body, "mandate_id").getOrElse("unknown")
    val dummy = BankAccount(iban = None, holderName = "", countryCode = "")
    DirectDebitMandate(
      id              = MandateId(id),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = dummy,
      creditorAccount = dummy,
      creditorName    = extractJsonString(body, "creditor_name").getOrElse(""),
      status          = status,
      sequenceType    = MandateSequenceType.Recurring,
    )

  private def parseMoneyFromDecimal(decimalStr: String, currencyCode: String): scalascript.payments.money.Money =
    import scalascript.payments.money.{Money, Currency}
    val ccy   = Currency(currencyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = BigDecimal(decimalStr)
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  /** Extract a string value for a key from a flat JSON object (no nested support needed). */
  private[sepa] def extractJsonString(json: String, key: String): Option[String] =
    val pattern = s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[sepa] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
