package scalascript.payments.sepa

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SepaProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val senderDE = BankAccount(
    iban        = Some("DE89370400440532013000"),
    holderName  = "Acme GmbH",
    countryCode = "DE",
  )
  private val recipientFR = BankAccount(
    iban        = Some("FR7630006000011234567890189"),
    holderName  = "Fournisseur SA",
    countryCode = "FR",
  )
  private val amount100EUR = Money(10000L, Currency("EUR"))  // €100.00

  // ── PAIN.001 XML structure tests ──────────────────────────────────────────

  test("SepaPainXml.buildPain001: document has correct namespace"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "ORDER-001",
      idempotencyKey = "idem-key-001",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.001.001.03"))

  test("SepaPainXml.buildPain001: contains sender IBAN"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "TEST-REF",
      idempotencyKey = "idem-key-002",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("DE89370400440532013000"), "Sender IBAN should appear in PAIN.001")

  test("SepaPainXml.buildPain001: contains recipient IBAN"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "TEST-REF",
      idempotencyKey = "idem-key-003",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("FR7630006000011234567890189"), "Recipient IBAN should appear in PAIN.001")

  test("SepaPainXml.buildPain001: amount formatted correctly for EUR"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = Money(4999L, Currency("EUR")),  // €49.99
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "REF",
      idempotencyKey = "idem-key-004",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("49.99"), "Amount 49.99 EUR should appear in PAIN.001")
    assert(xml.contains("""Ccy="EUR""""), "Currency attribute EUR should appear in PAIN.001")

  test("SepaPainXml.buildPain001: EndToEndId set from idempotency key"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "REF",
      idempotencyKey = "e2e-ref-xyz",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("<EndToEndId>e2e-ref-xyz</EndToEndId>"), "EndToEndId should equal idempotencyKey")

  test("SepaPainXml.buildPain001: MsgId present and non-empty"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "REF",
      idempotencyKey = "msg-id-test",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("<MsgId>msg-id-test</MsgId>"), "MsgId should be set from idempotency key")

  // ── PAIN.008 XML structure tests ──────────────────────────────────────────

  test("SepaPainXml.buildPain008: document has correct namespace"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId("MND-001"),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "Acme GmbH",
      reference       = "DD-REF-001",
      idempotencyKey  = "idem-dd-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-001"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "Acme GmbH",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.First,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.008.001.02"))

  test("SepaPainXml.buildPain008: SeqTp = FRST for First sequence"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId("MND-FRST"),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "Merchant X",
      reference       = "FIRST-DD",
      idempotencyKey  = "idem-frst-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-FRST"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "Merchant X",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.First,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains("<SeqTp>FRST</SeqTp>"), "First sequence should produce FRST SeqTp")

  test("SepaPainXml.buildPain008: SeqTp = RCUR for Recurring"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId("MND-RCUR"),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "Merchant X",
      reference       = "RECUR-DD",
      idempotencyKey  = "idem-rcur-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-RCUR"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "Merchant X",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.Recurring,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains("<SeqTp>RCUR</SeqTp>"), "Recurring sequence should produce RCUR SeqTp")

  test("SepaPainXml.buildPain008: mandate ID present in MndtId element"):
    val mndtId = "MND-UNIQUE-999"
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId(mndtId),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "Test Corp",
      reference       = "REF",
      idempotencyKey  = "idem-mnd-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId(mndtId),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "Test Corp",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.OneOff,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains(s"<MndtId>$mndtId</MndtId>"), "Mandate ID should appear in MndtId element")

  // ── Webhook HMAC verify tests ─────────────────────────────────────────────

  test("SepaWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "sepa-webhook-secret-abc"
    val body   = """{"type":"sepa.transfer.completed","transfer_id":"txn-001","amount":"49.99","currency":"EUR","reference":"REF-1"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("SepaWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val body   = """{"type":"sepa.transfer.completed","transfer_id":"txn-002","amount":"10.00","currency":"EUR","reference":"REF-2"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("SepaWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("SepaWebhookReceiver: parses sepa.transfer.completed event"):
    val secret = "test-secret"
    val body   = """{"type":"sepa.transfer.completed","transfer_id":"ct-123","amount":"100.00","currency":"EUR","reference":"ORD-456"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.SepaTransferCompleted])

  test("SepaWebhookReceiver: parses sepa.mandate.activated event"):
    val secret = "mandate-secret"
    val body   = """{"type":"sepa.mandate.activated","mandate_id":"MND-456","creditor_name":"Acme GmbH"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.SepaMandateActivated])

  // ── BankTransferStatus mapping tests ─────────────────────────────────────

  test("BankTransferStatus.Pending is the initial state"):
    val status = BankTransferStatus.Pending
    assert(status == BankTransferStatus.Pending)

  test("BankTransferStatus.Settled is a distinct terminal state"):
    val status = BankTransferStatus.Settled
    assert(status != BankTransferStatus.Pending)

  test("BankTransferStatus.Rejected carries RejectCode"):
    val code   = RejectCode("AC01")
    val status = BankTransferStatus.Rejected(code, "Incorrect account number")
    status match
      case BankTransferStatus.Rejected(c, desc) =>
        assert(c.value == "AC01")
        assert(desc.contains("account"))
      case _ => fail("Expected Rejected status")

  test("BankTransferStatus.Returned carries ReturnCode"):
    val code   = ReturnCode("MS02")
    val status = BankTransferStatus.Returned(code, "Not specified reason")
    status match
      case BankTransferStatus.Returned(c, _) => assert(c.value == "MS02")
      case _ => fail("Expected Returned status")

  // ── RCode parsing tests ───────────────────────────────────────────────────

  test("RCode.R01 value and description"):
    val r = RCode.R01
    assert(r.value == "R01")
    assert(r.description.contains("Insufficient"))

  test("RCode.apply parses arbitrary R-code"):
    val r = RCode("R10")
    assert(r.value == "R10")
    assert(r.description.contains("Not Authorized"))

  test("RCode.R02 account closed description"):
    assert(RCode.R02.description.contains("Closed"))

  // ── SepaConfig from env vars tests ────────────────────────────────────────

  test("SepaConfig.fromEnv uses fallback defaults when env vars absent"):
    // No env vars set in test — should fall back to defaults without throwing
    val cfg = SepaConfig.fromEnv
    assert(cfg.apiUrl.nonEmpty, "Default apiUrl should be non-empty")

  test("SepaConfig fields accessible"):
    val cfg = SepaConfig(
      apiUrl     = "https://api.example.com/sepa/v1",
      apiKey     = "test-key-12345",
      creditorId = "DE98ZZZ09999999999",
    )
    assert(cfg.apiUrl == "https://api.example.com/sepa/v1")
    assert(cfg.apiKey == "test-key-12345")
    assert(cfg.creditorId == "DE98ZZZ09999999999")

  // ── XML escaping tests ───────────────────────────────────────────────────

  test("SepaPainXml escapes XML special chars in reference field"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "REF & <test>",
      idempotencyKey = "idem-escape-001",
    )
    val xml = SepaPainXml.buildPain001(req)
    assert(xml.contains("REF &amp; &lt;test&gt;"), "Special chars in reference should be XML-escaped")

  // ── MandateSequenceType tests ─────────────────────────────────────────────

  test("MandateSequenceType.OneOff produces OOFF SeqTp"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId("MND-OOFF"),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "One-off Corp",
      reference       = "OOFF-REF",
      idempotencyKey  = "idem-ooff-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-OOFF"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "One-off Corp",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.OneOff,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains("<SeqTp>OOFF</SeqTp>"), "OneOff sequence should produce OOFF SeqTp")

  test("MandateSequenceType.Final produces FNAL SeqTp"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount100EUR,
      mandateId       = MandateId("MND-FNAL"),
      creditorAccount = senderDE,
      debtorAccount   = recipientFR,
      creditorName    = "Final Corp",
      reference       = "FNAL-REF",
      idempotencyKey  = "idem-fnal-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-FNAL"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = recipientFR,
      creditorAccount = senderDE,
      creditorName    = "Final Corp",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.Final,
    )
    val xml = SepaPainXml.buildPain008(req, mandate)
    assert(xml.contains("<SeqTp>FNAL</SeqTp>"), "Final sequence should produce FNAL SeqTp")

  // ── formatAmount helper tests ─────────────────────────────────────────────

  test("SepaPainXml.formatAmount: EUR 100.00"):
    val money = Money(10000L, Currency("EUR"))
    assert(SepaPainXml.formatAmount(money) == "100.00")

  test("SepaPainXml.formatAmount: EUR 1.01"):
    val money = Money(101L, Currency("EUR"))
    assert(SepaPainXml.formatAmount(money) == "1.01")

  test("SepaPainXml.formatAmount: EUR 0.99"):
    val money = Money(99L, Currency("EUR"))
    assert(SepaPainXml.formatAmount(money) == "0.99")

  // ── SCT Inst: pacs.008 XML structure tests ────────────────────────────────

  test("SepaPainXml.buildSctInstPacs008: document has pacs.008.001.08 namespace"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-REF-001",
      idempotencyKey = "sct-inst-001",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"),
      "SCT Inst must use pacs.008.001.08 namespace, not pain.001")

  test("SepaPainXml.buildSctInstPacs008: LclInstrm is INST"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-REF-002",
      idempotencyKey = "sct-inst-002",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<LclInstrm><Cd>INST</Cd></LclInstrm>"),
      "SCT Inst local instrument must be INST (not CORE or empty)")

  test("SepaPainXml.buildSctInstPacs008: SvcLvl is SEPA"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-REF-003",
      idempotencyKey = "sct-inst-003",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<SvcLvl><Cd>SEPA</Cd></SvcLvl>"),
      "SCT Inst service level must be SEPA")

  test("SepaPainXml.buildSctInstPacs008: SttlmMtd is CLRG"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-REF-004",
      idempotencyKey = "sct-inst-004",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<SttlmMtd>CLRG</SttlmMtd>"),
      "SCT Inst settlement method must be CLRG (cleared via scheme)")

  test("SepaPainXml.buildSctInstPacs008: ClrSys is SCTInst"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-REF-005",
      idempotencyKey = "sct-inst-005",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<Cd>SCTInst</Cd>"),
      "ClrSys must identify SCTInst scheme for TIPS/RT1 routing")

  test("SepaPainXml.buildSctInstPacs008: contains sender IBAN"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-IBAN-TEST",
      idempotencyKey = "sct-inst-006",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("DE89370400440532013000"), "Sender IBAN must appear in pacs.008")

  test("SepaPainXml.buildSctInstPacs008: contains recipient IBAN"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-IBAN-CDTR",
      idempotencyKey = "sct-inst-007",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("FR7630006000011234567890189"), "Recipient IBAN must appear in pacs.008")

  test("SepaPainXml.buildSctInstPacs008: EndToEndId set from idempotency key"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-E2E",
      idempotencyKey = "sct-e2e-ref-xyz",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<EndToEndId>sct-e2e-ref-xyz</EndToEndId>"),
      "EndToEndId must equal idempotency key (maps to SCT Inst end-to-end reference)")

  test("SepaPainXml.buildSctInstPacs008: IntrBkSttlmAmt present with correct currency"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = Money(4999L, Currency("EUR")),
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-AMT",
      idempotencyKey = "sct-inst-amt-001",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("""IntrBkSttlmAmt Ccy="EUR">49.99"""),
      "IntrBkSttlmAmt element must carry the settlement amount and EUR currency")

  test("SepaPainXml.buildSctInstPacs008: uses FIToFICstmrCdtTrf root element (not CstmrCdtTrfInitn)"):
    val req = InitiateTransferRequest(
      rail           = RailKind.SCT_INST,
      amount         = amount100EUR,
      sender         = senderDE,
      recipient      = recipientFR,
      reference      = "INST-ROOT",
      idempotencyKey = "sct-inst-root",
    )
    val xml = SepaPainXml.buildSctInstPacs008(req)
    assert(xml.contains("<FIToFICstmrCdtTrf>"),
      "SCT Inst pacs.008 must use FIToFICstmrCdtTrf root (FI-to-FI), not CstmrCdtTrfInitn (customer-to-FI)")
    assert(!xml.contains("<CstmrCdtTrfInitn>"),
      "SCT Inst must not contain CstmrCdtTrfInitn element")

  // ── SCT Inst: SepaProvider routing tests ─────────────────────────────────

  test("SepaProvider.supportedRails includes SCT_INST"):
    val config = SepaConfig(
      apiUrl     = "https://api.example.com/v1",
      apiKey     = "test-key",
      creditorId = "DE98ZZZ09999999999",
    )
    val provider = SepaProvider(config)
    assert(provider.supportedRails.contains(RailKind.SCT_INST),
      "SepaProvider must declare SCT_INST support (EU Regulation 2024/886)")

  // ── SCT Inst: SctInstTimeout error tests ─────────────────────────────────

  test("SctInstTimeout carries endToEndId and elapsed milliseconds"):
    val err = SctInstTimeout("e2e-timeout-001", 10_250L)
    assert(err.endToEndId == "e2e-timeout-001")
    assert(err.elapsedMs == 10_250L)
    assert(err.getMessage.contains("e2e-timeout-001"))
    assert(err.getMessage.contains("10250"))

  test("SctInstTimeout is a BankRailsError subtype"):
    val err: BankRailsError = SctInstTimeout("e2e-001", 9999L)
    assert(err.isInstanceOf[BankRailsError])
    assert(err.isInstanceOf[SctInstTimeout])

  // ── SCT Inst: webhook SctInstSettled event tests ──────────────────────────

  test("SepaWebhookReceiver: parses SCTInst.CreditTransfer.Settlement event"):
    val secret = "sct-inst-secret"
    val body   = """{"type":"SCTInst.CreditTransfer.Settlement","end_to_end_id":"inst-e2e-001","amount":"250.00","currency":"EUR"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got $result")
    result.toOption.get match
      case BankRailsEvent.SctInstSettled(e2eId, amount, currency) =>
        assert(e2eId    == "inst-e2e-001")
        assert(amount   == "250.00")
        assert(currency == "EUR")
      case other => fail(s"Expected SctInstSettled, got $other")

  test("SepaWebhookReceiver: parses SCTInst.CreditTransfer.Rejection event"):
    val secret = "sct-inst-secret"
    val body   = """{"type":"SCTInst.CreditTransfer.Rejection","end_to_end_id":"inst-e2e-002","reason":"AC01"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got $result")
    result.toOption.get match
      case BankRailsEvent.SctInstRejected(e2eId, reason) =>
        assert(e2eId  == "inst-e2e-002")
        assert(reason == "AC01")
      case other => fail(s"Expected SctInstRejected, got $other")

  test("SepaWebhookReceiver: SCTInst.CreditTransfer.Settlement with camelCase endToEndId field"):
    val secret = "sct-inst-secret"
    val body   = """{"type":"SCTInst.CreditTransfer.Settlement","endToEndId":"inst-e2e-003","amount":"100.00","currency":"EUR"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.SctInstSettled(e2eId, _, _) =>
        assert(e2eId == "inst-e2e-003", "Should fall back to endToEndId camelCase field")
      case other => fail(s"Expected SctInstSettled, got $other")

  test("SepaWebhookReceiver: SCTInst.CreditTransfer.Rejection falls back to reason_code field"):
    val secret = "sct-inst-secret"
    val body   = """{"type":"SCTInst.CreditTransfer.Rejection","end_to_end_id":"inst-e2e-004","reason_code":"MS03"}"""
    val sig    = makeSignature(secret, body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.SctInstRejected(_, reason) =>
        assert(reason == "MS03", "Should fall back to reason_code field when reason absent")
      case other => fail(s"Expected SctInstRejected, got $other")

  test("SepaWebhookReceiver: wrong HMAC rejects SCTInst.CreditTransfer.Settlement"):
    val secret = "correct-secret"
    val body   = """{"type":"SCTInst.CreditTransfer.Settlement","end_to_end_id":"inst-e2e-005","amount":"10.00","currency":"EUR"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = SepaWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-SEPA-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong HMAC must be rejected for SCT Inst event")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  // ── SCT Inst: RailKind enum tests ─────────────────────────────────────────

  test("RailKind.SCT_INST is a distinct rail kind"):
    assert(RailKind.SCT_INST != RailKind.SEPA_CT)
    assert(RailKind.SCT_INST != RailKind.SEPA_DD)
    assert(RailKind.SCT_INST.toString == "SCT_INST")
