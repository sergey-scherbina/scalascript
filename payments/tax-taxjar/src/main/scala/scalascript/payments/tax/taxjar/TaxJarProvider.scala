package scalascript.payments.tax.taxjar

import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.concurrent.{Future, ExecutionContext}

/** TaxJar SmartCalcs REST API v2 adapter.
 *
 *  Endpoint: POST `https://api.taxjar.com/v2/taxes`
 *
 *  Auth: Bearer token (`Authorization: Bearer <apiKey>`).
 *
 *  Wire format: JSON. Amounts in major-unit decimals (e.g. `7.25` for USD 7.25).
 *
 *  Response: `{ "tax": { "amount_to_collect": 0.72, "breakdown": {...} } }`.
 *
 *  `validateTaxId`: format-only check (TaxJar has no public validation endpoint).
 *
 *  `getSupportedJurisdictions`: hardcoded list of countries and US states.
 *
 *  See `docs/specs/traditional-payments.md §TaxProvider §taxjar`.
 */
class TaxJarProvider(config: TaxJarConfig) extends TaxProvider:

  def id:          String = "taxjar"
  def displayName: String = "TaxJar SmartCalcs REST API v2"

  private val baseUrl = config.baseUrl.stripSuffix("/")

  // ── Injectable HTTP method (overridden in tests) ──────────────────────

  protected def postJson(path: String, jsonBody: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type",  "application/json")
      .header("x-api-version", "2022-01-24")
      .POST(JHttpRequest.BodyPublishers.ofString(jsonBody))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw TaxError.TaxCalculationFailed(s"TaxJar API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  // ── TaxProvider interface ─────────────────────────────────────────────

  def calculateTax(req: TaxRequest)(using ExecutionContext): Future[TaxQuote] =
    Future {
      val jsonBody = buildJson(req)
      val body     = postJson("/v2/taxes", jsonBody)
      parseQuote(body, req)
    }.recoverWith {
      case e: TaxError => Future.failed(e)
      case e           => Future.failed(TaxError.TaxCalculationFailed(e.getMessage, e))
    }

  def validateTaxId(taxId: String, country: String)(using ExecutionContext): Future[TaxIdValidation] =
    Future.successful(validateFormat(taxId, country))

  def getSupportedJurisdictions(using ExecutionContext): Future[List[Jurisdiction]] =
    Future.successful(TaxJarProvider.supportedJurisdictions)

  // ── Request builder ────────────────────────────────────────────────────

  private def buildJson(req: TaxRequest): String =
    val totalAmount = req.lineItems.foldLeft(BigDecimal(0))(_ + _.amount.toDecimal)
    val lineItems = req.lineItems.zipWithIndex.map { case (item, i) =>
      val taxCode = item.taxCode.map(tc => s""","product_tax_code":"$tc"""").getOrElse("")
      s"""{"id":"${i + 1}","quantity":${item.quantity},"unit_price":${item.amount.toDecimal},"description":"${jsonEscape(item.description)}"$taxCode}"""
    }.mkString(",")

    s"""{
      "from_country":"${req.fromAddress.country}",
      "from_zip":"${req.fromAddress.postalCode.getOrElse("")}",
      "from_state":"${req.fromAddress.state.getOrElse("")}",
      "from_city":"${jsonEscape(req.fromAddress.city)}",
      "from_street":"${jsonEscape(req.fromAddress.line1)}",
      "to_country":"${req.toAddress.country}",
      "to_zip":"${req.toAddress.postalCode.getOrElse("")}",
      "to_state":"${req.toAddress.state.getOrElse("")}",
      "to_city":"${jsonEscape(req.toAddress.city)}",
      "to_street":"${jsonEscape(req.toAddress.line1)}",
      "amount":$totalAmount,
      "shipping":0,
      "currency":"${req.currency.code}",
      "line_items":[$lineItems]
    }"""

  // ── Response parser ────────────────────────────────────────────────────

  private def parseQuote(body: String, req: TaxRequest): TaxQuote =
    val currency      = req.currency
    val scale         = math.pow(10, Currency.minorUnitsPower(currency)).toLong
    val amtToCollect  = extractDecimal(body, "\"amount_to_collect\"").getOrElse(BigDecimal(0))
    val totalTaxMinor = (amtToCollect * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact

    val preTaxMinor = req.lineItems.foldLeft(0L)(_ + _.amount.minorUnits)

    // Per-line tax: breakdown.line_items[].tax_collectable
    val lineTaxPattern = """"tax_collectable"\s*:\s*([\d.]+)""".r
    val allMatches     = lineTaxPattern.findAllMatchIn(body).map { m =>
      (BigDecimal(m.group(1)) * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact
    }.toList
    // First occurrence is the top-level field; per-line ones follow
    val lineTaxes = if allMatches.length > 1 then allMatches.drop(1) else Nil

    val taxedLines = req.lineItems.zipWithIndex.map { case (item, i) =>
      val taxAmt = if i < lineTaxes.length then lineTaxes(i) else 0L
      TaxedLineItem(item.description, item.amount, Money(taxAmt, currency), taxable = taxAmt > 0)
    }

    TaxQuote(
      lineItems       = taxedLines,
      totalTax        = Money(totalTaxMinor, currency),
      totalAmount     = Money(preTaxMinor + totalTaxMinor, currency),
      currency        = currency,
      breakdown       = parseJurisdictions(body, currency, scale),
      providerQuoteId = None
    )

  private def parseJurisdictions(body: String, currency: Currency, scale: Long): List[JurisdictionTax] =
    val stateTax  = extractDecimal(body, "\"state_tax_collectable\"")
    val countyTax = extractDecimal(body, "\"county_tax_collectable\"")
    val cityTax   = extractDecimal(body, "\"city_tax_collectable\"")
    List(
      stateTax.map  { v => JurisdictionTax("State",  "state",  BigDecimal(0), Money((v * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact, currency)) },
      countyTax.map { v => JurisdictionTax("County", "county", BigDecimal(0), Money((v * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact, currency)) },
      cityTax.map   { v => JurisdictionTax("City",   "city",   BigDecimal(0), Money((v * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact, currency)) },
    ).flatten.filter(_.taxAmount.minorUnits > 0)

  // ── Tax ID format validation ───────────────────────────────────────────

  private def validateFormat(taxId: String, country: String): TaxIdValidation =
    val clean   = taxId.replaceAll("[\\s-]", "")
    val isValid = country.toUpperCase match
      case "US" =>
        clean.matches("\\d{9}")
      case c if euVatCountries.contains(c) =>
        clean.toUpperCase.matches("[A-Z]{2}[A-Z0-9]{2,13}")
      case "GB" =>
        clean.toUpperCase.matches("GB\\d{9}") || clean.toUpperCase.matches("GB\\d{12}")
      case "AU" =>
        clean.matches("\\d{9}") || clean.matches("\\d{11}")
      case "CA" =>
        clean.matches("\\d{9}")
      case _ =>
        return TaxIdValidation.Unknown(taxId, country)
    if isValid then TaxIdValidation.Valid(taxId, country)
    else TaxIdValidation.Invalid(taxId, country, s"$taxId is not a valid tax ID for $country")

  private val euVatCountries = Set(
    "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI",
    "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT",
    "NL", "PL", "PT", "RO", "SE", "SI", "SK"
  )

  // ── Helpers ────────────────────────────────────────────────────────────

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

  private def extractDecimal(body: String, key: String): Option[BigDecimal] =
    val pat = s"""$key\\s*:\\s*([\\d.]+)""".r
    pat.findFirstMatchIn(body).flatMap(m => scala.util.Try(BigDecimal(m.group(1))).toOption)


/** TaxJar adapter configuration. */
case class TaxJarConfig(
  apiKey:  String,
  baseUrl: String = "https://api.taxjar.com"
)

object TaxJarConfig:
  def fromEnv: TaxJarConfig =
    TaxJarConfig(
      apiKey  = sys.env.getOrElse("TAXJAR_API_KEY", ""),
      baseUrl = sys.env.getOrElse("TAXJAR_BASE_URL", "https://api.taxjar.com")
    )

object TaxJarProvider:
  val supportedJurisdictions: List[Jurisdiction] =
    List(
      Jurisdiction("US", "United States",  "country"),
      Jurisdiction("CA", "Canada",         "country"),
      Jurisdiction("AU", "Australia",      "country"),
      Jurisdiction("GB", "United Kingdom", "country"),
      Jurisdiction("DE", "Germany",        "country"),
      Jurisdiction("FR", "France",         "country"),
      Jurisdiction("ES", "Spain",          "country"),
      Jurisdiction("IT", "Italy",          "country"),
      Jurisdiction("NL", "Netherlands",    "country"),
      Jurisdiction("BE", "Belgium",        "country"),
      Jurisdiction("AT", "Austria",        "country"),
      Jurisdiction("SE", "Sweden",         "country"),
      Jurisdiction("NO", "Norway",         "country"),
      Jurisdiction("CH", "Switzerland",    "country"),
      Jurisdiction("IE", "Ireland",        "country"),
      Jurisdiction("NZ", "New Zealand",    "country"),
    ) ++ usStates.map { case (code, name) =>
      Jurisdiction(s"US-$code", name, "state")
    }

  private lazy val usStates = List(
    "AL" -> "Alabama", "AK" -> "Alaska", "AZ" -> "Arizona", "AR" -> "Arkansas",
    "CA" -> "California", "CO" -> "Colorado", "CT" -> "Connecticut",
    "DE" -> "Delaware", "FL" -> "Florida", "GA" -> "Georgia",
    "HI" -> "Hawaii", "ID" -> "Idaho", "IL" -> "Illinois", "IN" -> "Indiana",
    "IA" -> "Iowa", "KS" -> "Kansas", "KY" -> "Kentucky", "LA" -> "Louisiana",
    "ME" -> "Maine", "MD" -> "Maryland", "MA" -> "Massachusetts",
    "MI" -> "Michigan", "MN" -> "Minnesota", "MS" -> "Mississippi",
    "MO" -> "Missouri", "MT" -> "Montana", "NE" -> "Nebraska",
    "NV" -> "Nevada", "NH" -> "New Hampshire", "NJ" -> "New Jersey",
    "NM" -> "New Mexico", "NY" -> "New York", "NC" -> "North Carolina",
    "ND" -> "North Dakota", "OH" -> "Ohio", "OK" -> "Oklahoma",
    "OR" -> "Oregon", "PA" -> "Pennsylvania", "RI" -> "Rhode Island",
    "SC" -> "South Carolina", "SD" -> "South Dakota", "TN" -> "Tennessee",
    "TX" -> "Texas", "UT" -> "Utah", "VT" -> "Vermont", "VA" -> "Virginia",
    "WA" -> "Washington", "WV" -> "West Virginia", "WI" -> "Wisconsin",
    "WY" -> "Wyoming", "DC" -> "District of Columbia"
  )
