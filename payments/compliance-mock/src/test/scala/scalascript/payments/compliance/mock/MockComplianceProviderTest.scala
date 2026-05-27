package scalascript.payments.compliance.mock

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.compliance.*
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class MockComplianceProviderTest extends AnyFunSuite with Matchers:

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private val individual = ComplianceEntity(
    name        = "Test Person",
    entityType  = EntityType.Individual,
    country     = Some("US")
  )

  private val ethAddress = BlockchainAddress("0xabc", "ETH", "ethereum", TransferDirection.Received)

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'mock'"):
    MockComplianceProvider().id shouldBe "mock"

  test("displayName includes 'Mock'"):
    MockComplianceProvider().displayName should include("Mock")

  // ── named constructors ────────────────────────────────────────────────

  test("allApproved: all checks return Approved"):
    val p = MockComplianceProvider.allApproved
    await(p.screenAml(individual)(using global)).status      shouldBe ComplianceStatus.Approved
    await(p.verifyKyc(individual)(using global)).status      shouldBe ComplianceStatus.Approved
    await(p.checkSanctions(individual)(using global)).status shouldBe ComplianceStatus.Approved

  test("allRejected: all checks return Rejected"):
    val p = MockComplianceProvider.allRejected
    await(p.screenAml(individual)(using global)).status      shouldBe ComplianceStatus.Rejected
    await(p.verifyKyc(individual)(using global)).status      shouldBe ComplianceStatus.Rejected
    await(p.checkSanctions(individual)(using global)).status shouldBe ComplianceStatus.Rejected

  test("manualReview: all checks return ManualReview"):
    val p = MockComplianceProvider.manualReview
    await(p.screenAml(individual)(using global)).status      shouldBe ComplianceStatus.ManualReview
    await(p.verifyKyc(individual)(using global)).status      shouldBe ComplianceStatus.ManualReview
    await(p.checkSanctions(individual)(using global)).status shouldBe ComplianceStatus.ManualReview

  test("sanctionsHit: only sanctions rejected"):
    val p = MockComplianceProvider.sanctionsHit
    await(p.screenAml(individual)(using global)).status      shouldBe ComplianceStatus.Approved
    await(p.checkSanctions(individual)(using global)).status shouldBe ComplianceStatus.Rejected
    await(p.checkSanctions(individual)(using global)).matchedLists should contain("OFAC SDN")

  test("highRiskTransfer: screenTransfer returns Critical risk"):
    val p = MockComplianceProvider.highRiskTransfer
    val r = await(p.screenTransfer(ethAddress)(using global))
    r.riskLevel shouldBe RiskLevel.Critical
    r.riskScore shouldBe 95

  // ── screenAml ─────────────────────────────────────────────────────────

  test("screenAml: Approved → Low risk level"):
    val p = MockComplianceProvider(amlStatus = ComplianceStatus.Approved)
    val r = await(p.screenAml(individual)(using global))
    r.status    shouldBe ComplianceStatus.Approved
    r.riskLevel shouldBe RiskLevel.Low
    r.entity    shouldBe individual

  test("screenAml: Rejected → High risk level"):
    val p = MockComplianceProvider(amlStatus = ComplianceStatus.Rejected)
    val r = await(p.screenAml(individual)(using global))
    r.status    shouldBe ComplianceStatus.Rejected
    r.riskLevel shouldBe RiskLevel.High

  test("screenAml: ManualReview → Medium risk level"):
    val p = MockComplianceProvider(amlStatus = ComplianceStatus.ManualReview)
    val r = await(p.screenAml(individual)(using global))
    r.status    shouldBe ComplianceStatus.ManualReview
    r.riskLevel shouldBe RiskLevel.Medium

  test("screenAml: custom amlMatches carried through"):
    val match1 = AmlMatch("John Doe Jr", 0.92, "PEP", "UN Consolidated")
    val p      = MockComplianceProvider(
      amlStatus  = ComplianceStatus.Rejected,
      amlMatches = List(match1)
    )
    val r = await(p.screenAml(individual)(using global))
    r.matchCount shouldBe 1
    r.matches    shouldBe List(match1)

  // ── verifyKyc ─────────────────────────────────────────────────────────

  test("verifyKyc: Approved → verifiedAt is set"):
    val p = MockComplianceProvider()
    val r = await(p.verifyKyc(individual)(using global))
    r.status              shouldBe ComplianceStatus.Approved
    r.verifiedAt.isDefined shouldBe true
    r.failures             shouldBe Nil

  test("verifyKyc: Rejected with custom failures"):
    val p = MockComplianceProvider(
      kycStatus   = ComplianceStatus.Rejected,
      kycFailures = List("DOB mismatch", "Address not verified")
    )
    val r = await(p.verifyKyc(individual)(using global))
    r.status   shouldBe ComplianceStatus.Rejected
    r.failures should contain("DOB mismatch")
    r.failures should contain("Address not verified")

  // ── checkSanctions ─────────────────────────────────────────────────────

  test("checkSanctions: Approved → not matched, empty lists"):
    val p = MockComplianceProvider()
    val r = await(p.checkSanctions(individual)(using global))
    r.matched      shouldBe false
    r.status       shouldBe ComplianceStatus.Approved
    r.matchedLists shouldBe Nil

  test("checkSanctions: Rejected → matched, sanctionLists non-empty"):
    val lists = List("OFAC SDN", "EU Consolidated")
    val p     = MockComplianceProvider(sanctionsStatus = ComplianceStatus.Rejected, sanctionLists = lists)
    val r     = await(p.checkSanctions(individual)(using global))
    r.matched      shouldBe true
    r.status       shouldBe ComplianceStatus.Rejected
    r.matchedLists shouldBe lists

  // ── getStatus ─────────────────────────────────────────────────────────

  test("getStatus: returns report with given checkId"):
    val p      = MockComplianceProvider()
    val report = await(p.getStatus("check_abc")(using global))
    report.reportId      shouldBe Some("check_abc")
    report.overallStatus shouldBe ComplianceStatus.Approved

  test("getStatus: allRejected → Rejected report"):
    val p      = MockComplianceProvider.allRejected
    val report = await(p.getStatus("check_rejected")(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected

  // ── screenTransfer ────────────────────────────────────────────────────

  test("screenTransfer: Low risk by default"):
    val p = MockComplianceProvider()
    val r = await(p.screenTransfer(ethAddress)(using global))
    r.riskLevel shouldBe RiskLevel.Low
    r.riskScore shouldBe 0
    r.address   shouldBe ethAddress

  test("screenTransfer: custom risk level and score"):
    val p = MockComplianceProvider(transferRiskLevel = RiskLevel.High, transferRiskScore = 75)
    val r = await(p.screenTransfer(ethAddress)(using global))
    r.riskLevel shouldBe RiskLevel.High
    r.riskScore shouldBe 75

  // ── fullReport (default ComplianceProvider impl) ──────────────────────

  test("fullReport: all Approved → Approved overall"):
    val p      = MockComplianceProvider.allApproved
    val report = await(p.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.Approved
    report.aml.isDefined       shouldBe true
    report.kyc.isDefined       shouldBe true
    report.sanctions.isDefined shouldBe true

  test("fullReport: sanctions Rejected → overall Rejected"):
    val p      = MockComplianceProvider.sanctionsHit
    val report = await(p.fullReport(individual)(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected
