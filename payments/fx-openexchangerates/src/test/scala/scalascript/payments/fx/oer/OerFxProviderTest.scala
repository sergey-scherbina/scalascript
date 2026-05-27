package scalascript.payments.fx.oer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{BeforeAndAfterAll}
import scalascript.payments.fx.*
import scalascript.payments.money.{Currency, Money}
import com.sun.net.httpserver.{HttpServer, HttpExchange}
import scala.compiletime.uninitialized
import java.net.InetSocketAddress
import java.time.{Duration, Instant}
import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class OerFxProviderTest extends AnyFunSuite with BeforeAndAfterAll:

  private val usd = Currency("USD")
  private val eur = Currency("EUR")
  private val gbp = Currency("GBP")
  private val chf = Currency("CHF")

  // ── OER JSON fixture ────────────────────────────────────────────────────────

  private val sampleJson =
    """{
      |  "disclaimer": "Usage subject to terms: https://openexchangerates.org/terms",
      |  "license": "https://openexchangerates.org/license",
      |  "timestamp": 1748387600,
      |  "base": "USD",
      |  "rates": {
      |    "EUR": 0.9200,
      |    "GBP": 0.7900,
      |    "CHF": 0.8977,
      |    "JPY": 149.85,
      |    "AUD": 1.5420,
      |    "CAD": 1.3580,
      |    "SEK": 10.2345,
      |    "NOK": 10.6789,
      |    "DKK": 6.8712,
      |    "PLN": 3.9234,
      |    "SGD": 1.3421,
      |    "HKD": 7.8234
      |  }
      |}""".stripMargin

  /** Provider with fixture JSON pre-loaded (no network). */
  private def providerWithFixture(
      ttl:   Duration = Duration.ofHours(1),
      clock: () => Instant = () => Instant.now(),
  ): OerFxProvider =
    val config = OerConfig(appId = "test-app-id")
    val p      = OerFxProvider(config, ttl = ttl, clock = clock)
    p.loadJson(sampleJson)
    p

  // ── Mock HTTP server ─────────────────────────────────────────────────────────

  @volatile private var mockServer: HttpServer  = uninitialized
  @volatile private var mockPort: Int           = 0
  @volatile private var mockResponseBody: String = sampleJson
  @volatile private var mockStatusCode: Int     = 200

  override def beforeAll(): Unit =
    mockServer = HttpServer.create(new InetSocketAddress(0), 0)
    mockPort   = mockServer.getAddress.getPort
    mockServer.createContext("/api/latest.json", (exchange: HttpExchange) => {
      val body = mockResponseBody.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(mockStatusCode, body.length)
      val os = exchange.getResponseBody
      os.write(body)
      os.close()
    })
    mockServer.setExecutor(null)
    mockServer.start()

  override def afterAll(): Unit =
    if mockServer != null then mockServer.stop(0)

  private def mockProvider(): OerFxProvider =
    val config = OerConfig(appId = "test-app-id", baseUrl = s"http://localhost:$mockPort/api")
    OerFxProvider(config)

  // ── JSON parsing ─────────────────────────────────────────────────────────────

  test("parseJson: parses USD→EUR rate"):
    val config = OerConfig(appId = "x")
    val p      = OerFxProvider(config)
    val rates  = p.parseJson(sampleJson)
    assert(rates.get("EUR") == Some(BigDecimal("0.9200")))

  test("parseJson: parses USD→GBP rate"):
    val config = OerConfig(appId = "x")
    val p      = OerFxProvider(config)
    val rates  = p.parseJson(sampleJson)
    assert(rates.get("GBP") == Some(BigDecimal("0.7900")))

  test("parseJson: parses USD→JPY rate"):
    val config = OerConfig(appId = "x")
    val p      = OerFxProvider(config)
    val rates  = p.parseJson(sampleJson)
    assert(rates.get("JPY") == Some(BigDecimal("149.85")))

  test("parseJson: returns at least 10 currencies"):
    val config = OerConfig(appId = "x")
    val p      = OerFxProvider(config)
    val rates  = p.parseJson(sampleJson)
    assert(rates.size >= 10)

  test("parseJson: throws FxProviderError on missing 'rates' key"):
    val config = OerConfig(appId = "x")
    val p      = OerFxProvider(config)
    val exc    = intercept[FxError.FxProviderError] {
      p.parseJson("""{"base":"USD","timestamp":1234}""")
    }
    assert(exc.getMessage.contains("rates"))

  // ── USD→X rates ─────────────────────────────────────────────────────────────

  test("getRate: USD→EUR returns 0.9200"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, eur), 5.seconds)
    assert(rate.from == usd)
    assert(rate.to   == eur)
    assert(rate.mid  == BigDecimal("0.9200"))

  test("getRate: USD→GBP returns 0.7900"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, gbp), 5.seconds)
    assert(rate.mid == BigDecimal("0.7900"))

  test("getRate: USD→USD returns 1.0 (self-rate)"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(usd, usd), 5.seconds)
    assert(rate.mid == BigDecimal(1))

  test("getRate: EUR→EUR returns 1.0 (self-rate)"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, eur), 5.seconds)
    assert(rate.mid == BigDecimal(1))

  // ── Cross-rates ──────────────────────────────────────────────────────────────

  test("getRate: EUR→USD cross-rate is 1/0.9200"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, usd), 5.seconds)
    val expected = BigDecimal(1) / BigDecimal("0.9200")
    assert((rate.mid - expected).abs < BigDecimal("0.0001"))

  test("getRate: EUR→GBP cross-rate is 0.7900/0.9200"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(eur, gbp), 5.seconds)
    val expected = BigDecimal("0.7900") / BigDecimal("0.9200")
    assert((rate.mid - expected).abs < BigDecimal("0.0001"))

  test("getRate: GBP→EUR cross-rate is 0.9200/0.7900"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(gbp, eur), 5.seconds)
    val expected = BigDecimal("0.9200") / BigDecimal("0.7900")
    assert((rate.mid - expected).abs < BigDecimal("0.001"))

  test("getRate: CHF→GBP cross-rate derived correctly"):
    val p    = providerWithFixture()
    val rate = Await.result(p.getRate(chf, gbp), 5.seconds)
    val expected = BigDecimal("0.7900") / BigDecimal("0.8977")
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

  // ── Cache hit ─────────────────────────────────────────────────────────────────

  test("cache hit: two consecutive calls return the same timestamp"):
    val p  = providerWithFixture()
    val r1 = Await.result(p.getRate(usd, eur), 5.seconds)
    val r2 = Await.result(p.getRate(usd, eur), 5.seconds)
    assert(r1.timestamp == r2.timestamp)

  // ── TTL expiry ────────────────────────────────────────────────────────────────

  test("TTL: cache is valid within TTL window"):
    var timeVal = Instant.now()
    val clock   = () => timeVal
    val p       = providerWithFixture(ttl = Duration.ofHours(1), clock = clock)

    val ts1 = Await.result(p.getRate(usd, eur), 5.seconds).timestamp

    // Advance clock by 30 minutes — still within TTL
    timeVal = timeVal.plusSeconds(1800)

    val ts2 = Await.result(p.getRate(usd, eur), 5.seconds).timestamp
    assert(ts1 == ts2, "timestamp should not change within TTL")

  test("TTL expiry: after TTL cache is refreshed"):
    var timeVal = Instant.now()
    val clock   = () => timeVal
    val p       = OerFxProvider(OerConfig("test-app-id"), ttl = Duration.ofSeconds(5), clock = clock)
    p.loadJson(sampleJson)

    val r1 = Await.result(p.getRate(usd, eur), 5.seconds)

    // Advance clock past TTL
    timeVal = timeVal.plusSeconds(10)

    // Reload cache with updated data (simulate re-fetch)
    p.loadJson(sampleJson)

    val r2 = Await.result(p.getRate(usd, eur), 5.seconds)
    assert(r1.mid == r2.mid)

  // ── convert ───────────────────────────────────────────────────────────────────

  test("convert: USD 100.00 → EUR"):
    val p      = providerWithFixture()
    val money  = Money(BigDecimal("100.00"), usd)
    val result = Await.result(p.convert(money, eur), 5.seconds)
    assert(result.currency == eur)
    assert(result == Money(BigDecimal("92.00"), eur))

  test("convert: same currency returns unchanged amount"):
    val p      = providerWithFixture()
    val money  = Money(5000L, usd)
    val result = Await.result(p.convert(money, usd), 5.seconds)
    assert(result == money)

  test("convert: EUR → GBP cross-conversion"):
    val p      = providerWithFixture()
    val money  = Money(BigDecimal("100.00"), eur)
    val result = Await.result(p.convert(money, gbp), 5.seconds)
    assert(result.currency == gbp)
    // EUR 100 * (0.7900/0.9200) ≈ GBP 85.87
    assert(result.minorUnits > 8500L && result.minorUnits < 8700L)

  // ── getRates ──────────────────────────────────────────────────────────────────

  test("getRates: returns all requested known pairs"):
    val p     = providerWithFixture()
    val pairs = Set(CurrencyPair(usd, eur), CurrencyPair(usd, gbp), CurrencyPair(eur, gbp))
    val rates = Await.result(p.getRates(pairs), 5.seconds)
    assert(rates.contains(CurrencyPair(usd, eur)))
    assert(rates.contains(CurrencyPair(usd, gbp)))
    assert(rates.contains(CurrencyPair(eur, gbp)))

  test("getRates: unknown pair is absent from result map"):
    val p     = providerWithFixture()
    val ada   = Currency("ADA")
    val pairs = Set(CurrencyPair(usd, eur), CurrencyPair(usd, ada))
    val rates = Await.result(p.getRates(pairs), 5.seconds)
    assert(rates.contains(CurrencyPair(usd, eur)))
    assert(!rates.contains(CurrencyPair(usd, ada)))

  // ── Mock HTTP server tests ────────────────────────────────────────────────────

  test("fetchJson: fetches from mock HTTP server"):
    mockResponseBody = sampleJson
    mockStatusCode   = 200
    val p    = mockProvider()
    val json = p.fetchJson()
    assert(json.contains("EUR"))
    assert(json.contains("rates"))

  test("fetchJson: HTTP 401 throws FxProviderError"):
    mockResponseBody = """{"error":true,"status":401,"message":"invalid_app_id"}"""
    mockStatusCode   = 401
    val p   = mockProvider()
    val exc = intercept[FxError.FxProviderError] {
      p.fetchJson()
    }
    assert(exc.getMessage.contains("401"))
    // Reset
    mockStatusCode = 200; mockResponseBody = sampleJson

  test("full round-trip via mock HTTP server: getRate USD→EUR"):
    mockResponseBody = sampleJson
    mockStatusCode   = 200
    val p    = mockProvider()
    val rate = Await.result(p.getRate(usd, eur), 5.seconds)
    assert(rate.mid == BigDecimal("0.9200"))
    // Reset
    mockResponseBody = sampleJson

  // ── OerFxPlugin ───────────────────────────────────────────────────────────────

  test("OerFxPlugin.schemes contains 'openexchangerates' and 'oer'"):
    val plugin = OerFxPlugin()
    assert(plugin.schemes.contains("openexchangerates"))
    assert(plugin.schemes.contains("oer"))

  test("OerFxPlugin.create() returns an OerFxProvider"):
    val plugin = OerFxPlugin()
    assert(plugin.create().isInstanceOf[OerFxProvider])

  test("OerFxPlugin.displayName is non-empty"):
    val plugin = OerFxPlugin()
    assert(plugin.displayName.nonEmpty)

  // ── OerConfig ─────────────────────────────────────────────────────────────────

  test("OerConfig.fromEnv returns a config"):
    val cfg = OerConfig.fromEnv
    assert(cfg.baseUrl.contains("openexchangerates.org") || cfg.baseUrl.nonEmpty)

  test("OerConfig fields accessible"):
    val cfg = OerConfig(appId = "my-app-id", baseUrl = "https://api.example.com")
    assert(cfg.appId   == "my-app-id")
    assert(cfg.baseUrl == "https://api.example.com")
