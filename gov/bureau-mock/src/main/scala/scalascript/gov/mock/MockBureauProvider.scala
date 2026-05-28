package scalascript.gov.mock

import scalascript.gov.*
import scalascript.gov.providers.*
import scalascript.gov.registry.*

/** Configurable in-memory CountryProvider for testing.
 *
 *  Use the named constructors for pre-wired configurations:
 *  - `MockBureauProvider.poland()` — PL country with all 3 domain providers
 *  - `MockBureauProvider.vat()` — EU VAT-only (fiscal + registry)
 *  - `MockBureauProvider.all()` — all domains for a generic test country */
class MockBureauProvider(
  val country:      CountryCode,
  val displayName:  String,
  val legalForms:   Set[LegalForm],
  val capabilities: Set[GovDomain],
  override val fiscal:   Option[FiscalProvider]   = None,
  override val social:   Option[SocialProvider]   = None,
  override val registry: Option[RegistryProvider] = None,
) extends CountryProvider

object MockBureauProvider:

  def poland(succeed: Boolean = true): MockBureauProvider =
    new MockBureauProvider(
      country      = CountryCode.PL,
      displayName  = "Poland",
      legalForms   = Set(LegalForm.SoleProprietor, LegalForm.LimitedLiabilityCompany, LegalForm.JointStockCompany),
      capabilities = Set(GovDomain.Fiscal, GovDomain.Social, GovDomain.Registry),
      fiscal       = Some(MockFiscalProvider(succeed)),
      social       = Some(MockSocialProvider(succeed)),
      registry     = Some(MockRegistryProvider(succeed, defaultRecord = Some(mockPlRecord))),
    )

  def vat(succeed: Boolean = true): MockBureauProvider =
    new MockBureauProvider(
      country      = CountryCode.EU,
      displayName  = "European Union (VAT)",
      legalForms   = Set.empty,
      capabilities = Set(GovDomain.Fiscal, GovDomain.Registry),
      fiscal       = Some(MockFiscalProvider(succeed)),
      registry     = Some(MockRegistryProvider(succeed, defaultRecord = Some(mockEuRecord))),
    )

  def all(succeed: Boolean = true): MockBureauProvider =
    new MockBureauProvider(
      country      = CountryCode.PL,
      displayName  = "Mock All",
      legalForms   = Set(LegalForm.SoleProprietor, LegalForm.LimitedLiabilityCompany, LegalForm.JointStockCompany,
                        LegalForm.GeneralPartnership, LegalForm.LimitedPartnership, LegalForm.Cooperative,
                        LegalForm.Foundation, LegalForm.Association, LegalForm.Branch, LegalForm.CivilPartnership),
      capabilities = GovDomain.values.toSet,
      fiscal       = Some(MockFiscalProvider(succeed)),
      social       = Some(MockSocialProvider(succeed)),
      registry     = Some(MockRegistryProvider(succeed, defaultRecord = Some(mockPlRecord))),
    )

  private val mockPlRecord: BusinessRecord = BusinessRecord(
    name         = "MOCK SP Z O O",
    legalForm    = Some(LegalForm.LimitedLiabilityCompany),
    taxIds       = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address      = Some(Address(line1 = "ul. Testowa 1", postalCode = "00-001", city = "Warszawa", country = CountryCode.PL)),
    status       = RegistrationStatus.Active,
    registeredAt = None,
    metadata     = Map("source" -> "mock"),
  )

  private val mockEuRecord: BusinessRecord = BusinessRecord(
    name         = "MOCK EU LTD",
    legalForm    = None,
    taxIds       = List(TaxIdentifier(TaxIdType.VatEU, TaxId("PL1234567890"), CountryCode.PL)),
    address      = None,
    status       = RegistrationStatus.Active,
    registeredAt = None,
    metadata     = Map("source" -> "mock"),
  )
