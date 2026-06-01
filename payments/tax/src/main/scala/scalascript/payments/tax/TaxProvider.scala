package scalascript.payments.tax

import scala.concurrent.{Future, ExecutionContext}

/** SPI for tax calculation and validation.
 *
 *  Adapters implement this trait to integrate a tax-calculation backend
 *  (Stripe Tax, Avalara AvaTax, TaxJar SmartCalcs, etc.).
 *
 *  All methods return `Future` so network-bound adapters are non-blocking.
 *
 *  See `docs/traditional-payments.md §TaxProvider`.
 */
trait TaxProvider:

  /** Unique identifier for this adapter, e.g. "stripe-tax", "avalara", "taxjar". */
  def id: String

  /** Human-readable name for logging / diagnostics. */
  def displayName: String

  /** Calculate tax for a transaction.
   *
   *  @param req  tax calculation request with line items and addresses
   *  @return     tax quote with per-line breakdown and jurisdiction totals
   *  @throws     `TaxError.TaxCalculationFailed` (in a failed Future) on provider error
   */
  def calculateTax(req: TaxRequest)(using ExecutionContext): Future[TaxQuote]

  /** Validate a tax identification number (VAT, EIN, GST, etc.).
   *
   *  @param taxId    the tax ID string to validate
   *  @param country  ISO 3166-1 alpha-2 country code (e.g. "US", "DE", "GB")
   *  @return         validation result — Valid / Invalid / Unknown
   */
  def validateTaxId(taxId: String, country: String)(using ExecutionContext): Future[TaxIdValidation]

  /** Return the list of jurisdictions this provider can calculate tax for.
   *
   *  May be a static list (hardcoded by the adapter) or fetched from the API.
   */
  def getSupportedJurisdictions(using ExecutionContext): Future[List[Jurisdiction]]
