package scalascript.payments.ukchaps

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** UK CHAPS webhook receiver: verifies `X-CHAPS-Signature: sha256=<hex>` header
 *  (HMAC-SHA256 over raw body) and parses the payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    chaps.payment.settled  → ChapsSettled
 *    chaps.payment.rejected → ChapsRejected
 *
 *  Webhook auth: `X-CHAPS-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body,
 *  shared secret configured at aggregator on-boarding.
 *
 *  See specs/international-bank-rails.md §7 (UK CHAPS).
 */
class UkChapsWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-CHAPS-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"CHAPS event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.ChapsSettled(endToEndId, _, _) => s"chaps.settled.$endToEndId"
    case BankRailsEvent.ChapsRejected(endToEndId, _)   => s"chaps.rejected.$endToEndId"
    case other                                         => s"chaps.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in CHAPS webhook event")
    )
    eventType match
      case "chaps.payment.settled" =>
        val endToEndId = extractJsonString(body, "endToEndId").getOrElse(
          extractJsonString(body, "end_to_end_id").getOrElse("unknown")
        )
        val amount     = extractJsonString(body, "amount").getOrElse("0")
        val currency   = extractJsonString(body, "currency").getOrElse("GBP")
        BankRailsEvent.ChapsSettled(endToEndId, amount, currency)

      case "chaps.payment.rejected" =>
        val endToEndId = extractJsonString(body, "endToEndId").getOrElse(
          extractJsonString(body, "end_to_end_id").getOrElse("unknown")
        )
        val reason     = extractJsonString(body, "reason").getOrElse(
          extractJsonString(body, "rejectionReason").getOrElse("unknown")
        )
        BankRailsEvent.ChapsRejected(endToEndId, reason)

      case other =>
        throw new IllegalArgumentException(s"Unknown CHAPS event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[ukchaps] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[ukchaps] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
