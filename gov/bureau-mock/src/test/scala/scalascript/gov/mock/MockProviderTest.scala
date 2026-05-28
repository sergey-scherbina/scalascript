package scalascript.gov.mock

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.registry.*
import scalascript.gov.social.*
import scalascript.payments.money.{Currency, Money}
import java.time.{LocalDate, YearMonth}
import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

// ─── helpers ────────────────────────────────────────────────────────────────

private def plEntity: BusinessEntity = BusinessEntity(
  name      = "TEST SP Z O O",
  legalForm = LegalForm.LimitedLiabilityCompany,
  country   = CountryCode.PL,
  taxIds    = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
  address   = Address(line1 = "ul. Test 1", postalCode = "00-001", city = "Warszawa", country = CountryCode.PL),
)

private def mockDeclaration = ContributionDeclaration("DRA", YearMonth.of(2024, 1), plEntity, "<xml/>", "4-0")
private def mockEmployee    = EmployeeRecord("Jan", "Kowalski", None, None,
  LocalDate.of(1990, 1, 1),
  Address(line1 = "ul. Prosta 1", postalCode = "00-001", city = "Warszawa", country = CountryCode.PL),
  ContractType.Employment, LocalDate.of(2024, 1, 1), plEntity)

// ─── MockBureauProviderTest ──────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class MockBureauProviderTest extends AnyFunSuite with Matchers:

  test("poland() creates PL country provider") {
    val p = MockBureauProvider.poland()
    p.country shouldBe CountryCode.PL
    p.displayName shouldBe "Poland"
  }

  test("poland() has fiscal, social, registry providers") {
    val p = MockBureauProvider.poland()
    p.fiscal.isDefined   shouldBe true
    p.social.isDefined   shouldBe true
    p.registry.isDefined shouldBe true
  }

  test("poland() capabilities include Fiscal, Social, Registry") {
    val p = MockBureauProvider.poland()
    p.capabilities should contain(GovDomain.Fiscal)
    p.capabilities should contain(GovDomain.Social)
    p.capabilities should contain(GovDomain.Registry)
  }

  test("vat() creates EU country provider") {
    val p = MockBureauProvider.vat()
    p.country shouldBe CountryCode.EU
    p.fiscal.isDefined   shouldBe true
    p.registry.isDefined shouldBe true
    p.social shouldBe None
  }

  test("all() provides all GovDomain capabilities") {
    val p = MockBureauProvider.all()
    GovDomain.values.filterNot(_ == GovDomain.Identity).foreach { d =>
      p.capabilities should contain(d)
    }
  }

  test("poland(succeed=false) fiscal returns failure") {
    val p = MockBureauProvider.poland(succeed = false)
    intercept[BureauError.ApiError] {
      Await.result(p.fiscal.get.submitDeclaration(
        TaxDeclaration("CIT-8", YearMonth.of(2024, 1), plEntity, "", "4-0")
      )(using global), Duration.Inf)
    }
  }

// ─── MockFiscalProviderTest ──────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class MockFiscalProviderTest extends AnyFunSuite with Matchers:

  test("submitDeclaration records call and returns Pending") {
    val p    = MockFiscalProvider()
    val decl = TaxDeclaration("CIT-8", YearMonth.of(2024, 1), plEntity, "", "4-0")
    Await.result(p.submitDeclaration(decl)(using global), Duration.Inf)
    p.recordedDeclarations should have size 1
    p.recordedDeclarations.head shouldBe decl
  }

  test("submitDeclaration with succeed=false returns failure") {
    val p = MockFiscalProvider(succeed = false)
    intercept[BureauError.ApiError] {
      Await.result(p.submitDeclaration(TaxDeclaration("VAT-7", YearMonth.of(2024, 1), plEntity, "", "4-0"))(using global), Duration.Inf)
    }
  }

  test("pollDeclarationStatus returns Accepted when succeed=true") {
    val p      = MockFiscalProvider()
    val result = Await.result(p.pollDeclarationStatus("ticket-1")(using global), Duration.Inf)
    result.status shouldBe SubmissionStatus.Accepted
  }

  test("submitAuditFile records call") {
    val p    = MockFiscalProvider()
    val file = AuditFile("JPK_VAT7M", YearMonth.of(2024, 1), plEntity, "<xml/>", "4-0")
    Await.result(p.submitAuditFile(file)(using global), Duration.Inf)
    p.recordedAuditFiles should have size 1
  }

  test("verifyVatNumber records call and returns valid=true") {
    val p      = MockFiscalProvider()
    val id     = TaxIdentifier(TaxIdType.VatEU, TaxId("PL1234567890"), CountryCode.PL)
    val result = Await.result(p.verifyVatNumber(id)(using global), Duration.Inf)
    result.valid shouldBe true
    p.recordedVatChecks should have size 1
  }

  test("verifyVatNumber with succeed=false returns valid=false (not throws)") {
    val p      = MockFiscalProvider(succeed = false)
    val id     = TaxIdentifier(TaxIdType.VatEU, TaxId("PL0000000000"), CountryCode.PL)
    val result = Await.result(p.verifyVatNumber(id)(using global), Duration.Inf)
    result.valid shouldBe false
  }

  test("reset clears all recorded calls") {
    val p = MockFiscalProvider()
    Await.result(p.submitDeclaration(TaxDeclaration("CIT-8", YearMonth.of(2024, 1), plEntity, "", "4-0"))(using global), Duration.Inf)
    p.reset()
    p.recordedDeclarations shouldBe empty
  }

  test("queryInvoices returns empty list when succeed=true") {
    val p      = MockFiscalProvider()
    val result = Await.result(p.queryInvoices(InvoiceFilter())(using global), Duration.Inf)
    result shouldBe Nil
  }

