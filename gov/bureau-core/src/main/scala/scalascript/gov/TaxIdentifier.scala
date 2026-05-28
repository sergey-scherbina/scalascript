package scalascript.gov

enum TaxIdType:
  // Poland
  case NIP    // Numer Identyfikacji Podatkowej (10 digits)
  case REGON  // Statistical number (9 digits sole / 14 digits companies)
  case KRS    // National Court Register number (10 digits)
  case PESEL  // Personal ID (11 digits, individuals only)
  // EU supranational
  case VatEU  // EU VAT number incl. country prefix (PL1234567890)
  // Other countries
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
