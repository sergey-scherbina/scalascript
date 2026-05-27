package scalascript.payments.compliance

import scala.concurrent.{Future, ExecutionContext}

/** SPI extension for blockchain transaction risk scoring (Chainalysis KYT, Elliptic, etc.).
 *
 *  Implemented by adapters that support blockchain transfer screening in addition
 *  to standard AML/KYC/sanctions checks.
 */
trait BlockchainComplianceProvider extends ComplianceProvider:

  /** Screen a blockchain address for transfer risk.
   *
   *  Checks the address against the provider's risk graph: known exchange clusters,
   *  dark-web markets, scam addresses, mixer outputs, ransomware wallets, etc.
   *
   *  @param address  blockchain address with asset and direction
   *  @return         transfer risk result with risk score and any alerts
   *  @throws         `ComplianceError.CheckFailed` on provider API error
   */
  def screenTransfer(address: BlockchainAddress)(using ExecutionContext): Future[TransferRiskResult]
