package scalascript.coinbase

import scala.concurrent.{ExecutionContext, Future, blocking}
import sttp.client4.*
import ujson.*

case class CoinbaseConfig(
  apiKey:    String,
  apiSecret: String,
  baseUrl:   String = "https://api.coinbase.com",
)

// ── Domain types ──────────────────────────────────────────────────────────────

case class Product(
  id:            String,
  baseCurrency:  String,
  quoteCurrency: String,
  price:         BigDecimal,
)

case class Candle(
  start:  Long,
  open:   BigDecimal,
  high:   BigDecimal,
  low:    BigDecimal,
  close:  BigDecimal,
  volume: BigDecimal,
)

case class Account(
  uuid:             String,
  currency:         String,
  availableBalance: BigDecimal,
)

case class Order(
  orderId:    String,
  status:     String,
  side:       String,
  filledSize: BigDecimal,
)

case class CdpWallet(id: String, network: String, primaryAddress: String)
case class CdpTransfer(id: String, status: String, txHash: Option[String])

// ── Sub-API traits ────────────────────────────────────────────────────────────

trait CoinbaseTrade:
  def listProducts(): Future[List[Product]]
  def getProduct(productId: String): Future[Product]
  def getCandles(productId: String, granularity: String, start: Long, end: Long): Future[List[Candle]]
  def listAccounts(): Future[List[Account]]
  def createOrder(productId: String, side: String, size: BigDecimal, price: Option[BigDecimal] = None): Future[Order]
  def cancelOrder(orderId: String): Future[Unit]
  def listOrders(status: String = "OPEN"): Future[List[Order]]

trait CoinbaseCdp:
  def createWallet(network: String): Future[CdpWallet]
  def getWallet(id: String): Future[CdpWallet]
  def transfer(walletId: String, to: String, amount: BigDecimal, asset: String): Future[CdpTransfer]
  def listBalances(walletId: String): Future[Map[String, BigDecimal]]

trait CoinbaseFacilitator:
  def verify(payload: ujson.Value): Future[ujson.Value]
  def settle(payload: ujson.Value): Future[ujson.Value]

// ── Main client ───────────────────────────────────────────────────────────────

trait CoinbaseClient:
  def trade: CoinbaseTrade
  def cdp:   CoinbaseCdp
  def x402:  CoinbaseFacilitator

object Coinbase:
  def connect(config: CoinbaseConfig)(using ExecutionContext): CoinbaseClient =
    new CoinbaseClientImpl(config)

  def connect(apiKey: String, apiSecret: String)(using ExecutionContext): CoinbaseClient =
    connect(CoinbaseConfig(apiKey, apiSecret))

// ── Implementation ────────────────────────────────────────────────────────────

