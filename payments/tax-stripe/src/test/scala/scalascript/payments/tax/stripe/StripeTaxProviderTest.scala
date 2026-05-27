package scalascript.payments.tax.stripe

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.tax.*
import scalascript.payments.money.{Money, Currency}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class StripeTaxProviderTest extends AnyFunSuite with Matchers:

  private val usd     = Currency("USD")
  private val from    = TaxAddress("1 Market St",  "San Francisco", Some("CA"), Some("94105"), "US")
  private val to      = TaxAddress("200 W 34th St", "New York",     Some("NY"), Some("10001"), "US")
  private val config  = StripeTaxConfig("sk_test_123", "https://api.stripe.com")

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  /** Create a test provider that returns the given JSON body instead of calling the real API. */
  private def provider(responseBody: String): StripeTaxProvider =
    new StripeTaxProvider(config):
      override protected def postForm(path: String, body: String, idempotencyKey: Option[String]): String =
        responseBody

  private def reqWith(lineItems: List[TaxLineItem]): TaxRequest =
    TaxRequest(lineItems, usd, from, to)

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'stripe-tax'"):
    StripeTaxProvider(config).id shouldBe "stripe-tax"

  test("displayName includes 'Stripe Tax'"):
    StripeTaxProvider(config).displayName should include("Stripe Tax")

  // ── calculateTax ──────────────────────────────────────────────────────

  test("calculateTax: single line item, correct totalTax from tax_amount_exclusive"):
    val json = """{"id":"taxcalc_abc","tax_amount_exclusive":725,"line_items":{"data":[]}}"""
    val p    = provider(json)
    val req  = reqWith(List(TaxLineItem("Widget", Money(10000, usd))))
    val quote = await(p.calculateTax(req)(using global))
    quote.totalTax.minorUnits    shouldBe 725L
    quote.totalAmount.minorUnits shouldBe 10725L
    quote.providerQuoteId        shouldBe Some("taxcalc_abc")

  test("calculateTax: multiple line items, tax sum applies to totalTax"):
    val json = """{"id":"taxcalc_xyz","tax_amount_exclusive":200,"line_items":{"data":[]}}"""
    val p    = provider(json)
    val items = List(
      TaxLineItem("Item A", Money(1000, usd)),
      TaxLineItem("Item B", Money(2000, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.totalTax.minorUnits      shouldBe 200L
    quote.lineItems.length         shouldBe 2

  test("calculateTax: lineItems order preserved in result"):
    val json  = """{"id":"taxcalc_ord","tax_amount_exclusive":100,"line_items":{"data":[]}}"""
    val p     = provider(json)
    val items = List(
      TaxLineItem("First",  Money(500, usd)),
      TaxLineItem("Second", Money(800, usd)),
      TaxLineItem("Third",  Money(1200, usd))
    )
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.map(_.description) shouldBe List("First", "Second", "Third")

  test("calculateTax: zero-tax response → totalTax = 0"):
    val json = """{"id":"taxcalc_zero","tax_amount_exclusive":0,"line_items":{"data":[]}}"""
    val p    = provider(json)
    val quote = await(p.calculateTax(reqWith(List(TaxLineItem("Tax-free", Money(1000, usd)))))(using global))
    quote.totalTax.minorUnits shouldBe 0L

  test("calculateTax: line item is non-taxable when taxAmount = 0"):
    val json = """{"id":"taxcalc_ex","tax_amount_exclusive":0,"line_items":{"data":[]}}"""
    val p    = provider(json)
    val items = List(TaxLineItem("Exempt", Money(500, usd), Some("txcd_exempt")))
    val quote = await(p.calculateTax(reqWith(items))(using global))
    quote.lineItems.head.taxable shouldBe false

  test("calculateTax: currency preserved from request"):
    val eur  = Currency("EUR")
    val json = """{"id":"taxcalc_eur","tax_amount_exclusive":50,"line_items":{"data":[]}}"""
    val p    = provider(json)
    val req  = TaxRequest(List(TaxLineItem("EU Goods", Money(1000, eur))), eur,
                          from.copy(country = "DE"), to.copy(country = "FR", state = None))
    val quote = await(p.calculateTax(req)(using global))
    quote.currency shouldBe eur

  test("calculateTax: API 400 error → TaxError.TaxCalculationFailed"):
    val failingProvider = new StripeTaxProvider(config):
      override protected def postForm(path: String, body: String, idempotencyKey: Option[String]): String =
        throw TaxError.TaxCalculationFailed("Stripe Tax API returned 400: Invalid request")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    result.failed.map { ex =>
      ex shouldBe a[TaxError.TaxCalculationFailed]
    }(using global)

  test("calculateTax: unexpected exception wrapped in TaxProviderError"):
    val failingProvider = new StripeTaxProvider(config):
      override protected def postForm(path: String, body: String, idempotencyKey: Option[String]): String =
        throw new java.net.ConnectException("Connection refused")
    val result = failingProvider.calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global)
    val ex = await(result.failed)
    ex shouldBe a[TaxError.TaxProviderError]

  test("calculateTax: idempotencyKey passed to postForm"):
    var capturedKey: Option[String] = None
    val p = new StripeTaxProvider(config):
      override protected def postForm(path: String, body: String, idempotencyKey: Option[String]): String =
        capturedKey = idempotencyKey
        """{"id":"taxcalc_idem","tax_amount_exclusive":50,"line_items":{"data":[]}}"""
    val req = TaxRequest(List(TaxLineItem("X", Money(500, usd))), usd, from, to,
                         idempotencyKey = Some("idem-key-123"))
    await(p.calculateTax(req)(using global))
    capturedKey shouldBe Some("idem-key-123")

  test("calculateTax: providerQuoteId extracted from id field"):
    val json  = """{"id":"taxcalc_QUOTE007","tax_amount_exclusive":10,"line_items":{"data":[]}}"""
    val quote = await(provider(json).calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global))
    quote.providerQuoteId shouldBe Some("taxcalc_QUOTE007")

  test("calculateTax: missing id field → providerQuoteId = None"):
    val json  = """{"tax_amount_exclusive":10,"line_items":{"data":[]}}"""
    val quote = await(provider(json).calculateTax(reqWith(List(TaxLineItem("X", Money(100, usd)))))(using global))
    quote.providerQuoteId shouldBe None

  // ── validateTaxId ─────────────────────────────────────────────────────

  test("validateTaxId: valid US EIN (9 digits) → Valid"):
    val result = await(StripeTaxProvider(config).validateTaxId("123456789", "US")(using global))
    result shouldBe a[TaxIdValidation.Valid]

  test("validateTaxId: invalid US format → Invalid"):
    val result = await(StripeTaxProvider(config).validateTaxId("12345", "US")(using global))
    result shouldBe a[TaxIdValidation.Invalid]

  test("validateTaxId: EU VAT DE123456789 → Valid"):
    val result = await(StripeTaxProvider(config).validateTaxId("DE123456789", "DE")(using global))
    result shouldBe a[TaxIdValidation.Valid]

  test("validateTaxId: unknown country → Unknown"):
    val result = await(StripeTaxProvider(config).validateTaxId("XYZ999", "ZZ")(using global))
    result shouldBe a[TaxIdValidation.Unknown]

  // ── getSupportedJurisdictions ─────────────────────────────────────────

  test("getSupportedJurisdictions: returns non-empty list including US and DE"):
    val jurisdictions = await(StripeTaxProvider(config).getSupportedJurisdictions(using global))
    jurisdictions should not be empty
    jurisdictions.map(_.code) should contain("US")
    jurisdictions.map(_.code) should contain("DE")

  test("getSupportedJurisdictions: all entries have non-empty code, name, level"):
    val jurisdictions = await(StripeTaxProvider(config).getSupportedJurisdictions(using global))
    jurisdictions.foreach { j =>
      j.code  should not be empty
      j.name  should not be empty
      j.level should not be empty
    }
