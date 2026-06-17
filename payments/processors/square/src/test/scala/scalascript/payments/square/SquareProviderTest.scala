package scalascript.payments.square

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SquareProviderTest extends AnyFunSuite:

  private def squareHmac(key: String, url: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"))
    Base64.getEncoder.encodeToString(mac.doFinal((url + body).getBytes("UTF-8")))

  // ── SquareProvider basic properties ───────────────────────────────────────

  test("SquareProvider: id is square"):
    val p = SquareProvider("tok", "loc", mode = PaymentMode.Test)
    assert(p.id == "square")

  test("SquareProvider: displayName is Square"):
    val p = SquareProvider("tok", "loc", mode = PaymentMode.Test)
    assert(p.displayName == "Square")

  test("SquareProvider: Test mode preserved"):
    val p = SquareProvider("tok", "loc", mode = PaymentMode.Test)
    assert(p.mode == PaymentMode.Test)

  test("SquareProvider: Live mode preserved"):
    val p = SquareProvider("tok", "loc", mode = PaymentMode.Live)
    assert(p.mode == PaymentMode.Live)

  test("SquareProvider: capabilities match Square feature set"):
    val p = SquareProvider("tok", "loc", mode = PaymentMode.Test)
    assert(p.capabilities.supportsSubscriptions)
    assert(p.capabilities.supportsApplePay)
    assert(p.capabilities.supportsGooglePay)
    assert(p.capabilities.supportsRefunds)
    assert(p.capabilities.supportsDisputes)
    assert(!p.capabilities.supportsSCA)
    assert(!p.capabilities.supportsMandates)

  test("SquareProvider: webhookReceiver returns SquareWebhookReceiver"):
    val p = SquareProvider("tok", "loc", "sigkey", PaymentMode.Test)
    assert(p.webhookReceiver.isInstanceOf[SquareWebhookReceiver])

  // ── SquareWebhookReceiver ─────────────────────────────────────────────────

  test("SquareWebhookReceiver: missing x-square-hmacsha1-signature returns MissingHeader"):
    val receiver = SquareWebhookReceiver("")
    val req      = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("SquareWebhookReceiver: valid HMAC + payment.completed parsed"):
    val key  = "sq_webhook_secret"
    val url  = "https://example.com/webhooks/square"
    val body = """{"type":"payment.completed","data":{"object":{"payment":{"id":"pay_sq_001","status":"COMPLETED","amount_money":{"amount":2000,"currency":"USD"}}}}}"""
    val sig  = squareHmac(key, url, body)
    val recv = SquareWebhookReceiver(key, url)
    val req  = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> sig), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.PaymentIntentSucceeded])

  test("SquareWebhookReceiver: invalid HMAC rejected"):
    val key  = "sq_secret"
    val url  = "https://example.com/webhooks/square"
    val body = """{"type":"payment.completed","data":{"object":{"payment":{"id":"pay_sq_002","status":"COMPLETED","amount_money":{"amount":500,"currency":"USD"}}}}}"""
    val recv = SquareWebhookReceiver(key, url)
    val req  = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> "badsig=="), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[InvalidSignature])

  test("SquareWebhookReceiver: refund.completed parsed as ChargeRefunded"):
    val body = """{"type":"refund.completed","data":{"object":{"refund":{"id":"ref_sq_001","payment_id":"pay_sq_003","amount_money":{"amount":1000,"currency":"EUR"}}}}}"""
    val recv = SquareWebhookReceiver("")
    val req  = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> "ignored"), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.ChargeRefunded])

  test("SquareWebhookReceiver: dispute.created parsed as DisputeCreated"):
    val body = """{"type":"dispute.created","data":{"object":{"dispute":{"id":"dsp_sq_001","payment_id":"pay_sq_004","amount_money":{"amount":3000,"currency":"GBP"},"due_at":"2026-06-10T00:00:00Z","state":"EVIDENCE_REQUIRED"}}}}"""
    val recv = SquareWebhookReceiver("")
    val req  = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> ""), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.DisputeEvidenceRequired])

  test("SquareWebhookReceiver: subscription.canceled parsed as SubscriptionCanceled"):
    val body = """{"type":"subscription.canceled","data":{"object":{"subscription":{"id":"sub_sq_001","customer_id":"cust_sq_001","plan_variation_id":"plan_sq_001"}}}}"""
    val recv = SquareWebhookReceiver("")
    val req  = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> ""), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.SubscriptionCanceled])

  test("SquareWebhookReceiver: idempotencyKey for ChargeRefunded"):
    val recv   = SquareWebhookReceiver("")
    val refund = Refund(
      id       = RefundId("ref_xyz"),
      intentId = IntentId("pay_xyz"),
      amount   = Money(1000L, Currency.USD),
      reason   = RefundReason.RequestedByCustomer,
      status   = RefundStatus.Succeeded,
    )
    val event = PaymentEvent.ChargeRefunded(refund)
    assert(recv.idempotencyKey(event) == "refund.ref_xyz")

  test("SquareWebhookReceiver: deduplication on second delivery"):
    val body     = """{"type":"payment.completed","data":{"object":{"payment":{"id":"pay_dedup","status":"COMPLETED","amount_money":{"amount":100,"currency":"USD"}}}}}"""
    val seenKeys = InMemorySeenKeyStore()
    val recv     = SquareWebhookReceiver("", seenKeys = seenKeys)
    val req      = WebhookRequest(headers = Map("x-square-hmacsha1-signature" -> ""), rawBody = body)
    var count    = 0
    recv.handle(req, "") { case _ => count += 1 }
    recv.handle(req, "") { case _ => count += 1 }
    assert(count == 1)
