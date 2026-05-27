package scalascript.payments.stripe

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import ujson.*

/** Stripe webhook receiver: verifies `Stripe-Signature` header (HMAC-SHA256 + timestamp)
 *  and parses the raw body into typed `PaymentEvent` values. */
class StripeWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    parseAndVerify(req, secret)

  private def parseAndVerify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    val headerOpt = req.headers.get("Stripe-Signature").orElse(req.headers.get("stripe-signature"))
    if headerOpt.isEmpty then return Left(MissingHeader("Stripe-Signature"))
    val header = headerOpt.get

    // Parse t=...,v1=...,...
    val parts = header.split(",").map { part =>
      val eq = part.indexOf('=')
      if eq < 0 then ("", part) else (part.take(eq), part.drop(eq + 1))
    }.toMap

    val timestampOpt = parts.get("t").flatMap(s => scala.util.Try(s.toLong).toOption)
    if timestampOpt.isEmpty then return Left(InvalidSignature("Missing timestamp in Stripe-Signature"))
    val timestamp = timestampOpt.get

    val v1sigOpt = parts.get("v1")
    if v1sigOpt.isEmpty then return Left(InvalidSignature("Missing v1 signature in Stripe-Signature"))
    val v1sig = v1sigOpt.get

    // Timestamp check
    val delta = Math.abs(Instant.now().getEpochSecond - timestamp)
    if delta > config.timestampToleranceSeconds then
      return Left(TimestampOutOfRange(delta, config.timestampToleranceSeconds))

    // HMAC-SHA256 verify
    val payload  = s"$timestamp.${req.rawBody}"
    val expected = hmacSha256(secret, payload)
    if !constantTimeEquals(expected, v1sig) then
      return Left(InvalidSignature("Stripe-Signature HMAC mismatch"))

    // Parse the event
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

  // ── Event parsing ──────────────────────────────────────────────────────────

  private def parseEvent(json: ujson.Value): PaymentEvent =
    val eventType = json("type").str
    val data      = json("data")("object")
    eventType match
      case "payment_intent.succeeded" =>
        PaymentEvent.PaymentIntentSucceeded(parseIntent(data))
      case "payment_intent.payment_failed" =>
        val err = PermanentProviderError(
          data.obj.get("last_payment_error").flatMap(_.obj.get("code")).map(_.str).getOrElse("failed"),
          data.obj.get("last_payment_error").flatMap(_.obj.get("message")).map(_.str).getOrElse(""),
        )
        PaymentEvent.PaymentIntentFailed(parseIntent(data), err)
      case "charge.refunded" =>
        val refund = data("refunds")("data").arr.headOption
          .map(r => parseRefund(r, IntentId(data("payment_intent").str),
                                parseMoney(data)))
          .getOrElse(parseRefundObj(data))
        PaymentEvent.ChargeRefunded(refund)
      case "invoice.payment_succeeded" =>
        val subId = data.obj.get("subscription").map(v => SubscriptionId(v.str)).getOrElse(SubscriptionId(""))
        PaymentEvent.InvoicePaymentSucceeded(data("id").str, parseMoney(data), subId)
      case "invoice.payment_failed" =>
        val subId = data.obj.get("subscription").map(v => SubscriptionId(v.str)).getOrElse(SubscriptionId(""))
        val attempts = data.obj.get("attempt_count").map(_.num.toInt).getOrElse(0)
        val maxAttempts = 4
        PaymentEvent.InvoicePaymentFailed(data("id").str, subId, maxAttempts - attempts)
      case "customer.subscription.updated" =>
        PaymentEvent.SubscriptionUpdated(parseSubscription(data))
      case "customer.subscription.deleted" =>
        PaymentEvent.SubscriptionCanceled(parseSubscription(data))
      case "charge.dispute.created" =>
        PaymentEvent.DisputeCreated(parseDispute(data))
      case "charge.dispute.funds_reinstated" | "charge.dispute.updated" =>
        if data.obj.get("status").map(_.str).contains("needs_response") then
          PaymentEvent.DisputeEvidenceRequired(parseDispute(data))
        else
          PaymentEvent.DisputeUpdated(parseDispute(data))
      case "review.opened" =>
        val reason = data.obj.get("reason").map(_.str).getOrElse("")
        val piId   = data.obj.get("payment_intent").map(v => IntentId(v.str)).getOrElse(IntentId(""))
        PaymentEvent.ManualReviewRequired(piId, reason)
      case other =>
        // Unknown event type — treat as a generic intent event
        PaymentEvent.ManualReviewRequired(IntentId(""), s"unhandled event type: $other")

  private def parseIntent(j: ujson.Value): PaymentIntent =
    val id     = IntentId(j("id").str)
    val amount = parseMoney(j)
    j("status").str match
      case "succeeded" =>
        val chargeId = j.obj.get("latest_charge").flatMap {
          case ujson.Str(s) => Some(ChargeId(s))
          case _            => None
        }.getOrElse(ChargeId(""))
        PaymentIntent.Succeeded(id, amount, Charge(chargeId, id, amount, paid = true))
      case "canceled"  => PaymentIntent.Canceled(id, CancelReason.Other(""))
      case _           => PaymentIntent.Processing(id, amount)

  private def parseMoney(j: ujson.Value): Money =
    val a = j.obj.get("amount").orElse(j.obj.get("amount_due")).map(_.num.toLong).getOrElse(0L)
    val c = j.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD")
    Money(a, Currency(c))

  private def parseRefund(j: ujson.Value, intentId: IntentId, fallbackAmount: Money): Refund =
    val currency = j.obj.get("currency").map(v => Currency(v.str.toUpperCase)).getOrElse(fallbackAmount.currency)
    Refund(
      id       = RefundId(j("id").str),
      intentId = intentId,
      amount   = Money(j("amount").num.toLong, currency),
      reason   = RefundReason.RequestedByCustomer,
      status   = RefundStatus.Succeeded,
    )

  private def parseRefundObj(j: ujson.Value): Refund =
    Refund(
      id       = RefundId(j.obj.get("id").map(_.str).getOrElse("")),
      intentId = IntentId(j.obj.get("payment_intent").map(_.str).getOrElse("")),
      amount   = parseMoney(j),
      reason   = RefundReason.RequestedByCustomer,
      status   = RefundStatus.Succeeded,
    )

  private def parseSubscription(j: ujson.Value): Subscription =
    Subscription(
      id               = SubscriptionId(j("id").str),
      customerId       = CustomerId(j("customer").str),
      planId           = PlanId(""),
      status           = parseSubStatus(j("status").str),
      currentPeriodEnd = java.time.Instant.ofEpochSecond(j("current_period_end").num.toLong),
      cancelAtPeriodEnd = j.obj.get("cancel_at_period_end").exists(_.bool),
      trialEnd         = j.obj.get("trial_end").flatMap {
        case ujson.Num(n) => Some(java.time.Instant.ofEpochSecond(n.toLong))
        case _            => None
      },
    )

  private def parseDispute(j: ujson.Value): Dispute =
    val currency = j.obj.get("currency").map(v => Currency(v.str.toUpperCase)).getOrElse(Currency.USD)
    Dispute(
      id       = DisputeId(j("id").str),
      intentId = IntentId(j.obj.get("payment_intent").map(_.str).getOrElse("")),
      amount   = Money(j("amount").num.toLong, currency),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = java.time.Instant.ofEpochSecond(j.obj.get("evidence_due_by").map(_.num.toLong).getOrElse(0L)),
      evidence = None,
    )

  private def parseSubStatus(s: String): SubscriptionStatus = s match
    case "trialing" => SubscriptionStatus.Trialing
    case "active"   => SubscriptionStatus.Active
    case "past_due" => SubscriptionStatus.PastDue
    case "canceled" => SubscriptionStatus.Canceled
    case "unpaid"   => SubscriptionStatus.Unpaid
    case "paused"   => SubscriptionStatus.Paused
    case _          => SubscriptionStatus.Active

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  private def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