// ─── MockSocialProviderTest ──────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class MockSocialProviderTest extends AnyFunSuite with Matchers:

  test("submitDeclaration records call") {
    val p = MockSocialProvider()
    Await.result(p.submitDeclaration(mockDeclaration)(using global), Duration.Inf)
    p.recordedDeclarations should have size 1
  }

  test("registerEmployee records call") {
    val p = MockSocialProvider()
    Await.result(p.registerEmployee(mockEmployee)(using global), Duration.Inf)
    p.recordedRegistrations should have size 1
    p.recordedRegistrations.head.firstName shouldBe "Jan"
  }

  test("deregisterEmployee records call with reason") {
    val p = MockSocialProvider()
    Await.result(p.deregisterEmployee(mockEmployee, DeregistrationReason.Termination, LocalDate.of(2024, 12, 31))(using global), Duration.Inf)
    p.recordedDeregistrations should have size 1
    p.recordedDeregistrations.head._2 shouldBe DeregistrationReason.Termination
  }

  test("updateEmployee records call") {
    val p = MockSocialProvider()
    Await.result(p.updateEmployee(mockEmployee)(using global), Duration.Inf)
    p.recordedUpdates should have size 1
  }

  test("calculateContributions returns zero totals") {
    val p      = MockSocialProvider()
    val params = ContributionParams(plEntity, YearMonth.of(2024, 1), Money(500000L, Currency("PLN")), ContributionBase.SelfEmployed)
    val result = Await.result(p.calculateContributions(params)(using global), Duration.Inf)
    result.total.minorUnits shouldBe 0L
  }

  test("getPaymentReference returns reference with PLN currency") {
    val p   = MockSocialProvider()
    val ref = Await.result(p.getPaymentReference(plEntity, YearMonth.of(2024, 1))(using global), Duration.Inf)
    ref.period shouldBe YearMonth.of(2024, 1)
    ref.amount.currency.code shouldBe "PLN"
  }

  test("succeed=false submitDeclaration fails") {
    val p = MockSocialProvider(succeed = false)
    intercept[BureauError.ApiError] {
      Await.result(p.submitDeclaration(mockDeclaration)(using global), Duration.Inf)
    }
  }

  test("reset clears all recorded calls") {
    val p = MockSocialProvider()
    Await.result(p.registerEmployee(mockEmployee)(using global), Duration.Inf)
    p.reset()
    p.recordedRegistrations shouldBe empty
  }

// ─── MockRegistryProviderTest ────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class MockRegistryProviderTest extends AnyFunSuite with Matchers:

  private val vatId = TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)

  test("lookup records call") {
    val p = MockRegistryProvider()
    Await.result(p.lookup(vatId)(using global), Duration.Inf)
    p.recordedLookups should have size 1
  }

  test("lookup returns None by default") {
    val p      = MockRegistryProvider()
    val result = Await.result(p.lookup(vatId)(using global), Duration.Inf)
    result shouldBe None
  }

  test("lookup returns defaultRecord when set") {
    val record = BusinessRecord("TEST CO", None, List(vatId), None, RegistrationStatus.Active, None)
    val p      = MockRegistryProvider(defaultRecord = Some(record))
    val result = Await.result(p.lookup(vatId)(using global), Duration.Inf)
    result shouldBe Some(record)
  }

  test("search records call") {
    val p = MockRegistryProvider()
    Await.result(p.search("test", CountryCode.PL)(using global), Duration.Inf)
    p.recordedSearches should have size 1
    p.recordedSearches.head._1 shouldBe "test"
  }

  test("checkVatStatus returns active=true by default") {
    val p      = MockRegistryProvider()
    val status = Await.result(p.checkVatStatus(vatId)(using global), Duration.Inf)
    status.active shouldBe true
  }

  test("checkVatStatus uses defaultStatus when set") {
    import java.time.Instant
    val custom = VatPayerStatus(active = false, id = vatId, name = Some("CLOSED"), bankAccounts = Nil, checkedAt = Instant.now())
    val p      = MockRegistryProvider(defaultStatus = Some(custom))
    val status = Await.result(p.checkVatStatus(vatId)(using global), Duration.Inf)
    status.active shouldBe false
    status.name shouldBe Some("CLOSED")
  }

  test("succeed=false lookup fails") {
    val p = MockRegistryProvider(succeed = false)
    intercept[BureauError.ApiError] {
      Await.result(p.lookup(vatId)(using global), Duration.Inf)
    }
  }

  test("reset clears recorded calls") {
    val p = MockRegistryProvider()
    Await.result(p.lookup(vatId)(using global), Duration.Inf)
    p.reset()
    p.recordedLookups shouldBe empty
  }

  test("getDetails returns RegistrationDetails when defaultRecord set") {
    val record  = BusinessRecord("TEST CO", None, List(vatId), None, RegistrationStatus.Active, None)
    val p       = MockRegistryProvider(defaultRecord = Some(record))
    val details = Await.result(p.getDetails(vatId)(using global), Duration.Inf)
    details.record.name shouldBe "TEST CO"
  }

  test("getDetails fails when no defaultRecord") {
    val p = MockRegistryProvider()
    intercept[BureauError.ApiError] {
      Await.result(p.getDetails(vatId)(using global), Duration.Inf)
    }
  }
