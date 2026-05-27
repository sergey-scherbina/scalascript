package scalascript.payments.zengin

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Zengin webhook receiver.
 *
 *  Verification: HMAC-SHA256 over raw body; signature header `X-Zengin-Signature: sha256=<hex>`.
 *  Uses the same raw-body HMAC signing convention as SEPA, ACH, and UK FPS.
 *
 *  Supported event types (JSON `"type"` field):
 *    zengin.transfer.completed  → ZenginSettled(transferId, amount)
 *    zengin.transfer.failed     → ZenginRejected(transferId, reason)
 *
 *  See docs/international-bank-rails.md §7 (Japan Zengin webhook table) for event taxonomy.
 */
class ZenginWebhookReceiver(
    override val config:   WebhookConfig = WebhookConfig(),
    override val seenKeys: SeenKeyStore  = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-Zengin-Signature"

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
      return Left(InvalidSignature("X-Zengin-Signature HMAC-SHA256 mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"Zengin event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.ZenginSettled(transferId, _)  => s"zengin.settled.$transferId"
    case BankRailsEvent.ZenginRejected(transferId, _) => s"zengin.rejected.$transferId"
    case other                                         => s"zengin.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ──────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in Zengin webhook event")
    )
    eventType match
      case "zengin.transfer.completed" =>
        val transferId = extractJsonString(body, "transfer_id").getOrElse("unknown")
        val amount     = extractJsonString(body, "amount").getOrElse("0")
        BankRailsEvent.ZenginSettled(transferId, amount)

      case "zengin.transfer.failed" =>
        val transferId = extractJsonString(body, "transfer_id").getOrElse("unknown")
        val reason     = extractJsonString(body, "reason").getOrElse("unknown reason")
        BankRailsEvent.ZenginRejected(transferId, reason)

      case other =>
        throw new IllegalArgumentException(s"Unknown Zengin event type: $other")

  // ── Minimal JSON field extraction ────────────────────────────────────────

  private[zengin] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ───────────────────────────────────────────────────────

  private[zengin] def hmacSha256Hex(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
