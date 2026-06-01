package scalascript.payments.tax.avalara

import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, LocalDate}
import scala.concurrent.{Future, ExecutionContext}

/** Avalara AvaTax REST v2 adapter.
 *
 *  Endpoint: POST `https://rest.avatax.com/api/v2/transactions/create`
 *
 *  Auth: Basic authentication (`accountNumber:licenseKey` Base64-encoded).
 *
 *  Wire format: JSON.
 *
 *  Response: `{ "id": 123, "totalTax": 0.5, "lines": [...] }`.
 *
 *  `validateTaxId`: GET `/api/v2/taxnumbervalidation?taxnumber=...&countryCode=...`.
 *
 *  `getSupportedJurisdictions`: hardcoded list of countries and US states Avalara covers.
 *
 *  See `docs/traditional-payments.md §TaxProvider §avalara`.
 */
class AvalaraTaxProvider(config: AvalaraConfig) extends TaxProvider:

  def id:          String = "avalara"
  def displayName: String = "Avalara AvaTax REST v2"

  private val baseUrl = config.baseUrl.stripSuffix("/")
  private val authHeader =
    val creds = s"${config.accountNumber}:${config.licenseKey}"
    s"Basic ${java.util.Base64.getEncoder.encodeToString(creds.getBytes)}"

  // ── Injectable HTTP methods (overridden in tests) ─────────────────────

  protected def postJson(path: String, jsonBody: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", authHeader)
      .header("Content-Type",  "application/json")
      .header("X-Avalara-Client", s"ScalaScript;1.58;Scala3")
      .POST(JHttpRequest.BodyPublishers.ofString(jsonBody))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw TaxError.TaxCalculationFailed(s"Avalara API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  protected def getJson(path: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", authHeader)
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.body()

  // ── TaxProvider interface ─────────────────────────────────────────────

  def calculateTax(req: TaxRequest)(using ExecutionContext): Future[TaxQuote] =
    Future {
      val jsonBody = buildJson(req)
      val body     = postJson("/api/v2/transactions/create", jsonBody)
      parseQuote(body, req)
    }.recoverWith {
      case e: TaxError => Future.failed(e)
      case e           => Future.failed(TaxError.TaxCalculationFailed(e.getMessage, e))
    }

  def validateTaxId(taxId: String, country: String)(using ExecutionContext): Future[TaxIdValidation] =
    Future {
      val encoded = java.net.URLEncoder.encode(taxId, "UTF-8")
      val body    = getJson(s"/api/v2/taxnumbervalidation?taxnumber=$encoded&countryCode=$country")
      parseValidation(body, taxId, country)
    }.recoverWith {
      case e: TaxError => Future.failed(e)
      case e           => Future.failed(TaxError.TaxIdValidationFailed(taxId, country, e.getMessage))
    }

  def getSupportedJurisdictions(using ExecutionContext): Future[List[Jurisdiction]] =
    Future.successful(AvalaraTaxProvider.supportedJurisdictions)

  // ── Request builder ────────────────────────────────────────────────────

  private def buildJson(req: TaxRequest): String =
    val date    = LocalDate.now().toString
    val lines   = req.lineItems.zipWithIndex.map { case (item, i) =>
      val taxCode = item.taxCode.map(tc => s""","taxCode":"$tc"""").getOrElse("")
      s"""{
        "number": "${i + 1}",
        "quantity": ${item.quantity},
        "amount": ${item.amount.toDecimal},
        "description": "${jsonEscape(item.description)}"$taxCode
      }"""
    }.mkString(",\n")

    s"""{
      "type": "SalesOrder",
      "companyCode": "${jsonEscape(config.companyCode)}",
      "date": "$date",
      "customerCode": "${req.customerId.getOrElse("default")}",
      "currencyCode": "${req.currency.code}",
      "addresses": {
        "shipFrom": {
          "line1": "${jsonEscape(req.fromAddress.line1)}",
          "city": "${jsonEscape(req.fromAddress.city)}",
          "region": "${req.fromAddress.state.getOrElse("")}",
          "postalCode": "${req.fromAddress.postalCode.getOrElse("")}",
          "country": "${req.fromAddress.country}"
        },
        "shipTo": {
          "line1": "${jsonEscape(req.toAddress.line1)}",
          "city": "${jsonEscape(req.toAddress.city)}",
          "region": "${req.toAddress.state.getOrElse("")}",
          "postalCode": "${req.toAddress.postalCode.getOrElse("")}",
          "country": "${req.toAddress.country}"
        }
      },
      "lines": [$lines]
    }"""

  // ── Response parser ────────────────────────────────────────────────────

  private def parseQuote(body: String, req: TaxRequest): TaxQuote =
    val quoteId      = extractStr(body, "\"id\"")
    val totalTaxStr  = extractDecimal(body, "\"totalTax\"").getOrElse(BigDecimal(0))
    val currency     = req.currency

    // Convert totalTax (decimal) to minor units
    val scale         = math.pow(10, Currency.minorUnitsPower(currency)).toLong
    val totalTaxMinor = (totalTaxStr * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact

    val preTaxMinor   = req.lineItems.foldLeft(0L)(_ + _.amount.minorUnits)

    // Parse per-line tax from "lines" array
    val lineTaxPattern = """"taxCalculated"\s*:\s*([\d.]+)""".r
    val lineTaxes      = lineTaxPattern.findAllMatchIn(body).map { m =>
      (BigDecimal(m.group(1)) * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact
    }.toList

    val taxedLines = req.lineItems.zipWithIndex.map { case (item, i) =>
      val taxAmt = if i < lineTaxes.length then lineTaxes(i) else 0L
      TaxedLineItem(item.description, item.amount, Money(taxAmt, currency), taxable = taxAmt > 0)
    }

    // Parse jurisdiction breakdown from "details" array
    val jurisdictions = parseJurisdictions(body, currency, scale)

    TaxQuote(
      lineItems       = taxedLines,
      totalTax        = Money(totalTaxMinor, currency),
      totalAmount     = Money(preTaxMinor + totalTaxMinor, currency),
      currency        = currency,
      breakdown       = jurisdictions,
      providerQuoteId = quoteId
    )

  private def parseJurisdictions(body: String, currency: Currency, scale: Long): List[JurisdictionTax] =
    val jurisPattern  = """"jurisName"\s*:\s*"([^"]+)"""".r
    val jurisType     = """"jurisType"\s*:\s*"([^"]+)"""".r
    val taxAmtPattern = """"taxCalculated"\s*:\s*([\d.]+)""".r
    val names    = jurisPattern.findAllMatchIn(body).map(_.group(1)).toList
    val types    = jurisType.findAllMatchIn(body).map(_.group(1).toLowerCase).toList
    val amounts  = taxAmtPattern.findAllMatchIn(body).map { m =>
      (BigDecimal(m.group(1)) * scale).setScale(0, BigDecimal.RoundingMode.HALF_EVEN).toLongExact
    }.toList
    names.zip(types).zip(amounts).map { case ((name, level), amt) =>
      JurisdictionTax(name, level, BigDecimal(0), Money(amt, currency))
    }

  private def parseValidation(body: String, taxId: String, country: String): TaxIdValidation =
    val validPattern = """"isValid"\s*:\s*(true|false)""".r
    validPattern.findFirstMatchIn(body).map(_.group(1)) match
      case Some("true")  => TaxIdValidation.Valid(taxId, country)
      case Some("false") => TaxIdValidation.Invalid(taxId, country, "Tax ID is invalid per Avalara")
      case _             => TaxIdValidation.Unknown(taxId, country)

  // ── Helpers ────────────────────────────────────────────────────────────

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

  private def extractStr(body: String, key: String): Option[String] =
    val pat = s"""$key\\s*:\\s*"([^"]+)"""".r
    pat.findFirstMatchIn(body).map(_.group(1))

  private def extractDecimal(body: String, key: String): Option[BigDecimal] =
    val pat = s"""$key\\s*:\\s*([\\d.]+)""".r
    pat.findFirstMatchIn(body).flatMap(m => scala.util.Try(BigDecimal(m.group(1))).toOption)


/** Avalara AvaTax adapter configuration. */
case class AvalaraConfig(
  accountNumber: String,
  licenseKey:    String,
  companyCode:   String,
  baseUrl:       String = "https://rest.avatax.com"
)

object AvalaraConfig:
  def fromEnv: AvalaraConfig =
    AvalaraConfig(
      accountNumber = sys.env.getOrElse("AVALARA_ACCOUNT_NUMBER", ""),
      licenseKey    = sys.env.getOrElse("AVALARA_LICENSE_KEY",    ""),
      companyCode   = sys.env.getOrElse("AVALARA_COMPANY_CODE",   "DEFAULT"),
      baseUrl       = sys.env.getOrElse("AVALARA_BASE_URL",       "https://rest.avatax.com")
    )

object AvalaraTaxProvider:
  val supportedJurisdictions: List[Jurisdiction] =
    List(
      Jurisdiction("US",  "United States", "country"),
      Jurisdiction("CA",  "Canada",        "country"),
      Jurisdiction("GB",  "United Kingdom","country"),
      Jurisdiction("AU",  "Australia",     "country"),
      Jurisdiction("DE",  "Germany",       "country"),
      Jurisdiction("FR",  "France",        "country"),
      Jurisdiction("BR",  "Brazil",        "country"),
      Jurisdiction("IN",  "India",         "country"),
      Jurisdiction("SG",  "Singapore",     "country"),
      Jurisdiction("JP",  "Japan",         "country"),
      Jurisdiction("MX",  "Mexico",        "country"),
      Jurisdiction("ZA",  "South Africa",  "country"),
    ) ++ usStates.map { case (code, name) =>
      Jurisdiction(s"US-$code", name, "state")
    }

  private val usStates = List(
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
