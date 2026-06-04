package scalascript.payments.tax.stripe

import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.concurrent.{Future, ExecutionContext}

/** Stripe Tax Calculations API v1 adapter.
 *
 *  Endpoint: POST `https://api.stripe.com/v1/tax/calculations`
 *
 *  Auth: HTTP Basic with `apiKey` as username, empty password
 *  (Stripe's Bearer token is passed as Basic auth for form-encoded requests).
 *
 *  Wire format: `application/x-www-form-urlencoded`.
 *
 *  Response: JSON `{ "id": "taxcalc_...", "tax_amount_exclusive": 100, "line_items": [...] }`.
 *
 *  Tax ID validation: POST `/v1/identity/verification_sessions` is out of scope.
 *  `validateTaxId` performs a format-only check for common patterns (EU VAT, US EIN, AU GST).
 *
 *  getSupportedJurisdictions returns the countries Stripe Tax currently supports (hardcoded).
 *
 *  See `docs/specs/traditional-payments.md §TaxProvider §stripe-tax`.
 */
class StripeTaxProvider(config: StripeTaxConfig) extends TaxProvider:

  def id:          String = "stripe-tax"
  def displayName: String = "Stripe Tax Calculations API v1"

  private val baseUrl = config.baseUrl.stripSuffix("/")

  // ── Injectable HTTP method (overridden in tests) ──────────────────────

  protected def postForm(path: String, body: String, idempotencyKey: Option[String] = None): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val creds   = java.util.Base64.getEncoder.encodeToString(s"${config.apiKey}:".getBytes)
    val reqBuilder = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Basic $creds")
      .header("Content-Type",  "application/x-www-form-urlencoded")
    idempotencyKey.foreach(k => reqBuilder.header("Idempotency-Key", k))
    val req  = reqBuilder.POST(JHttpRequest.BodyPublishers.ofString(body)).build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw TaxError.TaxCalculationFailed(s"Stripe Tax API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  // ── TaxProvider interface ─────────────────────────────────────────────

  def calculateTax(req: TaxRequest)(using ExecutionContext): Future[TaxQuote] =
    Future {
      val params = buildParams(req)
      val body   = postForm("/v1/tax/calculations", params, req.idempotencyKey)
      parseQuote(body, req)
    }.recoverWith {
      case e: TaxError => Future.failed(e)
      case e           => Future.failed(TaxError.TaxProviderError(e.getMessage, e))
    }

  def validateTaxId(taxId: String, country: String)(using ExecutionContext): Future[TaxIdValidation] =
    Future.successful(validateFormat(taxId, country))

  def getSupportedJurisdictions(using ExecutionContext): Future[List[Jurisdiction]] =
    Future.successful(StripeTaxProvider.supportedJurisdictions)

  // ── Request builder ───────────────────────────────────────────────────

  private def buildParams(req: TaxRequest): String =
    val sb = new StringBuilder()

    def enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    def add(k: String, v: String): Unit =
      if sb.nonEmpty then sb.append('&')
      sb.append(enc(k)).append('=').append(enc(v))

    add("currency", req.currency.code.toLowerCase)

    // Ship-from address
    add("ship_from_details[address][country]", req.fromAddress.country)
    req.fromAddress.state.foreach(s => add("ship_from_details[address][state]", s))
    req.fromAddress.postalCode.foreach(p => add("ship_from_details[address][postal_code]", p))
    add("ship_from_details[address][city]", req.fromAddress.city)
    add("ship_from_details[address][line1]", req.fromAddress.line1)

    // Customer (ship-to) address
    add("customer_details[address_source]", "billing")
    add("customer_details[address][country]", req.toAddress.country)
    req.toAddress.state.foreach(s => add("customer_details[address][state]", s))
    req.toAddress.postalCode.foreach(p => add("customer_details[address][postal_code]", p))
    add("customer_details[address][city]", req.toAddress.city)
    add("customer_details[address][line1]", req.toAddress.line1)

    req.customerId.foreach(c => add("customer", c))

    // Line items
    req.lineItems.zipWithIndex.foreach { case (item, i) =>
      add(s"line_items[$i][amount]",   item.amount.minorUnits.toString)
      add(s"line_items[$i][reference]", item.description.take(200))
      item.taxCode.foreach(tc => add(s"line_items[$i][tax_code]", tc))
      if item.quantity != 1 then add(s"line_items[$i][quantity]", item.quantity.toString)
    }

    sb.toString

  // ── Response parser ────────────────────────────────────────────────────

  private def parseQuote(body: String, req: TaxRequest): TaxQuote =
    val quoteId     = extractStr(body, "\"id\"")
    val totalTaxRaw = extractLong(body, "\"tax_amount_exclusive\"")
        .orElse(extractLong(body, "\"amount_tax\""))
        .getOrElse(0L)

    val currency = req.currency

    // Parse per-line items from "line_items": { "data": [...] }
    val taxedLines = req.lineItems.zip(parseLineItems(body, req.lineItems.length))
      .map { case (orig, taxAmt) =>
        TaxedLineItem(
          description = orig.description,
          amount      = orig.amount,
          taxAmount   = Money(taxAmt, currency),
          taxable     = taxAmt > 0,
          breakdown   = parseJurisdictions(body, currency)
        )
      }

    val preTaxTotal = req.lineItems.foldLeft(0L)(_ + _.amount.minorUnits)
    val totalTax    = Money(totalTaxRaw, currency)

    TaxQuote(
      lineItems       = taxedLines,
      totalTax        = totalTax,
      totalAmount     = Money(preTaxTotal + totalTaxRaw, currency),
      currency        = currency,
      breakdown       = parseJurisdictions(body, currency),
      providerQuoteId = quoteId
    )

  private def parseLineItems(body: String, count: Int): List[Long] =
    // Extract tax amounts from line_items.data[].amount_tax
    val pattern = """"amount_tax"\s*:\s*(\d+)""".r
    val amounts = pattern.findAllMatchIn(body).map(_.group(1).toLong).toList
    if amounts.length >= count then amounts.take(count)
    else
      // Fall back: split total evenly if individual breakdowns not available
      val totalRaw = extractLong(body, "\"tax_amount_exclusive\"").getOrElse(0L)
      if count == 0 then Nil
      else {
        val perItem = totalRaw / count
        val remainder = totalRaw % count
        List.tabulate(count)(i => if i == 0 then perItem + remainder else perItem)
      }

  private def parseJurisdictions(body: String, currency: Currency): List[JurisdictionTax] =
    // Try to extract tax_breakdown array
    val namePattern   = """"jurisdiction"\s*:\s*\{[^}]*"display_name"\s*:\s*"([^"]+)"""".r
    val amountPattern = """"tax_amount_exclusive"\s*:\s*(\d+)""".r
    val names   = namePattern.findAllMatchIn(body).map(_.group(1)).toList
    val amounts = amountPattern.findAllMatchIn(body).map(_.group(1).toLong).toList.drop(1)
    names.zip(amounts).map { case (name, amt) =>
      JurisdictionTax(name, "state", BigDecimal(0), Money(amt, currency))
    }

  // ── Tax ID format validation ───────────────────────────────────────────

  private def validateFormat(taxId: String, country: String): TaxIdValidation =
    val clean   = taxId.replaceAll("[\\s-]", "")
    val isValid = country.toUpperCase match
      case "US" =>
        // US EIN: 9 digits (XX-XXXXXXX) or 9 bare digits
        clean.matches("\\d{9}")
      case c if euVatCountries.contains(c) =>
        // EU VAT: 2-letter country prefix + 2-13 alphanumeric chars
        clean.toUpperCase.matches("[A-Z]{2}[A-Z0-9]{2,13}")
      case "GB" =>
        // UK VAT: GB + 9 or 12 digits
        clean.toUpperCase.matches("GB\\d{9}") || clean.toUpperCase.matches("GB\\d{12}")
      case "AU" =>
        // AU ABN/ACN: 11 or 9 digits
        clean.matches("\\d{9}") || clean.matches("\\d{11}")
      case "CA" =>
        // CA BN: 9 digits
        clean.matches("\\d{9}")
      case _ =>
        // Unknown country: format unknown, return Unknown
        return TaxIdValidation.Unknown(taxId, country)
    if isValid then TaxIdValidation.Valid(taxId, country)
    else TaxIdValidation.Invalid(taxId, country, s"$taxId is not a valid tax ID for $country")

  private val euVatCountries = Set(
    "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI",
    "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT",
    "NL", "PL", "PT", "RO", "SE", "SI", "SK"
  )

  // ── JSON extraction helpers ───────────────────────────────────────────

  private def extractStr(body: String, key: String): Option[String] =
    val pat = s"""$key\\s*:\\s*"([^"]+)"""".r
    pat.findFirstMatchIn(body).map(_.group(1))

  private def extractLong(body: String, key: String): Option[Long] =
    val pat = s"""$key\\s*:\\s*(\\d+)""".r
    pat.findFirstMatchIn(body).flatMap(m => scala.util.Try(m.group(1).toLong).toOption)


/** Stripe Tax adapter configuration. */
case class StripeTaxConfig(
  apiKey:  String,
  baseUrl: String = "https://api.stripe.com"
)

object StripeTaxConfig:
  def fromEnv: StripeTaxConfig =
    StripeTaxConfig(
      apiKey  = sys.env.getOrElse("STRIPE_API_KEY", ""),
      baseUrl = sys.env.getOrElse("STRIPE_BASE_URL", "https://api.stripe.com")
    )

object StripeTaxProvider:
  val supportedJurisdictions: List[Jurisdiction] = List(
    Jurisdiction("US",    "United States",  "country"),
    Jurisdiction("US-CA", "California",     "state"),
    Jurisdiction("US-NY", "New York",       "state"),
    Jurisdiction("US-TX", "Texas",          "state"),
    Jurisdiction("US-FL", "Florida",        "state"),
    Jurisdiction("US-WA", "Washington",     "state"),
    Jurisdiction("DE",    "Germany",        "country"),
    Jurisdiction("FR",    "France",         "country"),
    Jurisdiction("GB",    "United Kingdom", "country"),
    Jurisdiction("AU",    "Australia",      "country"),
    Jurisdiction("CA",    "Canada",         "country"),
    Jurisdiction("JP",    "Japan",          "country"),
    Jurisdiction("SG",    "Singapore",      "country"),
    Jurisdiction("NZ",    "New Zealand",    "country"),
    Jurisdiction("IE",    "Ireland",        "country"),
    Jurisdiction("NL",    "Netherlands",    "country"),
    Jurisdiction("SE",    "Sweden",         "country"),
    Jurisdiction("NO",    "Norway",         "country"),
    Jurisdiction("CH",    "Switzerland",    "country"),
  )
