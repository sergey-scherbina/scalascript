package scalascript.payments.swift

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

class SwiftProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val senderUS = BankAccount(
    iban          = Some("US00000000000000000000000000"),
    accountNumber = Some("123456789"),
    routingNumber = Some("021000021"),
    holderName    = "Acme Corp",
    countryCode   = "US",
    bic           = Some("CHASUS33"),
  )

  private val recipientDE = BankAccount(
    iban        = Some("DE89370400440532013000"),
    holderName  = "Deutsche Partner GmbH",
    countryCode = "DE",
    bic         = Some("DEUTDEDB"),
  )

  private val amount1000USD = Money(100000L, Currency("USD"))  // $1000.00
  private val amount250EUR  = Money(25000L,  Currency("EUR"))  // €250.00

  private def makeMt103Req(
    key: String = "TRN-001",
    cb: ChargeBearer = ChargeBearer.SHA,
    uetr: Option[Uetr] = None,
  ) = InitiateTransferRequest(
    rail           = RailKind.SWIFT_MT103,
    amount         = amount1000USD,
    sender         = senderUS,
    recipient      = recipientDE,
    reference      = "Invoice 12345",
    idempotencyKey = key,
    chargeBearer   = cb,
    uetr           = uetr,
  )

  private def makePacs008Req(
    key: String = "PACS-001",
    cb: ChargeBearer = ChargeBearer.SHA,
    uetr: Option[Uetr] = None,
  ) = InitiateTransferRequest(
    rail           = RailKind.SWIFT_PACS008,
    amount         = amount250EUR,
    sender         = senderUS,
    recipient      = recipientDE,
    reference      = "Supplier payment",
    idempotencyKey = key,
    chargeBearer   = cb,
    uetr           = uetr,
  )

  // ── UETR tests ────────────────────────────────────────────────────────────

  test("Uetr.generate() returns a valid UUID v4"):
    val u = Uetr.generate()
    assert(Uetr.isValid(u.value), s"Generated UETR is not a valid UUID v4: ${u.value}")

  test("Uetr.isValid: correct UUID v4 passes"):
    assert(Uetr.isValid("550e8400-e29b-41d4-a716-446655440000") == true)  // valid: version=4, variant=a
    assert(Uetr.isValid("550e8400-e29b-4100-a716-446655440000") == true)  // valid: version=4, variant=a

  test("Uetr.isValid: wrong version byte fails"):
    assert(Uetr.isValid("550e8400-e29b-3100-a716-446655440000") == false)  // version 3 not 4

  test("Uetr.isValid: wrong variant byte fails"):
    assert(Uetr.isValid("550e8400-e29b-4100-0716-446655440000") == false)  // variant must be 8-9 or a-b

  test("Uetr.isValid: non-UUID format fails"):
    assert(Uetr.isValid("not-a-uuid") == false)
    assert(Uetr.isValid("") == false)

  test("Uetr.apply wraps string and .value unwraps it"):
    val uetr = Uetr("550e8400-e29b-4100-a716-446655440000")
    assert(uetr.value == "550e8400-e29b-4100-a716-446655440000")

  test("Uetr.generate() produces unique values"):
    val u1 = Uetr.generate()
    val u2 = Uetr.generate()
    assert(u1 != u2, "Two generated UETRs must be distinct")

  // ── ChargeBearer tests ────────────────────────────────────────────────────

  test("ChargeBearer enum has OUR, SHA, BEN cases"):
    assert(ChargeBearer.OUR != ChargeBearer.SHA)
    assert(ChargeBearer.SHA != ChargeBearer.BEN)
    assert(ChargeBearer.OUR != ChargeBearer.BEN)

  test("Default ChargeBearer in InitiateTransferRequest is SHA"):
    val req = makeMt103Req()
    assert(req.chargeBearer == ChargeBearer.SHA)

  // ── SwiftMt103Builder tests ───────────────────────────────────────────────

  test("SwiftMt103Builder: field 20 TRN from idempotency key"):
    val req  = makeMt103Req(key = "TRN-ABCD1234")
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":20:TRN-ABCD1234"), "Field 20 TRN should contain idempotency key")

  test("SwiftMt103Builder: field 32A contains currency"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains("USD"), "Field 32A should contain currency USD")

  test("SwiftMt103Builder: field 32A contains comma-formatted amount"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    // $1000.00 → "1000,00" in MT103 format
    assert(mt.contains("1000,00"), "Field 32A amount should use comma separator")

  test("SwiftMt103Builder: field 50K contains sender name"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains("Acme Corp"), "Field 50K should contain sender name")

  test("SwiftMt103Builder: field 57A contains recipient BIC"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":57A:DEUTDEDB"), "Field 57A should contain recipient BIC")

  test("SwiftMt103Builder: field 59 contains recipient name"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains("Deutsche Partner GmbH"), "Field 59 should contain recipient name")

  test("SwiftMt103Builder: field 70 contains remittance reference"):
    val req  = makeMt103Req()
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":70:Invoice 12345"), "Field 70 should contain reference")

  test("SwiftMt103Builder: field 71A SHA charge bearer"):
    val req  = makeMt103Req(cb = ChargeBearer.SHA)
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":71A:SHA"), "Field 71A should be SHA")

  test("SwiftMt103Builder: field 71A OUR charge bearer"):
    val req  = makeMt103Req(cb = ChargeBearer.OUR)
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":71A:OUR"), "Field 71A should be OUR")

  test("SwiftMt103Builder: field 71A BEN charge bearer"):
    val req  = makeMt103Req(cb = ChargeBearer.BEN)
    val uetr = Uetr.generate()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(":71A:BEN"), "Field 71A should be BEN")

  test("SwiftMt103Builder: field 121 UETR present"):
    val uetr = Uetr.generate()
    val req  = makeMt103Req()
    val mt   = SwiftMt103Builder.build(req, uetr)
    assert(mt.contains(s":121:${uetr.value}"), "Field 121 should contain UETR")

  test("SwiftMt103Builder: TRN sanitizeTrn truncates to 16 chars"):
    val longKey = "ABCDEFGHIJ-1234567890"   // 21 chars
    assert(SwiftMt103Builder.sanitizeTrn(longKey).length <= 16)

  test("SwiftMt103Builder: formatMt103Amount USD 1000.00"):
    val money = Money(100000L, Currency("USD"))
    assert(SwiftMt103Builder.formatMt103Amount(money) == "1000,00")

  test("SwiftMt103Builder: formatMt103Amount EUR 0.99"):
    val money = Money(99L, Currency("EUR"))
    assert(SwiftMt103Builder.formatMt103Amount(money) == "0,99")

  test("SwiftMt103Builder: chargeBearer OUR → OUR"):
    assert(SwiftMt103Builder.chargeBearer(ChargeBearer.OUR) == "OUR")

  test("SwiftMt103Builder: chargeBearer SHA → SHA"):
    assert(SwiftMt103Builder.chargeBearer(ChargeBearer.SHA) == "SHA")

  test("SwiftMt103Builder: chargeBearer BEN → BEN"):
    assert(SwiftMt103Builder.chargeBearer(ChargeBearer.BEN) == "BEN")

  // ── SwiftPacs008Builder tests ─────────────────────────────────────────────

  test("SwiftPacs008Builder: document has pacs.008.001.10 namespace"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10"))

  test("SwiftPacs008Builder: GrpHdr MsgId set from idempotency key"):
    val req  = makePacs008Req(key = "PACS-XYZ-789")
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<MsgId>PACS-XYZ-789</MsgId>"), "MsgId should equal idempotency key")

  test("SwiftPacs008Builder: UETR present in CdtTrfTxInf"):
    val uetr = Uetr("550e8400-e29b-4100-a716-446655440000")
    val req  = makePacs008Req()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains(s"<UETR>${uetr.value}</UETR>"), "UETR element should be present")

  test("SwiftPacs008Builder: IntrBkSttlmAmt contains amount and currency"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("""Ccy="EUR">250.00"""), "Settlement amount should be 250.00 EUR")

  test("SwiftPacs008Builder: DbtrAgt BICFI set to sender BIC"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<BICFI>CHASUS33</BICFI>"), "DbtrAgt BICFI should be sender BIC")

  test("SwiftPacs008Builder: CdtrAgt BICFI set to recipient BIC"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<BICFI>DEUTDEDB</BICFI>"), "CdtrAgt BICFI should be recipient BIC")

  test("SwiftPacs008Builder: ChrgBr SHAR for SHA"):
    val req  = makePacs008Req(cb = ChargeBearer.SHA)
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<ChrgBr>SHAR</ChrgBr>"), "ChrgBr should be SHAR for SHA")

  test("SwiftPacs008Builder: ChrgBr DEBT for OUR"):
    val req  = makePacs008Req(cb = ChargeBearer.OUR)
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<ChrgBr>DEBT</ChrgBr>"), "ChrgBr should be DEBT for OUR")

  test("SwiftPacs008Builder: ChrgBr CRED for BEN"):
    val req  = makePacs008Req(cb = ChargeBearer.BEN)
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<ChrgBr>CRED</ChrgBr>"), "ChrgBr should be CRED for BEN")

  test("SwiftPacs008Builder: Dbtr Nm contains sender name"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<Nm>Acme Corp</Nm>"), "Dbtr Nm should contain sender name")

  test("SwiftPacs008Builder: Cdtr Nm contains recipient name"):
    val req  = makePacs008Req()
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("<Nm>Deutsche Partner GmbH</Nm>"), "Cdtr Nm should contain recipient name")

  test("SwiftPacs008Builder: formatAmount EUR 250.00"):
    val money = Money(25000L, Currency("EUR"))
    assert(SwiftPacs008Builder.formatAmount(money) == "250.00")

  test("SwiftPacs008Builder: xml escapes ampersand in reference"):
    val req  = makePacs008Req().copy(reference = "Order & Invoice")
    val uetr = Uetr.generate()
    val xml  = SwiftPacs008Builder.build(req, uetr)
    assert(xml.contains("Order &amp; Invoice"), "Ampersand in reference should be XML-escaped")

  // ── GpiTracker tests ──────────────────────────────────────────────────────

  test("GpiTracker.parseEvent: parses gpi.v4.credits.ValueDateChanged"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.credits.ValueDateChanged","uetr":"$uetr","agentBic":"DEUTDEDB","status":"ACSP","updatedAt":"2024-01-15T10:00:00Z"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    assert(result.isRight, s"Expected Right but got $result")
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.SwiftGpiAdvanced])

  test("GpiTracker.parseEvent: SwiftGpiAdvanced hop has correct BIC"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.credits.ValueDateChanged","uetr":"$uetr","agentBic":"CHASUS33","status":"ACSP","updatedAt":"2024-01-15T10:00:00Z"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    result.toOption.get match
      case BankRailsEvent.SwiftGpiAdvanced(u, hop) =>
        assert(u == uetr)
        assert(hop.agentBic == "CHASUS33")
        assert(hop.status == "ACSP")
      case other => fail(s"Expected SwiftGpiAdvanced but got $other")

  test("GpiTracker.parseEvent: parses gpi.v4.credits.Completed as pacs.008 settled"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.credits.Completed","uetr":"$uetr","amount":"1000.00","currency":"USD"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.SwiftPacs008Settled(u, amt, ccy) =>
        assert(u == uetr)
        assert(amt == "1000.00")
        assert(ccy == "USD")
      case other => fail(s"Expected SwiftPacs008Settled but got $other")

  test("GpiTracker.parseEvent: parses gpi.v4.credits.Completed as MT103 booked"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.credits.Completed","uetr":"$uetr","amount":"500.00","currency":"GBP"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_MT103)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.SwiftMt103Booked])

  test("GpiTracker.parseEvent: parses gpi.v4.credits.CancellationCompleted as SwiftRejected"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.credits.CancellationCompleted","uetr":"$uetr","statusCode":"RJCT","reason":"Cancelled by sender"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.SwiftRejected(u, code, reason) =>
        assert(u == uetr)
        assert(code == "RJCT")
        assert(reason == "Cancelled by sender")
      case other => fail(s"Expected SwiftRejected but got $other")

  test("GpiTracker.parseEvent: returns Left for missing uetr"):
    val json = s"""{"event":"gpi.v4.credits.Completed","amount":"100.00","currency":"USD"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    assert(result.isLeft, "Missing uetr should return Left")

  test("GpiTracker.parseEvent: returns Left for unknown event type"):
    val uetr = Uetr.generate().value
    val json = s"""{"event":"gpi.v4.unknown","uetr":"$uetr"}"""
    val result = GpiTracker.parseEvent(json, RailKind.SWIFT_PACS008)
    assert(result.isLeft, "Unknown event type should return Left")

  test("GpiTracker.parseHops: parses hop list from JSON"):
    val json = s"""{"hops":[{"agentBic":"CHASUS33","status":"ACSP","updatedAt":"2024-01-15T09:00:00Z"},{"agentBic":"DEUTDEDB","status":"ACCC","updatedAt":"2024-01-16T14:00:00Z"}]}"""
    val hops = GpiTracker.parseHops(json)
    assert(hops.length == 2)
    assert(hops.head.agentBic == "CHASUS33")
    assert(hops.head.status   == "ACSP")

  // ── SwiftWebhookReceiver HMAC tests ──────────────────────────────────────

  test("SwiftWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "swift-webhook-secret-xyz"
    val uetr   = Uetr.generate().value
    val body   = s"""{"event":"gpi.v4.credits.Completed","uetr":"$uetr","amount":"1000.00","currency":"USD"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SwiftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SWIFT-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("SwiftWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val uetr   = Uetr.generate().value
    val body   = s"""{"event":"gpi.v4.credits.Completed","uetr":"$uetr","amount":"100.00","currency":"EUR"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = SwiftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SWIFT-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("SwiftWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = SwiftWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("SwiftWebhookReceiver: signature format without sha256= prefix is rejected"):
    val recv   = SwiftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SWIFT-Signature" -> "badsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("SwiftWebhookReceiver: parses SwiftGpiAdvanced event"):
    val secret = "gpi-secret"
    val uetr   = Uetr.generate().value
    val body   = s"""{"event":"gpi.v4.credits.ValueDateChanged","uetr":"$uetr","agentBic":"BNPAFRPP","status":"ACSP","updatedAt":"2024-06-01T12:00:00Z"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SwiftWebhookReceiver(RailKind.SWIFT_PACS008)
    val req    = WebhookRequest(headers = Map("X-SWIFT-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.SwiftGpiAdvanced])

  // ── GpiHop tests ─────────────────────────────────────────────────────────

  test("GpiHop: constructor with all fields"):
    val hop = GpiHop(
      agentBic    = "DEUTDEDB",
      status      = "ACCC",
      updatedAt   = Instant.parse("2024-01-16T14:00:00Z"),
      debitAmount = Some(Money(100000L, Currency("USD"))),
      creditAmount = Some(Money(93500L, Currency("EUR"))),
    )
    assert(hop.agentBic    == "DEUTDEDB")
    assert(hop.status      == "ACCC")
    assert(hop.debitAmount.isDefined)
    assert(hop.creditAmount.isDefined)

  test("GpiHop: optional fields default to None"):
    val hop = GpiHop(agentBic = "CHASUS33", status = "ACSP", updatedAt = Instant.now())
    assert(hop.debitAmount.isEmpty)
    assert(hop.creditAmount.isEmpty)

  // ── BankTransfer SWIFT fields tests ──────────────────────────────────────

  test("BankTransfer: uetr field defaults to None"):
    val dummy = BankAccount(holderName = "Test", countryCode = "US")
    val t = BankTransfer(
      id        = TransferId("t1"),
      rail      = RailKind.SWIFT_PACS008,
      amount    = amount1000USD,
      sender    = dummy,
      recipient = dummy,
      reference = "ref",
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )
    assert(t.uetr.isEmpty)

  test("BankTransfer: gpiTrail field defaults to Nil"):
    val dummy = BankAccount(holderName = "Test", countryCode = "US")
    val t = BankTransfer(
      id        = TransferId("t2"),
      rail      = RailKind.SWIFT_PACS008,
      amount    = amount1000USD,
      sender    = dummy,
      recipient = dummy,
      reference = "ref",
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )
    assert(t.gpiTrail == Nil)

  test("BankTransfer: chargeBearer field defaults to None"):
    val dummy = BankAccount(holderName = "Test", countryCode = "US")
    val t = BankTransfer(
      id        = TransferId("t3"),
      rail      = RailKind.SWIFT_PACS008,
      amount    = amount1000USD,
      sender    = dummy,
      recipient = dummy,
      reference = "ref",
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
    )
    assert(t.chargeBearer.isEmpty)

  // ── BankAccount.bic tests ─────────────────────────────────────────────────

  test("BankAccount: bic field defaults to None"):
    val acct = BankAccount(holderName = "Test Corp", countryCode = "US")
    assert(acct.bic.isEmpty)

  test("BankAccount: bic field can be set"):
    val acct = BankAccount(holderName = "Test Corp", countryCode = "US", bic = Some("CHASUS33"))
    assert(acct.bic == Some("CHASUS33"))

  // ── RailKind SWIFT cases ──────────────────────────────────────────────────

  test("RailKind.SWIFT_MT103 is distinct from SWIFT_PACS008"):
    assert(RailKind.SWIFT_MT103 != RailKind.SWIFT_PACS008)

  test("RailKind includes all v1.55 cases"):
    val cases = RailKind.values.toSet
    assert(cases.contains(RailKind.SWIFT_MT103))
    assert(cases.contains(RailKind.SWIFT_PACS008))
    assert(cases.contains(RailKind.SCT_INST))
    assert(cases.contains(RailKind.UK_FPS))
    assert(cases.contains(RailKind.UK_BACS_DD))
    assert(cases.contains(RailKind.UK_CHAPS))
    assert(cases.contains(RailKind.IN_UPI))
    assert(cases.contains(RailKind.JP_ZENGIN))
    assert(cases.contains(RailKind.SG_PAYNOW))

  // ── SwiftConfig tests ──────────────────────────────────────────────────────

  test("SwiftConfig.fromEnv uses fallback defaults when env vars absent"):
    val cfg = SwiftConfig.fromEnv
    assert(cfg.aggregatorUrl.nonEmpty, "Default aggregatorUrl should be non-empty")
    assert(cfg.defaultCharge == ChargeBearer.SHA, "Default charge should be SHA")

  test("SwiftConfig fields accessible"):
    val cfg = SwiftConfig(
      aggregatorUrl = "https://api.currencycloud.com/v2",
      apiKey        = "test-api-key-123",
      defaultCharge = ChargeBearer.OUR,
    )
    assert(cfg.aggregatorUrl == "https://api.currencycloud.com/v2")
    assert(cfg.defaultCharge == ChargeBearer.OUR)

  // ── SwiftSanctionsHit / SwiftUetrInvalid error tests ─────────────────────

  test("SwiftSanctionsHit error message contains UETR and sanctions ref"):
    val err = SwiftSanctionsHit("some-uetr", "OFAC-12345")
    assert(err.getMessage.contains("some-uetr"))
    assert(err.getMessage.contains("OFAC-12345"))

  test("SwiftUetrInvalid error message contains the invalid UETR"):
    val err = SwiftUetrInvalid("not-a-uuid")
    assert(err.getMessage.contains("not-a-uuid"))
