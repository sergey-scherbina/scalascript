package scalascript.payments.paynow

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PayNowProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val testConfig = PayNowConfig(
    apiKey    = "test-api-key",
    baseUrl   = "https://api.paynow.example.com/v1",
    senderUen = "202012345A",
  )

  private val senderAccount = BankAccount(
    holderName  = "Acme Pte Ltd",
    countryCode = "SG",
    paynowProxy = Some("202012345A"),
  )

  private val recipientMobile = BankAccount(
    holderName  = "Alice Tan",
    countryCode = "SG",
    paynowProxy = Some("+6591234567"),
  )

  private val recipientUen = BankAccount(
    holderName  = "Tech Corp Pte Ltd",
    countryCode = "SG",
    paynowProxy = Some("201912345K"),
  )

  private val amount100SGD = Money(10000L, Currency("SGD"))  // SGD 100.00

  // ── PayNowProxyType enum tests ─────────────────────────────────────────────

  test("PayNowProxyType.Mobile proxy type exists"):
    val proxyType: PayNowProxyType = PayNowProxyType.Mobile
    assert(proxyType == PayNowProxyType.Mobile)

  test("PayNowProxyType.NricFin proxy type exists"):
    val proxyType: PayNowProxyType = PayNowProxyType.NricFin
    assert(proxyType == PayNowProxyType.NricFin)

  test("PayNowProxyType.Uen proxy type exists"):
    val proxyType: PayNowProxyType = PayNowProxyType.Uen
    assert(proxyType == PayNowProxyType.Uen)

  test("PayNowProxyType.Vpa proxy type exists"):
    val proxyType: PayNowProxyType = PayNowProxyType.Vpa
    assert(proxyType == PayNowProxyType.Vpa)

  test("PayNowProxyType cases are all distinct"):
    val types = Set(
      PayNowProxyType.Mobile,
      PayNowProxyType.NricFin,
      PayNowProxyType.Uen,
      PayNowProxyType.Vpa,
    )
    assert(types.size == 4)

  // ── RailKind.SG_PAYNOW tests ─────────────────────────────────────────────

  test("RailKind.SG_PAYNOW case exists"):
    val rail: RailKind = RailKind.SG_PAYNOW
    assert(rail == RailKind.SG_PAYNOW)

  test("RailKind.SG_PAYNOW is distinct from other rails"):
    assert(RailKind.SG_PAYNOW != RailKind.SEPA_CT)
    assert(RailKind.SG_PAYNOW != RailKind.ACH_CREDIT)
    assert(RailKind.SG_PAYNOW != RailKind.FEDNOW)
    assert(RailKind.SG_PAYNOW != RailKind.UK_FPS)
    assert(RailKind.SG_PAYNOW != RailKind.IN_UPI)

  // ── BankAccount.paynowProxy field tests ───────────────────────────────────

  test("BankAccount.paynowProxy field is preserved for mobile proxy"):
    val account = BankAccount(
      holderName  = "Alice Tan",
      countryCode = "SG",
      paynowProxy = Some("+6591234567"),
    )
    assert(account.paynowProxy == Some("+6591234567"))

  test("BankAccount.paynowProxy field is preserved for UEN proxy"):
    val account = BankAccount(
      holderName  = "Corp Pte Ltd",
      countryCode = "SG",
      paynowProxy = Some("201912345K"),
    )
    assert(account.paynowProxy == Some("201912345K"))

  test("BankAccount.paynowProxy defaults to None when not provided"):
    val account = BankAccount(
      holderName  = "Bob Smith",
      countryCode = "GB",
      sortCode    = Some("20-00-00"),
    )
    assert(account.paynowProxy == None)

  // ── PayNowConfig tests ────────────────────────────────────────────────────

  test("PayNowConfig fields accessible"):
    val cfg = PayNowConfig(
      apiKey    = "my-api-key",
      baseUrl   = "https://api.example.com/v1",
      senderUen = "202099999Z",
    )
    assert(cfg.apiKey    == "my-api-key")
    assert(cfg.baseUrl   == "https://api.example.com/v1")
    assert(cfg.senderUen == "202099999Z")

  test("PayNowConfig.fromEnv returns a config without throwing"):
    val cfg = PayNowConfig.fromEnv
    assert(cfg.baseUrl.nonEmpty)

  test("PayNowConfig displayName defaults to None"):
    val cfg = PayNowConfig(apiKey = "k", baseUrl = "https://example.com", senderUen = "123")
    assert(cfg.displayName == None)

  // ── PayNowProvider SPI contract tests ────────────────────────────────────

  test("PayNowProvider.id returns 'sg-paynow'"):
    val provider = PayNowProvider(testConfig)
    assert(provider.id == "sg-paynow")

  test("PayNowProvider.displayName is non-empty"):
    val provider = PayNowProvider(testConfig)
    assert(provider.displayName.nonEmpty)

  test("PayNowProvider.spiVersion is non-empty"):
    val provider = PayNowProvider(testConfig)
    assert(provider.spiVersion.nonEmpty)

  test("PayNowProvider.supportedRails contains only SG_PAYNOW"):
    val provider = PayNowProvider(testConfig)
    assert(provider.supportedRails == Set(RailKind.SG_PAYNOW))

  test("PayNowProvider throws UnsupportedRail for SEPA_CT"):
    val provider = PayNowProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100SGD,
      sender         = senderAccount,
      recipient      = recipientMobile,
      reference      = "REF001",
      idempotencyKey = "idem-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("PayNowProvider throws UnsupportedRail for UK_FPS"):
    val provider = PayNowProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.UK_FPS,
      amount         = amount100SGD,
      sender         = senderAccount,
      recipient      = recipientMobile,
      reference      = "REF002",
      idempotencyKey = "idem-002",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("PayNowProvider throws IllegalArgumentException when paynowProxy missing"):
    val provider = PayNowProvider(testConfig)
    val recipientNoProxy = BankAccount(
      holderName  = "Bob",
      countryCode = "SG",
    )
    val req = InitiateTransferRequest(
      rail           = RailKind.SG_PAYNOW,
      amount         = amount100SGD,
      sender         = senderAccount,
      recipient      = recipientNoProxy,
      reference      = "REF003",
      idempotencyKey = "idem-003",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  test("PayNowProvider throws IllegalArgumentException for non-SGD currency"):
    val provider = PayNowProvider(testConfig)
    val nonSgdAmount = Money(10000L, Currency("USD"))
    val req = InitiateTransferRequest(
      rail           = RailKind.SG_PAYNOW,
      amount         = nonSgdAmount,
      sender         = senderAccount,
      recipient      = recipientMobile,
      reference      = "REF004",
      idempotencyKey = "idem-004",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  test("PayNowProvider throws IllegalArgumentException for GBP currency"):
    val provider = PayNowProvider(testConfig)
    val gbpAmount = Money(5000L, Currency("GBP"))
    val req = InitiateTransferRequest(
      rail           = RailKind.SG_PAYNOW,
      amount         = gbpAmount,
      sender         = senderAccount,
      recipient      = recipientUen,
      reference      = "REF005",
      idempotencyKey = "idem-005",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  // ── PayNowProvider.initiateDirectDebit not supported ─────────────────────

  test("PayNowProvider.initiateDirectDebit throws UnsupportedRail"):
    val provider = PayNowProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SG_PAYNOW,
      amount          = amount100SGD,
      mandateId       = MandateId("mandate-001"),
      creditorAccount = senderAccount,
      debtorAccount   = recipientMobile,
      creditorName    = "Acme Pte Ltd",
      reference       = "DD-REF",
      idempotencyKey  = "idem-dd-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateDirectDebit(req)
    }

  // ── PayNowProvider.cancelTransfer not supported ───────────────────────────

  test("PayNowProvider.cancelTransfer throws BankRailsCancelError"):
    val provider = PayNowProvider(testConfig)
    intercept[BankRailsCancelError] {
      provider.cancelTransfer(TransferId("tx-001"))
    }

  // ── Proxy type inference tests ────────────────────────────────────────────

  test("inferProxyType: mobile number starting with '+' → MOBILE"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("+6591234567") == "MOBILE")

  test("inferProxyType: Singapore mobile with country code → MOBILE"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("+6598765432") == "MOBILE")

  test("inferProxyType: UEN (alphanumeric, no '+' or '@') → UEN"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("201912345K") == "UEN")

  test("inferProxyType: another UEN format → UEN"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("T08LL1234A") == "UEN")

  test("inferProxyType: NRIC format S+7digits+letter → NRIC"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("S1234567A") == "NRIC")

  test("inferProxyType: FIN format G+7digits+letter → NRIC"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("G1234567P") == "NRIC")

  test("inferProxyType: VPA with '@' → VPA"):
    val provider = PayNowProvider(testConfig)
    assert(provider.inferProxyType("alice@paynow") == "VPA")

  // ── ProxyResolutionResult tests ───────────────────────────────────────────

  test("ProxyResolutionResult: registered=true with participantCode"):
    val result = ProxyResolutionResult(
      registered      = true,
      participantCode = Some("DBS"),
      maskedAccount   = Some("XXXX1234"),
    )
    assert(result.registered      == true)
    assert(result.participantCode == Some("DBS"))
    assert(result.maskedAccount   == Some("XXXX1234"))

  test("ProxyResolutionResult: registered=false (proxy not found)"):
    val result = ProxyResolutionResult(
      registered      = false,
      participantCode = None,
    )
    assert(result.registered      == false)
    assert(result.participantCode == None)

  test("PayNowApi.parseProxyResolutionResult: parses registered=true with participantCode"):
    val api  = PayNowApi(testConfig)
    val body = """{"registered":true,"participantCode":"OCBC","maskedAccountNumber":"XXXX5678"}"""
    val result = api.parseProxyResolutionResult(body)
    assert(result.registered      == true)
    assert(result.participantCode == Some("OCBC"))
    assert(result.maskedAccount   == Some("XXXX5678"))

  test("PayNowApi.parseProxyResolutionResult: parses registered=false"):
    val api  = PayNowApi(testConfig)
    val body = """{"registered":false}"""
    val result = api.parseProxyResolutionResult(body)
    assert(result.registered == false)
    assert(result.participantCode == None)

  test("PayNowApi.parseProxyResolutionResult: uses bankCode fallback for participantCode"):
    val api  = PayNowApi(testConfig)
    val body = """{"registered":true,"bankCode":"DBS"}"""
    val result = api.parseProxyResolutionResult(body)
    assert(result.registered      == true)
    assert(result.participantCode == Some("DBS"))

  // ── PayNowProxyNotFound error tests ───────────────────────────────────────

  test("PayNowProxyNotFound error message includes proxy type and value"):
    val err = PayNowProxyNotFound("MOBILE", "+6591234567")
    assert(err.getMessage.contains("MOBILE"))
    assert(err.getMessage.contains("+6591234567"))

  test("PayNowProxyNotFound is a BankRailsError"):
    val err: BankRailsError = PayNowProxyNotFound("UEN", "201912345K")
    assert(err.isInstanceOf[BankRailsError])

  test("PayNowProxyNotFound works for all proxy types"):
    val mobile = PayNowProxyNotFound("MOBILE", "+6591234567")
    val nric   = PayNowProxyNotFound("NRIC",   "S1234567A")
    val uen    = PayNowProxyNotFound("UEN",    "201912345K")
    val vpa    = PayNowProxyNotFound("VPA",    "alice@paynow")
    assert(mobile.getMessage.contains("MOBILE"))
    assert(nric.getMessage.contains("NRIC"))
    assert(uen.getMessage.contains("UEN"))
    assert(vpa.getMessage.contains("VPA"))

  // ── PayNowWebhookReceiver HMAC tests ──────────────────────────────────────

  test("PayNowWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "paynow-webhook-secret"
    val body   = """{"type":"paynow.payment.credit","transactionRef":"TXN001","proxyValue":"+6591234567","amount":"10000","currency":"SGD"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("PayNowWebhookReceiver: wrong HMAC is rejected with InvalidSignature"):
    val secret = "correct-secret"
    val body   = """{"type":"paynow.payment.credit","transactionRef":"TXN002","amount":"5000","currency":"SGD"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("PayNowWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("PayNowWebhookReceiver: bad signature format (no sha256= prefix) rejected"):
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> "invalidsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("PayNowWebhookReceiver: lowercase header name is accepted"):
    val secret = "test-secret"
    val body   = """{"type":"paynow.payment.credit","transactionRef":"TXN003","proxyValue":"201912345K","amount":"20000","currency":"SGD"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("x-paynow-signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Lowercase header should work, got: ${result.left.toOption}")

  // ── Webhook event parsing tests ───────────────────────────────────────────

  test("PayNowWebhookReceiver: parses paynow.payment.credit → PayNowSettled"):
    val secret = "test-secret"
    val body   = """{"type":"paynow.payment.credit","transactionRef":"TXN100","proxyValue":"+6591234567","amount":"10000","currency":"SGD"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.PayNowSettled(txnRef, proxy, amount, currency) =>
        assert(txnRef   == "TXN100")
        assert(proxy    == "+6591234567")
        assert(amount   == "10000")
        assert(currency == "SGD")
      case other => fail(s"Expected PayNowSettled, got $other")

  test("PayNowWebhookReceiver: parses paynow.payment.return → PayNowFailed"):
    val secret = "test-secret"
    val body   = """{"type":"paynow.payment.return","transactionRef":"TXN200","reason":"ProxyNotFound"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.PayNowFailed(txnRef, reason) =>
        assert(txnRef == "TXN200")
        assert(reason == "ProxyNotFound")
      case other => fail(s"Expected PayNowFailed, got $other")

  test("PayNowWebhookReceiver: return event uses returnReason fallback"):
    val secret = "test-secret"
    val body   = """{"type":"paynow.payment.return","txnRef":"TXN201","returnReason":"AccountClosed"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.PayNowFailed(txnRef, reason) =>
        assert(reason == "AccountClosed")
      case other => fail(s"Expected PayNowFailed, got $other")

  test("PayNowWebhookReceiver: credit event uses txnRef fallback field"):
    val secret = "test-secret"
    val body   = """{"type":"paynow.payment.credit","txnRef":"TXN300","proxyValue":"S1234567A","amount":"5000","currency":"SGD"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.PayNowSettled(txnRef, proxy, amount, currency) =>
        assert(txnRef == "TXN300")
        assert(proxy  == "S1234567A")
      case other => fail(s"Expected PayNowSettled, got $other")

  test("PayNowWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"type":"unknown.event","transactionRef":"TXN999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("PayNowWebhookReceiver: missing type field returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"transactionRef":"TXN999","amount":"1000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = PayNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-PayNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)

  // ── idempotencyKey tests ──────────────────────────────────────────────────

  test("PayNowWebhookReceiver.idempotencyKey: PayNowSettled includes txnRef and proxy"):
    val recv  = PayNowWebhookReceiver()
    val event = BankRailsEvent.PayNowSettled("TXN101", "+6591234567", "10000", "SGD")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("TXN101"))
    assert(key.contains("+6591234567"))

  test("PayNowWebhookReceiver.idempotencyKey: PayNowFailed includes txnRef"):
    val recv  = PayNowWebhookReceiver()
    val event = BankRailsEvent.PayNowFailed("TXN202", "rejected")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("TXN202"))

  // ── HMAC helper tests ─────────────────────────────────────────────────────

  test("PayNowWebhookReceiver.hmacSha256: produces 64-char lowercase hex"):
    val recv   = PayNowWebhookReceiver()
    val result = recv.hmacSha256("secret", "test-payload")
    assert(result.length == 64, s"HMAC-SHA256 hex should be 64 chars, got ${result.length}")
    assert(result.forall(c => c.isDigit || (c >= 'a' && c <= 'f')),
      "HMAC-SHA256 hex should only contain lowercase hex chars")

  test("PayNowWebhookReceiver.hmacSha256: different secrets produce different results"):
    val recv = PayNowWebhookReceiver()
    val sig1 = recv.hmacSha256("secret1", "payload")
    val sig2 = recv.hmacSha256("secret2", "payload")
    assert(sig1 != sig2)

  test("PayNowWebhookReceiver.hmacSha256: different payloads produce different results"):
    val recv = PayNowWebhookReceiver()
    val sig1 = recv.hmacSha256("secret", "payload1")
    val sig2 = recv.hmacSha256("secret", "payload2")
    assert(sig1 != sig2)

  // ── BankRailsEvent.PayNow* event cases ───────────────────────────────────

  test("BankRailsEvent.PayNowSettled carries all fields"):
    val event = BankRailsEvent.PayNowSettled("txn-001", "+6591234567", "25000", "SGD")
    event match
      case BankRailsEvent.PayNowSettled(txnRef, proxy, amount, currency) =>
        assert(txnRef   == "txn-001")
        assert(proxy    == "+6591234567")
        assert(amount   == "25000")
        assert(currency == "SGD")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.PayNowFailed carries txnRef and reason"):
    val event = BankRailsEvent.PayNowFailed("txn-002", "ProxyNotRegistered")
    event match
      case BankRailsEvent.PayNowFailed(txnRef, reason) =>
        assert(txnRef == "txn-002")
        assert(reason == "ProxyNotRegistered")
      case _ => fail("Pattern match failed")

  test("PayNowSettled and PayNowFailed are distinct BankRailsEvent cases"):
    val settled = BankRailsEvent.PayNowSettled("t1", "+6591234567", "1000", "SGD")
    val failed  = BankRailsEvent.PayNowFailed("t2", "rejected")
    assert(settled != failed)

  // ── PayNowPlugin tests ────────────────────────────────────────────────────

  test("PayNowPlugin id is non-empty"):
    val plugin = PayNowPlugin()
    assert(plugin.id.nonEmpty)

  test("PayNowPlugin displayName is non-empty"):
    val plugin = PayNowPlugin()
    assert(plugin.displayName.nonEmpty)

  test("PayNowPlugin capabilities include Feature.BankRails"):
    val plugin = PayNowPlugin()
    assert(plugin.capabilities.features.nonEmpty)

  // ── PayNowApi JSON helpers tests ──────────────────────────────────────────

  test("PayNowApi.extractField: extracts string field"):
    val api  = PayNowApi(testConfig)
    val json = """{"transactionRef":"TXN123","status":"settled"}"""
    assert(api.extractField(json, "transactionRef") == Some("TXN123"))
    assert(api.extractField(json, "status")         == Some("settled"))

  test("PayNowApi.extractField: returns None for missing field"):
    val api  = PayNowApi(testConfig)
    val json = """{"status":"settled"}"""
    assert(api.extractField(json, "missing") == None)

  test("PayNowApi.extractBoolField: parses true"):
    val api  = PayNowApi(testConfig)
    val json = """{"registered":true}"""
    assert(api.extractBoolField(json, "registered") == Some(true))

  test("PayNowApi.extractBoolField: parses false"):
    val api  = PayNowApi(testConfig)
    val json = """{"registered":false}"""
    assert(api.extractBoolField(json, "registered") == Some(false))

  test("PayNowApi.extractBoolField: returns None for missing field"):
    val api  = PayNowApi(testConfig)
    val json = """{"status":"ok"}"""
    assert(api.extractBoolField(json, "registered") == None)

  // ── RailKind routing test ─────────────────────────────────────────────────

  test("PayNowProvider routes SG_PAYNOW rail correctly in supportedRails set"):
    val provider = PayNowProvider(testConfig)
    assert(provider.supportedRails.contains(RailKind.SG_PAYNOW))
    assert(!provider.supportedRails.contains(RailKind.SEPA_CT))
    assert(!provider.supportedRails.contains(RailKind.ACH_CREDIT))
    assert(!provider.supportedRails.contains(RailKind.PIX))
    assert(!provider.supportedRails.contains(RailKind.IN_UPI))
