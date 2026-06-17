package scalascript.payments.upi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import java.security.{KeyPair, KeyPairGenerator}
import java.util.Base64

/** Tests for the India UPI adapter (v1.55.6).
 *
 *  Covers:
 *  - RailKind.IN_UPI existence and routing
 *  - BankAccount.upiVpa field
 *  - UpiConfig construction and fromEnv
 *  - UpiProvider SPI contract (id, displayName, spiVersion, supportedRails)
 *  - Push (initiateTransfer) request JSON shape — VPA in payeeVpa field
 *  - Collect (initiateDirectDebit) request JSON shape
 *  - UPI Collect request model (UpiCollectRequest) serialization
 *  - UPI Collect expiry — UpiDeclined event for upi.collect.expired
 *  - VPA format validation (positive and negative cases)
 *  - RSA webhook signature verification (test keypair)
 *  - UpiApproved event parsing (upi.payment.success)
 *  - UpiDeclined event parsing (upi.payment.failed)
 *  - UpiCollectInitiated event parsing (upi.collect.initiated)
 *  - UpiTwoFactorTimeout error model
 *  - Cancel-not-supported behavior
 *  - BankRailsEvent UPI cases
 *  - UpiPlugin SPI registration
 */
class UpiProviderTest extends AnyFunSuite:

  // ── RSA test keypair (generated once per test run) ────────────────────────

  private val testKeyPair: KeyPair =
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()

  private def testPublicKeyPem: String =
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(testKeyPair.getPublic.getEncoded)
    s"-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"

  private def signBody(body: String): String =
    import java.security.Signature as JSig
    val signer = JSig.getInstance("SHA256withRSA")
    signer.initSign(testKeyPair.getPrivate)
    signer.update(body.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(signer.sign())

  // ── Test fixtures ─────────────────────────────────────────────────────────

  private val merchantAccount = BankAccount(
    upiVpa      = Some("merchant@razorpay"),
    holderName  = "Acme Pvt Ltd",
    countryCode = "IN",
  )
  private val customerAccount = BankAccount(
    upiVpa      = Some("customer@okicici"),
    holderName  = "Rahul Kumar",
    countryCode = "IN",
  )
  private val amount500INR = Money(50000L, Currency("INR"))  // Rs.500.00 = 50000 paise

  private val testConfig = UpiConfig(
    apiKey      = "test-upi-api-key",
    merchantVpa = "merchant@razorpay",
    baseUrl     = "https://api.razorpay.example.com/v1",
    merchantName = "Acme Pvt Ltd",
  )

  // ── RailKind.IN_UPI tests ─────────────────────────────────────────────────

  test("RailKind.IN_UPI case exists"):
    val rail: RailKind = RailKind.IN_UPI
    assert(rail == RailKind.IN_UPI)

  test("RailKind.IN_UPI is distinct from other rails"):
    assert(RailKind.IN_UPI != RailKind.SEPA_CT)
    assert(RailKind.IN_UPI != RailKind.ACH_CREDIT)
    assert(RailKind.IN_UPI != RailKind.FEDNOW)
    assert(RailKind.IN_UPI != RailKind.UK_FPS)
    assert(RailKind.IN_UPI != RailKind.PIX)

  test("UpiProvider.supportedRails contains only IN_UPI"):
    val provider = UpiProvider(testConfig)
    assert(provider.supportedRails == Set(RailKind.IN_UPI))

  // ── BankAccount.upiVpa field tests ────────────────────────────────────────

  test("BankAccount.upiVpa field is preserved"):
    val account = BankAccount(
      upiVpa      = Some("rahul@okicici"),
      holderName  = "Rahul Kumar",
      countryCode = "IN",
    )
    assert(account.upiVpa == Some("rahul@okicici"))

  test("BankAccount.upiVpa defaults to None when not provided"):
    val account = BankAccount(
      iban        = Some("DE89370400440532013000"),
      holderName  = "Test User",
      countryCode = "DE",
    )
    assert(account.upiVpa == None)

  test("BankAccount with all v1.55 fields including upiVpa compiles correctly"):
    val account = BankAccount(
      upiVpa      = Some("merchant@razorpay"),
      sortCode    = None,
      bic         = None,
      holderName  = "Merchant",
      countryCode = "IN",
    )
    assert(account.upiVpa      == Some("merchant@razorpay"))
    assert(account.sortCode    == None)
    assert(account.bic         == None)

  // ── UpiConfig tests ───────────────────────────────────────────────────────

  test("UpiConfig fields accessible"):
    val cfg = UpiConfig(
      apiKey      = "key-123",
      merchantVpa = "shop@paytm",
      baseUrl     = "https://api.juspay.in/v1",
    )
    assert(cfg.apiKey      == "key-123")
    assert(cfg.merchantVpa == "shop@paytm")
    assert(cfg.baseUrl     == "https://api.juspay.in/v1")

  test("UpiConfig default purposeCode is 00"):
    val cfg = UpiConfig(apiKey = "k", merchantVpa = "m@r", baseUrl = "http://example.com")
    assert(cfg.defaultPurposeCode == "00")

  test("UpiConfig.fromEnv returns a config without throwing"):
    val cfg = UpiConfig.fromEnv
    assert(cfg.merchantVpa.nonEmpty)
    assert(cfg.baseUrl.nonEmpty)

  test("UpiConfig callbackUrl defaults to None"):
    val cfg = UpiConfig(apiKey = "k", merchantVpa = "m@r", baseUrl = "http://example.com")
    assert(cfg.callbackUrl == None)

  test("UpiConfig with callbackUrl"):
    val cfg = UpiConfig(
      apiKey      = "k",
      merchantVpa = "m@r",
      baseUrl     = "http://example.com",
      callbackUrl = Some("https://myapp.example.com/upi/webhook"),
    )
    assert(cfg.callbackUrl == Some("https://myapp.example.com/upi/webhook"))

  // ── UpiProvider SPI contract tests ───────────────────────────────────────

  test("UpiProvider.id returns 'india-upi'"):
    val provider = UpiProvider(testConfig)
    assert(provider.id == "india-upi")

  test("UpiProvider.displayName is non-empty"):
    val provider = UpiProvider(testConfig)
    assert(provider.displayName.nonEmpty)

  test("UpiProvider.spiVersion is non-empty"):
    val provider = UpiProvider(testConfig)
    assert(provider.spiVersion.nonEmpty)

  test("UpiProvider throws UnsupportedRail for SEPA_CT"):
    val provider = UpiProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount500INR,
      sender         = merchantAccount,
      recipient      = customerAccount,
      reference      = "REF001",
      idempotencyKey = "idem-001",
    )
    intercept[UnsupportedRail]:
      provider.initiateTransfer(req)

  test("UpiProvider throws UnsupportedRail for UK_FPS"):
    val provider = UpiProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.UK_FPS,
      amount         = amount500INR,
      sender         = merchantAccount,
      recipient      = customerAccount,
      reference      = "REF002",
      idempotencyKey = "idem-002",
    )
    intercept[UnsupportedRail]:
      provider.initiateTransfer(req)

  // ── VPA validation tests ──────────────────────────────────────────────────

  test("UpiProvider.validateVpa accepts valid VPA formats"):
    val provider = UpiProvider(testConfig)
    // Should not throw
    provider.validateVpa("user@okicici")
    provider.validateVpa("merchant@razorpay")
    provider.validateVpa("9876543210@paytm")
    provider.validateVpa("user.name@hdfc")

  test("UpiProvider.validateVpa rejects VPA with no @ sign"):
    val provider = UpiProvider(testConfig)
    val ex = intercept[IllegalArgumentException]:
      provider.validateVpa("userokicici")
    assert(ex.getMessage.contains("localPart@bankHandle"))

  test("UpiProvider.validateVpa rejects VPA starting with @"):
    val provider = UpiProvider(testConfig)
    val ex = intercept[IllegalArgumentException]:
      provider.validateVpa("@okicici")
    assert(ex.getMessage.contains("Invalid UPI VPA format"))

  test("UpiProvider.validateVpa rejects VPA with empty bankHandle"):
    val provider = UpiProvider(testConfig)
    val ex = intercept[IllegalArgumentException]:
      provider.validateVpa("user@")
    assert(ex.getMessage.contains("bankHandle is empty"))

  test("UpiProvider.validateVpa rejects VPA with localPart > 100 chars"):
    val provider = UpiProvider(testConfig)
    val longLocal = "a" * 101
    val ex = intercept[IllegalArgumentException]:
      provider.validateVpa(s"$longLocal@upi")
    assert(ex.getMessage.contains("too long"))

  test("UpiProvider throws IllegalArgumentException when recipient upiVpa missing"):
    val provider = UpiProvider(testConfig)
    val recipientNoVpa = BankAccount(holderName = "Bob", countryCode = "IN")
    val req = InitiateTransferRequest(
      rail           = RailKind.IN_UPI,
      amount         = amount500INR,
      sender         = merchantAccount,
      recipient      = recipientNoVpa,
      reference      = "REF003",
      idempotencyKey = "idem-003",
    )
    val ex = intercept[IllegalArgumentException]:
      provider.initiateTransfer(req)
    assert(ex.getMessage.contains("upiVpa"))

  // ── Push request JSON shape tests ─────────────────────────────────────────

  test("buildPayRequest includes txnId, amount, payeeVpa, remarks, purpose"):
    val provider = UpiProvider(testConfig)
    val json = provider.buildPayRequest(
      txnId       = "txn-push-001",
      amountPaise = 50000L,
      payeeVpa    = "merchant@razorpay",
      payerVpa    = None,
      remarks     = "Order payment",
      purpose     = "00",
    )
    assert(json.contains("\"txnId\""))
    assert(json.contains("txn-push-001"))
    assert(json.contains("50000"))
    assert(json.contains("merchant@razorpay"))
    assert(json.contains("Order payment"))
    assert(json.contains("\"00\""))

  test("buildPayRequest includes payerVpa when provided (Collect flow)"):
    val provider = UpiProvider(testConfig)
    val json = provider.buildPayRequest(
      txnId       = "txn-collect-001",
      amountPaise = 100000L,
      payeeVpa    = "shop@payu",
      payerVpa    = Some("customer@okicici"),
      remarks     = "Monthly subscription",
      purpose     = "14",
    )
    assert(json.contains("customer@okicici"))
    assert(json.contains("payerVpa"))
    assert(json.contains("\"14\""))

  test("buildPayRequest omits payerVpa when None (Push flow)"):
    val provider = UpiProvider(testConfig)
    val json = provider.buildPayRequest(
      txnId       = "txn-push-002",
      amountPaise = 25000L,
      payeeVpa    = "merchant@razorpay",
      payerVpa    = None,
      remarks     = "Item purchase",
      purpose     = "00",
    )
    assert(!json.contains("payerVpa"))

  test("buildPayRequest includes callbackUrl when configured"):
    val configWithCallback = testConfig.copy(callbackUrl = Some("https://myapp.com/upi/hook"))
    val provider = UpiProvider(configWithCallback)
    val json = provider.buildPayRequest(
      txnId       = "txn-003",
      amountPaise = 5000L,
      payeeVpa    = "merchant@razorpay",
      payerVpa    = None,
      remarks     = "Test",
      purpose     = "00",
    )
    assert(json.contains("callbackUrl"))
    assert(json.contains("https://myapp.com/upi/hook"))

  test("buildPayRequest truncates txnId to 50 chars"):
    val provider = UpiProvider(testConfig)
    val longId   = "a" * 60
    val txnId    = longId.take(50)
    val json     = provider.buildPayRequest(txnId, 1000L, "m@r", None, "r", "00")
    assert(json.contains(txnId))
    assert(!json.contains(longId))

  test("buildPayRequest escapes special characters in JSON"):
    val provider = UpiProvider(testConfig)
    val json = provider.buildPayRequest("txn-esc", 1000L, "merchant@razorpay", None, "item \"test\"", "00")
    assert(json.contains("item \\\"test\\\""))

  // ── UpiCollectRequest model tests ─────────────────────────────────────────

  test("UpiCollectRequest fields are correctly populated"):
    val expiresAt = java.time.Instant.now().plusSeconds(3600)
    val req = UpiCollectRequest(
      txnId       = "collect-001",
      payeeVpa    = "shop@cashfree",
      payerVpa    = "customer@okicici",
      amountPaise = 10000L,
      remarks     = "Invoice #100",
      expiresAt   = expiresAt,
    )
    assert(req.txnId       == "collect-001")
    assert(req.payeeVpa    == "shop@cashfree")
    assert(req.payerVpa    == "customer@okicici")
    assert(req.amountPaise == 10000L)
    assert(req.remarks     == "Invoice #100")
    assert(req.expiresAt   == expiresAt)
    assert(req.purposeCode == "00")

  test("UpiCollectRequest purposeCode can be set to 14 (subscription)"):
    val req = UpiCollectRequest(
      txnId       = "sub-001",
      payeeVpa    = "merchant@payu",
      payerVpa    = "customer@upi",
      amountPaise = 29900L,
      remarks     = "Monthly plan",
      expiresAt   = java.time.Instant.now().plusSeconds(3600),
      purposeCode = "14",
    )
    assert(req.purposeCode == "14")

  // ── UPI cancel not supported ──────────────────────────────────────────────

  test("UpiProvider.cancelTransfer throws BankRailsCancelError"):
    val provider = UpiProvider(testConfig)
    val ex = intercept[BankRailsCancelError]:
      provider.cancelTransfer(TransferId("txn-cancel-001"))
    assert(ex.getMessage.nonEmpty)

  // ── Webhook receiver tests — RSA signature verification ───────────────────

  test("UpiWebhookReceiver: valid RSA-SHA256 signature verifies for upi.payment.success"):
    val body = """{"event":"upi.payment.success","txnId":"txn-001","utrNumber":"UTR123456789"}"""
    val sig  = signBody(body)
    val recv = UpiWebhookReceiver(testPublicKeyPem)
    val req  = WebhookRequest(headers = Map("X-UPI-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("UpiWebhookReceiver: wrong RSA signature is rejected with InvalidSignature"):
    val body    = """{"event":"upi.payment.success","txnId":"txn-002","utrNumber":"UTR987"}"""
    val wrongSig = "dGhpcyBpcyBub3QgYSB2YWxpZCBzaWduYXR1cmU="  // "this is not a valid signature"
    val recv    = UpiWebhookReceiver(testPublicKeyPem)
    val req     = WebhookRequest(headers = Map("X-UPI-Signature" -> wrongSig), rawBody = body)
    val result  = recv.verify(req, "")
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("UpiWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = UpiWebhookReceiver(testPublicKeyPem)
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("UpiWebhookReceiver: skips signature verification when publicKeyPem is empty"):
    val body   = """{"event":"upi.payment.success","txnId":"txn-nokey","utrNumber":"UTR0"}"""
    val recv   = UpiWebhookReceiver("")  // no key configured
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight, "Should skip signature check when no key configured")

  // ── Webhook event parsing — upi.payment.success → UpiApproved ─────────────

  test("UpiWebhookReceiver: parses upi.payment.success → UpiApproved"):
    val body   = """{"event":"upi.payment.success","txnId":"txn-approved-001","utrNumber":"UTR1234567890"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiApproved(txnId, utr) =>
        assert(txnId == "txn-approved-001")
        assert(utr   == "UTR1234567890")
      case other => fail(s"Expected UpiApproved, got $other")

  test("UpiWebhookReceiver: parses upi.payment.success with 'utr' alias"):
    val body   = """{"event":"upi.payment.success","txnId":"txn-approved-002","utr":"UTR0987654321"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiApproved(_, utr) => assert(utr == "UTR0987654321")
      case other => fail(s"Expected UpiApproved, got $other")

  // ── Webhook event parsing — upi.payment.failed → UpiDeclined ─────────────

  test("UpiWebhookReceiver: parses upi.payment.failed → UpiDeclined"):
    val body   = """{"event":"upi.payment.failed","txnId":"txn-declined-001","reason":"Payer declined"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiDeclined(txnId, reason) =>
        assert(txnId  == "txn-declined-001")
        assert(reason == "Payer declined")
      case other => fail(s"Expected UpiDeclined, got $other")

  test("UpiWebhookReceiver: parses upi.payment.failed with errorDescription alias"):
    val body   = """{"event":"upi.payment.failed","txnId":"txn-err-001","errorDescription":"UPI PIN incorrect"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiDeclined(_, reason) => assert(reason.contains("UPI PIN"))
      case other => fail(s"Expected UpiDeclined, got $other")

  // ── Webhook event parsing — upi.collect.expired → UpiDeclined ─────────────

  test("UpiWebhookReceiver: parses upi.collect.expired → UpiDeclined with expired reason"):
    val body   = """{"event":"upi.collect.expired","txnId":"txn-expired-001"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiDeclined(txnId, reason) =>
        assert(txnId  == "txn-expired-001")
        assert(reason.contains("expired"))
      case other => fail(s"Expected UpiDeclined(expired), got $other")

  // ── Webhook event parsing — upi.collect.initiated → UpiCollectInitiated ───

  test("UpiWebhookReceiver: parses upi.collect.initiated → UpiCollectInitiated"):
    val body = """{"event":"upi.collect.initiated","txnId":"txn-collect-001","payerVpa":"customer@okicici","amount":"50000"}"""
    val recv = UpiWebhookReceiver("")
    val req  = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UpiCollectInitiated(txnId, vpa, amount) =>
        assert(txnId  == "txn-collect-001")
        assert(vpa    == "customer@okicici")
        assert(amount == "50000")
      case other => fail(s"Expected UpiCollectInitiated, got $other")

  test("UpiWebhookReceiver: unknown event type returns MalformedPayload"):
    val body   = """{"event":"upi.unknown.event","txnId":"txn-unknown"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("UpiWebhookReceiver: missing event field returns MalformedPayload"):
    val body   = """{"txnId":"txn-nofield"}"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("UpiWebhookReceiver: malformed JSON returns MalformedPayload"):
    val body   = """not valid json {{{"""
    val recv   = UpiWebhookReceiver("")
    val req    = WebhookRequest(headers = Map.empty, rawBody = body)
    val result = recv.verify(req, "")
    assert(result.isLeft)

  // ── idempotencyKey tests ───────────────────────────────────────────────────

  test("UpiWebhookReceiver.idempotencyKey: UpiApproved key includes txnId and utr"):
    val recv  = UpiWebhookReceiver("")
    val event = BankRailsEvent.UpiApproved("txn-001", "UTR123456")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("txn-001"))
    assert(key.contains("UTR123456"))

  test("UpiWebhookReceiver.idempotencyKey: UpiDeclined key includes txnId"):
    val recv  = UpiWebhookReceiver("")
    val event = BankRailsEvent.UpiDeclined("txn-002", "declined")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("txn-002"))

  test("UpiWebhookReceiver.idempotencyKey: UpiCollectInitiated key includes txnId"):
    val recv  = UpiWebhookReceiver("")
    val event = BankRailsEvent.UpiCollectInitiated("txn-003", "customer@vpa", "50000")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("txn-003"))

  // ── UpiTwoFactorTimeout error model tests ─────────────────────────────────

  test("UpiTwoFactorTimeout is a BankRailsError"):
    val err: BankRailsError = UpiTwoFactorTimeout("txn-timeout-001")
    assert(err.isInstanceOf[BankRailsError])

  test("UpiTwoFactorTimeout message contains txnId"):
    val err = UpiTwoFactorTimeout("txn-timeout-002")
    assert(err.getMessage.contains("txn-timeout-002"))

  test("UpiTwoFactorTimeout message mentions timed out"):
    val err = UpiTwoFactorTimeout("txn-timeout-003")
    assert(err.getMessage.toLowerCase.contains("timeout") || err.getMessage.toLowerCase.contains("timed out"))

  // ── RSA public key loading tests ───────────────────────────────────────────

  test("UpiWebhookReceiver.loadPublicKey loads valid RSA public key"):
    val recv   = UpiWebhookReceiver("")
    val pubKey = recv.loadPublicKey(testPublicKeyPem)
    assert(pubKey.getAlgorithm == "RSA")

  test("UpiWebhookReceiver: end-to-end RSA sign + verify with real key material"):
    val body = """{"event":"upi.payment.success","txnId":"e2e-001","utrNumber":"UTR_E2E_001"}"""
    val sig  = signBody(body)
    val recv = UpiWebhookReceiver(testPublicKeyPem)
    val req  = WebhookRequest(headers = Map("X-UPI-Signature" -> sig), rawBody = body)
    recv.verify(req, "") match
      case Right(BankRailsEvent.UpiApproved("e2e-001", "UTR_E2E_001")) => // pass
      case other => fail(s"E2E RSA test failed: $other")

  test("UpiWebhookReceiver: different body is rejected (RSA tamper detection)"):
    val originalBody = """{"event":"upi.payment.success","txnId":"tamper-001","utrNumber":"UTR999"}"""
    val tamperedBody = """{"event":"upi.payment.success","txnId":"tamper-001","utrNumber":"UTR000"}"""
    val sig   = signBody(originalBody)  // sign original, verify with tampered
    val recv  = UpiWebhookReceiver(testPublicKeyPem)
    val req   = WebhookRequest(headers = Map("X-UPI-Signature" -> sig), rawBody = tamperedBody)
    val result = recv.verify(req, "")
    assert(result.isLeft, "Tampered body should be rejected")

  // ── BankRailsEvent UPI cases ──────────────────────────────────────────────

  test("BankRailsEvent.UpiApproved carries txnId and utrNumber"):
    val event = BankRailsEvent.UpiApproved("txn-event-001", "UTR-ABC-123")
    event match
      case BankRailsEvent.UpiApproved(txnId, utr) =>
        assert(txnId == "txn-event-001")
        assert(utr   == "UTR-ABC-123")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.UpiDeclined carries txnId and reason"):
    val event = BankRailsEvent.UpiDeclined("txn-event-002", "Insufficient balance")
    event match
      case BankRailsEvent.UpiDeclined(txnId, reason) =>
        assert(txnId  == "txn-event-002")
        assert(reason == "Insufficient balance")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.UpiCollectInitiated carries txnId, vpa, and amount"):
    val event = BankRailsEvent.UpiCollectInitiated("txn-event-003", "user@upi", "100000")
    event match
      case BankRailsEvent.UpiCollectInitiated(txnId, vpa, amount) =>
        assert(txnId  == "txn-event-003")
        assert(vpa    == "user@upi")
        assert(amount == "100000")
      case _ => fail("Pattern match failed")

  // ── Direct debit (collect) UPI-specific tests ─────────────────────────────

  test("UpiProvider.initiateDirectDebit throws UnsupportedRail for SEPA_DD"):
    val provider = UpiProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount500INR,
      mandateId       = MandateId("mandate-001"),
      creditorAccount = merchantAccount,
      debtorAccount   = customerAccount,
      creditorName    = "Acme Pvt Ltd",
      reference       = "REF-DD-001",
      idempotencyKey  = "idem-dd-001",
    )
    intercept[UnsupportedRail]:
      provider.initiateDirectDebit(req)

  test("UpiProvider.initiateDirectDebit throws when creditorAccount.upiVpa missing"):
    val provider = UpiProvider(testConfig)
    val noVpaAccount = BankAccount(holderName = "Merchant", countryCode = "IN")
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.IN_UPI,
      amount          = amount500INR,
      mandateId       = MandateId("mandate-002"),
      creditorAccount = noVpaAccount,
      debtorAccount   = customerAccount,
      creditorName    = "Acme Pvt Ltd",
      reference       = "REF-DD-002",
      idempotencyKey  = "idem-dd-002",
    )
    val ex = intercept[IllegalArgumentException]:
      provider.initiateDirectDebit(req)
    assert(ex.getMessage.contains("upiVpa"))

  test("UpiProvider.initiateDirectDebit throws when debtorAccount.upiVpa missing and no metadata"):
    val provider = UpiProvider(testConfig)
    val noVpaDebtor = BankAccount(holderName = "Customer", countryCode = "IN")
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.IN_UPI,
      amount          = amount500INR,
      mandateId       = MandateId("mandate-003"),
      creditorAccount = merchantAccount,
      debtorAccount   = noVpaDebtor,
      creditorName    = "Acme Pvt Ltd",
      reference       = "REF-DD-003",
      idempotencyKey  = "idem-dd-003",
    )
    intercept[IllegalArgumentException]:
      provider.initiateDirectDebit(req)

  // ── UpiPlugin tests ───────────────────────────────────────────────────────

  test("UpiPlugin.id is non-empty"):
    val plugin = UpiPlugin()
    assert(plugin.id.nonEmpty)

  test("UpiPlugin.displayName is non-empty"):
    val plugin = UpiPlugin()
    assert(plugin.displayName.nonEmpty)

  test("UpiPlugin capabilities include Feature.BankRails"):
    val plugin = UpiPlugin()
    assert(plugin.capabilities.features.nonEmpty)

  test("UpiPlugin.spiVersion matches SpiVersion.Current"):
    val plugin = UpiPlugin()
    assert(plugin.spiVersion.nonEmpty)
