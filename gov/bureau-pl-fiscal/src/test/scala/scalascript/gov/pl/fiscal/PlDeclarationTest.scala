package scalascript.gov.pl.fiscal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.signing.*
import java.time.{LocalDate, YearMonth}
import scala.annotation.nowarn
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

@nowarn("msg=not declared infix")
class PlDeclarationAdapterTest extends AnyFunSuite with Matchers:

  private given ExecutionContext = global
  private def await[T](f: Future[T]): T = Await.result(f, Duration(10, "seconds"))

  private val mockSigning = MockSigningProvider()
  private val cfg         = PlDeclarationConfig()

  private val sellerEntity = BusinessEntity(
    name      = "Tax Payer Sp. z o.o.",
    legalForm = LegalForm.LimitedLiabilityCompany,
    country   = CountryCode.PL,
    taxIds    = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address   = Address("ul. Podatkowa 1", None, "Warszawa", "00-001", CountryCode.PL),
  )

  private val sampleDecl = TaxDeclaration(
    declarationType = "JPK_VAT7M",
    period          = YearMonth.of(2024, 1),
    entity          = sellerEntity,
    xmlContent      = """<?xml version="1.0"?><JPK_VAT7M><Dokument><NIP>1234567890</NIP></Dokument></JPK_VAT7M>""",
    schemaVersion   = "1-0",
  )

  private val sampleAuditFile = AuditFile(
    fileType      = "JPK_FA",
    period        = YearMonth.of(2024, 1),
    entity        = sellerEntity,
    xmlContent    = """<?xml version="1.0"?><JPK_FA><NIP>1234567890</NIP></JPK_FA>""",
    schemaVersion = "3-0",
  )

  private val submitOkResp = """<?xml version="1.0"?><Response>
    <NumerReferencyjny>EDK-2024-001</NumerReferencyjny>
    <KodStatusu>303</KodStatusu>
  </Response>"""

  private val submitRejectResp = """<?xml version="1.0"?><Response>
    <NumerReferencyjny>EDK-2024-ERR</NumerReferencyjny>
    <KodBledu>SCHEMA_ERROR</KodBledu>
    <OpisBledu>Schema validation failed</OpisBledu>
  </Response>"""

  private val statusOkResp = """<?xml version="1.0"?><Response>
    <KodStatusu>200</KodStatusu>
    <NumerUPO>UPO-2024-001</NumerUPO>
  </Response>"""

  private val statusProcessingResp = """<?xml version="1.0"?><Response>
    <KodStatusu>WRealizacji</KodStatusu>
  </Response>"""

  private val statusRejectedResp = """<?xml version="1.0"?><Response>
    <KodStatusu>400</KodStatusu>
    <OpisBledu>Invalid VAT amount</OpisBledu>
  </Response>"""

  private def adapter(postResponses: List[String], getResponses: List[String] = Nil): PlDeclarationAdapter =
    val postQ = collection.mutable.Queue.from(postResponses)
    val getQ  = collection.mutable.Queue.from(getResponses)
    new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        if postQ.isEmpty then throw BureauError.ServiceUnavailable("no more post responses")
        else postQ.dequeue()
      override protected def getHttp(path: String): String =
        if getQ.isEmpty then throw BureauError.ServiceUnavailable("no more get responses")
        else getQ.dequeue()
    }

  test("submitDeclaration JPK_VAT7M returns Pending with reference number") {
    val a   = adapter(List(submitOkResp))
    val res = await(a.submitDeclaration(sampleDecl))
    res.submissionId shouldBe "EDK-2024-001"
    res.status.isInstanceOf[SubmissionStatus.Pending] shouldBe true
    val pending: SubmissionStatus.Pending = res.status.asInstanceOf[SubmissionStatus.Pending]
    pending.ticketId shouldBe "EDK-2024-001"
  }

  test("submitDeclaration returns Rejected when response contains error") {
    val a   = adapter(List(submitRejectResp))
    val res = await(a.submitDeclaration(sampleDecl))
    res.status.isInstanceOf[SubmissionStatus.Rejected] shouldBe true
    val rejected: SubmissionStatus.Rejected = res.status.asInstanceOf[SubmissionStatus.Rejected]
    rejected.errors should not be empty
    rejected.errors.head.code shouldBe "SCHEMA_ERROR"
    rejected.errors.head.message should include("Schema validation failed")
  }

  test("submitDeclaration includes signed XML in SOAP envelope") {
    val envelopes = collection.mutable.ListBuffer.empty[String]
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        envelopes += envelope
        submitOkResp
    }
    await(a.submitDeclaration(sampleDecl))
    envelopes should not be empty
    val env = envelopes.head
    env should include("PrzeslijDokument")
    env should include("JPK_VAT7M")
  }

  test("submitDeclaration CIT-8 type is passed correctly") {
    val citDecl = sampleDecl.copy(
      declarationType = "CIT-8",
      xmlContent      = """<?xml version="1.0"?><CIT-8><Dokument><NIP>1234567890</NIP></Dokument></CIT-8>""",
    )
    val envelopes = collection.mutable.ListBuffer.empty[String]
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        envelopes += envelope
        submitOkResp
    }
    await(a.submitDeclaration(citDecl))
    envelopes.head should include("CIT-8")
  }

  test("submitDeclaration PIT-36 type is passed correctly") {
    val pitDecl = sampleDecl.copy(
      declarationType = "PIT-36",
      xmlContent      = """<?xml version="1.0"?><PIT-36><Dokument/></PIT-36>""",
    )
    val envelopes = collection.mutable.ListBuffer.empty[String]
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        envelopes += envelope
        submitOkResp
    }
    await(a.submitDeclaration(pitDecl))
    envelopes.head should include("PIT-36")
  }

  test("pollDeclarationStatus returns Accepted with UPO reference") {
    val a   = adapter(Nil, List(statusOkResp))
    val res = await(a.pollDeclarationStatus("EDK-2024-001"))
    res.status shouldBe SubmissionStatus.Accepted
    res.reference shouldBe Some("UPO-2024-001")
  }

  test("pollDeclarationStatus returns Processing for WRealizacji") {
    val a   = adapter(Nil, List(statusProcessingResp))
    val res = await(a.pollDeclarationStatus("EDK-2024-001"))
    res.status shouldBe SubmissionStatus.Processing
  }

  test("pollDeclarationStatus returns Rejected for status 400") {
    val a   = adapter(Nil, List(statusRejectedResp))
    val res = await(a.pollDeclarationStatus("EDK-2024-001"))
    res.status.isInstanceOf[SubmissionStatus.Rejected] shouldBe true
    val rejected: SubmissionStatus.Rejected = res.status.asInstanceOf[SubmissionStatus.Rejected]
    rejected.errors.head.message should include("Invalid VAT amount")
  }

  test("pollDeclarationStatus returns Pending for unknown status code") {
    val pendingResp = """<?xml version="1.0"?><Response><KodStatusu>UNKNOWN</KodStatusu></Response>"""
    val a           = adapter(Nil, List(pendingResp))
    val res         = await(a.pollDeclarationStatus("EDK-2024-001"))
    res.status.isInstanceOf[SubmissionStatus.Pending] shouldBe true
  }

  test("submitAuditFile JPK_FA sends correct envelope") {
    val envelopes = collection.mutable.ListBuffer.empty[String]
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        envelopes += envelope
        submitOkResp
    }
    await(a.submitAuditFile(sampleAuditFile))
    envelopes.head should include("WyslijJpk")
    envelopes.head should include("JPK_FA")
  }

  test("submitAuditFile returns Pending with reference") {
    val a   = adapter(List(submitOkResp))
    val res = await(a.submitAuditFile(sampleAuditFile))
    res.submissionId shouldBe "EDK-2024-001"
    res.status.isInstanceOf[SubmissionStatus.Pending] shouldBe true
  }

  test("pollAuditFileStatus uses /statusjpk endpoint") {
    val paths = collection.mutable.ListBuffer.empty[String]
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def getHttp(path: String): String =
        paths += path
        statusOkResp
    }
    await(a.pollAuditFileStatus("JPK-REF-001"))
    paths.head should include("statusjpk")
    paths.head should include("JPK-REF-001")
  }

  test("pollAuditFileStatus returns Accepted for status 200") {
    val a   = adapter(Nil, List(statusOkResp))
    val res = await(a.pollAuditFileStatus("JPK-REF-001"))
    res.status shouldBe SubmissionStatus.Accepted
  }

  test("submitDeclaration on SOAP error throws BureauError.ApiError") {
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        throw BureauError.ApiError("e-Deklaracje SOAP 500", Some("500"), Some(500))
    }
    an[BureauError.ApiError] should be thrownBy await(a.submitDeclaration(sampleDecl))
  }

  test("submitDeclaration on 429 throws RateLimitError") {
    val a = new PlDeclarationAdapter(cfg, mockSigning) {
      override protected def postSoap(path: String, envelope: String): String =
        throw BureauError.RateLimitError(Some(60))
    }
    an[BureauError.RateLimitError] should be thrownBy await(a.submitDeclaration(sampleDecl))
  }

  test("submitInvoice throws UnsupportedOperation (KSeF only)") {
    val a  = adapter(Nil)
    val ex = intercept[BureauError.UnsupportedOperation] {
      val fakeInvoice = scalascript.gov.fiscal.FiscalInvoice(
        invoiceNumber = "FV/001", issueDate = LocalDate.now(),
        seller = sellerEntity, buyer = sellerEntity, lines = Nil, taxSummary = Nil,
        totalNet = scalascript.payments.money.Money(0, scalascript.payments.money.Currency("PLN")),
        totalTax = scalascript.payments.money.Money(0, scalascript.payments.money.Currency("PLN")),
        totalGross = scalascript.payments.money.Money(0, scalascript.payments.money.Currency("PLN")),
        currency = scalascript.payments.money.Currency("PLN"),
      )
      await(a.submitInvoice(fakeInvoice))
    }
    ex.getMessage should include("PlKsefAdapter")
  }

  test("parseSubmitResponse extracts reference number from SOAP response") {
    val a   = adapter(Nil)
    val xml = """<Response><NumerReferencyjny>TEST-REF-123</NumerReferencyjny></Response>"""
    val res = a.parseSubmitResponse(xml)
    res.submissionId shouldBe "TEST-REF-123"
  }

  test("extractXmlTag handles namespaced tags") {
    val a   = adapter(Nil)
    val xml = """<ns:Tag xmlns:ns="http://test"><ns:Value>hello</ns:Value></ns:Tag>"""
    a.extractXmlTag(xml, "Value") shouldBe Some("hello")
  }

  test("extractXmlTag returns None for missing tag") {
    val a = adapter(Nil)
    a.extractXmlTag("<root></root>", "missing") shouldBe None
  }
