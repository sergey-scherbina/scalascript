package scalascript.payments.paynow

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** PayNow webhook receiver: verifies `X-PayNow-Signature` header (HMAC-SHA256 over raw body)
 *  and parses the JSON payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    paynow.payment.credit  → PayNowSettled  (FAST settlement confirmed)
 *    paynow.payment.return  → PayNowFailed   (transaction returned by receiving bank)
 *
 *  Webhook auth: `X-PayNow-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body,
 *  shared secret configured at aggregator on-boarding.
 *
 *  See docs/specs/international-bank-rails.md §v1.56.8.
 */
class PayNowWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-PayNow-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"PayNow event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.PayNowSettled(txnRef, proxy, _, _) => s"paynow.settled.$txnRef.$proxy"
    case BankRailsEvent.PayNowFailed(txnRef, _)            => s"paynow.failed.$txnRef"
    case other                                             => s"paynow.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private[paynow] def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in PayNow webhook event")
    )
    eventType match
      case "paynow.payment.credit" =>
        val txnRef   = extractJsonString(body, "transactionRef")
                         .orElse(extractJsonString(body, "txnRef"))
                         .getOrElse("unknown")
        val proxy    = extractJsonString(body, "proxyValue")
                         .orElse(extractJsonString(body, "proxy"))
                         .getOrElse("unknown")
        val amount   = extractJsonString(body, "amount").getOrElse("0")
        val currency = extractJsonString(body, "currency").getOrElse("SGD")
        BankRailsEvent.PayNowSettled(txnRef, proxy, amount, currency)

      case "paynow.payment.return" =>
        val txnRef = extractJsonString(body, "transactionRef")
                       .orElse(extractJsonString(body, "txnRef"))
                       .getOrElse("unknown")
        val reason = extractJsonString(body, "reason")
                       .orElse(extractJsonString(body, "returnReason"))
                       .orElse(extractJsonString(body, "returnCode"))
                       .getOrElse("returned")
        BankRailsEvent.PayNowFailed(txnRef, reason)

      case other =>
        throw new IllegalArgumentException(s"Unknown PayNow event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[paynow] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[paynow] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
