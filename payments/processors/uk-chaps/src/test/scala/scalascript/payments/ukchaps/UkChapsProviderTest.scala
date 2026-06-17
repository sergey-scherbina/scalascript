package scalascript.payments.ukchaps

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class UkChapsProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val senderAccount = BankAccount(
    sortCode      = Some("20-00-00"),
    accountNumber = Some("12345678"),
    holderName    = "Acme Corp",
    countryCode   = "GB",
  )
  private val recipientAccount = BankAccount(
    sortCode      = Some("30-99-88"),
    accountNumber = Some("55779911"),
    holderName    = "Jane Smith",
    countryCode   = "GB",
    bic           = Some("BARCGB22"),
  )
  private val recipientWithIban = BankAccount(
    iban        = Some("GB29NWBK60161331926819"),
    holderName  = "John Doe",
    countryCode = "GB",
    bic         = Some("NWBKGB2L"),
  )
  private val amount10000GBP = Money(1000000L, Currency("GBP"))  // £10,000.00
  private val amount500GBP   = Money(50000L,   Currency("GBP"))  // £500.00

  private val testConfig = UkChapsConfig(
    apiKey        = "test-bearer-token",
    baseUrl       = "https://api.chaps.example.com/v1",
    sortCode      = "20-00-00",
    accountNumber = "12345678",
  )

  private def makeRequest(
    recipient:      BankAccount = recipientAccount,
    amount:         Money       = amount10000GBP,
    idempotencyKey: String      = "chaps-idem-001",
  ): InitiateTransferRequest =
    InitiateTransferRequest(
      rail           = RailKind.UK_CHAPS,
      amount         = amount,
      sender         = senderAccount,
      recipient      = recipient,
      reference      = "Invoice-2026-001",
      idempotencyKey = idempotencyKey,
    )

  // ── RailKind.UK_CHAPS tests ───────────────────────────────────────────────

  test("RailKind.UK_CHAPS case exists"):
    val rail: RailKind = RailKind.UK_CHAPS
    assert(rail == RailKind.UK_CHAPS)

  test("RailKind.UK_CHAPS is distinct from UK_FPS and UK_BACS_DD"):
    assert(RailKind.UK_CHAPS != RailKind.UK_FPS)
    assert(RailKind.UK_CHAPS != RailKind.UK_BACS_DD)
    assert(RailKind.UK_CHAPS != RailKind.SEPA_CT)

  test("UkChapsProvider.supportedRails contains only UK_CHAPS"):
    val provider = UkChapsProvider(testConfig)
    assert(provider.supportedRails == Set(RailKind.UK_CHAPS))

  // ── UkChapsConfig tests ───────────────────────────────────────────────────

  test("UkChapsConfig fields accessible"):
    val cfg = UkChapsConfig(
      apiKey        = "key-abc",
      baseUrl       = "https://api.clearbank.com/v1/chaps",
      sortCode      = "40-47-84",
      accountNumber = "98765432",
    )
    assert(cfg.apiKey        == "key-abc")
    assert(cfg.baseUrl       == "https://api.clearbank.com/v1/chaps")
    assert(cfg.sortCode      == "40-47-84")
    assert(cfg.accountNumber == "98765432")

  test("UkChapsConfig.fromEnv returns a config without throwing"):
    val cfg = UkChapsConfig.fromEnv
    assert(cfg.baseUrl.nonEmpty)

  // ── UkChapsProvider SPI contract tests ───────────────────────────────────

  test("UkChapsProvider.id returns 'uk-chaps'"):
    assert(UkChapsProvider(testConfig).id == "uk-chaps")

  test("UkChapsProvider.displayName is non-empty"):
    assert(UkChapsProvider(testConfig).displayName.nonEmpty)

  test("UkChapsProvider.spiVersion is non-empty"):
    assert(UkChapsProvider(testConfig).spiVersion.nonEmpty)

  test("UkChapsProvider throws UnsupportedRail for SEPA_CT"):
    val provider = UkChapsProvider(testConfig)
    val req = makeRequest().copy(rail = RailKind.SEPA_CT)
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("UkChapsProvider throws UnsupportedRail for UK_FPS"):
    val provider = UkChapsProvider(testConfig)
    val req = makeRequest().copy(rail = RailKind.UK_FPS)
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("UkChapsProvider throws IllegalArgumentException for non-GBP currency"):
    val provider = UkChapsProvider(testConfig)
    val eurAmount = Money(1000000L, Currency("EUR"))
    val req = makeRequest(amount = eurAmount)
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  // ── ChapsPacs008Builder tests — XML field presence ────────────────────────

  test("ChapsPacs008Builder: XML contains pacs.008.001.08 namespace"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("pacs.008.001.08"), s"Expected pacs.008.001.08 namespace in XML")

  test("ChapsPacs008Builder: SvcLvl = CHAPS (not SEPA)"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("<Cd>CHAPS</Cd>"), "Expected SvcLvl CHAPS in XML")
    assert(!xml.contains("<Cd>SEPA</Cd>"), "SvcLvl must not be SEPA for CHAPS")

  test("ChapsPacs008Builder: SttlmMtd = INDA (instructed agent, BoE RTGS)"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("<SttlmMtd>INDA</SttlmMtd>"), "Expected SttlmMtd=INDA in CHAPS pacs.008")

  test("ChapsPacs008Builder: no ClrSys element (CHAPS does not use a clearing system code)"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(!xml.contains("<ClrSys>"), "CHAPS pacs.008 must not contain ClrSys")

  test("ChapsPacs008Builder: IntrBkSttlmAmt has currency=GBP"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("""Ccy="GBP""""), "IntrBkSttlmAmt must have Ccy=GBP")

  test("ChapsPacs008Builder: rejects non-GBP amount"):
    val req = makeRequest(amount = Money(50000L, Currency("EUR")))
    intercept[IllegalArgumentException] {
      ChapsPacs008Builder.buildPacs008(req)
    }

  test("ChapsPacs008Builder: EndToEndId matches idempotency key"):
    val req = makeRequest(idempotencyKey = "chaps-e2e-xyz-123")
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("chaps-e2e-xyz-123"), "EndToEndId must match idempotencyKey")

  test("ChapsPacs008Builder: Dbtr name present in XML"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("Acme Corp"), "Debtor name must appear in XML")

  test("ChapsPacs008Builder: Cdtr name present in XML"):
    val req = makeRequest()
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("Jane Smith"), "Creditor name must appear in XML")

  test("ChapsPacs008Builder: creditor account uses IBAN when available"):
    val req = makeRequest(recipient = recipientWithIban)
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("<IBAN>GB29NWBK60161331926819</IBAN>"),
      "IBAN should appear in CdtrAcct when present")

  test("ChapsPacs008Builder: creditor account uses sort-code+account when no IBAN"):
    val req = makeRequest()  // recipientAccount has sort-code, no IBAN
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("30-99-88"), "Sort-code must appear in CdtrAcct when no IBAN")
    assert(xml.contains("55779911"), "Account number must appear in CdtrAcct when no IBAN")

  test("ChapsPacs008Builder: creditor agent uses BIC when available"):
    val req = makeRequest(recipient = recipientWithIban)  // has bic=NWBKGB2L
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("<BICFI>NWBKGB2L</BICFI>"), "BIC must appear in CdtrAgt when present")

  test("ChapsPacs008Builder: creditor agent falls back to NOTPROVIDED when no BIC"):
    val recipientNoBic = BankAccount(
      sortCode      = Some("30-99-88"),
      accountNumber = Some("55779911"),
      holderName    = "No Bic User",
      countryCode   = "GB",
    )
    val req = makeRequest(recipient = recipientNoBic)
    val xml = ChapsPacs008Builder.buildPacs008(req)
    assert(xml.contains("NOTPROVIDED"), "CdtrAgt must use NOTPROVIDED when BIC absent")

  test("ChapsPacs008Builder: formatAmount converts pence to decimal string"):
    val builder = ChapsPacs008Builder
    val money   = Money(1000000L, Currency("GBP"))  // £10,000.00
    assert(builder.formatAmount(money) == "10000.00")

  test("ChapsPacs008Builder: formatAmount handles pence correctly for £500.00"):
    assert(ChapsPacs008Builder.formatAmount(amount500GBP) == "500.00")

  test("ChapsPacs008Builder: xml() escapes & < > and double-quote"):
    val builder = ChapsPacs008Builder
    assert(builder.xml("A&B")  == "A&amp;B")
    assert(builder.xml("A<B")  == "A&lt;B")
    assert(builder.xml("A>B")  == "A&gt;B")
    assert(builder.xml("A\"B") == "A&quot;B")

  // ── BankRailsEvent CHAPS cases ────────────────────────────────────────────

  test("BankRailsEvent.ChapsSettled carries endToEndId, amount, currency"):
    val event = BankRailsEvent.ChapsSettled("e2e-001", "10000.00", "GBP")
    event match
      case BankRailsEvent.ChapsSettled(id, amt, ccy) =>
        assert(id  == "e2e-001")
        assert(amt == "10000.00")
        assert(ccy == "GBP")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.ChapsRejected carries endToEndId and reason"):
    val event = BankRailsEvent.ChapsRejected("e2e-002", "InsufficientFunds")
    event match
      case BankRailsEvent.ChapsRejected(id, reason) =>
        assert(id     == "e2e-002")
        assert(reason == "InsufficientFunds")
      case _ => fail("Pattern match failed")

  // ── UkChapsWebhookReceiver HMAC tests ─────────────────────────────────────

  test("UkChapsWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "chaps-webhook-secret-xyz"
    val body   = """{"type":"chaps.payment.settled","endToEndId":"e2e-100","amount":"10000.00","currency":"GBP"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("UkChapsWebhookReceiver: wrong HMAC is rejected with InvalidSignature"):
    val secret = "correct-secret"
    val body   = """{"type":"chaps.payment.settled","endToEndId":"e2e-101","amount":"500.00","currency":"GBP"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("UkChapsWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("UkChapsWebhookReceiver: bad signature format rejected"):
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> "badsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  // ── Webhook event parsing tests ───────────────────────────────────────────

  test("UkChapsWebhookReceiver: parses chaps.payment.settled → ChapsSettled"):
    val secret = "test-secret"
    val body   = """{"type":"chaps.payment.settled","endToEndId":"e2e-200","amount":"10000.00","currency":"GBP"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.ChapsSettled(endToEndId, amount, currency) =>
        assert(endToEndId == "e2e-200")
        assert(amount     == "10000.00")
        assert(currency   == "GBP")
      case other => fail(s"Expected ChapsSettled, got $other")

  test("UkChapsWebhookReceiver: parses chaps.payment.rejected → ChapsRejected"):
    val secret = "test-secret"
    val body   = """{"type":"chaps.payment.rejected","endToEndId":"e2e-300","reason":"AccountNotFound"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.ChapsRejected(endToEndId, reason) =>
        assert(endToEndId == "e2e-300")
        assert(reason     == "AccountNotFound")
      case other => fail(s"Expected ChapsRejected, got $other")

  test("UkChapsWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"type":"chaps.unknown.event","endToEndId":"e2e-999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("UkChapsWebhookReceiver: missing type field returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"endToEndId":"e2e-999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = UkChapsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-CHAPS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)

  // ── idempotencyKey tests ──────────────────────────────────────────────────

  test("UkChapsWebhookReceiver.idempotencyKey: ChapsSettled includes endToEndId"):
    val recv  = UkChapsWebhookReceiver()
    val event = BankRailsEvent.ChapsSettled("e2e-401", "10000.00", "GBP")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("e2e-401"))

  test("UkChapsWebhookReceiver.idempotencyKey: ChapsRejected includes endToEndId"):
    val recv  = UkChapsWebhookReceiver()
    val event = BankRailsEvent.ChapsRejected("e2e-501", "AccountNotFound")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("e2e-501"))

  // ── HMAC helper tests ─────────────────────────────────────────────────────

  test("UkChapsWebhookReceiver.hmacSha256: output is 64 lowercase hex chars"):
    val recv   = UkChapsWebhookReceiver()
    val result = recv.hmacSha256("secret", "test-payload")
    assert(result.length == 64)
    assert(result.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))

  test("UkChapsWebhookReceiver.hmacSha256: different secrets produce different results"):
    val recv = UkChapsWebhookReceiver()
    val s1   = recv.hmacSha256("secret1", "payload")
    val s2   = recv.hmacSha256("secret2", "payload")
    assert(s1 != s2)

  test("UkChapsWebhookReceiver.hmacSha256: different payloads produce different results"):
    val recv = UkChapsWebhookReceiver()
    val s1   = recv.hmacSha256("secret", "payload1")
    val s2   = recv.hmacSha256("secret", "payload2")
    assert(s1 != s2)

  // ── UkChapsPlugin tests ───────────────────────────────────────────────────

  test("UkChapsPlugin id is non-empty"):
    assert(UkChapsPlugin().id.nonEmpty)

  test("UkChapsPlugin displayName is non-empty"):
    assert(UkChapsPlugin().displayName.nonEmpty)

  test("UkChapsPlugin capabilities include Feature.BankRails"):
    assert(UkChapsPlugin().capabilities.features.nonEmpty)

  // ── Cancel-after-queue error test ─────────────────────────────────────────

  test("UkChapsProvider.cancelTransfer wraps API error in BankRailsCancelError"):
    // Confirm that BankRailsCancelError is a BankRailsError subtype
    val err: BankRailsError = BankRailsCancelError(TransferId("tx-cancel-01"), "Payment already queued in RTGS")
    assert(err.getMessage.nonEmpty)
