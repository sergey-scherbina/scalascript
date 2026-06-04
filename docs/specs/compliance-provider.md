# v1.58 — AML/KYC/Sanctions Compliance Provider SPI

> **Status:** spec landed 2026-05-27. Go/no-go: **go**.

**Phases:** [v1.58-spec](#overview) | [v1.58-compliance-provider](#implementation)

---

## 1. Goals

Provide a uniform SPI for AML (Anti-Money Laundering), KYC (Know Your Customer), and sanctions screening that can be backed by any compliance-as-a-service vendor without changing business logic:

- `payments/compliance/` — provider-neutral SPI and shared model types
- `payments/compliance-complyadvantage/` — ComplyAdvantage REST v1 adapter (AML + KYC via PEP/sanctions search, HMAC-SHA256 webhooks)
- `payments/compliance-chainalysis/` — Chainalysis KYT v2 adapter (blockchain transfer risk scoring, entity risk via `/api/risk/v2/entities`)
- `payments/compliance-mock/` — configurable mock for unit tests (pass/fail/manual-review per check type)

Reuse `Future[T]` + `using ExecutionContext` pattern from `PaymentProvider` and `TaxProvider`. No new HTTP library dependency — use `java.net.http.HttpClient`.

## 2. Non-goals

- FX conversion or payment routing (see `docs/specs/bank-rails.md`)
- Real-time transaction monitoring beyond what the provider APIs expose
- Document OCR / biometric liveness for KYC (provider-side only)
- Webhook verification infrastructure — adapters use injectable `verifySignature` matching `WebhookReceiver[T]` pattern but do not implement that trait (compliance events are polled or received via provider dashboard)

## 3. Type-level surface

### 3.1 ComplianceEntity

```scala
case class ComplianceEntity(
  name:           String,
  entityType:     EntityType,
  country:        Option[String]     = None,   // ISO 3166-1 alpha-2
  dateOfBirth:    Option[String]     = None,   // "YYYY-MM-DD"
  nationality:    Option[String]     = None,
  passportNumber: Option[String]     = None,
  taxId:          Option[String]     = None,
  address:        Option[String]     = None,
  externalId:     Option[String]     = None,   // caller correlation ID
  metadata:       Map[String, String] = Map.empty
)
```

`entityType` covers `Individual | Organization | Vessel | Aircraft` matching OFAC and UN list categories.

### 3.2 ComplianceProvider SPI

```scala
trait ComplianceProvider:
  def id:          String
  def displayName: String
  def screenAml(entity: ComplianceEntity)(using ExecutionContext): Future[AmlResult]
  def verifyKyc(entity: ComplianceEntity)(using ExecutionContext): Future[KycResult]
  def checkSanctions(entity: ComplianceEntity)(using ExecutionContext): Future[SanctionsResult]
  def getStatus(checkId: String)(using ExecutionContext): Future[ComplianceReport]
  def fullReport(entity: ComplianceEntity)(using ExecutionContext): Future[ComplianceReport]  // default impl
```

`fullReport` runs all three checks sequentially and merges results; `Rejected` overrides `ManualReview` overrides `Pending` overrides `Approved`.

### 3.3 BlockchainComplianceProvider

```scala
trait BlockchainComplianceProvider extends ComplianceProvider:
  def screenTransfer(address: BlockchainAddress)(using ExecutionContext): Future[TransferRiskResult]
```

`BlockchainAddress(address, asset, network, direction)` where `direction` is `Received | Sent`.

### 3.4 Result types

| Type | Key fields |
|------|-----------|
| `AmlResult` | `entity`, `status`, `riskLevel`, `matchCount`, `matches: List[AmlMatch]`, `checkId` |
| `KycResult` | `entity`, `status`, `verifiedAt`, `checkId`, `failures: List[String]` |
| `SanctionsResult` | `entity`, `status`, `matched: Boolean`, `matchedLists`, `checkId` |
| `TransferRiskResult` | `address`, `riskLevel`, `riskScore: Int`, `cluster`, `alerts` |
| `ComplianceReport` | `entity`, `overallStatus`, `aml`, `kyc`, `sanctions`, `reportId` |

`RiskLevel`: `Low | Medium | High | Critical | Unknown`

`ComplianceStatus`: `Approved | Rejected | ManualReview | Pending`

### 3.5 ComplianceError hierarchy

```scala
sealed abstract class ComplianceError extends RuntimeException
  case class CheckFailed(message, cause)          // HTTP / parse error
  case class EntityRejected(entity, reason)       // provider-side rejection
  case class UnsupportedCheck(checkType, reason)  // not available for this country/type
  case class RateLimitExceeded(retryAfterSeconds) // 429 with Retry-After
  case class ProviderError(message, cause)        // auth, config, etc.
```

## 4. Module layout

```
payments/
  compliance/                    — SPI (no impl, no HTTP dependency)
    ComplianceProvider.scala
    BlockchainComplianceProvider.scala
    ComplianceModel.scala
    ComplianceError.scala
    src/test/  ComplianceSpiTest.scala

  compliance-complyadvantage/    — ComplyAdvantage REST v1 adapter
    ComplyAdvantageProvider.scala
    ComplyAdvantagePlugin.scala
    META-INF/services/...
    src/test/  ComplyAdvantageProviderTest.scala (20+ tests)

  compliance-chainalysis/        — Chainalysis KYT v2 adapter
    ChainalysisProvider.scala    — implements BlockchainComplianceProvider
    ChainalysisPlugin.scala
    META-INF/services/...
    src/test/  ChainalysisProviderTest.scala (25+ tests)

  compliance-mock/               — Configurable mock for testing
    MockComplianceProvider.scala — implements BlockchainComplianceProvider
    MockCompliancePlugin.scala
    META-INF/services/...
    src/test/  MockComplianceProviderTest.scala (21+ tests)
```

## 5. §complyadvantage — ComplyAdvantage REST v1

**Protocol:** HTTPS REST, JSON.

**Endpoint:** `POST https://api.complyadvantage.com/searches`

**Auth:** `Authorization: Token <apiKey>` header.

**Wire format:** JSON body with `search_term`, `fuzziness`, `search_profile`, and `filters.types` array (`pep`, `sanction`, `adverse-media`).

**Response:** `{ "data": { "id": "...", "risk_level": "low|medium|high|very_high", "total_hits": N, "hits": [...] } }`

**Risk mapping:**
- `very_high` / `critical` → `RiskLevel.Critical`, `ComplianceStatus.Rejected`
- `high` → `RiskLevel.High`, `ComplianceStatus.Rejected`
- `medium` → `RiskLevel.Medium`, `ComplianceStatus.ManualReview`
- `low` / `unknown` → `RiskLevel.Low` / `RiskLevel.Unknown`, `ComplianceStatus.Approved`

**KYC:** ComplyAdvantage does not provide identity document verification; `verifyKyc` performs a PEP + sanctions search and maps risk level to KYC status.

**Sanctions:** `checkSanctions` searches with `types=["sanction"]` and maps any sanction-type hits to `matched=true`.

**Rate limit:** 429 response throws `ComplianceError.RateLimitExceeded` with `Retry-After` header parsed into `retryAfterSeconds`.

**Adapter class:** `ComplyAdvantageProvider(config: ComplyAdvantageConfig)` — injectable `postJson` / `getJson` for testing.

**Config:**
```scala
case class ComplyAdvantageConfig(
  apiKey:             String,
  baseUrl:            String = "https://api.complyadvantage.com",
  fuzzinessThreshold: Double = 0.7,
  searchProfile:      String = "financial-crime"
)
```

## 6. §chainalysis — Chainalysis KYT v2

**Protocol:** HTTPS REST, JSON.

**Endpoints:**
- `POST /api/kyt/v2/transfers` — register a transfer for risk scoring
- `GET  /api/risk/v2/entities/<address>` — entity risk for `screenAml` / `checkSanctions`

**Auth:** `Token <apiKey>` header.

**Wire format (transfer):**
```json
{
  "network": "ETHEREUM",
  "asset": "ETH",
  "transferReference": "<address>",
  "direction": "RECEIVED"
}
```

**Risk score mapping (0–100):**
- ≥ 90 → `Critical` / sanctioned
- 70–89 → `High` / `Rejected`
- 30–69 → `Medium` / `ManualReview`
- < 30 → `Low` / `Approved`

**KYC:** Chainalysis does not provide KYC identity verification; `verifyKyc` always returns `Approved` (KYC must be handled by a separate provider).

**AML / Sanctions:** `screenAml` and `checkSanctions` use `externalId` or `metadata["blockchain_address"]` to look up the entity risk endpoint. If no blockchain address is provided, they return `Approved` with zero matches.

**Adapter class:** `ChainalysisProvider(config: ChainalysisConfig)` extends `BlockchainComplianceProvider`.

**Config:**
```scala
case class ChainalysisConfig(
  apiKey:  String,
  baseUrl: String = "https://api.chainalysis.com"
)
```

## 7. §mock — MockComplianceProvider

Configurable mock for unit tests that need a `ComplianceProvider` or `BlockchainComplianceProvider` without a real API:

```scala
class MockComplianceProvider(
  amlStatus:         ComplianceStatus = Approved,
  kycStatus:         ComplianceStatus = Approved,
  sanctionsStatus:   ComplianceStatus = Approved,
  kycFailures:       List[String]     = Nil,
  transferRiskLevel: RiskLevel        = Low,
  transferRiskScore: Int              = 0,
  amlMatches:        List[AmlMatch]   = Nil,
  sanctionLists:     List[String]     = Nil
) extends BlockchainComplianceProvider
```

Named constructors: `MockComplianceProvider.allApproved`, `.allRejected`, `.manualReview`, `.sanctionsHit`, `.highRiskTransfer`.

## 8. Testing strategy

- **SPI model tests** (`ComplianceSpiTest`): field round-trip, `fullReport` merge logic, `ComplianceError` hierarchy.
- **ComplyAdvantage** (`ComplyAdvantageProviderTest`, 20+ tests): injectable `postJson`/`getJson`; covers all risk levels, rate limit 429, API errors, network errors, `getStatus`.
- **Chainalysis** (`ChainalysisProviderTest`, 25+ tests): `screenTransfer` for all risk bands, AML/sanctions with and without blockchain address, `verifyKyc` always Approved, error cases.
- **Mock** (`MockComplianceProviderTest`, 21+ tests): all named constructors, per-check type configuration, `fullReport` via default impl.

No integration tests against live APIs — all tests use injectable HTTP methods.

## 9. sbt subprojects

Four subprojects added to the root aggregate:

| sbt name | directory |
|----------|-----------|
| `paymentsCompliance` | `payments/compliance` |
| `paymentsComplianceComplyadvantage` | `payments/compliance-complyadvantage` |
| `paymentsComplianceChainalysis` | `payments/compliance-chainalysis` |
| `paymentsComplianceMock` | `payments/compliance-mock` |

Each adapter subproject depends on `paymentsCompliance`. The mock is a test-scoped dependency for other modules.

## 10. Open questions

None — all decisions resolved:
- (a) Injectable HTTP pattern (same as `TaxProvider`, `BankRailsProvider`) — resolved: yes.
- (b) KYC via ComplyAdvantage — resolved: PEP + sanctions search maps to KYC status, documented as limitation.
- (c) Chainalysis KYC — resolved: always Approved, documented clearly.
- (d) Webhook verification — resolved: out of scope; compliance events are polled.

## 11. Go/no-go

**Go.** SPI cleanly separates provider contracts from business logic. Injectable HTTP allows full test coverage without live API keys. Mock provider satisfies downstream test isolation requirements.
