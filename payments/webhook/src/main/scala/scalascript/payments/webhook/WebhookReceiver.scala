package scalascript.payments.webhook

import java.time.Duration

case class WebhookRequest(
  headers: Map[String, String],
  rawBody: String,
)

case class WebhookResponse(status: Int, body: String = "")

object WebhookResponse:
  val ok:                 WebhookResponse = WebhookResponse(200)
  val badRequest:         WebhookResponse = WebhookResponse(400, "Bad Request")
  val internalError:      WebhookResponse = WebhookResponse(500, "Internal Server Error")

case class WebhookConfig(
  timestampToleranceSeconds: Long   = 300,
  seenKeyExpiry:             Duration = Duration.ofDays(30),
)

sealed class WebhookError(msg: String) extends RuntimeException(msg)
case class InvalidSignature(message: String) extends WebhookError(message)
case class TimestampOutOfRange(delta: Long, toleranceSeconds: Long)
    extends WebhookError(s"Timestamp delta ${delta}s exceeds tolerance ${toleranceSeconds}s")
case class MissingHeader(name: String) extends WebhookError(s"Missing webhook header: $name")
case class MalformedPayload(message: String) extends WebhookError(message)

/** PSP-agnostic webhook receiver SPI.
 *  Each PSP adapter implements this trait for its event type E. */
trait WebhookReceiver[E]:
  def config: WebhookConfig = WebhookConfig()
  def seenKeys: SeenKeyStore = InMemorySeenKeyStore()

  /** Verify the PSP signature + timestamp and parse the event payload. */
  def verify(req: WebhookRequest, secret: String): Either[WebhookError, E]

  /** Extract the idempotency key from a verified event (used for dedup). */
  def idempotencyKey(event: E): String

  /** Verify, deduplicate, dispatch, and return an HTTP response.
   *  Follows PSP best practice: ack quickly, process idempotently. */
  final def handle(req: WebhookRequest, secret: String)(
      handler: PartialFunction[E, Unit]
  ): WebhookResponse =
    verify(req, secret) match
      case Left(err: TimestampOutOfRange) =>
        WebhookResponse(400, s"Timestamp out of range: ${err.getMessage}")
      case Left(err) =>
        WebhookResponse(400, err.getMessage)
      case Right(event) =>
        val key = idempotencyKey(event)
        if seenKeys.wasSeen(key) then WebhookResponse.ok
        else
          try
            if handler.isDefinedAt(event) then handler(event)
            seenKeys.markSeen(key, config.seenKeyExpiry)
            WebhookResponse.ok
          catch
            case _: Exception => WebhookResponse.internalError
