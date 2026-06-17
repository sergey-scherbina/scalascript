package scalascript.payments.mock

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.{WebhookReceiver, WebhookRequest, WebhookConfig, WebhookError, InMemorySeenKeyStore, SeenKeyStore, MalformedPayload}
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** In-memory webhook receiver for testing.
 *  Skips all HMAC verification — any request body is accepted.
 *  Exposes `recorded` for test assertions. */
class MockWebhookReceiver(
    override val config:   WebhookConfig = WebhookConfig(),
    override val seenKeys: SeenKeyStore  = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  private val _recorded = new CopyOnWriteArrayList[PaymentEvent]()

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    if req.rawBody.trim.isEmpty then
      Left(MalformedPayload("Empty body"))
    else
      scala.util.Try(parseEvent(req.rawBody)) match
        case scala.util.Success(ev) =>
          _recorded.add(ev)
          Right(ev)
        case scala.util.Failure(e) =>
          Left(MalformedPayload(s"Cannot parse mock event: ${e.getMessage}"))

  def idempotencyKey(event: PaymentEvent): String = event match
    case PaymentEvent.PaymentIntentSucceeded(i)          => s"pi.succeeded.${i.id.value}"
    case PaymentEvent.PaymentIntentFailed(i, _)          => s"pi.failed.${i.id.value}"
    case PaymentEvent.ChargeRefunded(r)                  => s"refund.${r.id.value}"
    case PaymentEvent.InvoicePaymentSucceeded(inv, _, _) => s"inv.paid.$inv"
    case PaymentEvent.InvoicePaymentFailed(inv, _, _)    => s"inv.failed.$inv"
    case PaymentEvent.SubscriptionUpdated(s)             => s"sub.updated.${s.id.value}"
    case PaymentEvent.SubscriptionCanceled(s)            => s"sub.canceled.${s.id.value}"
    case PaymentEvent.DisputeCreated(d)                  => s"dispute.created.${d.id.value}"
    case PaymentEvent.DisputeEvidenceRequired(d)         => s"dispute.evidence.${d.id.value}"
    case PaymentEvent.DisputeUpdated(d)                  => s"dispute.updated.${d.id.value}"
    case PaymentEvent.ManualReviewRequired(id, _)        => s"review.${id.value}"

  def recorded: List[PaymentEvent] = _recorded.asScala.toList

  def reset(): Unit = _recorded.clear()

  // ── Minimal event factory for tests ───────────────────────────────────────

  private def parseEvent(body: String): PaymentEvent =
    import ujson.*
    val json      = ujson.read(body)
    val eventType = json("type").str
    eventType match
      case "payment_intent.succeeded" =>
        val id     = IntentId(json.obj.get("id").map(_.str).getOrElse("mock_pi"))
        val amount = parseMoneyOpt(json).getOrElse(Money.zero(Currency.USD))
        val charge = Charge(ChargeId("mock_ch"), id, amount, paid = true)
        PaymentEvent.PaymentIntentSucceeded(PaymentIntent.Succeeded(id, amount, charge))

      case "payment_intent.payment_failed" =>
        val id  = IntentId(json.obj.get("id").map(_.str).getOrElse("mock_pi"))
        val err = PermanentProviderError("mock_failure", "mock payment failed")
        val pi  = PaymentIntent.Failed(id, err, retryable = false)
        PaymentEvent.PaymentIntentFailed(pi, err)

      case "charge.refunded" =>
        val id       = RefundId(json.obj.get("refund_id").map(_.str).getOrElse("mock_re"))
        val intentId = IntentId(json.obj.get("intent_id").map(_.str).getOrElse("mock_pi"))
        val amount   = parseMoneyOpt(json).getOrElse(Money.zero(Currency.USD))
        PaymentEvent.ChargeRefunded(Refund(id, intentId, amount, RefundReason.RequestedByCustomer, RefundStatus.Succeeded))

      case "customer.subscription.updated" =>
        PaymentEvent.SubscriptionUpdated(parseSubscription(json))

      case "customer.subscription.deleted" =>
        PaymentEvent.SubscriptionCanceled(parseSubscription(json))

      case "charge.dispute.created" =>
        PaymentEvent.DisputeCreated(parseDispute(json))

      case other =>
        val id = IntentId(json.obj.get("id").map(_.str).getOrElse(""))
        PaymentEvent.ManualReviewRequired(id, s"mock event: $other")

  private def parseMoneyOpt(j: ujson.Value): Option[Money] =
    j.obj.get("amount").map(v => Money(v.num.toLong, Currency.USD))

  private def parseSubscription(j: ujson.Value): Subscription =
    Subscription(
      id               = SubscriptionId(j.obj.get("id").map(_.str).getOrElse("mock_sub")),
      customerId       = CustomerId(j.obj.get("customer").map(_.str).getOrElse("")),
      planId           = PlanId(j.obj.get("plan_id").map(_.str).getOrElse("")),
      status           = SubscriptionStatus.Active,
      currentPeriodEnd = Instant.now().plusSeconds(2592000),
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )

  private def parseDispute(j: ujson.Value): Dispute =
    Dispute(
      id       = DisputeId(j.obj.get("id").map(_.str).getOrElse("mock_dp")),
      intentId = IntentId(j.obj.get("payment_intent").map(_.str).getOrElse("")),
      amount   = parseMoneyOpt(j).getOrElse(Money.zero(Currency.USD)),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = Instant.now().plusSeconds(604800),
      evidence = None,
    )
