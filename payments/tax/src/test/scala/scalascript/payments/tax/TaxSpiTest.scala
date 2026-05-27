package scalascript.payments.tax

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.money.{Money, Currency}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.global

/** Unit tests for the Tax Provider SPI types and TaxMoneyConverter. */
class TaxSpiTest extends AnyFunSuite with Matchers:

  private val usd = Currency("USD")
  private val from = TaxAddress("100 Main St", "New York", Some("NY"), Some("10001"), "US")
  private val to   = TaxAddress("200 Elm St",  "San Francisco", Some("CA"), Some("94105"), "US")

  // ── TaxModel round-trip ──────────────────────────────────────────────

  test("TaxAddress stores all fields"):
    val addr = TaxAddress("1 St", "City", Some("CA"), Some("90210"), "US")
    addr.line1      shouldBe "1 St"
    addr.city       shouldBe "City"
    addr.state      shouldBe Some("CA")
    addr.postalCode shouldBe Some("90210")
    addr.country    shouldBe "US"

  test("TaxLineItem defaults quantity to 1"):
    val item = TaxLineItem("Widget", Money(1000, usd))
    item.quantity shouldBe 1
    item.taxCode  shouldBe None

  test("TaxRequest stores all fields and defaults"):
    val item = TaxLineItem("Item", Money(500, usd))
    val req  = TaxRequest(List(item), usd, from, to)
    req.lineItems.length     shouldBe 1
    req.currency             shouldBe usd
    req.customerId           shouldBe None
    req.idempotencyKey       shouldBe None
    req.metadata             shouldBe Map.empty

  test("TaxQuote totalAmount = preTax total + totalTax"):
    val currency  = usd
    val taxed     = TaxedLineItem("X", Money(1000, currency), Money(70, currency), taxable = true)
    val quote     = TaxQuote(List(taxed), totalTax = Money(70, currency),
                             totalAmount = Money(1070, currency), currency)
    quote.totalTax.minorUnits     shouldBe 70L
    quote.totalAmount.minorUnits  shouldBe 1070L

  test("TaxIdValidation.Valid carries business name"):
    val v = TaxIdValidation.Valid("123456789", "US", Some("ACME Corp"))
    v.businessName shouldBe Some("ACME Corp")
    v.taxId        shouldBe "123456789"

  test("TaxIdValidation.Invalid carries reason"):
    val v = TaxIdValidation.Invalid("bad", "US", "format error")
    v.reason shouldBe "format error"

  test("TaxIdValidation.Unknown carries taxId and country"):
    val v = TaxIdValidation.Unknown("DE123", "DE")
    v.taxId   shouldBe "DE123"
    v.country shouldBe "DE"

  test("JurisdictionTax carries rate as BigDecimal"):
    val jt = JurisdictionTax("California", "state", BigDecimal("0.0725"), Money(725, usd))
    jt.rate shouldBe BigDecimal("0.0725")
    jt.level shouldBe "state"

  test("Jurisdiction carries code/name/level"):
    val j = Jurisdiction("US-CA", "California", "state")
    j.code  shouldBe "US-CA"
    j.name  shouldBe "California"
    j.level shouldBe "state"

  // ── TaxError hierarchy ───────────────────────────────────────────────

  test("TaxError.TaxCalculationFailed is a RuntimeException"):
    val e = TaxError.TaxCalculationFailed("calc failed")
    e.getMessage shouldBe "calc failed"
    e.isInstanceOf[RuntimeException] shouldBe true

  test("TaxError.TaxCalculationFailed wraps cause"):
    val cause = new IllegalStateException("network")
    val e     = TaxError.TaxCalculationFailed("outer", cause)
    e.getCause shouldBe cause

  test("TaxError.UnsupportedJurisdiction includes jurisdiction in message"):
    val e = TaxError.UnsupportedJurisdiction("CN")
    e.getMessage should include("CN")

  // ── TaxMoneyConverter ────────────────────────────────────────────────

  private def stubProvider(taxAmount: Long, preTaxAmount: Long): TaxProvider = new TaxProvider:
    def id          = "stub"
    def displayName = "Stub"
    def calculateTax(req: TaxRequest)(using ExecutionContext): Future[TaxQuote] =
      val taxed = TaxedLineItem("X", Money(preTaxAmount, usd), Money(taxAmount, usd), taxable = true)
      Future.successful(TaxQuote(
        List(taxed),
        totalTax    = Money(taxAmount, usd),
        totalAmount = Money(preTaxAmount + taxAmount, usd),
        currency    = usd
      ))
    def validateTaxId(taxId: String, country: String)(using ExecutionContext) =
      Future.successful(TaxIdValidation.Unknown(taxId, country))
    def getSupportedJurisdictions(using ExecutionContext) = Future.successful(Nil)

  private val converter = TaxMoneyConverter(stubProvider(taxAmount = 70L, preTaxAmount = 1000L))
  private val req = TaxRequest(
    List(TaxLineItem("Item", Money(1000, usd))),
    usd, from, to
  )

  test("TaxMoneyConverter.totalTax returns tax amount"):
    val result = converter.totalTax(req)(using global)
    result.map(_.minorUnits)(using global)
    // sync check via Await
    val money = scala.concurrent.Await.result(converter.totalTax(req)(using global), scala.concurrent.duration.Duration(5, "seconds"))
    money.minorUnits shouldBe 70L

  test("TaxMoneyConverter.totalWithTax returns pre-tax + tax"):
    val total = scala.concurrent.Await.result(converter.totalWithTax(req)(using global), scala.concurrent.duration.Duration(5, "seconds"))
    total.minorUnits shouldBe 1070L

  test("TaxMoneyConverter.effectiveTaxRate returns tax/preTax ratio"):
    val rate = scala.concurrent.Await.result(converter.effectiveTaxRate(req)(using global), scala.concurrent.duration.Duration(5, "seconds"))
    rate shouldBe BigDecimal("0.07")

  test("TaxMoneyConverter.effectiveTaxRate returns 0 when pre-tax total is 0"):
    val zeroConverter = TaxMoneyConverter(stubProvider(taxAmount = 0L, preTaxAmount = 0L))
    val emptyReq = req.copy(lineItems = List(TaxLineItem("Free", Money(0, usd))))
    val rate = scala.concurrent.Await.result(zeroConverter.effectiveTaxRate(emptyReq)(using global), scala.concurrent.duration.Duration(5, "seconds"))
    rate shouldBe BigDecimal(0)