private class CoinbaseClientImpl(config: CoinbaseConfig)(using ec: ExecutionContext)
    extends CoinbaseClient:

  private val http = DefaultSyncBackend()

  private def get(path: String, params: Map[String, String] = Map.empty): Future[ujson.Value] =
    Future(blocking {
      val uri  = uri"${config.baseUrl}$path?$params"
      val resp = basicRequest
        .get(uri)
        .header("CB-ACCESS-KEY", config.apiKey)
        .header("Content-Type", "application/json")
        .send(http)
      ujson.read(resp.body.getOrElse(throw RuntimeException(s"Empty response from $path")))
    })

  private def post(path: String, body: ujson.Value): Future[ujson.Value] =
    Future(blocking {
      val resp = basicRequest
        .post(uri"${config.baseUrl}$path")
        .header("CB-ACCESS-KEY", config.apiKey)
        .header("Content-Type", "application/json")
        .body(body.toString)
        .send(http)
      ujson.read(resp.body.getOrElse(throw RuntimeException(s"Empty response from $path")))
    })

  val trade: CoinbaseTrade = new CoinbaseTrade:
    def listProducts(): Future[List[Product]] =
      get("/api/v3/brokerage/products").map { j =>
        j("products").arr.toList.map(p => Product(
          id            = p("product_id").str,
          baseCurrency  = p("base_currency_id").str,
          quoteCurrency = p("quote_currency_id").str,
          price         = BigDecimal(p("price").str),
        ))
      }

    def getProduct(productId: String): Future[Product] =
      get(s"/api/v3/brokerage/products/$productId").map { p =>
        Product(
          id            = p("product_id").str,
          baseCurrency  = p("base_currency_id").str,
          quoteCurrency = p("quote_currency_id").str,
          price         = BigDecimal(p("price").str),
        )
      }

    def getCandles(productId: String, granularity: String, start: Long, end: Long): Future[List[Candle]] =
      get(s"/api/v3/brokerage/products/$productId/candles",
        Map("granularity" -> granularity, "start" -> start.toString, "end" -> end.toString)
      ).map { j =>
        j("candles").arr.toList.map { c =>
          Candle(
            start  = c("start").str.toLong,
            open   = BigDecimal(c("open").str),
            high   = BigDecimal(c("high").str),
            low    = BigDecimal(c("low").str),
            close  = BigDecimal(c("close").str),
            volume = BigDecimal(c("volume").str),
          )
        }
      }

    def listAccounts(): Future[List[Account]] =
      get("/api/v3/brokerage/accounts").map { j =>
        j("accounts").arr.toList.map { a =>
          Account(
            uuid             = a("uuid").str,
            currency         = a("currency").str,
            availableBalance = BigDecimal(a("available_balance")("value").str),
          )
        }
      }

    def createOrder(productId: String, side: String, size: BigDecimal, price: Option[BigDecimal]): Future[Order] =
      val body = ujson.Obj(
        "product_id"   -> productId,
        "side"         -> side,
        "order_configuration" -> ujson.Obj(
          if price.isDefined then "limit_limit_gtc" -> ujson.Obj(
            "base_size"   -> size.toString,
            "limit_price" -> price.get.toString,
          )
          else "market_market_ioc" -> ujson.Obj("base_size" -> size.toString)
        )
      )
      post("/api/v3/brokerage/orders", body).map { j =>
        val o = j("success_response")
        Order(o("order_id").str, o("status").str, o("side").str, BigDecimal(o("filled_size").str))
      }

    def cancelOrder(orderId: String): Future[Unit] =
      post("/api/v3/brokerage/orders/batch_cancel",
        ujson.Obj("order_ids" -> ujson.Arr(orderId))
      ).map(_ => ())

    def listOrders(status: String = "OPEN"): Future[List[Order]] =
      get("/api/v3/brokerage/orders/historical/batch",
        Map("order_status" -> status)
      ).map { j =>
        j("orders").arr.toList.map { o =>
          Order(o("order_id").str, o("status").str, o("side").str,
            BigDecimal(o("filled_size").str))
        }
      }

  val cdp: CoinbaseCdp = new CoinbaseCdp:
    def createWallet(network: String): Future[CdpWallet] =
      post("/api/v3/wallets", ujson.Obj("network" -> network)).map { j =>
        CdpWallet(j("id").str, j("network").str, j("primary_address").str)
      }

    def getWallet(id: String): Future[CdpWallet] =
      get(s"/api/v3/wallets/$id").map { j =>
        CdpWallet(j("id").str, j("network").str, j("primary_address").str)
      }

    def transfer(walletId: String, to: String, amount: BigDecimal, asset: String): Future[CdpTransfer] =
      post(s"/api/v3/wallets/$walletId/transfers",
        ujson.Obj("to" -> to, "amount" -> amount.toString, "asset_id" -> asset)
      ).map { j =>
        CdpTransfer(j("id").str, j("status").str,
          if j.obj.contains("transaction_hash") then Some(j("transaction_hash").str) else None)
      }

    def listBalances(walletId: String): Future[Map[String, BigDecimal]] =
      get(s"/api/v3/wallets/$walletId/balances").map { j =>
        j("data").arr.map { b =>
          b("currency")("code").str -> BigDecimal(b("amount")("amount").str)
        }.toMap
      }

  val x402: CoinbaseFacilitator = new CoinbaseFacilitator:
    def verify(payload: ujson.Value): Future[ujson.Value] =
      post("/api/v3/x402/verify", payload)

    def settle(payload: ujson.Value): Future[ujson.Value] =
      post("/api/v3/x402/settle", payload)
