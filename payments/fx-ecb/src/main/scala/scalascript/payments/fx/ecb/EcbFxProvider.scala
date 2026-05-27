package scalascript.payments.fx.ecb

import scalascript.markup.{Markup, PureMarkupCodec}
import scalascript.payments.fx.*
import scalascript.payments.money.{Currency, Money}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.concurrent.{Future, ExecutionContext}

/** FX rate provider backed by the ECB daily reference rates XML feed.
 *
 *  Data source: `https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml`
 *
 *  The ECB publishes rates with EUR as base once per business day, typically
 *  around 16:00 CET.  This provider:
 *  1. Lazily fetches the XML on first call.
 *  2. Caches rates for `ttl` (default 1 hour).
 *  3. On each call, if the cache has expired, re-fetches in the calling thread
 *     (synchronized — only one thread re-fetches, others wait).
 *  4. Derives cross-rates on the fly: to convert USD→GBP, it computes
 *     (EUR/GBP) / (EUR/USD) = GBP/USD then inverts.
 *
 *  EUR→X rates are stored as "how many X per one EUR".
 *  Cross-rates: rate(A→B) = rate(EUR→B) / rate(EUR→A).
 *
 *  @param ttl        cache time-to-live (default 1 hour)
 *  @param feedUrl    override the ECB feed URL (useful for testing)
 *  @param clock      override how "now" is determined (useful for testing)
 */
class EcbFxProvider(
    ttl:     Duration = Duration.ofHours(1),
    feedUrl: String   = EcbFxProvider.DefaultFeedUrl,
    clock:   () => Instant = () => Instant.now(),
) extends FxProvider:

  def id:          String = "ecb"
  def displayName: String = "European Central Bank daily reference rates"

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── Cache ──────────────────────────────────────────────────────────────────

  /** Rates map: currency code → EUR→X rate.
   *  EUR itself is 1.0 (self-referential, always present). */
  @volatile private var cache: Map[String, BigDecimal] = Map.empty
  @volatile private var cacheAt: Instant               = Instant.EPOCH

  private val cacheLock = new Object

  private def ensureFresh(): Unit =
    if clock().isAfter(cacheAt.plus(ttl)) then
      cacheLock.synchronized {
        // double-check after acquiring lock
        if clock().isAfter(cacheAt.plus(ttl)) then
          val xml  = fetchXml()
          val rates = parseXml(xml)
          cache   = rates + ("EUR" -> BigDecimal(1))
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
            // rate(from→to) = rTo / rFrom  (both are EUR→X)
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

  private[ecb] def fetchXml(): String =
    val req = JHttpRequest.newBuilder(URI.create(feedUrl))
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    val resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw FxError.FxProviderError(s"ECB feed returned HTTP ${resp.statusCode()}")
    resp.body()

  // ── XML parsing ────────────────────────────────────────────────────────────

  /** Parse ECB daily XML and return a map of currencyCode → EUR-based rate. */
  private[ecb] def parseXml(xml: String): Map[String, BigDecimal] =
    PureMarkupCodec.parse(xml) match
      case Left(err)  => throw FxError.FxProviderError(s"ECB XML parse error: ${err.message}", err)
      case Right(doc) => extractRates(doc.root)

  private def extractRates(node: Markup.Node): Map[String, BigDecimal] =
    val result = scala.collection.mutable.Map.empty[String, BigDecimal]
    collectCubeRates(node, result)
    result.toMap

  private def collectCubeRates(node: Markup.Node, acc: scala.collection.mutable.Map[String, BigDecimal]): Unit =
    node match
      case e: Markup.Element =>
        // Look for <Cube currency="XXX" rate="Y.YYYY"/>
        if e.name.localName == "Cube" then
          val attrs = e.attrs.map(a => a.name.localName -> a.value).toMap
          (attrs.get("currency"), attrs.get("rate")) match
            case (Some(code), Some(rateStr)) =>
              scala.util.Try(BigDecimal(rateStr)).foreach { r =>
                acc(code) = r
              }
            case _ => ()
        e.children.foreach(collectCubeRates(_, acc))
      case _ => ()

  // ── Cache management (test hook) ──────────────────────────────────────────

  /** Load a pre-fetched XML string directly (used in tests to bypass HTTP). */
  private[ecb] def loadXml(xml: String): Unit =
    cacheLock.synchronized {
      val rates = parseXml(xml)
      cache   = rates + ("EUR" -> BigDecimal(1))
      cacheAt = clock()
    }

  /** Expire the cache immediately (used in tests to simulate TTL). */
  private[ecb] def expireCache(): Unit =
    cacheLock.synchronized {
      cacheAt = Instant.EPOCH
    }

  /** Inject a rate map directly (used in unit tests). */
  private[ecb] def setCache(rates: Map[String, BigDecimal], at: Instant): Unit =
    cacheLock.synchronized {
      cache   = rates
      cacheAt = at
    }


object EcbFxProvider:
  val DefaultFeedUrl = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
