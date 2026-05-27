package scalascript.payments.paypal

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.security.Signature
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.{Duration, Instant}
import java.util.zip.CRC32
import ujson.*

/** PayPal webhook receiver.
 *  Verification: RSA-SHA256 over `transmission_id|timestamp|webhook_id|body_crc32`.
 *  Headers: PAYPAL-TRANSMISSION-SIG, PAYPAL-TRANSMISSION-ID, PAYPAL-TRANSMISSION-TIME,
 *           PAYPAL-CERT-URL. */
class PayPalWebhookReceiver(
    webhookId: String,
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[PaymentEvent]:

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def verify(req: WebhookRequest, signingSecret: String): Either[WebhookError, PaymentEvent] =
    verifyAndParse(req)

  private def verifyAndParse(req: WebhookRequest): Either[WebhookError, PaymentEvent] =
    val sigOpt   = header(req, "PAYPAL-TRANSMISSION-SIG")
    val txIdOpt  = header(req, "PAYPAL-TRANSMISSION-ID")
    val timeOpt  = header(req, "PAYPAL-TRANSMISSION-TIME")
    val certOpt  = header(req, "PAYPAL-CERT-URL")

    if sigOpt.isEmpty   then return Left(MissingHeader("PAYPAL-TRANSMISSION-SIG"))
    if txIdOpt.isEmpty  then return Left(MissingHeader("PAYPAL-TRANSMISSION-ID"))
    if timeOpt.isEmpty  then return Left(MissingHeader("PAYPAL-TRANSMISSION-TIME"))
    if certOpt.isEmpty  then return Left(MissingHeader("PAYPAL-CERT-URL"))

    val sig    = sigOpt.get
    val txId   = txIdOpt.get
    val time   = timeOpt.get
    val certUrl = certOpt.get

    // Timestamp tolerance
    scala.util.Try(Instant.parse(time)) match
      case scala.util.Failure(_) => return Left(InvalidSignature("Invalid PAYPAL-TRANSMISSION-TIME"))
      case scala.util.Success(ts) =>
        val delta = Math.abs(Instant.now().getEpochSecond - ts.getEpochSecond)
        if delta > config.timestampToleranceSeconds then
          return Left(TimestampOutOfRange(delta, config.timestampToleranceSeconds))

    // Validate cert URL is from paypal.com
    if !certUrl.startsWith("https://api.paypal.com") && !certUrl.startsWith("https://api-m.paypal.com") &&
       !certUrl.startsWith("https://api.sandbox.paypal.com") && !certUrl.startsWith("https://api-m.sandbox.paypal.com") then
      return Left(InvalidSignature(s"Untrusted cert URL: $certUrl"))

    // Fetch cert and verify RSA-SHA256
    val certBytes = scala.util.Try {
      val certReq = JHttpRequest.newBuilder(URI.create(certUrl)).GET().timeout(Duration.ofSeconds(10)).build()
      http.send(certReq, BodyHandlers.ofByteArray()).body()
    } match
      case scala.util.Failure(ex) => return Left(MalformedPayload(s"Failed to fetch cert: ${ex.getMessage}"))
      case scala.util.Success(b)  => b

    val cert = scala.util.Try {
      CertificateFactory.getInstance("X.509")
        .generateCertificate(java.io.ByteArrayInputStream(certBytes))
        .asInstanceOf[X509Certificate]
    } match
      case scala.util.Failure(e) => return Left(MalformedPayload(s"Invalid cert: ${e.getMessage}"))
      case scala.util.Success(c) => c

    // crc32 of body
    val crc = CRC32()
    crc.update(req.rawBody.getBytes("UTF-8"))
    val bodyCrc32 = crc.getValue

    val message = s"$txId|$time|$webhookId|$bodyCrc32"
    val sigBytes = java.util.Base64.getDecoder.decode(sig)
    val valid = scala.util.Try {
      val verifier = Signature.getInstance("SHA256withRSA")
      verifier.initVerify(cert.getPublicKey)
      verifier.update(message.getBytes("UTF-8"))
      verifier.verify(sigBytes)
    }.getOrElse(false)

    if !valid then return Left(InvalidSignature("PayPal RSA-SHA256 signature mismatch"))

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
    val eventType = json("event_type").str
    val resource  = json("resource")
    eventType match
      case "PAYMENT.CAPTURE.COMPLETED" =>
        val id     = IntentId(resource.obj.get("supplementary_data").flatMap(_.obj.get("related_ids"))
          .flatMap(_.obj.get("order_id")).map(_.str).getOrElse(resource("id").str))
        val amount = parseAmount(resource)
        PaymentEvent.PaymentIntentSucceeded(
          PaymentIntent.Succeeded(id, amount, Charge(ChargeId(resource("id").str), id, amount, paid = true))
        )
      case "PAYMENT.CAPTURE.DENIED" | "PAYMENT.CAPTURE.DECLINED" =>
        val id = IntentId(resource("id").str)
        PaymentEvent.PaymentIntentFailed(
          PaymentIntent.Failed(id, PermanentProviderError(eventType, ""), retryable = false),
          PermanentProviderError(eventType, ""),
        )
      case "PAYMENT.CAPTURE.REFUNDED" =>
        val currency = resource.obj.get("amount").flatMap(_.obj.get("currency_code"))
          .map(v => Currency(v.str.toUpperCase)).getOrElse(Currency.USD)
        val amount = resource.obj.get("amount").flatMap(_.obj.get("value"))
          .map(v => Money(BigDecimal(v.str), currency)).getOrElse(Money.zero(currency))
        PaymentEvent.ChargeRefunded(Refund(
          id       = RefundId(resource("id").str),
          intentId = IntentId(resource.obj.get("links").flatMap(_.arr.find(l => l("rel").str == "up"))
            .flatMap(_.obj.get("href")).map(_.str.split("/").lastOption.getOrElse("")).getOrElse("")),
          amount   = amount,
          reason   = RefundReason.RequestedByCustomer,
          status   = RefundStatus.Succeeded,
        ))
      case "BILLING.SUBSCRIPTION.ACTIVATED" | "BILLING.SUBSCRIPTION.UPDATED" =>
        PaymentEvent.SubscriptionUpdated(parseSubscriptionResource(resource))
      case "BILLING.SUBSCRIPTION.CANCELLED" | "BILLING.SUBSCRIPTION.EXPIRED" =>
        PaymentEvent.SubscriptionCanceled(parseSubscriptionResource(resource))
      case "CUSTOMER.DISPUTE.CREATED" =>
        PaymentEvent.DisputeCreated(parseDisputeResource(resource))
      case "CUSTOMER.DISPUTE.UPDATED" =>
        PaymentEvent.DisputeEvidenceRequired(parseDisputeResource(resource))
      case "CUSTOMER.DISPUTE.RESOLVED" =>
        PaymentEvent.DisputeUpdated(parseDisputeResource(resource))
      case other =>
        PaymentEvent.ManualReviewRequired(IntentId(""), s"unhandled event type: $other")

  private def parseAmount(j: ujson.Value): Money =
    val a = j.obj.get("amount").getOrElse(j)
    val code = a.obj.get("currency_code").map(_.str.toUpperCase).getOrElse("USD")
    val value = a.obj.get("value").map(v => BigDecimal(v.str)).getOrElse(BigDecimal(0))
    Money(value, Currency(code))

  private def parseSubscriptionResource(j: ujson.Value): Subscription =
    val status = j.obj.get("status").map(_.str).getOrElse("ACTIVE") match
      case "ACTIVE"    => SubscriptionStatus.Active
      case "SUSPENDED" => SubscriptionStatus.PastDue
      case "CANCELLED" => SubscriptionStatus.Canceled
      case "EXPIRED"   => SubscriptionStatus.Unpaid
      case _           => SubscriptionStatus.Active
    Subscription(
      id               = SubscriptionId(j("id").str),
      customerId       = CustomerId(j.obj.get("subscriber").flatMap(_.obj.get("payer_id")).map(_.str).getOrElse("")),
      planId           = PlanId(j.obj.get("plan_id").map(_.str).getOrElse("")),
      status           = status,
      currentPeriodEnd = j.obj.get("billing_info").flatMap(_.obj.get("next_billing_time"))
        .map(v => Instant.parse(v.str)).getOrElse(Instant.now().plusSeconds(2592000)),
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )

  private def parseDisputeResource(j: ujson.Value): Dispute =
    val currency = j.obj.get("dispute_amount").flatMap(_.obj.get("currency_code"))
      .map(v => Currency(v.str.toUpperCase)).getOrElse(Currency.USD)
    val amount   = j.obj.get("dispute_amount").flatMap(_.obj.get("value"))
      .map(v => Money(BigDecimal(v.str), currency)).getOrElse(Money.zero(currency))
    Dispute(
      id       = DisputeId(j("dispute_id").str),
      intentId = IntentId(j.obj.get("disputed_transactions").flatMap(_.arr.headOption)
        .flatMap(_.obj.get("order_id")).map(_.str).getOrElse("")),
      amount   = amount,
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = Instant.now().plusSeconds(604800),
      evidence = None,
    )

  private def header(req: WebhookRequest, name: String): Option[String] =
    req.headers.get(name)
      .orElse(req.headers.get(name.toLowerCase))
      .orElse(req.headers.get(name.replace("-", "_").toLowerCase))
