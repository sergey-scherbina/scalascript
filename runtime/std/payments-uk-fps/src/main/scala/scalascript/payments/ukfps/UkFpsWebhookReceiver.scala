package scalascript.payments.ukfps

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** UK FPS webhook receiver: verifies `X-FPS-Signature: sha256=<hex>` header
 *  (HMAC-SHA256 over raw body) and parses the payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"type"` field):
 *    uk.faster-payments.credit   → UkFpsAccepted
 *    uk.faster-payments.rejected → UkFpsRejected
 *    uk.faster-payments.return   → UkFpsReturned
 *
 *  Webhook auth: `X-FPS-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body,
 *  shared secret configured at aggregator on-boarding.
 *
 *  See docs/specs/international-bank-rails.md §7 (UK FPS).
 */
class UkFpsWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-FPS-Signature"

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
      case scala.util.Failure(e)     => Left(MalformedPayload(s"UK FPS event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.UkFpsAccepted(txId, _)        => s"uk.fps.accepted.$txId"
    case BankRailsEvent.UkFpsRejected(txId, _)        => s"uk.fps.rejected.$txId"
    case BankRailsEvent.UkFpsReturned(txId, code, _)  => s"uk.fps.returned.$txId.$code"
    case other                                         => s"uk.fps.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in UK FPS webhook event")
    )
    eventType match
      case "uk.faster-payments.credit" =>
        val txId   = extractJsonString(body, "transactionId").getOrElse(
          extractJsonString(body, "transaction_id").getOrElse("unknown")
        )
        val amount = extractJsonString(body, "amount").getOrElse("0")
        BankRailsEvent.UkFpsAccepted(txId, amount)

      case "uk.faster-payments.rejected" =>
        val txId   = extractJsonString(body, "transactionId").getOrElse(
          extractJsonString(body, "transaction_id").getOrElse("unknown")
        )
        val reason = extractJsonString(body, "reason").getOrElse(
          extractJsonString(body, "rejectionReason").getOrElse("unknown")
        )
        BankRailsEvent.UkFpsRejected(txId, reason)

      case "uk.faster-payments.return" =>
        val txId  = extractJsonString(body, "transactionId").getOrElse(
          extractJsonString(body, "transaction_id").getOrElse("unknown")
        )
        val code  = extractJsonString(body, "returnCode").getOrElse(
          extractJsonString(body, "return_code").getOrElse("unknown")
        )
        val desc  = extractJsonString(body, "description").getOrElse("")
        BankRailsEvent.UkFpsReturned(txId, code, desc)

      case other =>
        throw new IllegalArgumentException(s"Unknown UK FPS event type: $other")

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[ukfps] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private[ukfps] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
