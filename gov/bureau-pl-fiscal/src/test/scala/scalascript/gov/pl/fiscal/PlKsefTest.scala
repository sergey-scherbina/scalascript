package scalascript.gov.pl.fiscal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.signing.*
import scalascript.payments.money.{Currency, Money}
import java.time.{LocalDate, YearMonth}
import scala.annotation.nowarn
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

@nowarn("msg=not declared infix")
class KsefSessionStoreTest extends AnyFunSuite with Matchers:

  test("KsefSessionStore is empty initially") {
    val store = KsefSessionStore()
    store.get() shouldBe None
    store.isValid shouldBe false
  }

  test("KsefSessionStore stores and retrieves a token") {
    val store = KsefSessionStore()
    store.put("abc123")
    store.get() shouldBe Some("abc123")
    store.isValid shouldBe true
  }

  test("KsefSessionStore invalidate clears the token") {
    val store = KsefSessionStore()
    store.put("abc123")
    store.invalidate()
    store.get() shouldBe None
  }

  test("KsefSessionStore expired token returns None") {
    val store = KsefSessionStore(ttlSeconds = -1L)
    store.put("expired-token")
    store.get() shouldBe None
  }

  test("KsefSessionStore re-put overwrites previous token") {
    val store = KsefSessionStore()
    store.put("token-1")
    store.put("token-2")
    store.get() shouldBe Some("token-2")
  }

@nowarn("msg=not declared infix")
class KsefXmlBuilderTest extends AnyFunSuite with Matchers:

  private val sellerEntity = BusinessEntity(
    name      = "Seller Company Sp. z o.o.",
    legalForm = LegalForm.LimitedLiabilityCompany,
    country   = CountryCode.PL,
    taxIds    = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address   = Address("ul. Sprzedawcy 1", None, "Warszawa", "00-001", CountryCode.PL),
  )

  private val buyerEntity = BusinessEntity(
    name      = "Buyer Corp & Co.",
    legalForm = LegalForm.LimitedLiabilityCompany,
    country   = CountryCode.PL,
    taxIds    = List(TaxIdentifier(TaxIdType.NIP, TaxId("9876543210"), CountryCode.PL)),
    address   = Address("ul. Kupca 5", None, "Kraków", "30-001", CountryCode.PL),
  )

  private val invoice = FiscalInvoice(
    invoiceNumber = "FV/2024/001",
    issueDate     = LocalDate.of(2024, 1, 15),
    seller        = sellerEntity,
    buyer         = buyerEntity,
    lines         = List(
      InvoiceLine("Software license", BigDecimal("1"), "szt.", Money(100000, Currency("PLN")), VatRate.Standard,
        Money(100000, Currency("PLN")), Money(23000, Currency("PLN")))
    ),
    taxSummary    = List(
      TaxSummaryLine(VatRate.Standard, Money(100000, Currency("PLN")), Money(23000, Currency("PLN")), Money(123000, Currency("PLN")))
    ),
    totalNet      = Money(100000, Currency("PLN")),
    totalTax      = Money(23000, Currency("PLN")),
    totalGross    = Money(123000, Currency("PLN")),
    currency      = Currency("PLN"),
  )

  test("buildFaVatXml produces valid XML declaration") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should startWith("<?xml version=\"1.0\"")
    xml should include("xmlns=\"http://crd.gov.pl/wzor")
  }

  test("buildFaVatXml includes seller NIP") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<NIP>1234567890</NIP>")
  }

  test("buildFaVatXml includes buyer name") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("Buyer Corp &amp; Co.")
  }

  test("buildFaVatXml escapes XML special chars in seller name") {
    val dangerInvoice = invoice.copy(seller = sellerEntity.copy(name = "A&B <Ltd>"))
    val xml = KsefXmlBuilder.buildFaVatXml(dangerInvoice)
    xml should include("A&amp;B &lt;Ltd&gt;")
    xml should not include "<Ltd>"
  }

  test("buildFaVatXml includes invoice number") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<P_2>FV/2024/001</P_2>")
  }

  test("buildFaVatXml includes issue date") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<P_1>2024-01-15</P_1>")
  }

  test("buildFaVatXml includes currency code") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<KodWaluty>PLN</KodWaluty>")
  }

  test("buildFaVatXml includes total gross amount") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<P_15>1230.00</P_15>")
  }

  test("buildFaVatXml includes line items") {
    val xml = KsefXmlBuilder.buildFaVatXml(invoice)
    xml should include("<FaWiersz>")
    xml should include("Software license")
  }

  test("buildFaVatXml escape function handles all XML special chars") {
    KsefXmlBuilder.escape("&") shouldBe "&amp;"
    KsefXmlBuilder.escape("<") shouldBe "&lt;"
    KsefXmlBuilder.escape(">") shouldBe "&gt;"
    KsefXmlBuilder.escape("\"") shouldBe "&quot;"
    KsefXmlBuilder.escape("'") shouldBe "&apos;"
  }

  test("parseFaVatXml round-trips key fields") {
    val xml    = KsefXmlBuilder.buildFaVatXml(invoice)
    val fields = KsefXmlBuilder.parseFaVatXml(xml)
    fields.get("P_2") shouldBe Some("FV/2024/001")
    fields.get("P_1") shouldBe Some("2024-01-15")
    fields.get("KodWaluty") shouldBe Some("PLN")
  }

