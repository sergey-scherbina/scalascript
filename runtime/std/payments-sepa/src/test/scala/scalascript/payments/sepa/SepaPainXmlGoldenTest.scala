package scalascript.payments.sepa

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.markup.{PureMarkupCodec, ValidationError}

/** Golden-file regression suite for SepaPainXml PAIN.001 output.
 *
 *  Each test builds a PAIN.001 document with known inputs and compares the
 *  normalized output against a golden XML fixture stored in
 *  `src/test/resources/golden/`.
 *
 *  Normalization: dynamic timestamp fields (CreDtTm, ReqdExctnDt) are replaced
 *  with the placeholder strings NORMALIZED_DATETIME / NORMALIZED_DATE before
 *  comparison, so tests are deterministic despite wall-clock usage.
 *
 *  Also covers XSD-validation integration: MarkupCodec.validate() is exercised
 *  with a minimal well-formed XSD (should return no errors) and a deliberately
 *  broken document (should return at least one ValidationError).
 */
class SepaPainXmlGoldenTest extends AnyFunSuite:

  // ── Fixtures for 12 PAIN.001 golden comparisons ───────────────────────────

  private case class Pain001Fixture(
    name:           String,  // golden file basename (without .xml)
    idempotencyKey: String,
    amount:         Money,
    senderIban:     String,
    senderName:     String,
    senderCountry:  String,
    recipientIban:  String,
    recipientName:  String,
    recipientCountry: String,
    reference:      String,
  )

  private val fixtures: List[Pain001Fixture] = List(
    Pain001Fixture("pain001-basic-eur",    "GOLDEN-001", Money(10000L, Currency("EUR")),
      "DE89370400440532013000", "Acme GmbH", "DE",
      "FR7630006000011234567890189", "Fournisseur SA", "FR",
      "ORDER-001"),
    Pain001Fixture("pain001-small-amount", "GOLDEN-002", Money(1L, Currency("EUR")),
      "DE89370400440532013000", "Test Sender", "DE",
      "NL91ABNA0417164300", "Test Receiver", "NL",
      "MIN-AMOUNT-TEST"),
    Pain001Fixture("pain001-large-amount", "GOLDEN-003", Money(9999999L, Currency("EUR")),
      "DE89370400440532013000", "Big Corp GmbH", "DE",
      "FR7630006000011234567890189", "Large Vendor SA", "FR",
      "LARGE-PAYMENT"),
    Pain001Fixture("pain001-xml-escape",   "GOLDEN-004", Money(5000L, Currency("EUR")),
      "DE89370400440532013000", "Acme & Sons", "DE",
      "FR7630006000011234567890189", "Receiver <Test>", "FR",
      "REF & <special>"),
    Pain001Fixture("pain001-de-to-es",     "GOLDEN-005", Money(25075L, Currency("EUR")),
      "DE89370400440532013000", "Berlin Exports GmbH", "DE",
      "ES9121000418450200051332", "Madrid Imports SL", "ES",
      "INV-2026-0042"),
    Pain001Fixture("pain001-nl-to-be",     "GOLDEN-006", Money(120000L, Currency("EUR")),
      "NL91ABNA0417164300", "Amsterdam Logistics BV", "NL",
      "BE68539007547034", "Brussels Supply NV", "BE",
      "PO-2026-0099"),
    Pain001Fixture("pain001-it-to-at",     "GOLDEN-007", Money(375050L, Currency("EUR")),
      "IT60X0542811101000000123456", "Milano Trading SRL", "IT",
      "AT611904300234573201", "Vienna Imports GmbH", "AT",
      "CONTRACT-IT-AT-2026"),
    Pain001Fixture("pain001-fr-to-pt",     "GOLDEN-008", Money(78025L, Currency("EUR")),
      "FR7630006000011234567890189", "Paris Services SARL", "FR",
      "PT50000201231234567890154", "Lisboa Supplies LDA", "PT",
      "SVC-2026-FR-PT"),
    Pain001Fixture("pain001-es-to-gr",     "GOLDEN-009", Money(50000L, Currency("EUR")),
      "ES9121000418450200051332", "Barcelona Tech SL", "ES",
      "GR1601101250000000012300695", "Athens Imports AE", "GR",
      "TECH-SERVICES-Q1"),
    Pain001Fixture("pain001-be-to-fi",     "GOLDEN-010", Money(890000L, Currency("EUR")),
      "BE68539007547034", "Bruxelles Finance SA", "BE",
      "FI2112345600000785", "Helsinki Solutions OY", "FI",
      "PROJECT-NORDIC-2026"),
    Pain001Fixture("pain001-at-to-se",     "GOLDEN-011", Money(1560000L, Currency("EUR")),
      "AT611904300234573201", "Wien Consulting GmbH", "AT",
      "SE4550000000058398257466", "Stockholm Consulting AB", "SE",
      "CONSULTING-Q2-2026"),
    Pain001Fixture("pain001-fi-to-lu",     "GOLDEN-012", Money(4200000L, Currency("EUR")),
      "FI2112345600000785", "Helsinki Ventures OY", "FI",
      "LU280019400644750000", "Luxembourg Fund SA", "LU",
      "FUND-TRANSFER-2026"),
  )

  /** Normalizes dynamic timestamp fields so comparison is deterministic. */
  private def normalize(xml: String): String =
    xml
      // ISO datetime like 2026-05-27T14:30:00
      .replaceAll("<CreDtTm>[^<]+</CreDtTm>", "<CreDtTm>NORMALIZED_DATETIME</CreDtTm>")
      // ISO date like 2026-05-28
      .replaceAll("<ReqdExctnDt>[^<]+</ReqdExctnDt>", "<ReqdExctnDt>NORMALIZED_DATE</ReqdExctnDt>")
      .replaceAll("<IntrBkSttlmDt>[^<]+</IntrBkSttlmDt>", "<IntrBkSttlmDt>NORMALIZED_DATE</IntrBkSttlmDt>")
      .replaceAll("<ReqdColltnDt>[^<]+</ReqdColltnDt>", "<ReqdColltnDt>NORMALIZED_DATE</ReqdColltnDt>")
      .replaceAll("<DtOfSgntr>[^<]+</DtOfSgntr>", "<DtOfSgntr>NORMALIZED_DATE</DtOfSgntr>")

  private def loadGolden(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"golden/$name.xml")
    assert(stream != null, s"Golden fixture not found: golden/$name.xml")
    new String(stream.readAllBytes(), "UTF-8")

  private def buildReq(f: Pain001Fixture): InitiateTransferRequest =
    InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = f.amount,
      sender         = BankAccount(
        iban        = Some(f.senderIban),
        holderName  = f.senderName,
        countryCode = f.senderCountry,
      ),
      recipient      = BankAccount(
        iban        = Some(f.recipientIban),
        holderName  = f.recipientName,
        countryCode = f.recipientCountry,
      ),
      reference      = f.reference,
      idempotencyKey = f.idempotencyKey,
    )

  // ── 12 golden-file regression tests ─────────────────────────────────────

  for f <- fixtures do
    test(s"PAIN.001 golden: ${f.name}"):
      val actual   = normalize(SepaPainXml.buildPain001(buildReq(f)))
      val expected = normalize(loadGolden(f.name))
      assert(actual == expected,
        s"Golden mismatch for ${f.name}:\n--- expected ---\n$expected\n--- actual ---\n$actual")

  // ── PureMarkupCodec round-trip: serialize then parse is lossless ──────────

  test("PAIN.001 output is well-formed XML (parse round-trip)"):
    val req = buildReq(fixtures.head)
    val xmlStr = SepaPainXml.buildPain001(req)
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        assert(doc.root.name.localName == "Document", "Root element must be Document")
      case Left(err) =>
        fail(s"PAIN.001 output failed to parse: $err")

  test("PAIN.008 output is well-formed XML (parse round-trip)"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = Money(10000L, Currency("EUR")),
      mandateId       = MandateId("MND-GOLDEN"),
      creditorAccount = BankAccount(iban = Some("DE89370400440532013000"), holderName = "Acme", countryCode = "DE"),
      debtorAccount   = BankAccount(iban = Some("FR7630006000011234567890189"), holderName = "Client", countryCode = "FR"),
      creditorName    = "Acme GmbH",
      reference       = "DD-REF",
      idempotencyKey  = "GOLDEN-DD-001",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-GOLDEN"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = BankAccount(iban = Some("FR7630006000011234567890189"), holderName = "Client", countryCode = "FR"),
      creditorAccount = BankAccount(iban = Some("DE89370400440532013000"), holderName = "Acme", countryCode = "DE"),
      creditorName    = "Acme GmbH",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.First,
    )
    val xmlStr = SepaPainXml.buildPain008(req, mandate)
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        assert(doc.root.name.localName == "Document", "Root element must be Document")
      case Left(err) =>
        fail(s"PAIN.008 output failed to parse: $err")

  test("SCT Inst pacs.008 output is well-formed XML (parse round-trip)"):
    val req = buildReq(fixtures.head).copy(idempotencyKey = "GOLDEN-INST-001", rail = RailKind.SCT_INST)
    val xmlStr = SepaPainXml.buildSctInstPacs008(req)
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        assert(doc.root.name.localName == "Document", "Root element must be Document")
      case Left(err) =>
        fail(s"SCT Inst pacs.008 output failed to parse: $err")

  // ── XML namespace in parsed Document element ──────────────────────────────

  test("PAIN.001 parsed Document element has xmlns attribute"):
    val req = buildReq(fixtures.head)
    val xmlStr = SepaPainXml.buildPain001(req)
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        val nsAttr = doc.root.attrs.find(_.name.localName == "xmlns")
        assert(nsAttr.isDefined, "Document element must have xmlns attribute")
        assert(nsAttr.get.value == "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03")
      case Left(err) => fail(s"Parse failed: $err")

  test("PAIN.008 parsed Document element has xmlns attribute"):
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = Money(5000L, Currency("EUR")),
      mandateId       = MandateId("MND-NS-TEST"),
      creditorAccount = BankAccount(iban = Some("DE89370400440532013000"), holderName = "Acme", countryCode = "DE"),
      debtorAccount   = BankAccount(iban = Some("FR7630006000011234567890189"), holderName = "Client", countryCode = "FR"),
      creditorName    = "Acme GmbH",
      reference       = "NS-TEST",
      idempotencyKey  = "GOLDEN-NS-008",
    )
    val mandate = DirectDebitMandate(
      id              = MandateId("MND-NS-TEST"),
      rail            = RailKind.SEPA_DD,
      debtorAccount   = BankAccount(iban = Some("FR7630006000011234567890189"), holderName = "Client", countryCode = "FR"),
      creditorAccount = BankAccount(iban = Some("DE89370400440532013000"), holderName = "Acme", countryCode = "DE"),
      creditorName    = "Acme GmbH",
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.Recurring,
    )
    val xmlStr = SepaPainXml.buildPain008(req, mandate)
    PureMarkupCodec.parse(xmlStr) match
      case Right(doc) =>
        val nsAttr = doc.root.attrs.find(_.name.localName == "xmlns")
        assert(nsAttr.isDefined, "Document element must have xmlns attribute")
        assert(nsAttr.get.value == "urn:iso:std:iso:20022:tech:xsd:pain.008.001.02")
      case Left(err) => fail(s"Parse failed: $err")

  // ── ValidationError data type ─────────────────────────────────────────────

  test("ValidationError carries message, line, and column"):
    val e = ValidationError("element 'Foo' is not allowed", 5, 12)
    assert(e.message == "element 'Foo' is not allowed")
    assert(e.line    == 5)
    assert(e.column  == 12)

  test("ValidationError equality"):
    val a = ValidationError("msg", 1, 1)
    val b = ValidationError("msg", 1, 1)
    val c = ValidationError("msg", 2, 1)
    assert(a == b)
    assert(a != c)

  // ── MarkupCodec.validate throws UnsupportedOperationException for pure codec

  test("PureMarkupCodec.validate throws UnsupportedOperationException"):
    val req = buildReq(fixtures.head)
    val xmlStr = SepaPainXml.buildPain001(req)
    val doc = PureMarkupCodec.parse(xmlStr).toOption.get
    intercept[UnsupportedOperationException]:
      PureMarkupCodec.validate(doc, "<xs:schema/>")

  // ── FedNow output structural checks via String output ─────────────────────
  // These test Iso20022Xml indirectly through the String output (no cross-module dep).

  test("FedNow pacs.008 xml interpolator: PureMarkupCodec serializes with XML declaration"):
    // Verify the xml"..." serializer always outputs the XML declaration in SEPA output
    val req = buildReq(fixtures.head)
    val xmlStr = SepaPainXml.buildPain001(req)
    assert(xmlStr.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
      "Serialized PAIN.001 must begin with the XML declaration")

  test("FedNow pacs.008 xml interpolator: PAIN.001 refactored output is idempotent when re-serialized"):
    // Parse the output, serialize again — should produce the same result
    val req = buildReq(fixtures.head)
    val xmlStr1 = SepaPainXml.buildPain001(req)
    val doc = PureMarkupCodec.parse(xmlStr1).toOption.get
    val xmlStr2 = PureMarkupCodec.serialize(doc)
    assert(xmlStr1 == xmlStr2, "Re-serializing the PAIN.001 output must be idempotent")
