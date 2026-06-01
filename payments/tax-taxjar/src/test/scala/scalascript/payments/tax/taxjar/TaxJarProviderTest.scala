package scalascript.payments.tax.taxjar

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class TaxJarProviderTest extends AnyFunSuite with Matchers:

  private val usd    = Currency("USD")
  private val from   = TaxAddress("1 Market St",   "San Francisco", Some("CA"), Some("94105"), "US")
  private val to     = TaxAddress("200 W 34th St",  "New York",      Some("NY"), Some("10001"), "US")
  private val config = TaxJarConfig("test-api-key", "https://api.taxjar.com")

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private def provider(responseBody: String): TaxJarProvider =
    new TaxJarProvider(config):
      override protected def postJson(path: String, jsonBody: String): String = responseBody

  private def reqWith(lineItems: List[TaxLineItem]): TaxRequest =
    TaxRequest(lineItems, usd, from, to)

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'taxjar'"):
    TaxJarProvider(config).id shouldBe "taxjar"

  test("displayName includes 'TaxJar'"):
    TaxJarProvider(config).displayName should include("TaxJar")

  // ── calculateTax ──────────────────────────────────────────────────────

  test("calculateTax: single line item, totalTax from amount_to_collect"):
    val json  = """{"tax":{"amount_to_collect":7.25,"breakdown":{}}}"""
    val p     = provider(json)
    val req   = reqWith(List(TaxLineItem("Widget", Money(10000, usd))))
    val quote = await(p.calculateTax(req)(using global))
    quote.totalTax.minorUnits    shouldBe 725L
    quote.totalAmount.minorUnits shouldBe 10725L

  test("calculateTax: zero-tax response → totalTax = 0"):
    val json  = """{"tax":{"amount_to_collect":0,"breakdown":{}}}"""
    val p     = provider(json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("Free", Money(1000, usd)))))(using global))
    quote.totalTax.minorUnits shouldBe 0L

  test("calculateTax: multiple line items, lineItems order preserved"):
    val json  = """{"tax":{"amount_to_collect":3.00,"breakdown":{}}}"""
    val p     = provider(json)
    val items = List(
      TaxLineItem("First",  Money(500, usd)),
      TaxLineItem("Second", Money(800, usd)),
      TaxLineItem("Third",  Money(1200, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.map(_.description) shouldBe List("First", "Second", "Third")

  test("calculateTax: multiple line items, total preserved"):
    val json  = """{"tax":{"amount_to_collect":2.00,"breakdown":{}}}"""
    val p     = provider(json)
    val items = List(
      TaxLineItem("Item A", Money(1000, usd)),
      TaxLineItem("Item B", Money(2000, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.totalTax.minorUnits shouldBe 200L
    quote.lineItems.length    shouldBe 2

  test("calculateTax: line item is non-taxable when no per-line tax"):
    val json  = """{"tax":{"amount_to_collect":0,"breakdown":{}}}"""
    val p     = provider(json)
    val items = List(TaxLineItem("Exempt", Money(500, usd), Some("99111")))
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.head.taxable shouldBe false

  test("calculateTax: currency preserved from request"):
    val eur  = Currency("EUR")
    val json = """{"tax":{"amount_to_collect":0.50,"breakdown":{}}}"""
    val p    = provider(json)
    val req  = TaxRequest(List(TaxLineItem("EU Goods", Money(1000, eur))), eur,
                          from.copy(country = "DE"), to.copy(country = "FR", state = None))
    val quote = await(p.calculateTax(req)(using global))
    quote.currency shouldBe eur

  test("calculateTax: providerQuoteId is always None for TaxJar"):
    val json  = """{"tax":{"amount_to_collect":1.00,"breakdown":{}}}"""
    val quote = await(provider(json).calculateTax(reqWith(List(TaxLineItem("X", Money(500, usd)))))(using global))
    quote.providerQuoteId shouldBe None

  test("calculateTax: API 400 error → TaxError.TaxCalculationFailed"):
    val failingProvider = new TaxJarProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw TaxError.TaxCalculationFailed("TaxJar API returned 400: Bad Request")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    val ex = await(result.failed)
    ex shouldBe a[TaxError.TaxCalculationFailed]

  test("calculateTax: unexpected exception wrapped in TaxCalculationFailed"):
    val failingProvider = new TaxJarProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw new java.net.ConnectException("Connection refused")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    val ex = await(result.failed)
    ex shouldBe a[TaxError.TaxCalculationFailed]

  test("calculateTax: state_tax_collectable parsed into breakdown"):
    val json = """{"tax":{"amount_to_collect":0.80,"state_tax_collectable":0.80,"breakdown":{}}}"""
    val p    = provider(json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("X", Money(1000, usd)))))(using global))
    quote.breakdown should not be empty

  // ── validateTaxId ─────────────────────────────────────────────────────

  test("validateTaxId: valid US EIN (9 digits) → Valid"):
    val result = await(TaxJarProvider(config).validateTaxId("123456789", "US")(using global))
    result shouldBe a[TaxIdValidation.Valid]

  test("validateTaxId: invalid US format → Invalid"):
    val result = await(TaxJarProvider(config).validateTaxId("12345", "US")(using global))
    result shouldBe a[TaxIdValidation.Invalid]

  test("validateTaxId: EU VAT DE123456789 → Valid"):
    val result = await(TaxJarProvider(config).validateTaxId("DE123456789", "DE")(using global))
    result shouldBe a[TaxIdValidation.Valid]

  test("validateTaxId: unknown country → Unknown"):
    val result = await(TaxJarProvider(config).validateTaxId("XYZ999", "ZZ")(using global))
    result shouldBe a[TaxIdValidation.Unknown]

  // ── getSupportedJurisdictions ─────────────────────────────────────────

  test("getSupportedJurisdictions: returns non-empty list including US and GB"):
    val jurisdictions = await(TaxJarProvider(config).getSupportedJurisdictions(using global))
    jurisdictions should not be empty
    jurisdictions.map(_.code) should contain("US")
    jurisdictions.map(_.code) should contain("GB")

  test("getSupportedJurisdictions: includes US states"):
    val jurisdictions = await(TaxJarProvider(config).getSupportedJurisdictions(using global))
    jurisdictions.map(_.code) should contain("US-CA")
    jurisdictions.map(_.code) should contain("US-TX")

  test("getSupportedJurisdictions: all entries have non-empty code, name, level"):
    val jurisdictions = await(TaxJarProvider(config).getSupportedJurisdictions(using global))
    jurisdictions.foreach { j =>
      j.code  should not be empty
      j.name  should not be empty
      j.level should not be empty
    }