@nowarn("msg=not declared infix")
class PlKsefAdapterTest extends AnyFunSuite with Matchers:

  private given ExecutionContext = global
  private def await[T](f: Future[T]): T = Await.result(f, Duration(10, "seconds"))

  private val mockSigning = MockSigningProvider()
  private val cfg         = PlKsefConfig(nip = "1234567890")

  private val challengeResp   = """{"challenge":"abc-challenge-xyz","timestamp":"2024-01-15T10:00:00Z"}"""
  private val authOkResp      = """{"sessionToken":"SESSION-TOKEN-001","referenceNumber":"ref-001"}"""
  private val submitOkResp    = """{"elementReferenceNumber":"KSEF-REF-001"}"""
  private val statusOkResp    = """{"invoiceStatus":"200","ksefReferenceNumber":"KSEF-INV-001"}"""
  private val statusPendResp  = """{"invoiceStatus":"100","processingCode":"PROCESSING"}"""
  private val statusRejResp   = """{"invoiceStatus":"400","message":"Schema validation failed"}"""
  private val fetchResp       = """{"invoiceData":"PD94bWwgdmVyc2lvbj0iMS4wIj48UF8yPkZWLzIwMjQvMDAxPC9QXzI+PFBfMT4yMDI0LTAxLTE1PC9QXzE+PEtvZFdhbHV0eT5QTE48L0tvZFdhbHV0eT48UF8xNT4xMjMwLjAwPC9QXzE1PjwvcGF5bG9hZD4="}"""
  private val queryResp       = """{"invoiceHeaderList":[{"ksefReferenceNumber":"KSEF-001","invoiceDate":"2024-01-15"},{"ksefReferenceNumber":"KSEF-002","invoiceDate":"2024-01-16"}]}"""

  private val sellerEntity = BusinessEntity(
    name = "Seller Sp. z o.o.", legalForm = LegalForm.LimitedLiabilityCompany, country = CountryCode.PL,
    taxIds = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address = Address("ul. Testowa 1", None, "Warszawa", "00-001", CountryCode.PL),
  )
  private val buyerEntity = sellerEntity.copy(name = "Buyer Sp. z o.o.")
  private val sampleInvoice = FiscalInvoice(
    invoiceNumber = "FV/2024/001", issueDate = LocalDate.of(2024, 1, 15),
    seller = sellerEntity, buyer = buyerEntity, lines = Nil, taxSummary = Nil,
    totalNet = Money(100000, Currency("PLN")), totalTax = Money(23000, Currency("PLN")),
    totalGross = Money(123000, Currency("PLN")), currency = Currency("PLN"),
  )

  private def adapter(responses: List[String]): PlKsefAdapter =
    val queue = collection.mutable.Queue.from(responses)
    new PlKsefAdapter(cfg, mockSigning) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        if queue.isEmpty then throw BureauError.ServiceUnavailable("no more mock responses")
        else queue.dequeue()
      override protected def getJson(path: String, token: Option[String]): String =
        if queue.isEmpty then throw BureauError.ServiceUnavailable("no more mock responses")
        else queue.dequeue()
      override protected def deleteJson(path: String, token: Option[String]): String =
        if queue.isEmpty then "{}" else queue.dequeue()
    }

  private def adapterWithSession(responses: List[String]): PlKsefAdapter =
    val queue = collection.mutable.Queue.from(responses)
    val store = KsefSessionStore()
    store.put("PREFILLED-SESSION-TOKEN")
    new PlKsefAdapter(cfg, mockSigning, store) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        if queue.isEmpty then throw BureauError.ServiceUnavailable("no more mock responses")
        else queue.dequeue()
      override protected def getJson(path: String, token: Option[String]): String =
        if queue.isEmpty then throw BureauError.ServiceUnavailable("no more mock responses")
        else queue.dequeue()
      override protected def deleteJson(path: String, token: Option[String]): String = "{}"
    }

  test("submitInvoice triggers auth challenge → sign → authorise → send") {
    val a   = adapter(List(challengeResp, authOkResp, submitOkResp))
    val res = await(a.submitInvoice(sampleInvoice))
    res.submissionResult.submissionId shouldBe "KSEF-REF-001"
    val status: SubmissionStatus = res.submissionResult.status
    status.isInstanceOf[SubmissionStatus.Pending] shouldBe true
  }

  test("submitInvoice with existing session token skips auth") {
    val a   = adapterWithSession(List(submitOkResp))
    val res = await(a.submitInvoice(sampleInvoice))
    res.submissionResult.submissionId shouldBe "KSEF-REF-001"
  }

  test("pollInvoiceStatus returns Accepted for status 200") {
    val a   = adapterWithSession(List(statusOkResp))
    val res = await(a.pollInvoiceStatus("KSEF-REF-001"))
    res.submissionResult.status shouldBe SubmissionStatus.Accepted
    res.invoiceId shouldBe Some("KSEF-INV-001")
  }

  test("pollInvoiceStatus returns Processing for status 100") {
    val a   = adapterWithSession(List(statusPendResp))
    val res = await(a.pollInvoiceStatus("KSEF-REF-001"))
    res.submissionResult.status shouldBe SubmissionStatus.Processing
  }

  test("pollInvoiceStatus returns Rejected for status 400") {
    val a   = adapterWithSession(List(statusRejResp))
    val res = await(a.pollInvoiceStatus("KSEF-REF-001"))
    val status: SubmissionStatus = res.submissionResult.status
    status.isInstanceOf[SubmissionStatus.Rejected] shouldBe true
    val rejected: SubmissionStatus.Rejected = status.asInstanceOf[SubmissionStatus.Rejected]
    rejected.errors should not be empty
  }

  test("fetchInvoice decodes base64 XML and returns FiscalInvoice") {
    val a   = adapterWithSession(List(fetchResp))
    val inv = await(a.fetchInvoice("KSEF-INV-001"))
    inv.metadata.get("ksefId") shouldBe Some("KSEF-INV-001")
  }

  test("queryInvoices parses KSeF reference numbers from response") {
    val a       = adapterWithSession(List(queryResp))
    val results = await(a.queryInvoices(InvoiceFilter()))
    results should have length 2
    results.map(_.id) should contain("KSEF-001")
    results.map(_.id) should contain("KSEF-002")
  }

  test("queryInvoices returns empty list when no results") {
    val a       = adapterWithSession(List("""{"invoiceHeaderList":[]}"""))
    val results = await(a.queryInvoices(InvoiceFilter()))
    results shouldBe Nil
  }

  test("submitInvoice on 401 throws AuthenticationError") {
    val a = new PlKsefAdapter(cfg, mockSigning) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        if path.contains("AuthorisationChallenge") then challengeResp
        else if path.contains("Authorisation") then authOkResp
        else throw BureauError.AuthenticationError("KSeF 401 Unauthorized: /online/Invoice/Send")
    }
    an[BureauError.AuthenticationError] should be thrownBy await(a.submitInvoice(sampleInvoice))
  }

  test("submitInvoice on 429 throws RateLimitError") {
    val a = new PlKsefAdapter(cfg, mockSigning) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        if path.contains("AuthorisationChallenge") then challengeResp
        else if path.contains("Authorisation") then authOkResp
        else throw BureauError.RateLimitError(Some(60))
    }
    an[BureauError.RateLimitError] should be thrownBy await(a.submitInvoice(sampleInvoice))
  }

  test("submitInvoice on 503 throws ServiceUnavailable") {
    val a = new PlKsefAdapter(cfg, mockSigning) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        if path.contains("AuthorisationChallenge") then challengeResp
        else if path.contains("Authorisation") then authOkResp
        else throw BureauError.ServiceUnavailable("KSeF 503 Service Unavailable: /online/Invoice/Send")
    }
    an[BureauError.ServiceUnavailable] should be thrownBy await(a.submitInvoice(sampleInvoice))
  }

  test("submitDeclaration throws UnsupportedOperation") {
    val a    = adapterWithSession(Nil)
    val decl = TaxDeclaration("JPK_VAT7M", YearMonth.of(2024, 1), sellerEntity, "<xml/>", "1-0")
    val ex   = intercept[BureauError.UnsupportedOperation] {
      await(a.submitDeclaration(decl))
    }
    ex.getMessage should include("PlDeclarationAdapter")
  }

  test("verifyVatNumber throws UnsupportedOperation") {
    val a  = adapterWithSession(Nil)
    val ex = intercept[BureauError.UnsupportedOperation] {
      await(a.verifyVatNumber(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    }
    ex.getMessage should include("PlRegistryProvider")
  }

  test("terminateSession calls DELETE and invalidates session store") {
    val store = KsefSessionStore()
    store.put("SESSION-TO-TERMINATE")
    val a = new PlKsefAdapter(cfg, mockSigning, store) {
      override protected def deleteJson(path: String, token: Option[String]): String = "{}"
    }
    await(a.terminateSession())
    store.isValid shouldBe false
  }

  test("extractJsonField parses JSON string field") {
    val a = adapterWithSession(Nil)
    a.extractJsonField("""{"key":"value"}""", "key") shouldBe Some("value")
    a.extractJsonField("""{"key":""}""", "key") shouldBe None
    a.extractJsonField("{}", "missing") shouldBe None
  }

  test("auth challenge is signed and included in auth request body") {
    val requests = collection.mutable.ListBuffer.empty[(String, String)]
    val a = new PlKsefAdapter(cfg, mockSigning) {
      override protected def postJson(path: String, body: String, token: Option[String]): String =
        requests += path -> body
        if path.contains("AuthorisationChallenge") then challengeResp
        else if path.contains("Authorisation") then authOkResp
        else submitOkResp
    }
    await(a.submitInvoice(sampleInvoice))
    val authReqBody = requests.find(_._2.contains("authorisationChallenge")).map(_._2).getOrElse("")
    authReqBody should include("abc-challenge-xyz")
    authReqBody should include("signature")
  }
