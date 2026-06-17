package scalascript.payments.square

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.util.Base64
import ujson.*

/** Square webhook receiver.
 *  Verification: HMAC-SHA1 over notification_url + raw_body.
 *  Header: x-square-hmacsha1-signature (base64-encoded HMAC).
 *  Square includes the notification URL in the signed payload to bind the
 *  signature to the specific endpoint — prevents relay attacks. */
class SquareWebhookReceiver(
    signatureKey: String,
    notificationUrl: String = "",
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    verifyAndParse(req)

  private def verifyAndParse(req: WebhookRequest): Either[WebhookError, PaymentEvent] =
    val sigOpt = req.headers.get("x-square-hmacsha1-signature")
      .orElse(req.headers.get("X-Square-HmacSha1-Signature"))
    if sigOpt.isEmpty then return Left(MissingHeader("x-square-hmacsha1-signature"))

    if signatureKey.nonEmpty then
      val sig         = sigOpt.get
      val computedSig = computeHmac(notificationUrl, req.rawBody)
      if !constantTimeEquals(computedSig, sig) then
        return Left(InvalidSignature("Square HMAC-SHA1 signature mismatch"))

    scala.util.Try(ujson.read(req.rawBody)) match
      case scala.util.Failure(e)     => Left(MalformedPayload(s"JSON parse error: ${e.getMessage}"))
      case scala.util.Success(json)  =>
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

  private def parseEvent(j: ujson.Value): PaymentEvent =
    val eventType = j.obj.get("type").map(_.str).getOrElse("")
    val data      = j.obj.get("data").getOrElse(ujson.Obj())
    val obj       = data.obj.get("object").getOrElse(data)
    eventType match
      case "payment.completed" | "payment.updated" =>
        val payment = obj.obj.get("payment").getOrElse(obj)
        val id      = IntentId(payment.obj.get("id").map(_.str).getOrElse(""))
        val amount  = payment.obj.get("amount_money").map { a =>
          Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
        }.getOrElse(Money.zero(Currency.USD))
        val status = payment.obj.get("status").map(_.str).getOrElse("")
        if status == "COMPLETED" then
          PaymentEvent.PaymentIntentSucceeded(
            PaymentIntent.Succeeded(id, amount, Charge(ChargeId(id.value), id, amount, paid = true))
          )
        else
          PaymentEvent.PaymentIntentFailed(
            PaymentIntent.Failed(id, PermanentProviderError("FAILED", status), retryable = false),
            PermanentProviderError("FAILED", status),
          )
      case "payment.failed" =>
        val payment = obj.obj.get("payment").getOrElse(obj)
        val id      = IntentId(payment.obj.get("id").map(_.str).getOrElse(""))
        val reason  = payment.obj.get("delay_action").map(_.str).getOrElse("payment failed")
        PaymentEvent.PaymentIntentFailed(
          PaymentIntent.Failed(id, PermanentProviderError("FAILED", reason), retryable = false),
          PermanentProviderError("FAILED", reason),
        )
      case "refund.completed" | "refund.updated" =>
        val refund   = obj.obj.get("refund").getOrElse(obj)
        val refundId = refund.obj.get("id").map(_.str).getOrElse("")
        val intentId = refund.obj.get("payment_id").map(_.str).getOrElse("")
        val amount   = refund.obj.get("amount_money").map { a =>
          Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
        }.getOrElse(Money.zero(Currency.USD))
        PaymentEvent.ChargeRefunded(Refund(
          id       = RefundId(refundId),
          intentId = IntentId(intentId),
          amount   = amount,
          reason   = RefundReason.RequestedByCustomer,
          status   = RefundStatus.Succeeded,
        ))
      case "dispute.state.changed" | "dispute.created" =>
        val dispute   = obj.obj.get("dispute").getOrElse(obj)
        val disputeId = dispute.obj.get("id").map(_.str).getOrElse("")
        val intentId  = dispute.obj.get("payment_id").map(_.str).getOrElse("")
        val amount    = dispute.obj.get("amount_money").map { a =>
          Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
        }.getOrElse(Money.zero(Currency.USD))
        val dueDate   = dispute.obj.get("due_at").map(v => Instant.parse(v.str))
          .getOrElse(Instant.now().plusSeconds(604800))
        val state = dispute.obj.get("state").map(_.str).getOrElse("")
        val d = Dispute(
          id       = DisputeId(disputeId),
          intentId = IntentId(intentId),
          amount   = amount,
          reason   = DisputeReason.General,
          status   = stateToStatus(state),
          dueDate  = dueDate,
          evidence = None,
        )
        state match
          case "EVIDENCE_REQUIRED" => PaymentEvent.DisputeEvidenceRequired(d)
          case "WON" | "LOST"      => PaymentEvent.DisputeUpdated(d)
          case _                   => PaymentEvent.DisputeCreated(d)
      case "subscription.updated" =>
        val sub = obj.obj.get("subscription").getOrElse(obj)
        PaymentEvent.SubscriptionUpdated(Subscription(
          id               = SubscriptionId(sub.obj.get("id").map(_.str).getOrElse("")),
          customerId       = CustomerId(sub.obj.get("customer_id").map(_.str).getOrElse("")),
          planId           = PlanId(sub.obj.get("plan_variation_id").map(_.str).getOrElse("")),
          status           = SubscriptionStatus.Active,
          currentPeriodEnd = Instant.now().plusSeconds(2592000),
          cancelAtPeriodEnd = false,
          trialEnd         = None,
        ))
      case "subscription.canceled" | "subscription.deactivated" =>
        val sub = obj.obj.get("subscription").getOrElse(obj)
        PaymentEvent.SubscriptionCanceled(Subscription(
          id               = SubscriptionId(sub.obj.get("id").map(_.str).getOrElse("")),
          customerId       = CustomerId(sub.obj.get("customer_id").map(_.str).getOrElse("")),
          planId           = PlanId(sub.obj.get("plan_variation_id").map(_.str).getOrElse("")),
          status           = SubscriptionStatus.Canceled,
          currentPeriodEnd = Instant.now(),
          cancelAtPeriodEnd = false,
          trialEnd         = None,
        ))
      case other =>
        val id = data.obj.get("id").map(_.str).getOrElse("")
        PaymentEvent.ManualReviewRequired(IntentId(id), s"unhandled square event: $other")

  private def stateToStatus(state: String): DisputeStatus = state match
    case "WON"                => DisputeStatus.Won
    case "LOST"               => DisputeStatus.Lost
    case "ACCEPTED"           => DisputeStatus.Lost
    case "EVIDENCE_REQUIRED"  => DisputeStatus.NeedsResponse
    case _                    => DisputeStatus.NeedsResponse

  private def computeHmac(url: String, body: String): String =
    val data    = url + body
    val mac     = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(signatureKey.getBytes("UTF-8"), "HmacSHA1"))
    Base64.getEncoder.encodeToString(mac.doFinal(data.getBytes("UTF-8")))

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
