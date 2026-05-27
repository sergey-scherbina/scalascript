package scalascript.payments.fx.oer

import scalascript.payments.fx.*
import scalascript.payments.money.{Currency, Money}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.concurrent.{Future, ExecutionContext}

/** FX rate provider backed by the Open Exchange Rates API v6.
 *
 *  Endpoint: `GET https://openexchangerates.org/api/latest.json?app_id=APP_ID`
 *
 *  OER publishes USD-based rates (USD = 1.0).  Cross-rates are derived on the fly:
 *  rate(A→B) = rate(USD→B) / rate(USD→A).
 *
 *  Authentication: `app_id` query parameter (or env `OPENEXCHANGERATES_APP_ID`).
 *
 *  Cache strategy: same as `EcbFxProvider` — lazy refresh on next call after TTL.
 *
 *  @param config  adapter config (appId, baseUrl)
 *  @param ttl     cache time-to-live (default 1 hour)
 *  @param clock   override how "now" is determined (useful for testing)
 */
class OerFxProvider(
    config:  OerConfig,
    ttl:     Duration = Duration.ofHours(1),
    clock:   () => Instant = () => Instant.now(),
) extends FxProvider:

  def id:          String = "openexchangerates"
  def displayName: String = "Open Exchange Rates API v6"

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── Cache ──────────────────────────────────────────────────────────────────

  /** Rates: currency code → USD→X rate.  USD itself is always 1.0. */
  @volatile private var cache: Map[String, BigDecimal] = Map.empty
  @volatile private var cacheAt: Instant               = Instant.EPOCH

  private val cacheLock = new Object

  private def ensureFresh(): Unit =
    if clock().isAfter(cacheAt.plus(ttl)) then
      cacheLock.synchronized {
        if clock().isAfter(cacheAt.plus(ttl)) then
          val json  = fetchJson()
          val rates = parseJson(json)
          cache   = rates + ("USD" -> BigDecimal(1))
          cacheAt = clock()
      }

  // ── FxProvider interface ───────────────────────────────────────────────────

  def getRate(from: Currency, to: Currency)(using ExecutionContext): Future[FxRate] =
    Future {
      ensureFresh()
      val snap    = cache
      val snapAt  = cacheAt
      val fromStr = from.code
      val toStr   = to.code
      if fromStr == toStr then
        FxRate.mid(from, to, BigDecimal(1), snapAt)
      else
        (snap.get(fromStr), snap.get(toStr)) match
          case (Some(rFrom), Some(rTo)) =>
            // rate(from→to) = rTo / rFrom  (both are USD→X)
            val mid = rTo / rFrom
            FxRate.mid(from, to, mid, snapAt)
          case _ =>
            throw FxError.RateUnavailable(from, to)
    }

  def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money] =
    if money.currency == to then Future.successful(money)
    else getRate(money.currency, to).map(r => Money(money.toDecimal * r.mid, to))

  def getRates(pairs: Set[CurrencyPair])(using ExecutionContext): Future[Map[CurrencyPair, FxRate]] =
    Future {
      ensureFresh()
      val snap   = cache
      val snapAt = cacheAt
      pairs.flatMap { pair =>
        val fromStr = pair.from.code
        val toStr   = pair.to.code
        if fromStr == toStr then
          Some(pair -> FxRate.mid(pair.from, pair.to, BigDecimal(1), snapAt))
        else
          for
            rFrom <- snap.get(fromStr)
            rTo   <- snap.get(toStr)
          yield
            val mid = rTo / rFrom
            pair -> FxRate.mid(pair.from, pair.to, mid, snapAt)
      }.toMap
    }

  // ── HTTP ───────────────────────────────────────────────────────────────────

  private[oer] def fetchJson(): String =
    val url = s"${config.baseUrl}/latest.json?app_id=${config.appId}"
    val req = JHttpRequest.newBuilder(URI.create(url))
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    val resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw FxError.FxProviderError(s"OER API returned HTTP ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON parsing ──────────────────────────────────────────────────────────

  /** Parse OER `/latest.json` response.
   *
   *  Expected shape (simplified):
   *  {{{
   *    {
   *      "base": "USD",
   *      "timestamp": 1234567890,
   *      "rates": {
   *        "EUR": 0.9200,
   *        "GBP": 0.7900,
   *        ...
   *      }
   *    }
   *  }}}
   *
   *  We parse this with a hand-rolled mini-parser to avoid adding a JSON
   *  library dependency to the payments/fx-openexchangerates module.
   */
  private[oer] def parseJson(json: String): Map[String, BigDecimal] =
    // Find the "rates": { ... } object
    val ratesPattern = """"rates"\s*:\s*\{([^}]*)\}""".r
    ratesPattern.findFirstMatchIn(json) match
      case None =>
        throw FxError.FxProviderError("OER JSON missing 'rates' object")
      case Some(m) =>
        val ratesBody = m.group(1)
        parseRatesBody(ratesBody)

  /** Parse a flat JSON object body like `"EUR": 0.9200, "GBP": 0.7900, ...` */
  private def parseRatesBody(body: String): Map[String, BigDecimal] =
    val entryPattern = """"([A-Z]{3,4})"\s*:\s*([\d.]+(?:e[+-]?\d+)?)""".r
    entryPattern.findAllMatchIn(body).flatMap { m =>
      val code     = m.group(1)
      val rateStr  = m.group(2)
      scala.util.Try(BigDecimal(rateStr)).toOption.map(r => code -> r)
    }.toMap

  // ── Cache management (test hooks) ─────────────────────────────────────────

  /** Load a pre-fetched JSON string directly (bypasses HTTP, for testing). */
  private[oer] def loadJson(json: String): Unit =
    cacheLock.synchronized {
      val rates = parseJson(json)
      cache   = rates + ("USD" -> BigDecimal(1))
      cacheAt = clock()
    }

  /** Inject a rate map directly (unit tests). */
  private[oer] def setCache(rates: Map[String, BigDecimal], at: Instant): Unit =
    cacheLock.synchronized {
      cache   = rates
      cacheAt = at
    }

  /** Expire the cache immediately (simulate TTL for tests). */
  private[oer] def expireCache(): Unit =
    cacheLock.synchronized {
      cacheAt = Instant.EPOCH
    }


/** Configuration for `OerFxProvider`.
 *
 *  @param appId    Open Exchange Rates application ID
 *  @param baseUrl  API base URL (default: https://openexchangerates.org/api)
 */
case class OerConfig(
  appId:   String,
  baseUrl: String = "https://openexchangerates.org/api",
)

object OerConfig:
  /** Load from environment variable `OPENEXCHANGERATES_APP_ID`.
   *  Falls back to an empty string if not set (provider will fail at fetch time). */
  def fromEnv: OerConfig =
    OerConfig(
      appId   = sys.env.getOrElse("OPENEXCHANGERATES_APP_ID", ""),
      baseUrl = sys.env.getOrElse("OPENEXCHANGERATES_BASE_URL", "https://openexchangerates.org/api"),
    )
