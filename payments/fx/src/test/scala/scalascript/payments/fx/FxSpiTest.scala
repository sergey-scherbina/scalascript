package scalascript.payments.fx

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.money.{Currency, Money}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class FxSpiTest extends AnyFunSuite:

  private val usd = Currency("USD")
  private val eur = Currency("EUR")
  private val gbp = Currency("GBP")
  private val now = Instant.now()

  // ── FxRate ─────────────────────────────────────────────────────────────────

  test("FxRate.mid constructs a rate with no bid/ask"):
    val rate = FxRate.mid(usd, eur, BigDecimal("0.92"), now)
    assert(rate.from      == usd)
    assert(rate.to        == eur)
    assert(rate.rate      == BigDecimal("0.92"))
    assert(rate.mid       == BigDecimal("0.92"))
    assert(rate.bid       == None)
    assert(rate.ask       == None)
    assert(rate.timestamp == now)

  test("FxRate full constructor preserves all fields"):
    val rate = FxRate(usd, eur, BigDecimal("0.92"), BigDecimal("0.92"), Some(BigDecimal("0.919")), Some(BigDecimal("0.921")), now)
    assert(rate.bid == Some(BigDecimal("0.919")))
    assert(rate.ask == Some(BigDecimal("0.921")))

  test("FxRate equality is structural"):
    val r1 = FxRate.mid(usd, eur, BigDecimal("0.92"), now)
    val r2 = FxRate.mid(usd, eur, BigDecimal("0.92"), now)
    assert(r1 == r2)

  // ── CurrencyPair ───────────────────────────────────────────────────────────

  test("CurrencyPair.from and .to are accessible"):
    val pair = CurrencyPair(usd, eur)
    assert(pair.from == usd)
    assert(pair.to   == eur)

  test("CurrencyPair.toString is FROM/TO"):
    val pair = CurrencyPair(usd, gbp)
    assert(pair.toString == "USD/GBP")

  test("CurrencyPair equality is structural"):
    val p1 = CurrencyPair(usd, eur)
    val p2 = CurrencyPair(usd, eur)
    assert(p1 == p2)

  test("CurrencyPair (USD,EUR) != (EUR,USD)"):
    assert(CurrencyPair(usd, eur) != CurrencyPair(eur, usd))

  test("CurrencyPair is usable as a Map key"):
    val m = Map(CurrencyPair(usd, eur) -> BigDecimal("0.92"))
    assert(m.get(CurrencyPair(usd, eur)) == Some(BigDecimal("0.92")))

  // ── FxError ────────────────────────────────────────────────────────────────

  test("FxError.RateUnavailable message includes currency codes"):
    val err = FxError.RateUnavailable(usd, gbp)
    assert(err.getMessage.contains("USD"))
    assert(err.getMessage.contains("GBP"))

  test("FxError.RateUnavailable is an FxError"):
    val err: FxError = FxError.RateUnavailable(usd, eur)
    assert(err.isInstanceOf[FxError])

  test("FxError.RateUnavailable is a RuntimeException"):
    val err = FxError.RateUnavailable(eur, gbp)
    assert(err.isInstanceOf[RuntimeException])

  test("FxError.FxProviderError carries message"):
    val err = FxError.FxProviderError("network timeout")
    assert(err.getMessage == "network timeout")

  test("FxError.FxProviderError carries cause"):
    val cause = new RuntimeException("underlying")
    val err   = FxError.FxProviderError("wrapped", cause)
    assert(err.getCause == cause)

  // ── FxMoneyConverter ───────────────────────────────────────────────────────

  /** Stub provider that returns a fixed rate map. */
  private class StubProvider(rates: Map[(String, String), BigDecimal]) extends FxProvider:
    def id:          String = "stub"
    def displayName: String = "Stub"

    def getRate(from: Currency, to: Currency)(using ExecutionContext): Future[FxRate] =
      rates.get((from.code, to.code)) match
        case Some(r) => Future.successful(FxRate.mid(from, to, r, now))
        case None    => Future.failed(FxError.RateUnavailable(from, to))

    def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money] =
      if money.currency == to then Future.successful(money)
      else getRate(money.currency, to).map(r => Money(money.toDecimal * r.mid, to))

    def getRates(pairs: Set[CurrencyPair])(using ExecutionContext): Future[Map[CurrencyPair, FxRate]] =
      val results = pairs.flatMap { p =>
        rates.get((p.from.code, p.to.code)).map(r => p -> FxRate.mid(p.from, p.to, r, now))
      }
      Future.successful(results.toMap)

  private val stub = StubProvider(Map(
    ("USD", "EUR") -> BigDecimal("0.92"),
    ("EUR", "USD") -> BigDecimal("1.0870"),
    ("USD", "GBP") -> BigDecimal("0.79"),
    ("EUR", "GBP") -> BigDecimal("0.8587"),
    ("GBP", "EUR") -> BigDecimal("1.1646"),
  ))

  private val converter = FxMoneyConverter(stub)

  test("FxMoneyConverter.convert: USD to EUR"):
    val money  = Money(10000L, usd)  // USD 100.00
    val result = Await.result(converter.convert(money, eur), 5.seconds)
    assert(result.currency == eur)
    // 100.00 * 0.92 = 92.00 EUR = 9200 minor units
    assert(result.minorUnits == 9200L)

  test("FxMoneyConverter.convert: same currency returns same minor units"):
    val money  = Money(5000L, usd)
    val result = Await.result(converter.convert(money, usd), 5.seconds)
    assert(result.currency == usd)
    assert(result.minorUnits == 5000L)

  test("FxMoneyConverter.convertAll: empty list returns empty list"):
    val result = Await.result(converter.convertAll(Nil, eur), 5.seconds)
    assert(result.isEmpty)

  test("FxMoneyConverter.convertAll: all same currency skips rate lookup"):
    val moneys = List(Money(1000L, usd), Money(2000L, usd))
    val result = Await.result(converter.convertAll(moneys, usd), 5.seconds)
    assert(result == moneys)

  test("FxMoneyConverter.convertAll: mixed currencies converted correctly"):
    val moneys = List(
      Money(10000L, usd),   // USD 100.00
      Money(10000L, eur),   // EUR 100.00
    )
    val result = Await.result(converter.convertAll(moneys, gbp), 5.seconds)
    assert(result.size == 2)
    assert(result.forall(_.currency == gbp))

  test("FxMoneyConverter.convertAll: missing rate fails the Future"):
    val chf    = Currency("CHF")
    val moneys = List(Money(1000L, chf))
    val fut    = converter.convertAll(moneys, eur)
    intercept[FxError.RateUnavailable] {
      Await.result(fut, 5.seconds)
    }
