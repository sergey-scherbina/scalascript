package scalascript.payments.braintree

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.util.Base64

/** Braintree webhook receiver.
 *  Verification: HMAC-SHA1 over bt-payload using private key as the HMAC secret.
 *  Headers: bt-signature (public_key&hmac), bt-payload (base64 XML body). */
class BraintreeWebhookReceiver(
    privateKey: String,
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, PaymentEvent] =
    verifyAndParse(req)

  private def verifyAndParse(req: WebhookRequest): Either[WebhookError, PaymentEvent] =
    val sigHeader     = req.headers.get("bt-signature").orElse(req.headers.get("Bt-Signature"))
    val payloadHeader = req.headers.get("bt-payload").orElse(req.headers.get("Bt-Payload"))

    if sigHeader.isEmpty   then return Left(MissingHeader("bt-signature"))
    if payloadHeader.isEmpty then return Left(MissingHeader("bt-payload"))

    val btSig     = sigHeader.get
    val btPayload = payloadHeader.get

    // bt-signature format: "public_key&hmac_hex"
    val parts = btSig.split("&", 2)
    if parts.length != 2 then return Left(InvalidSignature("Malformed bt-signature"))
    val hmacHex = parts(1)

    val expected = hmacSha1(privateKey, btPayload)
    if !constantTimeEquals(expected, hmacHex) then
      return Left(InvalidSignature("Braintree HMAC-SHA1 signature mismatch"))

    val xmlBody = scala.util.Try {
      new String(Base64.getDecoder.decode(btPayload.trim.replace("\n", "")), "UTF-8")
    } match
      case scala.util.Failure(e) => return Left(MalformedPayload(s"Base64 decode error: ${e.getMessage}"))
      case scala.util.Success(s) => s

    scala.util.Try(parseEvent(xmlBody)) match
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

  private def parseEvent(xml: String): PaymentEvent =
    val kind = extractXmlValue(xml, "kind")
    kind match
      case "check" =>
        PaymentEvent.ManualReviewRequired(IntentId(""), "braintree webhook check")
      case "transaction_settled" | "transaction_settlement_confirmed" =>
        val txId   = extractXmlValue(xml, "id")
        val amount = BigDecimal(extractXmlValue(xml, "amount", "0"))
        val currency = Currency(extractXmlValue(xml, "currency-iso-code", "USD").toUpperCase)
        val money  = Money(amount, currency)
        PaymentEvent.PaymentIntentSucceeded(
          PaymentIntent.Succeeded(IntentId(txId), money, Charge(ChargeId(txId), IntentId(txId), money, paid = true))
        )
      case "transaction_settlement_declined" | "transaction_declined" =>
        val txId = extractXmlValue(xml, "id")
        PaymentEvent.PaymentIntentFailed(
          PaymentIntent.Failed(IntentId(txId), PermanentProviderError(kind, ""), retryable = false),
          PermanentProviderError(kind, ""),
        )
      case "subscription_charged_successfully" =>
        val subId  = extractXmlValue(xml, "id", "")
        val planId = extractXmlValue(xml, "plan-id", "")
        PaymentEvent.SubscriptionUpdated(Subscription(
          id               = SubscriptionId(subId),
          customerId       = CustomerId(""),
          planId           = PlanId(planId),
          status           = SubscriptionStatus.Active,
          currentPeriodEnd = Instant.now().plusSeconds(2592000),
          cancelAtPeriodEnd = false,
          trialEnd         = None,
        ))
      case "subscription_charged_unsuccessfully" =>
        val subId  = extractXmlValue(xml, "id", "")
        PaymentEvent.InvoicePaymentFailed(subId, SubscriptionId(subId), 3)
      case "subscription_canceled" =>
        val subId  = extractXmlValue(xml, "id", "")
        val sub    = Subscription(
          id               = SubscriptionId(subId),
          customerId       = CustomerId(""),
          planId           = PlanId(""),
          status           = SubscriptionStatus.Canceled,
          currentPeriodEnd = Instant.now(),
          cancelAtPeriodEnd = false,
          trialEnd         = None,
        )
        PaymentEvent.SubscriptionCanceled(sub)
      case "dispute_opened" | "dispute_under_review" =>
        val disputeId = extractXmlValue(xml, "id", "")
        val txId      = extractXmlValue(xml, "transaction-id", "")
        val amount    = BigDecimal(extractXmlValue(xml, "amount", "0"))
        val dispute   = Dispute(
          id       = DisputeId(disputeId),
          intentId = IntentId(txId),
          amount   = Money(amount, Currency.USD),
          reason   = DisputeReason.General,
          status   = DisputeStatus.NeedsResponse,
          dueDate  = Instant.now().plusSeconds(604800),
          evidence = None,
        )
        if kind == "dispute_opened" then PaymentEvent.DisputeCreated(dispute)
        else PaymentEvent.DisputeEvidenceRequired(dispute)
      case "dispute_won" | "dispute_lost" =>
        val disputeId = extractXmlValue(xml, "id", "")
        val txId      = extractXmlValue(xml, "transaction-id", "")
        val amount    = BigDecimal(extractXmlValue(xml, "amount", "0"))
        PaymentEvent.DisputeUpdated(Dispute(
          id       = DisputeId(disputeId),
          intentId = IntentId(txId),
          amount   = Money(amount, Currency.USD),
          reason   = DisputeReason.General,
          status   = if kind == "dispute_won" then DisputeStatus.Won else DisputeStatus.Lost,
          dueDate  = Instant.now(),
          evidence = None,
        ))
      case "refund" | "refunded_transaction" =>
        val refundId = extractXmlValue(xml, "id", "")
        val txId     = extractXmlValue(xml, "refunded-transaction-id", extractXmlValue(xml, "transaction-id", ""))
        val amount   = BigDecimal(extractXmlValue(xml, "amount", "0"))
        PaymentEvent.ChargeRefunded(Refund(
          id       = RefundId(refundId),
          intentId = IntentId(txId),
          amount   = Money(amount, Currency.USD),
          reason   = RefundReason.RequestedByCustomer,
          status   = RefundStatus.Succeeded,
        ))
      case other =>
        PaymentEvent.ManualReviewRequired(IntentId(""), s"unhandled braintree event: $other")

  private def hmacSha1(key: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0

  private def extractXmlValue(xml: String, tag: String, default: String = ""): String =
    val start = xml.indexOf(s"<$tag>")
    val end   = xml.indexOf(s"</$tag>")
    if start < 0 || end < 0 then default
    else xml.substring(start + tag.length + 2, end)
