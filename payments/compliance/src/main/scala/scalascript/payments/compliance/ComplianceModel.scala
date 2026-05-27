package scalascript.payments.compliance

import java.time.Instant

/** Entity type for AML/sanctions screening. */
enum EntityType:
  case Individual
  case Organization
  case Vessel
  case Aircraft

/** Individual or organization to be screened. */
case class ComplianceEntity(
  name:          String,
  entityType:    EntityType,
  country:       Option[String]    = None,    // ISO 3166-1 alpha-2
  dateOfBirth:   Option[String]    = None,    // "YYYY-MM-DD" for individuals
  nationality:   Option[String]    = None,    // ISO 3166-1 alpha-2
  passportNumber: Option[String]   = None,
  taxId:         Option[String]    = None,
  address:       Option[String]    = None,
  externalId:    Option[String]    = None,    // caller's own ID for correlation
  metadata:      Map[String, String] = Map.empty
)

/** Address for a blockchain transfer (used in Chainalysis KYT). */
case class BlockchainAddress(
  address:   String,
  asset:     String,   // "ETH", "BTC", "USDC", etc.
  network:   String,   // "ethereum", "bitcoin", "solana", etc.
  direction: TransferDirection
)

/** Direction of a blockchain transfer. */
enum TransferDirection:
  case Received   // funds coming in (deposit)
  case Sent       // funds going out (withdrawal)

/** Risk level assigned by the provider. */
enum RiskLevel:
  case Low
  case Medium
  case High
  case Critical
  case Unknown

/** Status of a compliance check. */
enum ComplianceStatus:
  case Approved
  case Rejected
  case ManualReview
  case Pending

/** AML screening result. */
case class AmlResult(
  entity:      ComplianceEntity,
  status:      ComplianceStatus,
  riskLevel:   RiskLevel,
  matchCount:  Int,
  matches:     List[AmlMatch] = Nil,
  checkId:     Option[String] = None,
  checkedAt:   Instant        = Instant.now()
)

/** A single match in an AML screening result. */
case class AmlMatch(
  matchedName: String,
  matchScore:  Double,         // 0.0 – 1.0
  listType:    String,         // "PEP", "Sanctions", "Adverse Media", etc.
  listSource:  String,         // "OFAC", "EU Sanctions", "UN Consolidated", etc.
  matchDetails: String = ""
)

/** KYC (Know Your Customer) identity verification result. */
case class KycResult(
  entity:     ComplianceEntity,
  status:     ComplianceStatus,
  verifiedAt: Option[Instant]  = None,
  checkId:    Option[String]   = None,
  failures:   List[String]     = Nil,  // field-level failure reasons
  checkedAt:  Instant          = Instant.now()
)

/** Sanctions screening result. */
case class SanctionsResult(
  entity:     ComplianceEntity,
  status:     ComplianceStatus,
  matched:    Boolean,
  matchedLists: List[String]   = Nil,  // e.g. "OFAC SDN", "EU Sanctions"
  checkId:    Option[String]   = None,
  checkedAt:  Instant          = Instant.now()
)

/** Blockchain transfer risk result (Chainalysis KYT). */
case class TransferRiskResult(
  address:     BlockchainAddress,
  riskLevel:   RiskLevel,
  riskScore:   Int,                    // 0–100
  cluster:     Option[String]  = None, // e.g. "Exchange: Binance"
  alerts:      List[String]    = Nil,
  checkId:     Option[String]  = None,
  checkedAt:   Instant         = Instant.now()
)

/** Overall compliance status for an entity combining AML + KYC + sanctions. */
case class ComplianceReport(
  entity:        ComplianceEntity,
  overallStatus: ComplianceStatus,
  aml:           Option[AmlResult]       = None,
  kyc:           Option[KycResult]       = None,
  sanctions:     Option[SanctionsResult] = None,
  reportId:      Option[String]          = None,
  generatedAt:   Instant                 = Instant.now()
)
