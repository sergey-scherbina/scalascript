package scalascript.gov

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import scala.annotation.nowarn

@nowarn("msg=not declared infix")
class BureauCoreTest extends AnyFunSuite with Matchers:

  // ── CountryCode ────────────────────────────────────────────────────────────

  test("CountryCode constants are uppercase 2-char strings") {
    CountryCode.PL shouldBe "PL"
    CountryCode.DE shouldBe "DE"
    CountryCode.EU shouldBe "EU"
  }

  test("CountryCode.apply uppercases and accepts 2-char input") {
    CountryCode("pl") shouldBe "PL"
    CountryCode("de") shouldBe "DE"
  }

  test("CountryCode.apply rejects non-2-char input") {
    an[IllegalArgumentException] should be thrownBy CountryCode("POL")
    an[IllegalArgumentException] should be thrownBy CountryCode("P")
  }

  test("CountryCode is a subtype of String") {
    val code: String = CountryCode.PL
    code shouldBe "PL"
  }

  // ── LegalForm ─────────────────────────────────────────────────────────────

  test("LegalForm.Other carries name") {
    val form: LegalForm.Other = LegalForm.Other("Sole Trader")
    form.name shouldBe "Sole Trader"
  }

  test("LegalForm sealed hierarchy has expected cases") {
    val forms: List[LegalForm] = List(
      LegalForm.SoleProprietor,
      LegalForm.LimitedLiabilityCompany,
      LegalForm.JointStockCompany,
      LegalForm.GeneralPartnership,
      LegalForm.LimitedPartnership,
      LegalForm.Foundation,
      LegalForm.Association,
      LegalForm.Branch
    )
    forms.size shouldBe 8
  }

  // ── TaxIdentifier ─────────────────────────────────────────────────────────

  test("TaxId is a subtype of String") {
    val id: String = TaxId("1234567890")
    id shouldBe "1234567890"
  }

  test("TaxIdentifier holds type, value, country") {
    val ti = TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)
    ti.idType shouldBe TaxIdType.NIP
    ti.value shouldBe "1234567890"
    ti.country shouldBe "PL"
  }

  test("TaxIdType.Other carries country and name") {
    val t: TaxIdType.Other = TaxIdType.Other(CountryCode.UA, "ЄДРПОУ")
    t.name shouldBe "ЄДРПОУ"
  }

  // ── BusinessEntity ────────────────────────────────────────────────────────

  private def sampleAddress = Address(
    line1      = "ul. Testowa 1",
    city       = "Warszawa",
    postalCode = "00-001",
    country    = CountryCode.PL
  )

  private def sampleEntity(taxIds: List[TaxIdentifier] = Nil) = BusinessEntity(
    name      = "Test Sp. z o.o.",
    legalForm = LegalForm.LimitedLiabilityCompany,
    country   = CountryCode.PL,
    taxIds    = taxIds,
    address   = sampleAddress
  )

  test("BusinessEntity.taxId returns Some when present") {
    val entity = sampleEntity(List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    entity.taxId(TaxIdType.NIP) shouldBe Some(TaxId("1234567890"))
  }

  test("BusinessEntity.taxId returns None when absent") {
    sampleEntity().taxId(TaxIdType.KRS) shouldBe None
  }

  test("BusinessEntity.requireTaxId returns value when present") {
    val entity = sampleEntity(List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    entity.requireTaxId(TaxIdType.NIP) shouldBe TaxId("1234567890")
  }

  test("BusinessEntity.requireTaxId throws MissingTaxId when absent") {
    val ex = intercept[BureauError.MissingTaxId] {
      sampleEntity().requireTaxId(TaxIdType.REGON)
    }
    ex.idType shouldBe TaxIdType.REGON
    ex.country shouldBe CountryCode.PL
  }

  // ── SubmissionStatus ──────────────────────────────────────────────────────

  test("SubmissionStatus.Pending carries ticketId") {
    val s: SubmissionStatus.Pending = SubmissionStatus.Pending("TICKET-001")
    s.ticketId shouldBe "TICKET-001"
  }

  test("SubmissionStatus.Rejected carries error list") {
    val errors = List(GovError("E001", "Invalid NIP", Some("nip")))
    val s: SubmissionStatus.Rejected = SubmissionStatus.Rejected(errors)
    s.errors should have size 1
    s.errors.head.code shouldBe "E001"
  }

  test("SubmissionResult construction") {
    val result = SubmissionResult(
      submissionId = "SUB-001",
      status       = SubmissionStatus.Accepted,
      timestamp    = Instant.parse("2026-05-28T10:00:00Z"),
      reference    = Some("REF-PL-2026-001")
    )
    result.submissionId shouldBe "SUB-001"
    result.status shouldBe SubmissionStatus.Accepted
    result.reference shouldBe Some("REF-PL-2026-001")
    result.warnings shouldBe Nil
  }

  // ── BureauError hierarchy ─────────────────────────────────────────────────

  test("BureauError.ApiError carries message, code, httpStatus") {
    val e = BureauError.ApiError("Bad Request", Some("E400"), Some(400))
    e.getMessage shouldBe "Bad Request"
    e.code shouldBe Some("E400")
    e.httpStatus shouldBe Some(400)
  }

  test("BureauError.AuthenticationError carries message") {
    val e = BureauError.AuthenticationError("Invalid token")
    e.getMessage shouldBe "Invalid token"
  }

  test("BureauError.MissingTaxId formats message from idType and country") {
    val e = BureauError.MissingTaxId(TaxIdType.NIP, CountryCode.PL)
    e.getMessage should include("PL")
    e.getMessage should include("NIP")
    e.idType shouldBe TaxIdType.NIP
    e.country shouldBe "PL"
  }

  test("BureauError.UnsupportedOperation formats message") {
    val e = BureauError.UnsupportedOperation(CountryCode.DE, GovDomain.Fiscal, "submitAuditFile")
    e.getMessage should include("DE")
    e.getMessage should include("Fiscal")
    e.getMessage should include("submitAuditFile")
  }

  test("BureauError.RateLimitError with and without retry hint") {
    val withHint = BureauError.RateLimitError(Some(60))
    val noHint   = BureauError.RateLimitError()
    withHint.retryAfterSeconds shouldBe Some(60)
    noHint.retryAfterSeconds shouldBe None
  }

  test("BureauError.SubmissionRejected carries result") {
    val result = SubmissionResult("S1", SubmissionStatus.Rejected(Nil), Instant.now())
    val e = BureauError.SubmissionRejected(result)
    e.result.submissionId shouldBe "S1"
    e.getMessage should include("S1")
  }

  test("BureauError is a subtype of Exception") {
    val e: Exception = BureauError.ServiceUnavailable("down")
    e.getMessage shouldBe "down"
  }

  // ── GovDomain ─────────────────────────────────────────────────────────────

  test("GovDomain has all expected values") {
    val domains = Set(
      GovDomain.Fiscal, GovDomain.Social, GovDomain.Registry,
      GovDomain.Customs, GovDomain.Statistics, GovDomain.Environment, GovDomain.Identity
    )
    domains.size shouldBe 7
  }
