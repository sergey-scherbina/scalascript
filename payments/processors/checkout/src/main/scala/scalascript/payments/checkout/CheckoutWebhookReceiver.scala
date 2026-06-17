package scalascript.payments.checkout

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import ujson.*

/** Checkout.com webhook receiver.
 *  Verification: HMAC-SHA256 over raw body, compared against `Cko-Signature` header.
 *  The Cko-Signature is hex-encoded (not base64). */
class CheckoutWebhookReceiver(
    secret: String,
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  def verify(req: WebhookRequest, signingSecret: String): Either[WebhookError, PaymentEvent] =
    verifyAndParse(req)

  private def verifyAndParse(req: WebhookRequest): Either[WebhookError, PaymentEvent] =
    val sigOpt = req.headers.get("Cko-Signature")
      .orElse(req.headers.get("cko-signature"))
    if sigOpt.isEmpty then return Left(MissingHeader("Cko-Signature"))

    if secret.nonEmpty then
      val expected = hmacSha256Hex(secret, req.rawBody)
      if !constantTimeEquals(expected, sigOpt.get) then
        return Left(InvalidSignature("Checkout.com HMAC-SHA256 signature mismatch"))

    scala.util.Try(ujson.read(req.rawBody)) match
      case scala.util.Failure(e)    => Left(MalformedPayload(s"JSON parse error: ${e.getMessage}"))
      case scala.util.Success(json) =>
        scala.util.Try(parseEvent(json)) match
          case scala.util.Failure(e)     => Left(MalformedPayload(s"Event parse error: ${e.getMessage}"))
          case scala.util.Success(event) => Right(event)

  def idempotencyKey(event: PaymentEvent): String = event match
    case PaymentEvent.PaymentIntentSucceeded(i)            => s"pi.succeeded.${i.id.value}"
    case PaymentEvent.PaymentIntentFailed(i, _)            => s"pi.failed.${i.id.value}"
    case PaymentEvent.ChargeRefunded(r)                    => s"refund.${r.id.value}"
    case PaymentEvent.InvoicePaymentSucceeded(inv, _, _)   => s"inv.paid.$inv"
    case PaymentEvent.InvoicePaymentFailed(inv, _, _)      => s"inv.failed.$inv"
    case PaymentEvent.SubscriptionUpdated(s)               => s"sub.updated.${s.id.value}"
    case PaymentEvent.SubscriptionCanceled(s)              => s"sub.canceled.${s.id.value}"
    case PaymentEvent.DisputeCreated(d)                    => s"dispute.created.${d.id.value}"
    case PaymentEvent.DisputeEvidenceRequired(d)           => s"dispute.evidence.${d.id.value}"
    case PaymentEvent.DisputeUpdated(d)                    => s"dispute.updated.${d.id.value}"
    case PaymentEvent.ManualReviewRequired(id, _)          => s"review.${id.value}"

  private def parseEvent(json: ujson.Value): PaymentEvent =
    val eventType = json.obj.get("type").map(_.str).getOrElse("")
    val data      = json.obj.get("data").getOrElse(ujson.Obj())
    val paymentId = data.obj.get("id").map(_.str).getOrElse("")
    val amount    = data.obj.get("amount").map { a =>
      Money(a.num.toLong, Currency(data.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD")))
    }.getOrElse(Money.zero(Currency.USD))
    eventType match
      case "payment_approved" | "payment_captured" =>
        PaymentEvent.PaymentIntentSucceeded(
          PaymentIntent.Succeeded(IntentId(paymentId), amount,
            Charge(ChargeId(paymentId), IntentId(paymentId), amount, paid = true))
        )
      case "payment_declined" | "payment_expired" | "payment_canceled" =>
        val reason = data.obj.get("response_summary").map(_.str).getOrElse(eventType)
        PaymentEvent.PaymentIntentFailed(
          PaymentIntent.Failed(IntentId(paymentId), PermanentProviderError(eventType, reason), retryable = false),
          PermanentProviderError(eventType, reason),
        )
      case "payment_refunded" =>
        val refundId = data.obj.get("action_id").map(_.str).getOrElse("")
        PaymentEvent.ChargeRefunded(Refund(
          id       = RefundId(refundId),
          intentId = IntentId(paymentId),
          amount   = amount,
          reason   = RefundReason.RequestedByCustomer,
          status   = RefundStatus.Succeeded,
        ))
      case "dispute_created" | "dispute_evidence_required" =>
        val dispute = Dispute(
          id       = DisputeId(data.obj.get("id").map(_.str).getOrElse("")),
          intentId = IntentId(paymentId),
          amount   = amount,
          reason   = DisputeReason.General,
          status   = DisputeStatus.NeedsResponse,
          dueDate  = data.obj.get("evidence_required_by").map(v => Instant.parse(v.str))
            .getOrElse(Instant.now().plusSeconds(604800)),
          evidence = None,
        )
        if eventType == "dispute_created" then PaymentEvent.DisputeCreated(dispute)
        else PaymentEvent.DisputeEvidenceRequired(dispute)
      case "dispute_resolved" | "dispute_won" | "dispute_lost" =>
        val status = if eventType == "dispute_won" then DisputeStatus.Won else DisputeStatus.Lost
        PaymentEvent.DisputeUpdated(Dispute(
          id       = DisputeId(data.obj.get("id").map(_.str).getOrElse("")),
          intentId = IntentId(paymentId),
          amount   = amount,
          reason   = DisputeReason.General,
          status   = status,
          dueDate  = Instant.now(),
          evidence = None,
        ))
      case other =>
        PaymentEvent.ManualReviewRequired(IntentId(paymentId), s"unhandled checkout event: $other")

  private def hmacSha256Hex(key: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
