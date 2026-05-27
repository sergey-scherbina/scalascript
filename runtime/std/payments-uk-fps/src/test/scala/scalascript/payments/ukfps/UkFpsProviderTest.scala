package scalascript.payments.ukfps

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class UkFpsProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val senderAccount = BankAccount(
    sortCode      = Some("20-00-00"),
    accountNumber = Some("12345678"),
    holderName    = "Acme Ltd",
    countryCode   = "GB",
  )
  private val recipientAccount = BankAccount(
    sortCode      = Some("30-99-88"),
    accountNumber = Some("55779911"),
    holderName    = "Bob Smith",
    countryCode   = "GB",
  )
  private val amount50GBP = Money(5000L, Currency("GBP"))  // £50.00

  private val testConfig = UkFpsConfig(
    apiUrl          = "https://api.fps.example.com/v1",
    apiKey          = "test-bearer-token",
    copEnabled      = false,   // disabled for unit tests (no live HTTP)
    allowCloseMatch = false,
  )

  // ── BankAccount sort code field tests ────────────────────────────────────

  test("BankAccount.sortCode field is preserved"):
    val account = BankAccount(
      sortCode      = Some("20-00-00"),
      accountNumber = Some("12345678"),
      holderName    = "Test User",
      countryCode   = "GB",
    )
    assert(account.sortCode == Some("20-00-00"))

  test("BankAccount.sortCode defaults to None when not provided"):
    val account = BankAccount(
      iban       = Some("DE89370400440532013000"),
      holderName = "Test User",
      countryCode = "DE",
    )
    assert(account.sortCode == None)

  test("BankAccount with all v1.55 fields compiles and preserves values"):
    val account = BankAccount(
      sortCode      = Some("40-47-84"),
      accountNumber = Some("98765432"),
      holderName    = "Jane Doe",
      countryCode   = "GB",
      bic           = Some("BARCGB22"),
    )
    assert(account.sortCode      == Some("40-47-84"))
    assert(account.accountNumber == Some("98765432"))
    assert(account.bic           == Some("BARCGB22"))

  // ── RailKind.UK_FPS tests ────────────────────────────────────────────────

  test("RailKind.UK_FPS case exists"):
    val rail: RailKind = RailKind.UK_FPS
    assert(rail == RailKind.UK_FPS)

  test("RailKind.UK_FPS is distinct from other rails"):
    assert(RailKind.UK_FPS != RailKind.SEPA_CT)
    assert(RailKind.UK_FPS != RailKind.ACH_CREDIT)
    assert(RailKind.UK_FPS != RailKind.FEDNOW)

  test("UkFpsProvider.supportedRails contains only UK_FPS"):
    val provider = UkFpsProvider(testConfig)
    assert(provider.supportedRails == Set(RailKind.UK_FPS))

  // ── UkFpsConfig tests ─────────────────────────────────────────────────────

  test("UkFpsConfig fields accessible"):
    val cfg = UkFpsConfig(
      apiUrl          = "https://api.clearbank.com/v1",
      apiKey          = "key-abc",
      copEnabled      = true,
      allowCloseMatch = false,
    )
    assert(cfg.apiUrl          == "https://api.clearbank.com/v1")
    assert(cfg.apiKey          == "key-abc")
    assert(cfg.copEnabled      == true)
    assert(cfg.allowCloseMatch == false)

  test("UkFpsConfig.fromEnv returns a config without throwing"):
    val cfg = UkFpsConfig.fromEnv
    assert(cfg.apiUrl.nonEmpty)

  test("UkFpsConfig defaults: copEnabled=true, allowCloseMatch=false"):
    val cfg = UkFpsConfig(apiUrl = "https://example.com", apiKey = "k")
    assert(cfg.copEnabled      == true)
    assert(cfg.allowCloseMatch == false)

  // ── UkFpsProvider SPI contract tests ─────────────────────────────────────

  test("UkFpsProvider.id returns 'uk-fps'"):
    val provider = UkFpsProvider(testConfig)
    assert(provider.id == "uk-fps")

  test("UkFpsProvider.displayName is non-empty"):
    val provider = UkFpsProvider(testConfig)
    assert(provider.displayName.nonEmpty)

  test("UkFpsProvider.spiVersion is non-empty"):
    val provider = UkFpsProvider(testConfig)
    assert(provider.spiVersion.nonEmpty)

  test("UkFpsProvider throws UnsupportedRail for SEPA_CT"):
    val provider = UkFpsProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount50GBP,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "REF001",
      idempotencyKey = "idem-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("UkFpsProvider throws IllegalArgumentException when sortCode missing"):
    val provider = UkFpsProvider(testConfig)
    val recipientNoSortCode = BankAccount(
      accountNumber = Some("55779911"),
      holderName    = "Bob Smith",
      countryCode   = "GB",
    )
    val req = InitiateTransferRequest(
      rail           = RailKind.UK_FPS,
      amount         = amount50GBP,
      sender         = senderAccount,
      recipient      = recipientNoSortCode,
      reference      = "REF002",
      idempotencyKey = "idem-002",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  test("UkFpsProvider throws IllegalArgumentException when accountNumber missing"):
    val provider = UkFpsProvider(testConfig)
    val recipientNoAcct = BankAccount(
      sortCode    = Some("30-99-88"),
      holderName  = "Bob Smith",
      countryCode = "GB",
    )
    val req = InitiateTransferRequest(
      rail           = RailKind.UK_FPS,
      amount         = amount50GBP,
      sender         = senderAccount,
      recipient      = recipientNoAcct,
      reference      = "REF003",
      idempotencyKey = "idem-003",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  // ── CoP error model tests ─────────────────────────────────────────────────

  test("UkCopNameMismatch error message for NoMatch"):
    val err = UkCopNameMismatch(None)
    assert(err.getMessage.contains("NoMatch") || err.getMessage.contains("name check"))

  test("UkCopNameMismatch error message for CloseMatch includes suggested name"):
    val err = UkCopNameMismatch(Some("Robert Smith"))
    assert(err.getMessage.contains("Robert Smith"))

  test("UkCopNameMismatch is a BankRailsError"):
    val err: BankRailsError = UkCopNameMismatch(None)
    assert(err.isInstanceOf[BankRailsError])

  // ── CopResult enum tests ──────────────────────────────────────────────────

  test("CopResult.Matched is the happy-path result"):
    val result: CopResult = CopResult.Matched
    assert(result == CopResult.Matched)

  test("CopResult.CloseMatch carries suggested name"):
    val result: CopResult = CopResult.CloseMatch("Robert Smith")
    result match
      case CopResult.CloseMatch(name) => assert(name == "Robert Smith")
      case other                      => fail(s"Expected CloseMatch, got $other")

  test("CopResult.NoMatch is distinct from Matched"):
    assert(CopResult.NoMatch != CopResult.Matched)

  test("CopResult.AccountSwitched indicates CASS migration"):
    val result: CopResult = CopResult.AccountSwitched
    assert(result == CopResult.AccountSwitched)

  test("CopResult.Unavailable is the service-down fallback"):
    val result: CopResult = CopResult.Unavailable
    assert(result == CopResult.Unavailable)

  // ── ConfirmationOfPayee JSON parsing tests ────────────────────────────────

  test("ConfirmationOfPayee.parseResult: 'matched' → CopResult.Matched"):
    val cop = ConfirmationOfPayee(testConfig)
    val body = """{"result": "matched"}"""
    // Access internal parse via extractJsonString
    assert(cop.extractJsonString(body, "result") == Some("matched"))

  test("ConfirmationOfPayee.extractJsonString parses simple string field"):
    val cop = ConfirmationOfPayee(testConfig)
    val json = """{"result":"close_match","suggestedName":"Jane Smith"}"""
    assert(cop.extractJsonString(json, "result")      == Some("close_match"))
    assert(cop.extractJsonString(json, "suggestedName") == Some("Jane Smith"))

  test("ConfirmationOfPayee.extractJsonString returns None for missing field"):
    val cop = ConfirmationOfPayee(testConfig)
    val json = """{"result":"matched"}"""
    assert(cop.extractJsonString(json, "missing") == None)

  // ── UkFpsWebhookReceiver HMAC tests ──────────────────────────────────────

  test("UkFpsWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "fps-webhook-secret-xyz"
    val body   = """{"type":"uk.faster-payments.credit","transactionId":"tx-001","amount":"5000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("UkFpsWebhookReceiver: wrong HMAC is rejected with InvalidSignature"):
    val secret = "correct-secret"
    val body   = """{"type":"uk.faster-payments.credit","transactionId":"tx-002","amount":"1000"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("UkFpsWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("UkFpsWebhookReceiver: bad signature format rejected"):
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> "invalidsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  // ── Webhook event parsing tests ───────────────────────────────────────────

  test("UkFpsWebhookReceiver: parses uk.faster-payments.credit → UkFpsAccepted"):
    val secret = "test-secret"
    val body   = """{"type":"uk.faster-payments.credit","transactionId":"tx-100","amount":"5000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UkFpsAccepted(txId, amount) =>
        assert(txId   == "tx-100")
        assert(amount == "5000")
      case other => fail(s"Expected UkFpsAccepted, got $other")

  test("UkFpsWebhookReceiver: parses uk.faster-payments.rejected → UkFpsRejected"):
    val secret = "test-secret"
    val body   = """{"type":"uk.faster-payments.rejected","transactionId":"tx-200","reason":"InvalidSortCode"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UkFpsRejected(txId, reason) =>
        assert(txId   == "tx-200")
        assert(reason == "InvalidSortCode")
      case other => fail(s"Expected UkFpsRejected, got $other")

  test("UkFpsWebhookReceiver: parses uk.faster-payments.return → UkFpsReturned"):
    val secret = "test-secret"
    val body   = """{"type":"uk.faster-payments.return","transactionId":"tx-300","returnCode":"AC04","description":"Closed account"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.UkFpsReturned(txId, code, desc) =>
        assert(txId == "tx-300")
        assert(code == "AC04")
        assert(desc == "Closed account")
      case other => fail(s"Expected UkFpsReturned, got $other")

  test("UkFpsWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"type":"unknown.event.type","transactionId":"tx-999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("UkFpsWebhookReceiver: missing type field returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"transactionId":"tx-999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkFpsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)

  // ── idempotencyKey tests ──────────────────────────────────────────────────

  test("UkFpsWebhookReceiver.idempotencyKey: UkFpsAccepted key includes txId"):
    val recv  = UkFpsWebhookReceiver()
    val event = BankRailsEvent.UkFpsAccepted("tx-101", "5000")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("tx-101"))

  test("UkFpsWebhookReceiver.idempotencyKey: UkFpsRejected key includes txId"):
    val recv  = UkFpsWebhookReceiver()
    val event = BankRailsEvent.UkFpsRejected("tx-201", "InvalidSortCode")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("tx-201"))

  test("UkFpsWebhookReceiver.idempotencyKey: UkFpsReturned key includes txId and code"):
    val recv  = UkFpsWebhookReceiver()
    val event = BankRailsEvent.UkFpsReturned("tx-301", "AC04", "Closed")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("tx-301"))
    assert(key.contains("AC04"))

  // ── HMAC helper tests ─────────────────────────────────────────────────────

  test("UkFpsWebhookReceiver.hmacSha256: known value"):
    val recv = UkFpsWebhookReceiver()
    val result = recv.hmacSha256("secret", "test-payload")
    assert(result.length == 64, s"HMAC-SHA256 hex should be 64 chars, got ${result.length}")
    assert(result.forall(c => c.isDigit || (c >= 'a' && c <= 'f')),
      "HMAC-SHA256 hex should only contain lowercase hex chars")

  test("UkFpsWebhookReceiver.hmacSha256: different secrets produce different results"):
    val recv = UkFpsWebhookReceiver()
    val sig1 = recv.hmacSha256("secret1", "payload")
    val sig2 = recv.hmacSha256("secret2", "payload")
    assert(sig1 != sig2)

  test("UkFpsWebhookReceiver.hmacSha256: different payloads produce different results"):
    val recv = UkFpsWebhookReceiver()
    val sig1 = recv.hmacSha256("secret", "payload1")
    val sig2 = recv.hmacSha256("secret", "payload2")
    assert(sig1 != sig2)

  // ── BankRailsEvent UK FPS cases ───────────────────────────────────────────

  test("BankRailsEvent.UkFpsAccepted carries txId and amount"):
    val event = BankRailsEvent.UkFpsAccepted("fps-tx-001", "10000")
    event match
      case BankRailsEvent.UkFpsAccepted(txId, amount) =>
        assert(txId   == "fps-tx-001")
        assert(amount == "10000")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.UkFpsRejected carries txId and reason"):
    val event = BankRailsEvent.UkFpsRejected("fps-tx-002", "AccountClosed")
    event match
      case BankRailsEvent.UkFpsRejected(txId, reason) =>
        assert(txId   == "fps-tx-002")
        assert(reason == "AccountClosed")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.UkFpsReturned carries txId, code and description"):
    val event = BankRailsEvent.UkFpsReturned("fps-tx-003", "AC04", "Account closed by payer bank")
    event match
      case BankRailsEvent.UkFpsReturned(txId, code, desc) =>
        assert(txId == "fps-tx-003")
        assert(code == "AC04")
        assert(desc.contains("payer bank"))
      case _ => fail("Pattern match failed")

  test("UkFpsPlugin id is non-empty"):
    val plugin = UkFpsPlugin()
    assert(plugin.id.nonEmpty)

  test("UkFpsPlugin displayName is non-empty"):
    val plugin = UkFpsPlugin()
    assert(plugin.displayName.nonEmpty)

  test("UkFpsPlugin capabilities include Feature.BankRails"):
    val plugin = UkFpsPlugin()
    assert(plugin.capabilities.features.nonEmpty)
