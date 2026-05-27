package scalascript.payments.fednow

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.markup.{PureMarkupCodec, ValidationError}

/** Structural and golden-regression tests for the refactored Iso20022Xml builder.
 *
 *  Covers:
 *  - pacs.008 output with account-number (Othr/Id) and IBAN paths
 *  - pacs.008 parse round-trip (output is well-formed XML)
 *  - xml"..." interpolator: escaping, idempotent re-serialization
 *  - ValidationError type contracts (message/line/column)
 *  - MarkupCodec.validate throws UnsupportedOperationException for PureMarkupCodec
 */
class Iso20022XmlGoldenTest extends AnyFunSuite:

  private val senderAcctNum = BankAccount(
    accountNumber = Some("123456789"),
    routingNumber = Some("021000021"),
    holderName    = "Alice Corp",
    countryCode   = "US",
  )
  private val recipientAcctNum = BankAccount(
    accountNumber = Some("987654321"),
    bankCode      = Some("026009593"),
    holderName    = "Bob LLC",
    countryCode   = "US",
  )
  private val senderIban = BankAccount(
    iban        = Some("DE89370400440532013000"),
    holderName  = "IBAN Sender",
    countryCode = "DE",
  )
  private val recipientIban = BankAccount(
    iban        = Some("FR7630006000011234567890189"),
    holderName  = "IBAN Recipient",
    countryCode = "FR",
  )

  // ── pacs.008 account routing: Othr/Id path ───────────────────────────────

  test("Iso20022Xml.buildPacs008: account-number senders use Othr/Id element"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "ORDER-001",
      idempotencyKey = "GOLDEN-FN-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<Othr><Id>123456789</Id></Othr>"),
      "When no IBAN, sender account must use Othr/Id")
    assert(xml.contains("<Othr><Id>987654321</Id></Othr>"),
      "When no IBAN, recipient account must use Othr/Id")
    assert(!xml.contains("<IBAN>"), "No IBAN elements when accounts use Othr/Id")

  test("Iso20022Xml.buildPacs008: IBAN senders use IBAN element"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(5000L, Currency("USD")),
      sender         = senderIban,
      recipient      = recipientIban,
      reference      = "IBAN-TEST",
      idempotencyKey = "GOLDEN-FN-IBAN",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<IBAN>DE89370400440532013000</IBAN>"), "Sender IBAN must use IBAN element")
    assert(xml.contains("<IBAN>FR7630006000011234567890189</IBAN>"), "Recipient IBAN must use IBAN element")
    assert(!xml.contains("<Othr>"), "No Othr elements when IBAN is present")

  // ── pacs.008 structure ────────────────────────────────────────────────────

  test("Iso20022Xml.buildPacs008: output starts with XML declaration"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "DECL-TEST",
      idempotencyKey = "GOLDEN-FN-DECL",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
      "pacs.008 output must begin with XML declaration")

  test("Iso20022Xml.buildPacs008: Document namespace is pacs.008.001.08"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "NS-TEST",
      idempotencyKey = "GOLDEN-FN-NS",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"),
      "pacs.008 must declare pacs.008.001.08 namespace")

  test("Iso20022Xml.buildPacs008: FIToFICstmrCdtTrf root element present"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "ROOT-TEST",
      idempotencyKey = "GOLDEN-FN-ROOT",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<FIToFICstmrCdtTrf>"), "Root element must be FIToFICstmrCdtTrf")

  // ── pacs.008 parse round-trip ─────────────────────────────────────────────

  test("Iso20022Xml.buildPacs008: output is well-formed XML (parse round-trip)"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "ROUNDTRIP",
      idempotencyKey = "GOLDEN-FN-RT",
    )
    val xmlStr = Iso20022Xml.buildPacs008(req, "021000021")
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        assert(doc.root.name.localName == "Document", "Root must be Document")
      case Left(err) =>
        fail(s"pacs.008 output failed to parse: $err")

  test("Iso20022Xml.buildPacs008: output is idempotent when re-serialized"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "IDEM-TEST",
      idempotencyKey = "GOLDEN-FN-IDEM",
    )
    val xmlStr1 = Iso20022Xml.buildPacs008(req, "021000021")
    val doc     = PureMarkupCodec.parse(xmlStr1).toOption.get
    val xmlStr2 = PureMarkupCodec.serialize(doc)
    assert(xmlStr1 == xmlStr2, "Re-serializing pacs.008 output must be idempotent")

  // ── XML escaping via xml"..." interpolator ────────────────────────────────

  test("Iso20022Xml.buildPacs008: holder name with XML special chars is escaped"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1000L, Currency("USD")),
      sender         = senderAcctNum.copy(holderName = "Alice & Bob <LLC>"),
      recipient      = recipientAcctNum,
      reference      = "ESCAPE-TEST",
      idempotencyKey = "GOLDEN-FN-ESC",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("Alice &amp; Bob &lt;LLC&gt;"),
      "Special chars in holder name must be XML-escaped in pacs.008 output")

  // ── ValidationError type contracts ────────────────────────────────────────

  test("ValidationError: carries message, line, and column"):
    val e = ValidationError("element 'Amt' missing required child", 12, 4)
    assert(e.message == "element 'Amt' missing required child")
    assert(e.line    == 12)
    assert(e.column  == 4)

  test("ValidationError: equality is structural"):
    val a = ValidationError("err", 1, 1)
    val b = ValidationError("err", 1, 1)
    val c = ValidationError("err", 1, 2)
    assert(a == b)
    assert(a != c)

  // ── PureMarkupCodec.validate throws UnsupportedOperationException ──────────

  test("PureMarkupCodec.validate throws UnsupportedOperationException"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = Money(1050L, Currency("USD")),
      sender         = senderAcctNum,
      recipient      = recipientAcctNum,
      reference      = "XSD-TEST",
      idempotencyKey = "GOLDEN-FN-XSD",
    )
    val xmlStr = Iso20022Xml.buildPacs008(req, "021000021")
    val doc = PureMarkupCodec.parse(xmlStr).toOption.get
    intercept[UnsupportedOperationException]:
      PureMarkupCodec.validate(doc, "<xs:schema/>")
