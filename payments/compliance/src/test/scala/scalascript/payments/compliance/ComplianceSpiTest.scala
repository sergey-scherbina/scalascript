package scalascript.payments.compliance

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class ComplianceSpiTest extends AnyFunSuite with Matchers:

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private val individual = ComplianceEntity(
    name        = "John Doe",
    entityType  = EntityType.Individual,
    country     = Some("US"),
    dateOfBirth = Some("1990-01-15"),
    nationality = Some("US")
  )

  // ── ComplianceEntity ─────────────────────────────────────────────────

  test("ComplianceEntity stores all fields"):
    individual.name          shouldBe "John Doe"
    individual.entityType    shouldBe EntityType.Individual
    individual.country       shouldBe Some("US")
    individual.dateOfBirth   shouldBe Some("1990-01-15")
    individual.nationality   shouldBe Some("US")

  test("ComplianceEntity defaults to empty metadata and no optional fields"):
    val e = ComplianceEntity("Test", EntityType.Organization)
    e.country          shouldBe None
    e.dateOfBirth      shouldBe None
    e.taxId            shouldBe None
    e.metadata         shouldBe Map.empty

  // ── BlockchainAddress ─────────────────────────────────────────────────

  test("BlockchainAddress stores address, asset, network, direction"):
    val addr = BlockchainAddress("0xabc", "ETH", "ethereum", TransferDirection.Received)
    addr.address   shouldBe "0xabc"
    addr.asset     shouldBe "ETH"
    addr.network   shouldBe "ethereum"
    addr.direction shouldBe TransferDirection.Received

  // ── AmlResult ─────────────────────────────────────────────────────────

  test("AmlResult stores entity, status, riskLevel, matchCount"):
    val result = AmlResult(individual, ComplianceStatus.Approved, RiskLevel.Low, 0)
    result.entity     shouldBe individual
    result.status     shouldBe ComplianceStatus.Approved
    result.riskLevel  shouldBe RiskLevel.Low
    result.matchCount shouldBe 0
    result.matches    shouldBe Nil

  test("AmlMatch stores matchedName, score, listType, source"):
    val m = AmlMatch("John Doe", 0.95, "PEP", "UN Consolidated", "Director at XYZ")
    m.matchedName  shouldBe "John Doe"
    m.matchScore   shouldBe 0.95
    m.listType     shouldBe "PEP"
    m.listSource   shouldBe "UN Consolidated"

  // ── KycResult ────────────────────────────────────────────────────────

  test("KycResult stores entity, status, failures"):
    val result = KycResult(individual, ComplianceStatus.Approved)
    result.entity  shouldBe individual
    result.status  shouldBe ComplianceStatus.Approved
    result.failures shouldBe Nil

  test("KycResult can carry field-level failures"):
    val result = KycResult(individual, ComplianceStatus.Rejected,
                           failures = List("DOB mismatch", "Passport expired"))
    result.failures should contain("DOB mismatch")
    result.failures should contain("Passport expired")

  // ── SanctionsResult ───────────────────────────────────────────────────

  test("SanctionsResult matched=false for clean entity"):
    val result = SanctionsResult(individual, ComplianceStatus.Approved, matched = false)
    result.matched      shouldBe false
    result.matchedLists shouldBe Nil

  test("SanctionsResult matched=true with list names"):
    val result = SanctionsResult(individual, ComplianceStatus.Rejected, matched = true,
                                 matchedLists = List("OFAC SDN", "EU Sanctions"))
    result.matched      shouldBe true
    result.matchedLists should contain("OFAC SDN")
    result.matchedLists should contain("EU Sanctions")

  // ── ComplianceReport ─────────────────────────────────────────────────

  test("ComplianceReport combines AML + KYC + sanctions results"):
    val aml       = AmlResult(individual, ComplianceStatus.Approved, RiskLevel.Low, 0)
    val kyc       = KycResult(individual, ComplianceStatus.Approved)
    val sanctions = SanctionsResult(individual, ComplianceStatus.Approved, matched = false)
    val report    = ComplianceReport(individual, ComplianceStatus.Approved,
                                     aml = Some(aml), kyc = Some(kyc), sanctions = Some(sanctions))
    report.overallStatus shouldBe ComplianceStatus.Approved
    report.aml.isDefined       shouldBe true
    report.kyc.isDefined       shouldBe true
    report.sanctions.isDefined shouldBe true

  // ── ComplianceError hierarchy ─────────────────────────────────────────

  test("ComplianceError.CheckFailed is a RuntimeException"):
    val e = ComplianceError.CheckFailed("API error")
    e.getMessage         shouldBe "API error"
    e.isInstanceOf[RuntimeException] shouldBe true

  test("ComplianceError.EntityRejected includes entity name in message"):
    val e = ComplianceError.EntityRejected(individual, "sanctions match")
    e.getMessage should include("John Doe")
    e.getMessage should include("sanctions match")

  test("ComplianceError.UnsupportedCheck includes check type in message"):
    val e = ComplianceError.UnsupportedCheck("KYC", "not available in this region")
    e.getMessage should include("KYC")

  test("ComplianceError.RateLimitExceeded with retry-after"):
    val e = ComplianceError.RateLimitExceeded(Some(60))
    e.getMessage should include("60s")

  test("ComplianceError.RateLimitExceeded without retry-after"):
    val e = ComplianceError.RateLimitExceeded()
    e.getMessage should include("Rate limit")

  // ── ComplianceProvider default fullReport ─────────────────────────────

  private def stubProvider(
    amlStatus:       ComplianceStatus = ComplianceStatus.Approved,
    kycStatus:       ComplianceStatus = ComplianceStatus.Approved,
    sanctionsStatus: ComplianceStatus = ComplianceStatus.Approved
  ): ComplianceProvider = new ComplianceProvider:
    def id          = "stub"
    def displayName = "Stub"
    def screenAml(e: ComplianceEntity)(using ExecutionContext) =
      Future.successful(AmlResult(e, amlStatus, RiskLevel.Low, 0))
    def verifyKyc(e: ComplianceEntity)(using ExecutionContext) =
      Future.successful(KycResult(e, kycStatus))
    def checkSanctions(e: ComplianceEntity)(using ExecutionContext) =
      Future.successful(SanctionsResult(e, sanctionsStatus, matched = sanctionsStatus == ComplianceStatus.Rejected))
    def getStatus(checkId: String)(using ExecutionContext) =
      Future.failed(ComplianceError.CheckFailed("not supported"))

  test("fullReport merges all three checks, all approved → Approved overall"):
    val provider = stubProvider()
    val report   = await(provider.fullReport(individual)(using global))
    report.overallStatus         shouldBe ComplianceStatus.Approved
    report.aml.isDefined         shouldBe true
    report.kyc.isDefined         shouldBe true
    report.sanctions.isDefined   shouldBe true

  test("fullReport: AML rejected → overall Rejected"):
    val provider = stubProvider(amlStatus = ComplianceStatus.Rejected)
    val report   = await(provider.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected

  test("fullReport: KYC ManualReview → overall ManualReview when no Rejected"):
    val provider = stubProvider(kycStatus = ComplianceStatus.ManualReview)
    val report   = await(provider.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.ManualReview

  test("fullReport: Rejected overrides ManualReview in overall status"):
    val provider = stubProvider(amlStatus = ComplianceStatus.Rejected,
                                kycStatus = ComplianceStatus.ManualReview)
    val report   = await(provider.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected

  test("fullReport: sanctions Rejected → overall Rejected"):
    val provider = stubProvider(sanctionsStatus = ComplianceStatus.Rejected)
    val report   = await(provider.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected
