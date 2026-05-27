package scalascript.payments.tax

import scalascript.payments.money.{Money, Currency}

/** Physical or billing address used as origin or destination for tax calculation. */
case class TaxAddress(
  line1:      String,
  city:       String,
  state:      Option[String] = None,  // US state code (e.g. "CA"), province, region
  postalCode: Option[String] = None,
  country:    String                  // ISO 3166-1 alpha-2 (e.g. "US", "DE", "AU")
)

/** A single line item in a tax calculation request.
 *
 *  @param description  human-readable description for logging / reports
 *  @param amount       pre-tax amount (in the request currency)
 *  @param taxCode      provider-specific tax code:
 *                        Stripe: "txcd_10103001" (general physical goods)
 *                        Avalara: "P0000000" (tangible personal property)
 *                        TaxJar: "20010" (clothing)
 *  @param quantity     item count (defaults to 1)
 */
case class TaxLineItem(
  description: String,
  amount:      Money,
  taxCode:     Option[String] = None,
  quantity:    Int            = 1
)

/** Tax calculation request.
 *
 *  @param lineItems      items to calculate tax on
 *  @param currency       transaction currency (line item amounts must use this currency)
 *  @param fromAddress    seller / shipper / origin address
 *  @param toAddress      buyer / destination / delivery address
 *  @param customerId     provider-side customer ID (used for tax-exemption lookups)
 *  @param idempotencyKey provider deduplication key (passed as Stripe-Idempotency-Key etc.)
 *  @param metadata       arbitrary key-value pairs forwarded to the adapter
 */
case class TaxRequest(
  lineItems:      List[TaxLineItem],
  currency:       Currency,
  fromAddress:    TaxAddress,
  toAddress:      TaxAddress,
  customerId:     Option[String]      = None,
  idempotencyKey: Option[String]      = None,
  metadata:       Map[String, String] = Map.empty
)

/** Tax contribution from a single jurisdiction (state, county, city, federal). */
case class JurisdictionTax(
  name:      String,      // e.g. "California", "Los Angeles County", "US Federal"
  level:     String,      // "country" | "state" | "county" | "city" | "special"
  rate:      BigDecimal,  // 0.0725 = 7.25 %
  taxAmount: Money
)

/** Tax result for a single line item. */
case class TaxedLineItem(
  description: String,
  amount:      Money,                  // pre-tax
  taxAmount:   Money,                  // tax charged on this line
  taxable:     Boolean,
  breakdown:   List[JurisdictionTax] = Nil
)

/** Result of a tax calculation.
 *
 *  @param lineItems        per-line tax breakdown
 *  @param totalTax         sum of all line-item tax amounts
 *  @param totalAmount      pre-tax total + totalTax
 *  @param currency         currency of all Money fields
 *  @param breakdown        aggregate jurisdiction breakdown across all line items
 *  @param providerQuoteId  provider's reference ID (Stripe calculation ID, Avalara transaction ID, etc.)
 */
case class TaxQuote(
  lineItems:       List[TaxedLineItem],
  totalTax:        Money,
  totalAmount:     Money,
  currency:        Currency,
  breakdown:       List[JurisdictionTax] = Nil,
  providerQuoteId: Option[String]        = None
)

/** Result of tax ID validation. */
sealed trait TaxIdValidation
object TaxIdValidation:
  case class Valid(taxId: String, country: String, businessName: Option[String] = None) extends TaxIdValidation
  case class Invalid(taxId: String, country: String, reason: String) extends TaxIdValidation
  case class Unknown(taxId: String, country: String) extends TaxIdValidation

/** A jurisdiction the provider can calculate tax for. */
case class Jurisdiction(
  code:  String,  // ISO 3166-1 alpha-2 or US-CA, US-NY, etc.
  name:  String,
  level: String   // "country" | "state" | "province"
)
