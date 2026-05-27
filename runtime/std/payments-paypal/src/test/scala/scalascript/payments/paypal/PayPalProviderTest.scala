package scalascript.payments.paypal

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*

class PayPalProviderTest extends AnyFunSuite:

  // ── PayPalProvider basic properties ───────────────────────────────────────

  test("PayPalProvider: id is paypal"):
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Test)
    assert(provider.id == "paypal")

  test("PayPalProvider: displayName is PayPal"):
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Test)
    assert(provider.displayName == "PayPal")

  test("PayPalProvider: Test mode uses sandbox URL"):
    // Verify mode is preserved
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Test)
    assert(provider.mode == PaymentMode.Test)

  test("PayPalProvider: Live mode is preserved"):
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Live)
    assert(provider.mode == PaymentMode.Live)

  test("PayPalProvider: capabilities match PayPal feature set"):
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Test)
    assert(provider.capabilities.supportsSubscriptions)
    assert(provider.capabilities.supportsGooglePay)
    assert(provider.capabilities.supportsRefunds)
    assert(provider.capabilities.supportsDisputes)
    assert(!provider.capabilities.supportsMandates)
    assert(!provider.capabilities.supportsSCA)

  test("PayPalProvider: webhookReceiver returns PayPalWebhookReceiver"):
    val provider = PayPalProvider("client", "secret", mode = PaymentMode.Test)
    assert(provider.webhookReceiver.isInstanceOf[PayPalWebhookReceiver])

  // ── PayPalWebhookReceiver — missing headers ────────────────────────────────

  test("PayPalWebhookReceiver: missing PAYPAL-TRANSMISSION-SIG returns MissingHeader"):
    val receiver = PayPalWebhookReceiver("wh_id")
    val req      = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result   = receiver.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("PayPalWebhookReceiver: missing PAYPAL-CERT-URL returns MissingHeader"):
    val receiver = PayPalWebhookReceiver("wh_id")
    val req = WebhookRequest(
      headers = Map(
        "PAYPAL-TRANSMISSION-SIG"  -> "sig",
        "PAYPAL-TRANSMISSION-ID"   -> "txid",
        "PAYPAL-TRANSMISSION-TIME" -> java.time.Instant.now().toString,
      ),
      rawBody = "{}",
    )
    val result = receiver.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("PayPalWebhookReceiver: untrusted cert URL is rejected"):
    val receiver = PayPalWebhookReceiver("wh_id")
    val req = WebhookRequest(
      headers = Map(
        "PAYPAL-TRANSMISSION-SIG"  -> "sig",
        "PAYPAL-TRANSMISSION-ID"   -> "txid",
        "PAYPAL-TRANSMISSION-TIME" -> java.time.Instant.now().toString,
        "PAYPAL-CERT-URL"          -> "https://evil.example.com/cert",
      ),
      rawBody = "{}",
    )
    val result = receiver.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[InvalidSignature])

  test("PayPalWebhookReceiver: idempotencyKey for PaymentIntentSucceeded"):
    val receiver = PayPalWebhookReceiver("wh_id")
    val intent   = PaymentIntent.Succeeded(
      IntentId("ORDER-123"),
      Money(1000L, Currency.USD),
      Charge(ChargeId("CAP-456"), IntentId("ORDER-123"), Money(1000L, Currency.USD), paid = true),
    )
    val event = PaymentEvent.PaymentIntentSucceeded(intent)
    assert(receiver.idempotencyKey(event) == "pi.succeeded.ORDER-123")

  test("PayPalWebhookReceiver: idempotencyKey for SubscriptionCanceled"):
    val receiver = PayPalWebhookReceiver("wh_id")
    val sub = Subscription(
      id = SubscriptionId("SUB-ABC"), customerId = CustomerId(""), planId = PlanId(""),
      status = SubscriptionStatus.Canceled, currentPeriodEnd = java.time.Instant.now(),
      cancelAtPeriodEnd = false, trialEnd = None,
    )
    val event = PaymentEvent.SubscriptionCanceled(sub)
    assert(receiver.idempotencyKey(event) == "sub.canceled.SUB-ABC")
