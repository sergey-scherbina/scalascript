package scalascript.payments.braintree

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BraintreeProviderTest extends AnyFunSuite:

  private def makeBtSignature(privateKey: String, payload: String): (String, String) =
    val mac  = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(privateKey.getBytes("UTF-8"), "HmacSHA1"))
    val hmac = mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString
    val sig  = s"pub_key&$hmac"
    (sig, payload)

  // ── BraintreeProvider basic properties ────────────────────────────────────

  test("BraintreeProvider: id is braintree"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Test)
    assert(provider.id == "braintree")

  test("BraintreeProvider: displayName is Braintree"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Test)
    assert(provider.displayName == "Braintree")

  test("BraintreeProvider: Test mode preserved"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Test)
    assert(provider.mode == PaymentMode.Test)

  test("BraintreeProvider: Live mode preserved"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Live)
    assert(provider.mode == PaymentMode.Live)

  test("BraintreeProvider: capabilities match Braintree feature set"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Test)
    assert(provider.capabilities.supportsSubscriptions)
    assert(provider.capabilities.supportsApplePay)
    assert(provider.capabilities.supportsGooglePay)
    assert(provider.capabilities.supportsRefunds)
    assert(provider.capabilities.supportsDisputes)
    assert(!provider.capabilities.supportsMandates)
    assert(!provider.capabilities.supportsSCA)

  test("BraintreeProvider: webhookReceiver returns BraintreeWebhookReceiver"):
    val provider = BraintreeProvider("merchant", "pub", "priv", PaymentMode.Test)
    assert(provider.webhookReceiver.isInstanceOf[BraintreeWebhookReceiver])

  // ── BraintreeWebhookReceiver ──────────────────────────────────────────────

  test("BraintreeWebhookReceiver: missing bt-signature returns MissingHeader"):
    val receiver = BraintreeWebhookReceiver("priv")
    val req      = WebhookRequest(headers = Map.empty, rawBody = "")
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("BraintreeWebhookReceiver: missing bt-payload returns MissingHeader"):
    val receiver = BraintreeWebhookReceiver("priv")
    val req      = WebhookRequest(headers = Map("bt-signature" -> "sig"), rawBody = "")
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("BraintreeWebhookReceiver: invalid signature rejected"):
    val receiver = BraintreeWebhookReceiver("priv")
    val payload  = Base64.getEncoder.encodeToString("<notification><kind>check</kind></notification>".getBytes("UTF-8"))
    val req      = WebhookRequest(
      headers = Map("bt-signature" -> "pub_key&badhex", "bt-payload" -> payload),
      rawBody = "",
    )
    val result = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[InvalidSignature])

  test("BraintreeWebhookReceiver: valid check event is accepted"):
    val privateKey = "test_private_key"
    val xml        = "<notification><kind>check</kind></notification>"
    val payload    = Base64.getEncoder.encodeToString(xml.getBytes("UTF-8"))
    val (sig, _)   = makeBtSignature(privateKey, payload)
    val receiver   = BraintreeWebhookReceiver(privateKey)
    val req        = WebhookRequest(headers = Map("bt-signature" -> sig, "bt-payload" -> payload), rawBody = "")
    val result     = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.ManualReviewRequired])

  test("BraintreeWebhookReceiver: subscription_canceled event parsed"):
    val privateKey = "sk_test_bt"
    val xml        = "<notification><kind>subscription_canceled</kind><subscription><id>sub_xyz</id></subscription></notification>"
    val payload    = Base64.getEncoder.encodeToString(xml.getBytes("UTF-8"))
    val (sig, _)   = makeBtSignature(privateKey, payload)
    val receiver   = BraintreeWebhookReceiver(privateKey)
    val req        = WebhookRequest(headers = Map("bt-signature" -> sig, "bt-payload" -> payload), rawBody = "")
    val result     = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.SubscriptionCanceled])

  test("BraintreeWebhookReceiver: dispute_opened event parsed"):
    val privateKey = "sk_test_bt2"
    val xml        = "<notification><kind>dispute_opened</kind><dispute><id>dp_001</id><transaction-id>tx_001</transaction-id><amount>100.00</amount></dispute></notification>"
    val payload    = Base64.getEncoder.encodeToString(xml.getBytes("UTF-8"))
    val (sig, _)   = makeBtSignature(privateKey, payload)
    val receiver   = BraintreeWebhookReceiver(privateKey)
    val req        = WebhookRequest(headers = Map("bt-signature" -> sig, "bt-payload" -> payload), rawBody = "")
    val result     = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.DisputeCreated])

  test("BraintreeWebhookReceiver: idempotencyKey for DisputeCreated"):
    val receiver = BraintreeWebhookReceiver("priv")
    val dispute  = Dispute(
      id       = DisputeId("dp_abc"),
      intentId = IntentId("tx_abc"),
      amount   = Money(5000L, Currency.USD),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = java.time.Instant.now(),
      evidence = None,
    )
    val event = PaymentEvent.DisputeCreated(dispute)
    assert(receiver.idempotencyKey(event) == "dispute.created.dp_abc")

  test("BraintreeWebhookReceiver: second delivery deduplicated"):
    val privateKey = "sk_test_dedup"
    val xml        = "<notification><kind>check</kind></notification>"
    val payload    = Base64.getEncoder.encodeToString(xml.getBytes("UTF-8"))
    val (sig, _)   = makeBtSignature(privateKey, payload)
    val seenKeys   = InMemorySeenKeyStore()
    val receiver   = BraintreeWebhookReceiver(privateKey, seenKeys = seenKeys)
    val req        = WebhookRequest(headers = Map("bt-signature" -> sig, "bt-payload" -> payload), rawBody = "")
    var count      = 0
    receiver.handle(req, "") { case _ => count += 1 }
    receiver.handle(req, "") { case _ => count += 1 }
    assert(count == 1)
