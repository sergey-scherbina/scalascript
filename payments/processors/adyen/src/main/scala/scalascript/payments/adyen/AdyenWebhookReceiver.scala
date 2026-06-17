package scalascript.payments.adyen

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.util.Base64
import ujson.*

/** Adyen webhook receiver.
 *  Verification: HMAC-SHA256 over sorted notification fields (not raw body).
 *  Signed fields (in order): pspReference | originalReference | merchantAccountCode |
 *                             merchantReference | value | currency | eventCode | success.
 *  Header: X-Adyen-Hmac-Signature (base64-encoded HMAC). */
class AdyenWebhookReceiver(
    hmacKey: String,
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    verifyAndParse(req)

  private def verifyAndParse(req: WebhookRequest): Either[WebhookError, PaymentEvent] =
    val sigOpt = req.headers.get("X-Adyen-Hmac-Signature")
      .orElse(req.headers.get("x-adyen-hmac-signature"))
    if sigOpt.isEmpty then return Left(MissingHeader("X-Adyen-Hmac-Signature"))

    scala.util.Try(ujson.read(req.rawBody)) match
      case scala.util.Failure(e) => return Left(MalformedPayload(s"JSON parse error: ${e.getMessage}"))
      case scala.util.Success(json) =>
        // Adyen sends a NotificationRequest with an array of notificationItems
        val items = json.obj.get("notificationItems").map(_.arr.toList).getOrElse(
          // Or a direct notification object
          List(json)
        )
        if items.isEmpty then return Left(MalformedPayload("Empty notificationItems"))
        val item = items.head
        val notif = item.obj.get("NotificationRequestItem").getOrElse(item)

        if hmacKey.nonEmpty then
          val sig = sigOpt.get
          val notifSig = notif.obj.get("additionalData").flatMap(_.obj.get("hmacSignature"))
            .map(_.str).getOrElse(sig)
          val computedSig = computeHmac(notif)
          if !constantTimeEquals(computedSig, notifSig) then
            return Left(InvalidSignature("Adyen HMAC-SHA256 signature mismatch"))

        scala.util.Try(parseEvent(notif)) match
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
    val eventCode = j.obj.get("eventCode").map(_.str).getOrElse("")
    val pspRef    = j.obj.get("pspReference").map(_.str).getOrElse("")
    val success   = j.obj.get("success").map(_.str).getOrElse("false") == "true"
    val amount    = j.obj.get("amount").map { a =>
      Money(a("value").num.toLong, Currency(a("currency").str.toUpperCase))
    }.getOrElse(Money.zero(Currency.USD))
    eventCode match
      case "AUTHORISATION" if success =>
        PaymentEvent.PaymentIntentSucceeded(
          PaymentIntent.Succeeded(IntentId(pspRef), amount, Charge(ChargeId(pspRef), IntentId(pspRef), amount, paid = true))
        )
      case "AUTHORISATION" =>
        val reason = j.obj.get("reason").map(_.str).getOrElse("")
        PaymentEvent.PaymentIntentFailed(
          PaymentIntent.Failed(IntentId(pspRef), PermanentProviderError("REFUSED", reason), retryable = false),
          PermanentProviderError("REFUSED", reason),
        )
      case "CAPTURE" if success =>
        PaymentEvent.PaymentIntentSucceeded(
          PaymentIntent.Succeeded(IntentId(pspRef), amount, Charge(ChargeId(pspRef), IntentId(pspRef), amount, paid = true))
        )
      case "REFUND" if success =>
        val originalRef = j.obj.get("originalReference").map(_.str).getOrElse("")
        PaymentEvent.ChargeRefunded(Refund(
          id       = RefundId(pspRef),
          intentId = IntentId(originalRef),
          amount   = amount,
          reason   = RefundReason.RequestedByCustomer,
          status   = RefundStatus.Succeeded,
        ))
      case "REFUND" =>
        PaymentEvent.ManualReviewRequired(IntentId(pspRef), s"refund failed: ${j.obj.get("reason").map(_.str).getOrElse("")}")
      case "CHARGEBACK" | "REQUEST_FOR_INFORMATION" | "NOTIFICATION_OF_CHARGEBACK" =>
        val dispute = Dispute(
          id       = DisputeId(pspRef),
          intentId = IntentId(j.obj.get("originalReference").map(_.str).getOrElse("")),
          amount   = amount,
          reason   = DisputeReason.General,
          status   = DisputeStatus.NeedsResponse,
          dueDate  = Instant.now().plusSeconds(604800),
          evidence = None,
        )
        if eventCode == "CHARGEBACK" then PaymentEvent.DisputeCreated(dispute)
        else PaymentEvent.DisputeEvidenceRequired(dispute)
      case "CHARGEBACK_REVERSED" | "SECOND_CHARGEBACK" =>
        PaymentEvent.DisputeUpdated(Dispute(
          id       = DisputeId(pspRef),
          intentId = IntentId(j.obj.get("originalReference").map(_.str).getOrElse("")),
          amount   = amount,
          reason   = DisputeReason.General,
          status   = if eventCode == "CHARGEBACK_REVERSED" then DisputeStatus.Won else DisputeStatus.Lost,
          dueDate  = Instant.now(),
          evidence = None,
        ))
      case "RECURRING_CONTRACT" if success =>
        val subId = j.obj.get("additionalData").flatMap(_.obj.get("recurring.recurringDetailReference"))
          .map(_.str).getOrElse(pspRef)
        PaymentEvent.SubscriptionUpdated(Subscription(
          id               = SubscriptionId(subId),
          customerId       = CustomerId(j.obj.get("shopperReference").map(_.str).getOrElse("")),
          planId           = PlanId(""),
          status           = SubscriptionStatus.Active,
          currentPeriodEnd = Instant.now().plusSeconds(2592000),
          cancelAtPeriodEnd = false,
          trialEnd         = None,
        ))
      case other =>
        PaymentEvent.ManualReviewRequired(IntentId(pspRef), s"unhandled adyen event: $other")

  private def computeHmac(j: ujson.Value): String =
    // Adyen HMAC: signed fields in order, separated by ":"
    // Fields: pspReference, originalReference, merchantAccountCode, merchantReference, value, currency, eventCode, success
    val fields = List(
      j.obj.get("pspReference").map(_.str).getOrElse(""),
      j.obj.get("originalReference").map(_.str).getOrElse(""),
      j.obj.get("merchantAccountCode").map(_.str).getOrElse(""),
      j.obj.get("merchantReference").map(_.str).getOrElse(""),
      j.obj.get("amount").flatMap(_.obj.get("value")).map(_.num.toLong.toString).getOrElse(""),
      j.obj.get("amount").flatMap(_.obj.get("currency")).map(_.str).getOrElse(""),
      j.obj.get("eventCode").map(_.str).getOrElse(""),
      j.obj.get("success").map(_.str).getOrElse(""),
    )
    val data = fields.mkString(":")
    val keyBytes = Base64.getDecoder.decode(hmacKey)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
    Base64.getEncoder.encodeToString(mac.doFinal(data.getBytes("UTF-8")))

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
