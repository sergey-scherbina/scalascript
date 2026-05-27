package scalascript.payments.mxspei

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SPEI webhook receiver: verifies `X-SPEI-Signature` header (HMAC-SHA256 over raw body)
 *  and parses the JSON payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    spei.transfer.confirmed → MxSpeiConfirmed  (SPEI credit settled at recipient bank)
 *    spei.transfer.rejected  → MxSpeiRejected   (transfer rejected by BANXICO or recipient bank)
 *    spei.transfer.returned  → MxSpeiReturned   (settled transfer returned by recipient bank)
 *
 *  Webhook auth: `X-SPEI-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body.
 *
 *  See docs/payment-rails-apac.md §MX_SPEI.
 */
class MxSpeiWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-SPEI-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"SPEI event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.MxSpeiConfirmed(transfer)           => s"spei.confirmed.${transfer.id.value}"
    case BankRailsEvent.MxSpeiRejected(transfer, errorCode) => s"spei.rejected.${transfer.id.value}.$errorCode"
    case BankRailsEvent.MxSpeiReturned(transfer, returnCode) => s"spei.returned.${transfer.id.value}.$returnCode"
    case other                                              => s"spei.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private[mxspei] def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in SPEI webhook event")
    )
    val transferId = extractJsonString(body, "transferId")
                      .orElse(extractJsonString(body, "transactionId"))
                      .orElse(extractJsonString(body, "id"))
                      .getOrElse("unknown")
    val clabe      = extractJsonString(body, "clabe").getOrElse("")
    val amountStr  = extractJsonString(body, "amount").getOrElse("0")
    val dummy      = BankAccount(holderName = "", countryCode = "MX", clabe = Some(clabe))
    import scalascript.payments.money.{Money, Currency}
    val amount     = Money(scala.util.Try(amountStr.toLong).getOrElse(0L), Currency("MXN"))
    val transfer   = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.MX_SPEI,
      amount    = amount,
      sender    = dummy,
      recipient = dummy,
      reference = extractJsonString(body, "reference").getOrElse(""),
      status    = BankTransferStatus.Pending,
      createdAt = java.time.Instant.now(),
    )
    eventType match
      case "spei.transfer.confirmed" =>
        BankRailsEvent.MxSpeiConfirmed(transfer)

      case "spei.transfer.rejected" =>
        val errorCode = extractJsonString(body, "errorCode")
                          .orElse(extractJsonString(body, "rejectCode"))
                          .getOrElse("rejected")
        BankRailsEvent.MxSpeiRejected(transfer, errorCode)

      case "spei.transfer.returned" =>
        val returnCode = extractJsonString(body, "returnCode")
                          .orElse(extractJsonString(body, "reason"))
                          .getOrElse("returned")
        BankRailsEvent.MxSpeiReturned(transfer, returnCode)

      case other =>
        throw new IllegalArgumentException(s"Unknown SPEI event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[mxspei] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[mxspei] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
