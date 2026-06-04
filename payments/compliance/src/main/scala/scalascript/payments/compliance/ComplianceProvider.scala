package scalascript.payments.compliance

import scala.concurrent.{Future, ExecutionContext}

/** SPI for AML/KYC/sanctions compliance checks.
 *
 *  Adapters implement this trait to integrate a compliance-as-a-service backend
 *  (ComplyAdvantage, Chainalysis, Elliptic, etc.).
 *
 *  All methods return `Future` so network-bound adapters are non-blocking.
 *
 *  See `specs/compliance-provider.md`.
 */
trait ComplianceProvider:

  /** Unique identifier for this adapter, e.g. "complyadvantage", "chainalysis". */
  def id: String

  /** Human-readable name for logging / diagnostics. */
  def displayName: String

  /** Screen an entity for AML (Anti-Money Laundering) risk.
   *
   *  Queries the provider's risk database for PEP (politically exposed persons),
   *  adverse media, and sanctions lists.
   *
   *  @param entity  entity to screen
   *  @return        AML screening result with risk level and any matches
   *  @throws        `ComplianceError.CheckFailed` on provider API error
   */
  def screenAml(entity: ComplianceEntity)(using ExecutionContext): Future[AmlResult]

  /** Verify the identity of an individual (KYC).
   *
   *  Checks document validity, address, date of birth, and identity database lookups.
   *
   *  @param entity  individual to verify
   *  @return        KYC verification result
   *  @throws        `ComplianceError.CheckFailed` on provider API error
   */
  def verifyKyc(entity: ComplianceEntity)(using ExecutionContext): Future[KycResult]

  /** Screen an entity against global sanctions lists.
   *
   *  Checks OFAC SDN, EU Sanctions, UN Consolidated List, and provider-specific lists.
   *
   *  @param entity  entity to screen
   *  @return        sanctions screening result
   *  @throws        `ComplianceError.CheckFailed` on provider API error
   */
  def checkSanctions(entity: ComplianceEntity)(using ExecutionContext): Future[SanctionsResult]

  /** Get the current compliance status for an entity previously submitted.
   *
   *  @param checkId  the check ID returned by a previous screening call
   *  @return         the current compliance report for the check
   *  @throws         `ComplianceError.CheckFailed` if the check ID is unknown
   */
  def getStatus(checkId: String)(using ExecutionContext): Future[ComplianceReport]

  /** Run a full compliance report combining AML + KYC + sanctions.
   *
   *  Default implementation runs all three checks and merges results.
   *  Adapters may override to use a dedicated combined-check endpoint.
   */
  def fullReport(entity: ComplianceEntity)(using ExecutionContext): Future[ComplianceReport] =
    for
      aml       <- screenAml(entity)
      kyc       <- verifyKyc(entity)
      sanctions <- checkSanctions(entity)
    yield
      val overall = resolveOverall(aml.status, kyc.status, sanctions.status)
      ComplianceReport(
        entity        = entity,
        overallStatus = overall,
        aml           = Some(aml),
        kyc           = Some(kyc),
        sanctions     = Some(sanctions)
      )

  private def resolveOverall(statuses: ComplianceStatus*): ComplianceStatus =
    import ComplianceStatus.*
    if statuses.contains(Rejected)     then Rejected
    else if statuses.contains(ManualReview) then ManualReview
    else if statuses.contains(Pending) then Pending
    else Approved
