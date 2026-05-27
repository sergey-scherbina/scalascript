# v1.59 — Bureau: Business–Government Interaction SPI

> **Status:** spec landed 2026-05-27. Go/no-go: **go**.
> Implementation phases: [v1.59.1](#phases) → v1.59.9.

---

## 1. Goals

Provide a uniform, multi-country SPI for all programmatic interactions a business
has with government bodies — taxes, social insurance, business registries, customs,
statistics, and related domains:

- `gov/bureau-core/` — country-neutral SPI, shared model types, error hierarchy
- `gov/bureau-signing/` — electronic signature abstraction (QES, token, HSM, mock)
- `gov/bureau-scheduler/` — automation layer: scheduled submissions by period
- `gov/bureau-pl/` — Poland aggregate (delegates to domain sub-modules)
  - `gov/bureau-pl-fiscal/` — KSeF (e-invoicing), JPK, e-Deklaracje
  - `gov/bureau-pl-social/` — ZUS (KEDU declarations, payment references)
  - `gov/bureau-pl-registry/` — CEIDG, REGON, Biała Lista, KRS
  - `gov/bureau-pl-customs/` — INTRASTAT, PUESC
  - `gov/bureau-pl-statistics/` — GUS reports
- `gov/bureau-eu/` — EU-level: VIES VAT verification, PEPPOL routing stub
- `gov/bureau-mock/` — fully configurable in-memory mock for tests

**Country isolation is structural, not conventional.** Each country's code lives
in its own module; `CountryCode` is an opaque type; the provider registry keys on
`CountryCode` — the compiler prevents mixing PL and DE providers at the type level.

Reuses `Money` from `payments/money/` for all monetary amounts (tax due, contribution
amounts, penalties). Uses `Future[T]` + `using ExecutionContext` throughout, consistent
with `PaymentProvider` and `ComplianceProvider`. HTTP client: `java.net.http.HttpClient`
(no new dependency).

---

## 2. Non-goals

- **Accounting / bookkeeping** — bureau submits declarations; it does not compute
  profit/loss, depreciation, or double-entry ledgers.
- **Bank payments** — bureau generates payment references (NRB account numbers for
  ZUS, tax office accounts) but does not initiate transfers; that belongs to
  `payments/` PSD2/Open Banking adapters.
- **Document storage / DMS** — bureau returns `SubmissionResult` with a reference
  ID; persistence of submitted XML, PDFs, and receipts is the caller's
  responsibility.
- **Tax calculation** — bureau submits what the caller provides; it does not compute
  VAT amounts, ZUS base, or PIT liability (except `calculateContributions` which
  replicates the ZUS formula for convenience).
- **UI / filing wizard** — bureau is a headless library.
- **v1.59 scope** — customs (PUESC), statistics (GUS), and environment (BDO) are
  defined at SPI level only; Poland implementations land after registry, fiscal,
  and social are stable.

---

## 3. Module layout

```
gov/
├── bureau-core/
│   └── src/main/scala/scalascript/gov/
│       ├── CountryCode.scala          # opaque type + constants
│       ├── LegalForm.scala            # enum, all forms
│       ├── TaxIdentifier.scala        # TaxIdType + TaxId + TaxIdentifier
│       ├── BusinessEntity.scala       # core entity model
│       ├── GovDomain.scala            # enum of domains
│       ├── SubmissionTypes.scala      # SubmissionResult, SubmissionStatus, GovError
│       ├── providers/
│       │   ├── CountryProvider.scala  # top-level SPI
│       │   ├── FiscalProvider.scala
│       │   ├── SocialProvider.scala
│       │   ├── RegistryProvider.scala
│       │   ├── CustomsProvider.scala
│       │   ├── StatisticsProvider.scala
│       │   └── EnvProvider.scala
│       ├── fiscal/                    # shared fiscal types
│       │   ├── FiscalInvoice.scala
│       │   ├── TaxDeclaration.scala
│       │   └── AuditFile.scala
│       ├── social/                    # shared social types
│       │   ├── ContributionDeclaration.scala
│       │   ├── EmployeeRecord.scala
│       │   └── PaymentReference.scala
│       ├── registry/                  # shared registry types
│       │   ├── BusinessRecord.scala
│       │   └── VatPayerStatus.scala
│       └── BureauError.scala
│
├── bureau-signing/
│   └── src/main/scala/scalascript/gov/signing/
│       ├── SigningProvider.scala      # SPI
│       ├── SignatureFormat.scala
│       ├── CertificateInfo.scala
│       ├── PfxSigningProvider.scala   # .pfx / .p12 file
│       ├── MockSigningProvider.scala
│       └── SigningError.scala
│
├── bureau-scheduler/
│   └── src/main/scala/scalascript/gov/scheduler/
│       ├── BureauScheduler.scala      # SPI
│       ├── ScheduledJob.scala
│       ├── JobType.scala
│       └── SimpleScheduler.scala      # thread-based default impl
│
├── bureau-pl/                         # aggregate: depends on all bureau-pl-* modules
├── bureau-pl-registry/
├── bureau-pl-fiscal/
├── bureau-pl-social/
├── bureau-pl-customs/
├── bureau-pl-statistics/
├── bureau-eu/
└── bureau-mock/
```

---

## 4. Core types

### 4.1 Country and legal form

```scala
package scalascript.gov

opaque type CountryCode <: String = String
object CountryCode:
  val PL: CountryCode = "PL"
  val DE: CountryCode = "DE"
  val FR: CountryCode = "FR"
  val UA: CountryCode = "UA"
  val EU: CountryCode = "EU"   // supranational (VIES, PEPPOL)
  def apply(s: String): CountryCode =
    require(s.length == 2, s"CountryCode must be 2 chars: $s")
    s.toUpperCase

enum LegalForm:
  // Sole operators
  case SoleProprietor          // JDG (PL), Einzelunternehmer (DE), auto-entrepreneur (FR)
  // Capital companies
  case LimitedLiabilityCompany // sp. z o.o. (PL), GmbH (DE), Ltd (UK), SARL (FR), ТОВ (UA)
  case JointStockCompany       // SA (PL), AG (DE), PLC (UK), SAS (FR), АТ (UA)
  // Partnerships
  case GeneralPartnership      // spółka jawna (PL), OHG (DE), SNC (FR)
  case LimitedPartnership      // spółka komandytowa (PL), KG (DE), SCS (FR)
  case LimitedJointStockPartnership // spółka komandytowo-akcyjna (PL)
  case ProfessionalPartnership // spółka partnerska (PL)
  // Non-profit / other
  case Cooperative             // spółdzielnia (PL), Genossenschaft (DE)
  case Foundation              // fundacja (PL), Stiftung (DE)
  case Association             // stowarzyszenie (PL), Verein (DE)
  case Branch                  // oddział (PL) — branch of foreign entity
  case CivilPartnership        // spółka cywilna (PL) — not a legal person, partners file separately
  case Other(name: String)
```

### 4.2 Tax identifiers

```scala
enum TaxIdType:
  // Poland
  case NIP    // Numer Identyfikacji Podatkowej (10 digits, all entities)
  case REGON  // Statistical number (9 digits sole / 14 digits companies)
  case KRS    // National Court Register number (10 digits, companies/NGOs)
  case PESEL  // Personal ID (11 digits, individuals only)
  // EU supranational
  case VatEU  // EU VAT number incl. country prefix (PL1234567890)
  // Other countries (extensible)
  case EIN    // US Employer Identification Number
  case SIREN  // France (9 digits)
  case HRB    // Germany Handelsregister
  case Other(country: CountryCode, name: String)

opaque type TaxId <: String = String
object TaxId:
  def apply(s: String): TaxId = s

case class TaxIdentifier(
  idType:  TaxIdType,
  value:   TaxId,
  country: CountryCode
)
```

### 4.3 Business entity

```scala
case class Address(
  line1:      String,
  line2:      Option[String] = None,
  city:       String,
  postalCode: String,
  country:    CountryCode,
  region:     Option[String] = None   // voivodeship, Bundesland, etc.
)

case class BusinessEntity(
  name:          String,
  legalForm:     LegalForm,
  country:       CountryCode,
  taxIds:        List[TaxIdentifier],
  address:       Address,
  vatRegistered: Boolean              = false,
  registeredAt:  Option[LocalDate]   = None,
  metadata:      Map[String, String] = Map.empty
):
  def taxId(t: TaxIdType): Option[TaxId] =
    taxIds.find(_.idType == t).map(_.value)

  def requireTaxId(t: TaxIdType): TaxId =
    taxId(t).getOrElse(throw BureauError.MissingTaxId(t, country))
```

### 4.4 Government domains

```scala
enum GovDomain:
  case Fiscal       // taxes, VAT, e-invoicing, audit files
  case Social       // social insurance, payroll declarations
  case Registry     // business registries, VAT payer lookups
  case Customs      // import/export, INTRASTAT
  case Statistics   // statistical reporting
  case Environment  // environmental taxes, waste register (BDO)
  case Identity     // eID authentication, digital certificates
```

### 4.5 Submission lifecycle

Government APIs are often asynchronous: you submit, receive a ticket, then poll.
`SubmissionStatus` models the full lifecycle:

```scala
enum SubmissionStatus:
  case Accepted                              // immediately and finally accepted
  case Pending(ticketId: String)             // async processing; poll with ticketId
  case Processing                            // acknowledged but ticketId not yet assigned
  case Rejected(errors: List[GovError])      // definitively rejected
  case RequiresCorrection(errors: List[GovError]) // accepted but with mandatory follow-up

case class SubmissionResult(
  submissionId: String,
  status:       SubmissionStatus,
  timestamp:    Instant,
  reference:    Option[String]          = None,  // government-assigned reference number
  warnings:     List[GovWarning]        = Nil,
  metadata:     Map[String, String]     = Map.empty
)

case class GovError(code: String, message: String, field: Option[String] = None)
case class GovWarning(code: String, message: String)
```

### 4.6 Error hierarchy

```scala
sealed abstract class BureauError(message: String, cause: Throwable = null)
  extends Exception(message, cause)

object BureauError:
  case class ApiError(message: String, code: Option[String] = None, httpStatus: Option[Int] = None)
    extends BureauError(message)

  case class AuthenticationError(message: String)
    extends BureauError(message)

  case class SignatureError(message: String, cause: Throwable = null)
    extends BureauError(message, cause)

  case class ValidationError(message: String, fields: List[GovError] = Nil)
    extends BureauError(message)

  case class MissingTaxId(idType: TaxIdType, country: CountryCode)
    extends BureauError(s"$country entity missing required tax ID: $idType")

  case class UnsupportedOperation(country: CountryCode, domain: GovDomain, op: String)
    extends BureauError(s"$country/$domain: '$op' not supported")

  case class RateLimitError(retryAfterSeconds: Option[Int] = None)
    extends BureauError("rate limit exceeded")

  case class ServiceUnavailable(message: String)
    extends BureauError(message)

  case class SubmissionRejected(result: SubmissionResult)
    extends BureauError(s"submission ${result.submissionId} rejected")
```

---

## 5. Provider SPI

### 5.1 CountryProvider — top-level entry point

```scala
trait CountryProvider:
  def country:      CountryCode
  def displayName:  String
  def legalForms:   Set[LegalForm]     // which forms this provider handles
  def capabilities: Set[GovDomain]    // which domains are implemented

  // Domain sub-providers; None = domain not supported in this country/impl
  def fiscal:      Option[FiscalProvider]      = None
  def social:      Option[SocialProvider]      = None
  def registry:    Option[RegistryProvider]    = None
  def customs:     Option[CustomsProvider]     = None
  def statistics:  Option[StatisticsProvider]  = None
  def environment: Option[EnvProvider]         = None
```

Callers address domains via `provider.fiscal.getOrElse(throw BureauError.UnsupportedOperation(...))`.

### 5.2 FiscalProvider

```scala
trait FiscalProvider:

  // ── E-invoicing (KSeF / SDI / SII / ...) ──────────────────────────────

  def submitInvoice(invoice: FiscalInvoice)(using ExecutionContext): Future[InvoiceSubmissionResult]
  def pollInvoiceStatus(ticketId: String)(using ExecutionContext): Future[InvoiceSubmissionResult]
  def fetchInvoice(id: String)(using ExecutionContext): Future[FiscalInvoice]
  def queryInvoices(filter: InvoiceFilter)(using ExecutionContext): Future[List[InvoiceRef]]

  // ── Tax declarations (PIT, CIT, VAT returns) ─────────────────────────

  def submitDeclaration(decl: TaxDeclaration)(using ExecutionContext): Future[SubmissionResult]
  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  // ── Audit files (JPK, SAF-T) ─────────────────────────────────────────

  def submitAuditFile(file: AuditFile)(using ExecutionContext): Future[SubmissionResult]
  def pollAuditFileStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  // ── VAT verification ──────────────────────────────────────────────────

  def verifyVatNumber(id: TaxIdentifier)(using ExecutionContext): Future[VatVerificationResult]
```

Key shared fiscal types:

```scala
case class FiscalInvoice(
  invoiceNumber: String,
  issueDate:     LocalDate,
  seller:        BusinessEntity,
  buyer:         BusinessEntity,
  lines:         List[InvoiceLine],
  taxSummary:    List[TaxSummaryLine],
  totalNet:      Money,
  totalTax:      Money,
  totalGross:    Money,
  currency:      Currency,
  paymentDue:    Option[LocalDate]   = None,
  notes:         Option[String]      = None,
  metadata:      Map[String, String] = Map.empty
)

case class InvoiceLine(
  description: String,
  quantity:    BigDecimal,
  unit:        String,
  unitNet:     Money,
  vatRate:     VatRate,
  totalNet:    Money,
  totalTax:    Money
)

enum VatRate:
  case Standard                    // PL: 23%, DE: 19%, FR: 20%
  case Reduced                     // PL: 8% or 5%
  case SuperReduced                // some EU states (FR: 2.1%)
  case Zero
  case Exempt
  case ReverseCharge
  case Custom(rate: BigDecimal)    // exact rate when none of the above fits

case class TaxDeclaration(
  declarationType: String,         // "JPK_VAT7M", "JPK_VAT7K", "CIT-8", "PIT-36", etc.
  period:          YearMonth,
  entity:          BusinessEntity,
  xmlContent:      String,         // pre-built XML; bureau validates schema, signs, submits
  schemaVersion:   String
)

case class AuditFile(
  fileType:      String,           // "JPK_VAT", "JPK_FA", "JPK_KR", "JPK_WB", etc.
  period:        YearMonth,
  entity:        BusinessEntity,
  xmlContent:    String,
  schemaVersion: String
)

case class InvoiceFilter(
  dateFrom:    Option[LocalDate]  = None,
  dateTo:      Option[LocalDate]  = None,
  role:        InvoiceRole        = InvoiceRole.Any,
  limit:       Int                = 100
)

enum InvoiceRole:
  case Seller; case Buyer; case Any

case class InvoiceRef(id: String, date: LocalDate, total: Money, status: String)
case class InvoiceSubmissionResult(submissionResult: SubmissionResult, invoiceId: Option[String])
case class VatVerificationResult(valid: Boolean, entity: Option[BusinessRecord], checkedAt: Instant)
```

### 5.3 SocialProvider

```scala
trait SocialProvider:

  // ── Declarations ──────────────────────────────────────────────────────

  def submitDeclaration(decl: ContributionDeclaration)(using ExecutionContext): Future[SubmissionResult]
  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  // ── Payments ──────────────────────────────────────────────────────────

  /** Returns the target bank account (NRB/IBAN) and amount due for a period.
   *  Caller initiates the actual transfer via payments/ PSD2 adapter. */
  def getPaymentReference(entity: BusinessEntity, period: YearMonth)(using ExecutionContext): Future[PaymentReference]

  // ── Calculations ──────────────────────────────────────────────────────

  def calculateContributions(params: ContributionParams)(using ExecutionContext): Future[ContributionCalculation]

  // ── Employee lifecycle ────────────────────────────────────────────────

  def registerEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult]
  def deregisterEmployee(employee: EmployeeRecord, reason: DeregistrationReason, effectiveDate: LocalDate)
      (using ExecutionContext): Future[SubmissionResult]
  def updateEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult]
```

Key social types:

```scala
case class ContributionDeclaration(
  declarationType: String,         // "DRA", "RCA", "ZUA", "ZWUA", "ZZA", etc.
  period:          YearMonth,
  employer:        BusinessEntity,
  xmlContent:      String,         // KEDU-format XML (PL) or country equivalent
  schemaVersion:   String
)

case class PaymentReference(
  accountNumber: String,           // NRB (PL) or IBAN
  amount:        Money,
  dueDate:       LocalDate,
  period:        YearMonth,
  description:   String,
  metadata:      Map[String, String] = Map.empty
)

case class EmployeeRecord(
  firstName:   String,
  lastName:    String,
  pesel:       Option[TaxId]      = None,   // PL individuals
  passport:    Option[String]     = None,   // foreign nationals
  birthDate:   LocalDate,
  address:     Address,
  contractType: ContractType,
  startDate:   LocalDate,
  employer:    BusinessEntity,
  metadata:    Map[String, String] = Map.empty
)

enum ContractType:
  case Employment        // umowa o pracę
  case Mandate           // umowa zlecenie
  case SpecificWork      // umowa o dzieło (no ZUS for this in PL)
  case B2B               // business-to-business (self-employed contractor)

enum DeregistrationReason:
  case Termination; case Resignation; case Retirement; case Death; case Other(code: String)

case class ContributionParams(
  entity:          BusinessEntity,
  period:          YearMonth,
  baseAmount:      Money,           // assessment base
  contributionBase: ContributionBase
)

enum ContributionBase:
  case Employee(record: EmployeeRecord)
  case SelfEmployed                  // owner of JDG paying their own ZUS
  case PreferentialBase              // "mały ZUS plus" in PL

case class ContributionCalculation(
  period:           YearMonth,
  pension:          Money,           // emerytalne
  disability:       Money,           // rentowe
  sickness:         Money,           // chorobowe
  accident:         Money,           // wypadkowe
  health:           Money,           // zdrowotne (NFZ)
  laborFund:        Money,           // Fundusz Pracy
  fgsp:             Money,           // FGŚP
  total:            Money
)
```

### 5.4 RegistryProvider

```scala
trait RegistryProvider:
  def lookup(id: TaxIdentifier)(using ExecutionContext): Future[Option[BusinessRecord]]
  def search(query: String, country: CountryCode)(using ExecutionContext): Future[List[BusinessRecord]]
  def checkVatStatus(id: TaxIdentifier)(using ExecutionContext): Future[VatPayerStatus]
  def getDetails(id: TaxIdentifier)(using ExecutionContext): Future[RegistrationDetails]

case class BusinessRecord(
  name:       String,
  legalForm:  Option[LegalForm],
  taxIds:     List[TaxIdentifier],
  address:    Option[Address],
  status:     RegistrationStatus,
  registeredAt: Option[LocalDate],
  metadata:   Map[String, String] = Map.empty
)

enum RegistrationStatus:
  case Active; case Suspended; case Liquidation; case Dissolved; case Unknown

case class VatPayerStatus(
  active:       Boolean,
  id:           TaxIdentifier,
  name:         Option[String],
  bankAccounts: List[String],    // Biała Lista: whitelisted NRB accounts
  checkedAt:    Instant
)

case class RegistrationDetails(
  record:       BusinessRecord,
  directors:    List[String],
  shareholders: List[String],
  activities:   List[String],   // PKD codes (PL) or NACE (EU)
  capital:      Option[Money],
  rawData:      Map[String, String] = Map.empty
)
```

### 5.5 CustomsProvider (SPI stub — implementations in v1.59.7+)

```scala
trait CustomsProvider:
  def submitIntrastat(report: IntrastatReport)(using ExecutionContext): Future[SubmissionResult]
  def pollStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

case class IntrastatReport(
  period:    YearMonth,
  flow:      TradeFlow,
  entity:    BusinessEntity,
  lines:     List[IntrastatLine],
  xmlContent: String
)

enum TradeFlow:
  case Arrival; case Dispatch
```

### 5.6 StatisticsProvider (SPI stub — implementations in v1.59.8+)

```scala
trait StatisticsProvider:
  def submitReport(report: StatisticsReport)(using ExecutionContext): Future[SubmissionResult]
  def pollStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

case class StatisticsReport(
  reportType: String,
  period:     YearMonth,
  entity:     BusinessEntity,
  xmlContent: String
)
```

---

## 6. Signing SPI

Electronic signatures are mandatory for most government API integrations in Poland
and the EU. Bureau treats signing as a separate first-class concern so that the same
fiscal/social providers work with different key storage backends.

```scala
package scalascript.gov.signing

trait SigningProvider:
  def id:          String
  def displayName: String

  /** Signs raw bytes and returns a self-describing signed document. */
  def sign(data: Array[Byte], format: SignatureFormat)(using ExecutionContext): Future[SignedDocument]

  /** Verifies a previously signed document. */
  def verify(doc: SignedDocument)(using ExecutionContext): Future[VerificationResult]

  /** Returns metadata about the certificate in use. */
  def certificateInfo(using ExecutionContext): Future[CertificateInfo]

enum SignatureFormat:
  case XAdES   // XML Advanced Electronic Signature — most PL government APIs
  case PAdES   // PDF Advanced Electronic Signature
  case CAdES   // CMS / PKCS#7 detached
  case JWS     // JSON Web Signature (RFC 7515)

case class SignedDocument(
  originalData: Array[Byte],
  signature:    Array[Byte],
  format:       SignatureFormat,
  timestamp:    Instant
)

case class VerificationResult(
  valid:    Boolean,
  signer:   Option[CertificateInfo],
  errors:   List[String]
)

case class CertificateInfo(
  subject:      String,
  issuer:       String,
  validFrom:    Instant,
  validUntil:   Instant,
  serialNumber: String,
  qualified:    Boolean    // true = kwalifikowany podpis elektroniczny (QES)
)
```

### 6.1 Signing implementations

| Implementation | Module | When to use |
|---|---|---|
| `PfxSigningProvider` | `bureau-signing` | `.pfx` / `.p12` file — most common for automation |
| `MockSigningProvider` | `bureau-signing` | Tests — returns synthetic signatures, never fails |
| `HsmSigningProvider` | future (`bureau-signing-hsm`) | Hardware Security Module — high-volume production |
| `TokenSigningProvider` | future (`bureau-signing-token`) | USB/smart-card token |

`PfxSigningProvider` configuration:

```scala
case class PfxConfig(
  keystorePath: java.nio.file.Path,
  password:     Array[Char],      // not String — char arrays can be zeroed
  alias:        Option[String] = None   // None = use first private key entry
)

class PfxSigningProvider(config: PfxConfig) extends SigningProvider:
  def id:          String = "pfx"
  def displayName: String = "PFX/PKCS#12 file-based signing"
  // loads javax.crypto.KeyStore, signs with java.security.Signature
  // zeroes config.password on close
```

**Security invariant:** `PfxSigningProvider` never logs, serializes, or exposes
the keystore password. The `Array[Char]` convention (not `String`) allows the JVM
to zero the credential after use via `java.util.Arrays.fill`.

---

## 7. Scheduler

`BureauScheduler` automates recurring government obligations (monthly ZUS, quarterly
VAT, annual CIT). It is a pure automation layer — it calls country providers on a
schedule and delegates persistence to the caller via callbacks.

```scala
package scalascript.gov.scheduler

trait BureauScheduler:
  def addJob(job: ScheduledJob): Unit
  def removeJob(id: String): Unit
  def enableJob(id: String): Unit
  def disableJob(id: String): Unit
  def runNow(id: String)(using ExecutionContext): Future[SubmissionResult]
  def listJobs: List[ScheduledJob]
  def jobHistory(id: String, limit: Int = 20): List[JobRun]
  def onJobComplete(handler: (ScheduledJob, SubmissionResult) => Unit): Unit
  def onJobFailed(handler: (ScheduledJob, BureauError) => Unit): Unit
  def start(): Unit
  def stop(): Unit

case class ScheduledJob(
  id:       String,
  entity:   BusinessEntity,
  jobType:  JobType,
  schedule: JobSchedule,
  provider: CountryProvider,
  enabled:  Boolean              = true,
  metadata: Map[String, String]  = Map.empty
)

enum JobType:
  // Fiscal
  case KsefMonthlySubmission                   // PL: KSeF invoice batch
  case JpkVat(variant: String = "JPK_VAT7M")  // PL: monthly/quarterly JPK_VAT
  case JpkFa                                   // PL: invoice audit file
  case VatReturn(period: VatPeriod)
  case CorporateTaxReturn                      // annual CIT-8
  // Social
  case ZusMonthlyDeclaration                   // PL: DRA + RCA monthly
  case ZusPayment                              // triggers getPaymentReference
  // Customs
  case IntrastatReport(flow: TradeFlow)
  // Statistics
  case StatisticsReport(reportType: String)
  // Custom
  case Custom(name: String, domain: GovDomain)

enum VatPeriod:
  case Monthly; case Quarterly

enum JobSchedule:
  case Monthly(dayOfMonth: Int)          // e.g. every 25th
  case Quarterly(month: Int, day: Int)   // month within quarter (1-3) + day
  case Annual(month: Int, day: Int)
  case Cron(expression: String)          // full cron for custom needs

case class JobRun(
  jobId:      String,
  runId:      String,
  startedAt:  Instant,
  finishedAt: Option[Instant],
  result:     Option[SubmissionResult],
  error:      Option[BureauError]
)
```

`SimpleScheduler` — default implementation using a `ScheduledExecutorService`.
Keeps job history in-memory (no persistence — caller's `onJobComplete` handler
saves to DB if needed). No clustering — for single-process use.

---

## 8. Poland implementation

### 8.1 `PlCountryProvider` — aggregate

```scala
class PlCountryProvider(
  fiscalProv:    Option[PlFiscalProvider]    = None,
  socialProv:    Option[PlSocialProvider]    = None,
  registryProv:  Option[PlRegistryProvider]  = None,
  customsProv:   Option[PlCustomsProvider]   = None,
  statisticsProv: Option[PlStatisticsProvider] = None,
  signing:       SigningProvider
) extends CountryProvider:
  def country:     CountryCode   = CountryCode.PL
  def displayName: String        = "Poland (PL)"
  def legalForms:  Set[LegalForm] = LegalForm.values.toSet
  def capabilities: Set[GovDomain] = Set(
    if fiscalProv.isDefined then Some(GovDomain.Fiscal) else None,
    if socialProv.isDefined then Some(GovDomain.Social) else None,
    if registryProv.isDefined then Some(GovDomain.Registry) else None,
    // ...
  ).flatten
```

### 8.2 Fiscal — KSeF (Krajowy System e-Faktur)

| Property | Value |
|---|---|
| API | REST, `https://ksef.mf.gov.pl/api/` |
| Test env | `https://ksef-test.mf.gov.pl/api/` |
| Auth | Session token obtained via QES-signed init request |
| Invoice format | XML, schema `FA_VAT` (published by MF) |
| Async | Yes — `submitInvoice` returns `Pending(ticketId)`, poll via `pollInvoiceStatus` |
| Mandatory | February 2026 (large taxpayers); October 2026 (all) |

Auth flow:
1. `POST /online/Session/AuthorisationChallenge` — receive challenge
2. Sign the challenge with QES (`SigningProvider.sign`)
3. `POST /online/Session/Authorisation` — exchange signed challenge for session token
4. Use session token in `SessionToken` header for all subsequent calls
5. `DELETE /online/Session/Terminate` — explicit logout

```scala
class PlKsefProvider(config: PlKsefConfig, signing: SigningProvider) extends FiscalProvider:
  // HTTP injectable for tests
  protected def postJson(path: String, body: String, sessionToken: Option[String]): String
  protected def getJson(path: String, sessionToken: Option[String]): String

case class PlKsefConfig(
  apiKey:  String,             // NIP of the entity (not a secret — identifies, not authenticates)
  baseUrl: String = "https://ksef.mf.gov.pl/api"
)

object PlKsefConfig:
  def fromEnv: PlKsefConfig = PlKsefConfig(
    apiKey  = sys.env.getOrElse("KSEF_NIP", ""),
    baseUrl = sys.env.getOrElse("KSEF_BASE_URL", "https://ksef.mf.gov.pl/api")
  )
```

### 8.3 Fiscal — e-Deklaracje / JPK

| Property | Value |
|---|---|
| API | REST (new) + SOAP (legacy) |
| Endpoint | `https://e-deklaracje.mf.gov.pl/` |
| Auth | QES mandatory for most declarations |
| Formats | XML schemas published at `www.mf.gov.pl/kontrola-skarbowa/jpk/struktury-jpk` |
| Async | Yes — `SubmissionStatus.Pending(UPO-number)` |

Supports: JPK_VAT7M, JPK_VAT7K, JPK_FA, JPK_KR, JPK_WB, CIT-8, PIT-36, PIT-36L, PIT-28.

### 8.4 Social — ZUS

| Property | Value |
|---|---|
| API | PUE ZUS (partial REST) + KEDU XML format |
| Auth | PUE ZUS credentials; QES for some declaration types |
| Declaration format | KEDU XML (schemas at `www.zus.pl`) |
| Payment | NRB account number derived from NIP + contribution type |
| Async | Yes for declarations; payment reference is synchronous |

Key declaration types: DRA (monthly aggregate), RCA (individual contribution), ZUA
(employee registration), ZWUA (deregistration), ZZA (health-insurance-only registration).

NRB generation (PL): account number for ZUS payments is deterministic from the
entity's NIP and contribution type — `PlZusProvider.paymentReference` computes it
locally without an API call, matching the formula published by ZUS.

```scala
case class PlZusConfig(
  nip:      TaxId,
  login:    String,
  password: Array[Char],
  baseUrl:  String = "https://pue.zus.pl"
)

object PlZusConfig:
  def fromEnv: PlZusConfig = PlZusConfig(
    nip      = TaxId(sys.env.getOrElse("ZUS_NIP", "")),
    login    = sys.env.getOrElse("ZUS_LOGIN", ""),
    password = sys.env.getOrElse("ZUS_PASSWORD", "").toCharArray,
    baseUrl  = sys.env.getOrElse("ZUS_BASE_URL", "https://pue.zus.pl")
  )
```

### 8.5 Registry

| System | API | Auth | Notes |
|---|---|---|---|
| CEIDG | REST `aplikacja.ceidg.gov.pl/CEIDG/CEIDG.Public.UI/` | None (public) | Sole proprietors |
| REGON (BIR1) | SOAP `https://wyszukiwarkaregon.stat.gov.pl/` | API key (free, register at GUS) | All entities |
| Biała Lista | REST `https://wl-api.mf.gov.pl/` | API key | VAT status + bank accounts |
| KRS | REST `https://api-krs.ms.gov.pl/` | None (public) | Companies, NGOs |

`PlRegistryProvider` queries the appropriate underlying system based on `TaxIdType`:
- `NIP` → Biała Lista (for VAT status) + REGON (for full details)
- `KRS` → KRS API
- `PESEL` → CEIDG (sole proprietors)
- `REGON` → REGON BIR1

### 8.6 EU-level (`bureau-eu`)

| Service | API | Auth | Notes |
|---|---|---|---|
| VIES | SOAP `https://ec.europa.eu/taxation_customs/vies/checkVatService.wsdl` | None | EU VAT verification |
| PEPPOL (stub) | — | — | Routing SPI stub; full impl future |

`EuCountryProvider` has only `registry` implemented (VIES) in v1.59.

---

## 9. Mock provider

`MockCountryProvider` covers all domains with configurable responses for testing.

```scala
class MockCountryProvider(
  country:          CountryCode         = CountryCode.PL,
  submissionStatus: SubmissionStatus    = SubmissionStatus.Accepted,
  invoiceId:        String              = "mock-inv-001",
  businessRecord:   Option[BusinessRecord] = Some(MockData.sampleRecord),
  vatStatus:        VatPayerStatus      = MockData.activeVat,
  contributions:    ContributionCalculation = MockData.sampleContributions,
  paymentRef:       PaymentReference    = MockData.samplePaymentRef
) extends CountryProvider

object MockCountryProvider:
  def allAccepted(country: CountryCode = CountryCode.PL): MockCountryProvider =
    MockCountryProvider(country = country)

  def allRejected(country: CountryCode = CountryCode.PL): MockCountryProvider =
    MockCountryProvider(
      country = country,
      submissionStatus = SubmissionStatus.Rejected(List(
        GovError("MOCK_ERR", "Mock rejection for testing")
      ))
    )

  def pending(country: CountryCode = CountryCode.PL, ticketId: String = "mock-ticket-001"): MockCountryProvider =
    MockCountryProvider(
      country = country,
      submissionStatus = SubmissionStatus.Pending(ticketId)
    )
```

---

## 10. Currency handling

### 10.1 Core principle

All monetary amounts in bureau use `Money(minorUnits: Long, currency: Currency)` from
`payments/money/`. Bureau is **currency-transparent** — it does not convert,
round, or impose a local currency. The caller is responsible for providing amounts
in the correct currencies for each document type.

### 10.2 Invoices — any currency

`FiscalInvoice` can be issued in any currency (`invoice.currency`). This is legal
and common in EU B2B trade (EUR invoices between PL and DE entities, USD invoices
for services from outside EU). KSeF accepts invoices in any ISO 4217 currency.

When a foreign-currency invoice must be reported in VAT declarations (JPK_VAT),
Polish tax law requires the PLN equivalent computed using the NBP mid-rate from
the business day preceding the invoice date. **Bureau does not perform this
conversion.** The caller provides both the original `FiscalInvoice` (in the invoice
currency) and pre-converted PLN amounts in the `TaxDeclaration` / `AuditFile` XML.

### 10.3 Tax declarations — local currency only

`TaxDeclaration` and `AuditFile` contain pre-built XML where all amounts are already
in the country's local currency (PLN for Poland, EUR for euro-zone countries). The
exchange-rate line in JPK (`KursWaluty`, `DataKursu`) is the caller's responsibility.

### 10.4 Social contributions — always local currency

`ContributionDeclaration`, `ContributionCalculation`, and `PaymentReference` are
always in local currency. ZUS contributions in Poland are always PLN.

### 10.5 Multi-currency in `FiscalInvoice`

`FiscalInvoice` carries the invoice currency explicitly:

```scala
case class FiscalInvoice(
  // ...
  currency:         Currency,          // ISO 4217 — PLN, EUR, USD, CHF, …
  exchangeRate:     Option[ExchangeRate] = None,  // required when currency ≠ local
  // ...
)

case class ExchangeRate(
  fromCurrency: Currency,
  toCurrency:   Currency,
  rate:         BigDecimal,
  rateDate:     LocalDate,    // NBP: business day before invoice date
  source:       String        // "NBP", "ECB", "custom"
)
```

### 10.6 Multi-currency registries

`VatPayerStatus.bankAccounts` lists NRB/IBAN account numbers without currency
annotation — one entity can have accounts in multiple currencies. `RegistrationDetails`
preserves raw account data from the registry.

### 10.7 Future country considerations

Different countries use different local currencies and different exchange rate
authorities (NBP for PL, ECB for euro-zone, NBU for UA). When adding a new country
module, document which currency is local and which exchange rate source is legally
accepted for that country's tax declarations. Bureau-core imposes no constraint —
`Money` and `Currency` are already multi-currency at the type level.

---

## 11. Phased rollout

| Phase | Scope | Deliverable |
|---|---|---|
| **v1.59.1** | `bureau-core`: all SPI types, error hierarchy, `CountryProvider` + domain traits | Compile-checked SPI; all country impls depend on this |
| **v1.59.2** | `bureau-signing`: `SigningProvider` SPI + `PfxSigningProvider` + `MockSigningProvider` | File-based QES signing; tests use mock |
| **v1.59.3** | `bureau-pl-registry`: CEIDG, REGON, Biała Lista, KRS adapters | Lookups and VAT status — no signing needed |
| **v1.59.4** | `bureau-pl-fiscal` (KSeF): e-invoicing REST adapter with QES session auth | Submit + poll + fetch invoices via KSeF |
| **v1.59.5** | `bureau-pl-fiscal` (e-Deklaracje + JPK): declaration and audit file submission | JPK_VAT, JPK_FA, CIT-8, PIT-36 |
| **v1.59.6** | `bureau-pl-social` (ZUS): KEDU declarations, NRB payment reference, contributions calc | Monthly ZUS automation |
| **v1.59.7** | `bureau-eu`: VIES VAT verification; `bureau-pl` aggregate module | Cross-border VAT checks |
| **v1.59.8** | `bureau-scheduler`: `SimpleScheduler` + job types for all PL obligations | Full automation layer |
| **v1.59.9** | `bureau-mock`: full mock provider; integration tests; `docs/bureau.md` examples | Milestone close |

INTRASTAT (customs) and GUS (statistics) SPI stubs land in v1.59.1; implementations are **v1.60** scope.

---

## 12. Test plan

Each phase ships tests alongside its module. All tests use injectable HTTP methods
(same pattern as `ChainalysisProvider`, `ComplyAdvantageProvider`) — no live network
in CI.

| Phase | Tests |
|---|---|
| v1.59.1 | `BureauCoreTest`: type construction, `BusinessEntity.requireTaxId`, `SubmissionStatus`, `BureauError` hierarchy |
| v1.59.2 | `PfxSigningTest`: sign + verify round-trip with a generated test `.pfx`; `MockSigningTest`: always succeeds |
| v1.59.3 | `PlRegistryTest`: injectable HTTP, JSON parsing for each registry (CEIDG, REGON, Biała Lista, KRS), not-found paths, error handling |
| v1.59.4 | `PlKsefTest`: auth challenge flow, invoice submit + poll, fetch, query, API errors (401, 429, 503) |
| v1.59.5 | `PlDeclarationTest`: JPK_VAT7M submit + poll, schema validation, async rejection |
| v1.59.6 | `PlZusTest`: DRA declaration, NRB payment reference generation (formula verified against ZUS examples), contributions calculation |
| v1.59.7 | `ViesTest`: VAT number verify (valid, invalid, service down) |
| v1.59.8 | `SchedulerTest`: job scheduling, `runNow`, `onJobComplete` / `onJobFailed` callbacks, disable/enable |
| v1.59.9 | `MockProviderTest`: all named constructors, all domain sub-providers return expected mock values |

---

## 13. Adding a new country (protocol for future maintainers)

1. Create `gov/bureau-<cc>/` with its own `build.sbt` entry depending on `bureauCore`
   and `bureauSigning`.
2. Implement `CountryProvider` with `country = CountryCode("<CC>")`.
3. Implement only the domains that country requires — return `None` for the rest.
4. Register in `build.sbt` root aggregate; add to `CHANGELOG.md`.
5. No changes to `bureau-core`, `bureau-pl`, or any other country module.
6. Country-specific types live entirely within `gov/bureau-<cc>/` — they never
   leak into `bureau-core`.

The compiler enforces isolation: `CountryCode` is opaque; `TaxIdType.Other(country, name)`
captures the country; `CountryProvider.country` makes the provider self-describing.
A `Map[CountryCode, CountryProvider]` registry at the application level is the
recommended composition pattern.
