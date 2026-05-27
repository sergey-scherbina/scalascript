package scalascript.payments.compliance.mock

import scalascript.payments.compliance.*
import java.time.Instant
import scala.concurrent.{Future, ExecutionContext}

/** Configurable mock ComplianceProvider for testing.
 *
 *  Pass the desired status for each check type at construction time.
 *  `screenTransfer` uses `transferRiskLevel` and `transferRiskScore`.
 *
 *  By default all checks return `Approved` / `Low` risk.
 */
class MockComplianceProvider(
  amlStatus:         ComplianceStatus = ComplianceStatus.Approved,
  kycStatus:         ComplianceStatus = ComplianceStatus.Approved,
  sanctionsStatus:   ComplianceStatus = ComplianceStatus.Approved,
  kycFailures:       List[String]     = Nil,
  transferRiskLevel: RiskLevel        = RiskLevel.Low,
  transferRiskScore: Int              = 0,
  amlMatches:        List[AmlMatch]   = Nil,
  sanctionLists:     List[String]     = Nil
) extends BlockchainComplianceProvider:

  def id:          String = "mock"
  def displayName: String = "Mock Compliance Provider"

  def screenAml(entity: ComplianceEntity)(using ExecutionContext): Future[AmlResult] =
    val riskLevel = amlStatus match
      case ComplianceStatus.Rejected     => RiskLevel.High
      case ComplianceStatus.ManualReview => RiskLevel.Medium
      case _                             => RiskLevel.Low
    Future.successful(AmlResult(
      entity     = entity,
      status     = amlStatus,
      riskLevel  = riskLevel,
      matchCount = amlMatches.length,
      matches    = amlMatches
    ))

  def verifyKyc(entity: ComplianceEntity)(using ExecutionContext): Future[KycResult] =
    Future.successful(KycResult(
      entity     = entity,
      status     = kycStatus,
      verifiedAt = Some(Instant.now()),
      failures   = kycFailures
    ))

  def checkSanctions(entity: ComplianceEntity)(using ExecutionContext): Future[SanctionsResult] =
    val matched = sanctionsStatus == ComplianceStatus.Rejected
    Future.successful(SanctionsResult(
      entity       = entity,
      status       = sanctionsStatus,
      matched      = matched,
      matchedLists = if matched then sanctionLists else Nil
    ))

  def getStatus(checkId: String)(using ExecutionContext): Future[ComplianceReport] =
    Future.successful(ComplianceReport(
      entity        = ComplianceEntity("(mock)", EntityType.Individual),
      overallStatus = amlStatus,
      reportId      = Some(checkId)
    ))

  def screenTransfer(address: BlockchainAddress)(using ExecutionContext): Future[TransferRiskResult] =
    Future.successful(TransferRiskResult(
      address   = address,
      riskLevel = transferRiskLevel,
      riskScore = transferRiskScore
    ))


/** Companion with named constructors for common test scenarios. */
object MockComplianceProvider:
  def allApproved:    MockComplianceProvider = MockComplianceProvider()
  def allRejected:    MockComplianceProvider = MockComplianceProvider(
    amlStatus       = ComplianceStatus.Rejected,
    kycStatus       = ComplianceStatus.Rejected,
    sanctionsStatus = ComplianceStatus.Rejected,
    sanctionLists   = List("OFAC SDN", "EU Sanctions")
  )
  def manualReview:   MockComplianceProvider = MockComplianceProvider(
    amlStatus       = ComplianceStatus.ManualReview,
    kycStatus       = ComplianceStatus.ManualReview,
    sanctionsStatus = ComplianceStatus.ManualReview
  )
  def sanctionsHit:   MockComplianceProvider = MockComplianceProvider(
    sanctionsStatus = ComplianceStatus.Rejected,
    sanctionLists   = List("OFAC SDN")
  )
  def highRiskTransfer: MockComplianceProvider = MockComplianceProvider(
    transferRiskLevel = RiskLevel.Critical,
    transferRiskScore = 95
  )
