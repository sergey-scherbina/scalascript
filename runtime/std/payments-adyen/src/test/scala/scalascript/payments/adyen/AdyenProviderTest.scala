package scalascript.payments.adyen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AdyenProviderTest extends AnyFunSuite:

  private def computeAdyenHmac(keyBase64: String, fields: List[String]): String =
    val data     = fields.mkString(":")
    val keyBytes = Base64.getDecoder.decode(keyBase64)
    val mac      = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
    Base64.getEncoder.encodeToString(mac.doFinal(data.getBytes("UTF-8")))

  // ── AdyenProvider basic properties ────────────────────────────────────────

  test("AdyenProvider: id is adyen"):
    val p = AdyenProvider("key", "merchant", mode = PaymentMode.Test)
    assert(p.id == "adyen")

  test("AdyenProvider: displayName is Adyen"):
    val p = AdyenProvider("key", "merchant", mode = PaymentMode.Test)
    assert(p.displayName == "Adyen")

  test("AdyenProvider: Test mode preserved"):
    val p = AdyenProvider("key", "merchant", mode = PaymentMode.Test)
    assert(p.mode == PaymentMode.Test)

  test("AdyenProvider: Live mode preserved"):
    val p = AdyenProvider("key", "merchant", mode = PaymentMode.Live)
    assert(p.mode == PaymentMode.Live)

  test("AdyenProvider: capabilities match Adyen feature set"):
    val p = AdyenProvider("key", "merchant", mode = PaymentMode.Test)
    assert(p.capabilities.supportsSubscriptions)
    assert(p.capabilities.supportsSCA)
    assert(p.capabilities.supports3DS2)
    assert(p.capabilities.supportsApplePay)
    assert(p.capabilities.supportsGooglePay)
    assert(p.capabilities.supportsRefunds)
    assert(p.capabilities.supportsDisputes)
    assert(p.capabilities.supportsMandates)

  test("AdyenProvider: webhookReceiver returns AdyenWebhookReceiver"):
    val p = AdyenProvider("key", "merchant", "hmacKey", PaymentMode.Test)
    assert(p.webhookReceiver.isInstanceOf[AdyenWebhookReceiver])

  // ── AdyenWebhookReceiver ──────────────────────────────────────────────────

  test("AdyenWebhookReceiver: missing X-Adyen-Hmac-Signature returns MissingHeader"):
    val receiver = AdyenWebhookReceiver("")
    val req      = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result   = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[MissingHeader])

  test("AdyenWebhookReceiver: AUTHORISATION success event parsed"):
    val keyBase64 = Base64.getEncoder.encodeToString("test-hmac-key".getBytes("UTF-8"))
    val body = """{
      "notificationItems":[{"NotificationRequestItem":{
        "eventCode":"AUTHORISATION",
        "success":"true",
        "pspReference":"PSP-001",
        "originalReference":"",
        "merchantAccountCode":"merchant",
        "merchantReference":"ref-1",
        "amount":{"value":1000,"currency":"USD"}
      }}]
    }"""
    // Compute valid HMAC
    val fields = List("PSP-001", "", "merchant", "ref-1", "1000", "USD", "AUTHORISATION", "true")
    val hmac   = computeAdyenHmac(keyBase64, fields)
    // Inject hmacSignature into additionalData
    val bodyWithSig = body.replace(""""amount":{""", s""""additionalData":{"hmacSignature":"$hmac"},"amount":{""")
    val receiver    = AdyenWebhookReceiver(keyBase64)
    val req         = WebhookRequest(headers = Map("X-Adyen-Hmac-Signature" -> hmac), rawBody = bodyWithSig)
    val result      = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.PaymentIntentSucceeded])

  test("AdyenWebhookReceiver: invalid HMAC rejected"):
    val keyBase64 = Base64.getEncoder.encodeToString("secret-key".getBytes("UTF-8"))
    val body      = """{"notificationItems":[{"NotificationRequestItem":{"eventCode":"AUTHORISATION","success":"true","pspReference":"PSP-002","originalReference":"","merchantAccountCode":"merchant","merchantReference":"ref-2","amount":{"value":500,"currency":"USD"}}}]}"""
    val receiver  = AdyenWebhookReceiver(keyBase64)
    val req       = WebhookRequest(headers = Map("X-Adyen-Hmac-Signature" -> "badsig"), rawBody = body)
    val result    = receiver.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.getOrElse(null).isInstanceOf[InvalidSignature])

  test("AdyenWebhookReceiver: CHARGEBACK event parsed as DisputeCreated"):
    val body     = """{"notificationItems":[{"NotificationRequestItem":{"eventCode":"CHARGEBACK","success":"true","pspReference":"PSP-003","originalReference":"ORIG-001","merchantAccountCode":"merchant","merchantReference":"ref-3","amount":{"value":2000,"currency":"EUR"}}}]}"""
    val receiver = AdyenWebhookReceiver("")
    val req      = WebhookRequest(headers = Map("X-Adyen-Hmac-Signature" -> "ignored"), rawBody = body)
    val result   = receiver.verify(req, "")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.DisputeCreated])

  test("AdyenWebhookReceiver: idempotencyKey for PaymentIntentSucceeded"):
    val receiver = AdyenWebhookReceiver("")
    val intent   = PaymentIntent.Succeeded(
      IntentId("PSP-123"), Money(5000L, Currency.USD),
      Charge(ChargeId("PSP-123"), IntentId("PSP-123"), Money(5000L, Currency.USD), paid = true),
    )
    val event = PaymentEvent.PaymentIntentSucceeded(intent)
    assert(receiver.idempotencyKey(event) == "pi.succeeded.PSP-123")

  test("AdyenWebhookReceiver: deduplication on second delivery"):
    val body     = """{"notificationItems":[{"NotificationRequestItem":{"eventCode":"AUTHORISATION","success":"true","pspReference":"PSP-dup","originalReference":"","merchantAccountCode":"merchant","merchantReference":"ref-dup","amount":{"value":100,"currency":"USD"}}}]}"""
    val seenKeys = InMemorySeenKeyStore()
    val receiver = AdyenWebhookReceiver("", seenKeys = seenKeys)
    val req      = WebhookRequest(headers = Map("X-Adyen-Hmac-Signature" -> ""), rawBody = body)
    var count    = 0
    receiver.handle(req, "") { case _ => count += 1 }
    receiver.handle(req, "") { case _ => count += 1 }
    assert(count == 1)
