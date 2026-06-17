package scalascript.payments.checkout

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CheckoutProviderTest extends AnyFunSuite:

  private def ckoHmac(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString

  // ── CheckoutProvider basic properties ─────────────────────────────────────

  test("CheckoutProvider: id is checkout"):
    val p = CheckoutProvider("sk_test_key", mode = PaymentMode.Test)
    assert(p.id == "checkout")

  test("CheckoutProvider: displayName is Checkout.com"):
    val p = CheckoutProvider("sk_test_key", mode = PaymentMode.Test)
    assert(p.displayName == "Checkout.com")

  test("CheckoutProvider: Test mode preserved"):
    val p = CheckoutProvider("sk_test_key", mode = PaymentMode.Test)
    assert(p.mode == PaymentMode.Test)

  test("CheckoutProvider: Live mode preserved"):
    val p = CheckoutProvider("sk_live_key", mode = PaymentMode.Live)
    assert(p.mode == PaymentMode.Live)

  test("CheckoutProvider: capabilities match Checkout.com feature set"):
    val p = CheckoutProvider("sk_test_key", mode = PaymentMode.Test)
    assert(p.capabilities.supportsSubscriptions)
    assert(p.capabilities.supportsSCA)
    assert(p.capabilities.supports3DS2)
    assert(p.capabilities.supportsApplePay)
    assert(p.capabilities.supportsGooglePay)
    assert(p.capabilities.supportsRefunds)
    assert(p.capabilities.supportsDisputes)
    assert(p.capabilities.supportsMandates)

  test("CheckoutProvider: webhookReceiver returns CheckoutWebhookReceiver"):
    val p = CheckoutProvider("sk_test_key", "webhook_secret", PaymentMode.Test)
    assert(p.webhookReceiver.isInstanceOf[CheckoutWebhookReceiver])

  // ── CheckoutWebhookReceiver ───────────────────────────────────────────────

  test("CheckoutWebhookReceiver: missing Cko-Signature returns MissingHeader"):
    val receiver = CheckoutWebhookReceiver("")
    val req      = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("CheckoutWebhookReceiver: valid HMAC + payment_approved parsed"):
    val secret   = "cko_webhook_secret"
    val body     = """{"type":"payment_approved","data":{"id":"pay_001","amount":2000,"currency":"USD"}}"""
    val sig      = ckoHmac(secret, body)
    val receiver = CheckoutWebhookReceiver(secret)
    val req      = WebhookRequest(headers = Map("Cko-Signature" -> sig), rawBody = body)
    val result   = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.PaymentIntentSucceeded])

  test("CheckoutWebhookReceiver: invalid HMAC rejected"):
    val secret   = "cko_secret"
    val body     = """{"type":"payment_approved","data":{"id":"pay_002","amount":500,"currency":"USD"}}"""
    val receiver = CheckoutWebhookReceiver(secret)
    val req      = WebhookRequest(headers = Map("Cko-Signature" -> "badsig"), rawBody = body)
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[InvalidSignature])

  test("CheckoutWebhookReceiver: payment_refunded parsed as ChargeRefunded"):
    val body     = """{"type":"payment_refunded","data":{"id":"pay_003","action_id":"act_ref_001","amount":1000,"currency":"EUR"}}"""
    val receiver = CheckoutWebhookReceiver("")
    val req      = WebhookRequest(headers = Map("Cko-Signature" -> "ignored"), rawBody = body)
    val result   = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.ChargeRefunded])

  test("CheckoutWebhookReceiver: dispute_created parsed"):
    val body     = """{"type":"dispute_created","data":{"id":"dsp_001","payment_id":"pay_004","amount":3000,"currency":"GBP","evidence_required_by":"2026-06-10T00:00:00Z"}}"""
    val receiver = CheckoutWebhookReceiver("")
    val req      = WebhookRequest(headers = Map("Cko-Signature" -> ""), rawBody = body)
    val result   = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.DisputeCreated])

  test("CheckoutWebhookReceiver: idempotencyKey for ChargeRefunded"):
    val receiver = CheckoutWebhookReceiver("")
    val refund   = Refund(
      id       = RefundId("ref_xyz"),
      intentId = IntentId("pay_xyz"),
      amount   = Money(1000L, Currency.USD),
      reason   = RefundReason.RequestedByCustomer,
      status   = RefundStatus.Succeeded,
    )
    val event = PaymentEvent.ChargeRefunded(refund)
    assert(receiver.idempotencyKey(event) == "refund.ref_xyz")

  test("CheckoutWebhookReceiver: deduplication on second delivery"):
    val body     = """{"type":"payment_captured","data":{"id":"pay_dedup","amount":500,"currency":"USD"}}"""
    val seenKeys = InMemorySeenKeyStore()
    val receiver = CheckoutWebhookReceiver("", seenKeys = seenKeys)
    val req      = WebhookRequest(headers = Map("Cko-Signature" -> ""), rawBody = body)
    var count    = 0
    receiver.handle(req, "") { case _ => count += 1 }
    receiver.handle(req, "") { case _ => count += 1 }
    assert(count == 1)
