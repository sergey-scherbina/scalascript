package scalascript.payments.stripe

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StripeProviderTest extends AnyFunSuite:

  // ── Webhook signature verification ────────────────────────────────────────

  private def makeSignature(secret: String, timestamp: Long, body: String): String =
    val payload = s"$timestamp.$body"
    val mac     = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val sig = mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"t=$timestamp,v1=$sig"

  test("StripeWebhookReceiver: valid signature verifies"):
    val secret    = "whsec_test_secret"
    val now       = java.time.Instant.now().getEpochSecond
    val body      = """{"id":"evt_123","type":"payment_intent.succeeded","data":{"object":{"id":"pi_123","amount":4999,"currency":"usd","status":"succeeded","latest_charge":"ch_123"}}}"""
    val sig       = makeSignature(secret, now, body)
    val receiver  = StripeWebhookReceiver()
    val req       = WebhookRequest(headers = Map("Stripe-Signature" -> sig), rawBody = body)
    val result    = receiver.verify(req, secret)
    assert(result.isRight)
    result.foreach { event =>
      assert(event.isInstanceOf[PaymentEvent.PaymentIntentSucceeded])
    }

  test("StripeWebhookReceiver: invalid signature rejected"):
    val receiver  = StripeWebhookReceiver()
    val now       = java.time.Instant.now().getEpochSecond
    val body      = """{"id":"evt_123","type":"payment_intent.succeeded","data":{"object":{"id":"pi_123","amount":4999,"currency":"usd","status":"succeeded"}}}"""
    val req       = WebhookRequest(headers = Map("Stripe-Signature" -> s"t=$now,v1=badsig"), rawBody = body)
    assert(receiver.verify(req, "wrong_secret").isLeft)

  test("StripeWebhookReceiver: timestamp out of range rejected"):
    val secret    = "whsec_test"
    val old       = java.time.Instant.now().getEpochSecond - 400
    val body      = """{"id":"evt_999","type":"payment_intent.succeeded","data":{"object":{"id":"pi_999","amount":100,"currency":"usd","status":"succeeded"}}}"""
    val sig       = makeSignature(secret, old, body)
    val receiver  = StripeWebhookReceiver()
    val req       = WebhookRequest(headers = Map("Stripe-Signature" -> sig), rawBody = body)
    val result    = receiver.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[TimestampOutOfRange])

  test("StripeWebhookReceiver: missing header returns MissingHeader"):
    val receiver = StripeWebhookReceiver()
    val req      = WebhookRequest(headers = Map.empty, rawBody = "{}")
    assert(receiver.verify(req, "secret").swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("StripeWebhookReceiver: idempotencyKey for PaymentIntentSucceeded"):
    val receiver = StripeWebhookReceiver()
    val intent   = PaymentIntent.Succeeded(
      IntentId("pi_abc"),
      Money(4999L, Currency.USD),
      Charge(ChargeId("ch_xyz"), IntentId("pi_abc"), Money(4999L, Currency.USD), paid = true),
    )
    val event = PaymentEvent.PaymentIntentSucceeded(intent)
    assert(receiver.idempotencyKey(event) == "pi.succeeded.pi_abc")

  // ── SeenKeyStore ──────────────────────────────────────────────────────────

  test("InMemorySeenKeyStore: not seen before markSeen"):
    val store = InMemorySeenKeyStore()
    assert(!store.wasSeen("key1"))

  test("InMemorySeenKeyStore: seen after markSeen"):
    val store = InMemorySeenKeyStore()
    store.markSeen("key2", Duration.ofMinutes(5))
    assert(store.wasSeen("key2"))

  test("InMemorySeenKeyStore: not seen after expiry"):
    val store = InMemorySeenKeyStore()
    store.markSeen("key3", Duration.ofNanos(1))
    Thread.sleep(5)
    assert(!store.wasSeen("key3"))

  // ── WebhookReceiver.handle idempotency ────────────────────────────────────

  test("handle: second delivery with same key is deduplicated"):
    val secret   = "whsec_dup"
    val now      = java.time.Instant.now().getEpochSecond
    val body     = """{"id":"evt_dup","type":"payment_intent.succeeded","data":{"object":{"id":"pi_dup","amount":100,"currency":"usd","status":"succeeded"}}}"""
    val sig      = makeSignature(secret, now, body)
    val seenKeys = InMemorySeenKeyStore()
    val receiver = StripeWebhookReceiver(seenKeys = seenKeys)
    val req      = WebhookRequest(headers = Map("Stripe-Signature" -> sig), rawBody = body)
    var count    = 0
    receiver.handle(req, secret) { case _ => count += 1 }
    receiver.handle(req, secret) { case _ => count += 1 }
    // second delivery re-uses same timestamp → might fail timestamp check if clock moved;
    // instead test with a fresh signature at same timestamp:
    val sig2 = makeSignature(secret, now, body)
    val req2 = WebhookRequest(headers = Map("Stripe-Signature" -> sig2), rawBody = body)
    receiver.handle(req2, secret) { case _ => count += 1 }
    assert(count == 1)

  // ── StripeProvider — mode validation ──────────────────────────────────────

  test("StripeProvider: Test key accepted in Test mode"):
    val provider = StripeProvider("sk_test_fake123", PaymentMode.Test)
    assert(provider.mode == PaymentMode.Test)

  test("StripeProvider: Live key accepted in Live mode"):
    val provider = StripeProvider("sk_live_fake123", PaymentMode.Live)
    assert(provider.mode == PaymentMode.Live)

  test("StripeProvider: capabilities match Stripe feature set"):
    val provider = StripeProvider("sk_test_fake", PaymentMode.Test)
    assert(provider.capabilities.supportsSubscriptions)
    assert(provider.capabilities.supportsSCA)
    assert(provider.capabilities.supportsRefunds)
    assert(provider.capabilities.supportsDisputes)

  // ── Webhook event parsing ─────────────────────────────────────────────────

  test("StripeWebhookReceiver: parses invoice.payment_succeeded"):
    val secret   = "whsec_inv"
    val now      = java.time.Instant.now().getEpochSecond
    val body     = """{"id":"evt_inv","type":"invoice.payment_succeeded","data":{"object":{"id":"in_abc","amount_due":2999,"currency":"usd","subscription":"sub_xyz","status":"paid"}}}"""
    val sig      = makeSignature(secret, now, body)
    val receiver = StripeWebhookReceiver()
    val req      = WebhookRequest(headers = Map("Stripe-Signature" -> sig), rawBody = body)
    val result   = receiver.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.InvoicePaymentSucceeded])

  test("StripeWebhookReceiver: parses customer.subscription.deleted"):
    val secret   = "whsec_sub"
    val now      = java.time.Instant.now().getEpochSecond
    val body     = """{"id":"evt_sub","type":"customer.subscription.deleted","data":{"object":{"id":"sub_del","customer":"cus_xyz","status":"canceled","current_period_end":9999999999,"cancel_at_period_end":false}}}"""
    val sig      = makeSignature(secret, now, body)
    val receiver = StripeWebhookReceiver()
    val req      = WebhookRequest(headers = Map("Stripe-Signature" -> sig), rawBody = body)
    val result   = receiver.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.SubscriptionCanceled])
