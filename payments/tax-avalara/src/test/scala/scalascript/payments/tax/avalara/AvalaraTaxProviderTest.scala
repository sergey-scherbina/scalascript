package scalascript.payments.tax.avalara

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class AvalaraTaxProviderTest extends AnyFunSuite with Matchers:

  private val usd    = Currency("USD")
  private val from   = TaxAddress("1 Market St",   "San Francisco", Some("CA"), Some("94105"), "US")
  private val to     = TaxAddress("200 W 34th St",  "New York",      Some("NY"), Some("10001"), "US")
  private val config = AvalaraConfig("123456789", "abc123", "DEFAULT", "https://rest.avatax.com")

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private def provider(postResponse: String = "", getResponse: String = ""): AvalaraTaxProvider =
    new AvalaraTaxProvider(config):
      override protected def postJson(path: String, jsonBody: String): String = postResponse
      override protected def getJson(path: String): String = getResponse

  private def reqWith(lineItems: List[TaxLineItem]): TaxRequest =
    TaxRequest(lineItems, usd, from, to)

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'avalara'"):
    AvalaraTaxProvider(config).id shouldBe "avalara"

  test("displayName includes 'Avalara'"):
    AvalaraTaxProvider(config).displayName should include("Avalara")

  // ── calculateTax ──────────────────────────────────────────────────────

  test("calculateTax: single line item, totalTax from totalTax field"):
    val json  = """{"id":"12345","totalTax":7.25,"lines":[{"taxCalculated":7.25}]}"""
    val p     = provider(postResponse = json)
    val req   = reqWith(List(TaxLineItem("Widget", Money(10000, usd))))
    val quote = await(p.calculateTax(req)(using global))
    quote.totalTax.minorUnits    shouldBe 725L
    quote.totalAmount.minorUnits shouldBe 10725L

  test("calculateTax: providerQuoteId extracted from id field"):
    val json  = """{"id":"AVALARA-QUOTE-42","totalTax":1.00,"lines":[{"taxCalculated":1.00}]}"""
    val p     = provider(postResponse = json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("X", Money(500, usd)))))(using global))
    quote.providerQuoteId shouldBe Some("AVALARA-QUOTE-42")

  test("calculateTax: missing id → providerQuoteId = None"):
    val json  = """{"totalTax":0.50,"lines":[]}"""
    val p     = provider(postResponse = json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("X", Money(500, usd)))))(using global))
    quote.providerQuoteId shouldBe None

  test("calculateTax: zero-tax response → totalTax = 0"):
    val json  = """{"id":"0tax","totalTax":0,"lines":[]}"""
    val p     = provider(postResponse = json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("Free", Money(1000, usd)))))(using global))
    quote.totalTax.minorUnits shouldBe 0L

  test("calculateTax: multiple line items, per-line taxCalculated sum"):
    val json  = """{"id":"multi","totalTax":3.00,"lines":[{"taxCalculated":1.00},{"taxCalculated":2.00}]}"""
    val p     = provider(postResponse = json)
    val items = List(
      TaxLineItem("Item A", Money(1000, usd)),
      TaxLineItem("Item B", Money(2000, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.totalTax.minorUnits shouldBe 300L
    quote.lineItems.length    shouldBe 2

  test("calculateTax: line item is non-taxable when taxCalculated = 0"):
    val json  = """{"id":"ex","totalTax":0,"lines":[{"taxCalculated":0}]}"""
    val p     = provider(postResponse = json)
    val items = List(TaxLineItem("Exempt", Money(500, usd), Some("P0000000")))
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.head.taxable shouldBe false

  test("calculateTax: lineItems order preserved"):
    val json  = """{"id":"ord","totalTax":1.00,"lines":[{"taxCalculated":0.33},{"taxCalculated":0.33},{"taxCalculated":0.34}]}"""
    val p     = provider(postResponse = json)
    val items = List(
      TaxLineItem("First",  Money(500, usd)),
      TaxLineItem("Second", Money(800, usd)),
      TaxLineItem("Third",  Money(1200, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.map(_.description) shouldBe List("First", "Second", "Third")

  test("calculateTax: currency preserved from request"):
    val eur  = Currency("EUR")
    val json = """{"id":"eur","totalTax":0.50,"lines":[]}"""
    val p    = provider(postResponse = json)
    val req  = TaxRequest(List(TaxLineItem("EU Goods", Money(1000, eur))), eur,
                          from.copy(country = "DE"), to.copy(country = "FR", state = None))
    val quote = await(p.calculateTax(req)(using global))
    quote.currency shouldBe eur

  test("calculateTax: API 400 error → TaxError.TaxCalculationFailed"):
    val failingProvider = new AvalaraTaxProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw TaxError.TaxCalculationFailed("Avalara API returned 400: Bad Request")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    val ex = await(result.failed)
    ex shouldBe a[TaxError.TaxCalculationFailed]

  test("calculateTax: unexpected exception wrapped in TaxCalculationFailed"):
    val failingProvider = new AvalaraTaxProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw new java.net.ConnectException("Connection refused")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    val ex = await(result.failed)
    ex shouldBe a[TaxError.TaxCalculationFailed]

  // ── validateTaxId ─────────────────────────────────────────────────────

  test("validateTaxId: Avalara returns isValid:true → Valid"):
    val json   = """{"isValid":true,"taxNumberType":"EIN"}"""
    val p      = provider(getResponse = json)
    val result = await(p.validateTaxId("123456789", "US")(using global))
    result shouldBe a[TaxIdValidation.Valid]

  test("validateTaxId: Avalara returns isValid:false → Invalid"):
    val json   = """{"isValid":false}"""
    val p      = provider(getResponse = json)
    val result = await(p.validateTaxId("bad-id", "US")(using global))
    result shouldBe a[TaxIdValidation.Invalid]

  test("validateTaxId: missing isValid → Unknown"):
    val json   = """{"error":"not found"}"""
    val p      = provider(getResponse = json)
    val result = await(p.validateTaxId("XYZ999", "ZZ")(using global))
    result shouldBe a[TaxIdValidation.Unknown]

  // ── getSupportedJurisdictions ─────────────────────────────────────────

  test("getSupportedJurisdictions: returns non-empty list including US and CA"):
    val jurisdictions = await(AvalaraTaxProvider(config).getSupportedJurisdictions(using global))
    jurisdictions should not be empty
    jurisdictions.map(_.code) should contain("US")
    jurisdictions.map(_.code) should contain("CA")

  test("getSupportedJurisdictions: includes US states"):
    val jurisdictions = await(AvalaraTaxProvider(config).getSupportedJurisdictions(using global))
    jurisdictions.map(_.code) should contain("US-CA")
    jurisdictions.map(_.code) should contain("US-NY")
    jurisdictions.map(_.code) should contain("US-TX")

  test("getSupportedJurisdictions: all entries have non-empty code, name, level"):
    val jurisdictions = await(AvalaraTaxProvider(config).getSupportedJurisdictions(using global))
    jurisdictions.foreach { j =>
      j.code  should not be empty
      j.name  should not be empty
      j.level should not be empty
    }
