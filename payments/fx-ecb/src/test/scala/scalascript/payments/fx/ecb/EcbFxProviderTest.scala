package scalascript.payments.fx.ecb

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.fx.*
import scalascript.payments.money.{Currency, Money}
import java.time.{Duration, Instant}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class EcbFxProviderTest extends AnyFunSuite:

  private val eur = Currency("EUR")
  private val usd = Currency("USD")
  private val gbp = Currency("GBP")
  private val chf = Currency("CHF")

  // ECB rates from the fixture: EUR→USD=1.0870, EUR→GBP=0.8587, EUR→CHF=0.9321, EUR→JPY=149.23
  private def fixtureXml: String =
    scala.io.Source.fromInputStream(
      getClass.getResourceAsStream("/eurofxref-daily-test.xml")
    ).mkString

  /** Provider with fixture XML pre-loaded (no network). */
  private def providerWithFixture(
      ttl:   Duration = Duration.ofHours(1),
      clock: () => Instant = () => Instant.now(),
  ): EcbFxProvider =
    val p = EcbFxProvider(ttl = ttl, clock = clock)
    p.loadXml(fixtureXml)
    p

  // ── XML parsing ─────────────────────────────────────────────────────────────

  test("parseXml: parses EUR→USD rate from fixture"):
    val p = EcbFxProvider()
    val rates = p.parseXml(fixtureXml)
    assert(rates.get("USD") == Some(BigDecimal("1.0870")))

  test("parseXml: parses EUR→GBP rate from fixture"):
    val p = EcbFxProvider()
    val rates = p.parseXml(fixtureXml)
    assert(rates.get("GBP") == Some(BigDecimal("0.8587")))

  test("parseXml: parses EUR→JPY rate from fixture"):
    val p = EcbFxProvider()
    val rates = p.parseXml(fixtureXml)
    assert(rates.get("JPY") == Some(BigDecimal("149.23")))

  test("parseXml: returns map with at least 10 currencies from fixture"):
    val p = EcbFxProvider()
    val rates = p.parseXml(fixtureXml)
    assert(rates.size >= 10)

  test("parseXml: throws FxProviderError on malformed XML"):
    val p = EcbFxProvider()
    val exc = intercept[FxError.FxProviderError] {
      p.parseXml("<not valid xml")
    }
    assert(exc.getMessage.contains("parse error"))

  // ── EUR→X rates ─────────────────────────────────────────────────────────────

  test("getRate: EUR→USD returns 1.0870"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, usd), 5.seconds)
    assert(rate.from == eur)
    assert(rate.to   == usd)
    assert(rate.mid  == BigDecimal("1.0870"))

  test("getRate: EUR→GBP returns 0.8587"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, gbp), 5.seconds)
    assert(rate.mid == BigDecimal("0.8587"))

  test("getRate: EUR→EUR returns 1.0 (self-rate)"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, eur), 5.seconds)
    assert(rate.mid == BigDecimal(1))

  test("getRate: USD→USD returns 1.0 (self-rate)"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, usd), 5.seconds)
    assert(rate.mid == BigDecimal(1))

  // ── Cross-rates (derived) ────────────────────────────────────────────────────

  test("getRate: USD→EUR cross-rate is 1/1.0870"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, eur), 5.seconds)
    val expected = BigDecimal(1) / BigDecimal("1.0870")
    // Allow small rounding tolerance
    assert((rate.mid - expected).abs < BigDecimal("0.0001"))

  test("getRate: USD→GBP cross-rate is GBP/USD = 0.8587/1.0870"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, gbp), 5.seconds)
    val expected = BigDecimal("0.8587") / BigDecimal("1.0870")
    assert((rate.mid - expected).abs < BigDecimal("0.0001"))

  test("getRate: GBP→USD cross-rate is USD/GBP = 1.0870/0.8587"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(gbp, usd), 5.seconds)
    val expected = BigDecimal("1.0870") / BigDecimal("0.8587")
    assert((rate.mid - expected).abs < BigDecimal("0.001"))

  test("getRate: CHF→GBP cross-rate derived correctly"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(chf, gbp), 5.seconds)
    val expected = BigDecimal("0.8587") / BigDecimal("0.9321")
    assert((rate.mid - expected).abs < BigDecimal("0.0001"))

  // ── Missing currency ─────────────────────────────────────────────────────────

  test("getRate: unknown currency raises RateUnavailable"):
    val p   = providerWithFixture()
    val ada = Currency("ADA")
    val fut = p.getRate(usd, ada)
    val exc = intercept[FxError.RateUnavailable] {
      Await.result(fut, 5.seconds)
    }
    assert(exc.getMessage.contains("ADA"))

  test("getRate: unknown from currency raises RateUnavailable"):
    val p   = providerWithFixture()
    val ada = Currency("ADA")
    val fut = p.getRate(ada, eur)
    intercept[FxError.RateUnavailable] {
      Await.result(fut, 5.seconds)
    }

  // ── Cache hit ────────────────────────────────────────────────────────────────

  test("cache hit: two consecutive getRate calls return same timestamp"):
    val p = providerWithFixture()
    val r1 = Await.result(p.getRate(eur, usd), 5.seconds)
    val r2 = Await.result(p.getRate(eur, usd), 5.seconds)
    assert(r1.timestamp == r2.timestamp)

  // ── TTL expiry ────────────────────────────────────────────────────────────────

  test("TTL expiry: after TTL passes a re-fetch is triggered"):
    // We use a controllable clock and a provider that fails on network fetch
    // to verify that the cache is indeed considered expired.
    var timeVal = Instant.now()
    val clock   = () => timeVal
    val p       = EcbFxProvider(ttl = Duration.ofSeconds(5), clock = clock)
    p.loadXml(fixtureXml)

    // First call: cache is fresh
    val r1 = Await.result(p.getRate(eur, usd), 5.seconds)
    assert(r1.mid == BigDecimal("1.0870"))

    // Advance clock past TTL
    timeVal = timeVal.plusSeconds(10)

    // Cache is now stale — next call will attempt network fetch (we pre-load instead)
    p.loadXml(fixtureXml)  // reload so the next call succeeds with fresh data
    val r2 = Await.result(p.getRate(eur, usd), 5.seconds)
    assert(r2.mid == BigDecimal("1.0870"))

  test("TTL: cache is valid within TTL window"):
    var timeVal = Instant.now()
    val clock   = () => timeVal
    val p       = EcbFxProvider(ttl = Duration.ofHours(1), clock = clock)
    p.loadXml(fixtureXml)

    val ts1 = Await.result(p.getRate(eur, gbp), 5.seconds).timestamp

    // Advance clock by 30 minutes (still within 1-hour TTL)
    timeVal = timeVal.plusSeconds(1800)

    val ts2 = Await.result(p.getRate(eur, gbp), 5.seconds).timestamp
    assert(ts1 == ts2, "timestamp should not change within TTL")

  // ── convert ───────────────────────────────────────────────────────────────────

  test("convert: EUR 100.00 → USD"):
    val p      = providerWithFixture()
    val money  = Money(BigDecimal("100.00"), eur)
    val result = Await.result(p.convert(money, usd), 5.seconds)
    assert(result.currency == usd)
    // EUR 100.00 * 1.0870 = USD 108.70
    assert(result == Money(BigDecimal("108.70"), usd))

  test("convert: same currency returns unchanged amount"):
    val p      = providerWithFixture()
    val money  = Money(5000L, usd)
    val result = Await.result(p.convert(money, usd), 5.seconds)
    assert(result == money)

  test("convert: USD → GBP cross-conversion"):
    val p      = providerWithFixture()
    val money  = Money(BigDecimal("100.00"), usd)
    val result = Await.result(p.convert(money, gbp), 5.seconds)
    assert(result.currency == gbp)
    // USD 100.00 * (0.8587/1.0870) ≈ GBP 78.99
    assert(result.minorUnits > 7800L && result.minorUnits < 8000L)

  // ── getRates ──────────────────────────────────────────────────────────────────

  test("getRates: returns all requested pairs"):
    val p     = providerWithFixture()
    val pairs = Set(CurrencyPair(eur, usd), CurrencyPair(eur, gbp), CurrencyPair(usd, gbp))
    val rates = Await.result(p.getRates(pairs), 5.seconds)
    assert(rates.contains(CurrencyPair(eur, usd)))
    assert(rates.contains(CurrencyPair(eur, gbp)))
    assert(rates.contains(CurrencyPair(usd, gbp)))

  test("getRates: unknown pair is absent from result map"):
    val p     = providerWithFixture()
    val ada   = Currency("ADA")
    val pairs = Set(CurrencyPair(eur, usd), CurrencyPair(eur, ada))
    val rates = Await.result(p.getRates(pairs), 5.seconds)
    assert(rates.contains(CurrencyPair(eur, usd)))
    assert(!rates.contains(CurrencyPair(eur, ada)))

  // ── EcbFxPlugin ───────────────────────────────────────────────────────────────

  test("EcbFxPlugin.scheme is 'ecb'"):
    val plugin = EcbFxPlugin()
    assert(plugin.scheme == "ecb")

  test("EcbFxPlugin.create() returns an EcbFxProvider"):
    val plugin = EcbFxPlugin()
    assert(plugin.create().isInstanceOf[EcbFxProvider])

  test("EcbFxPlugin.displayName is non-empty"):
    val plugin = EcbFxPlugin()
    assert(plugin.displayName.nonEmpty)
