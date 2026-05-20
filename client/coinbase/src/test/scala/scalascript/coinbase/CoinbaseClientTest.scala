package scalascript.coinbase

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Assertions

class CoinbaseClientTest extends AnyFunSuite:

  test("CoinbaseConfig defaults") {
    val cfg = CoinbaseConfig("key", "secret")
    assert(cfg.baseUrl == "https://api.coinbase.com")
    assert(cfg.apiKey == "key")
    assert(cfg.apiSecret == "secret")
  }

  test("Product fields") {
    val p = Product("BTC-USD", "BTC", "USD", BigDecimal("50000.00"))
    assert(p.id == "BTC-USD")
    assert(p.baseCurrency == "BTC")
    assert(p.quoteCurrency == "USD")
    assert(p.price == BigDecimal("50000.00"))
  }

  test("Candle fields") {
    val c = Candle(1700000000L, BigDecimal("50000"), BigDecimal("51000"),
      BigDecimal("49000"), BigDecimal("50500"), BigDecimal("100.5"))
    assert(c.start == 1700000000L)
    assert(c.open == BigDecimal("50000"))
    assert(c.high == BigDecimal("51000"))
    assert(c.low == BigDecimal("49000"))
    assert(c.close == BigDecimal("50500"))
    assert(c.volume == BigDecimal("100.5"))
  }

  test("Account fields") {
    val a = Account("uuid-123", "BTC", BigDecimal("1.5"))
    assert(a.uuid == "uuid-123")
    assert(a.currency == "BTC")
    assert(a.availableBalance == BigDecimal("1.5"))
  }

  test("Order fields") {
    val o = Order("order-123", "OPEN", "BUY", BigDecimal("0.1"))
    assert(o.orderId == "order-123")
    assert(o.status == "OPEN")
    assert(o.side == "BUY")
    assert(o.filledSize == BigDecimal("0.1"))
  }

  test("CdpWallet fields") {
    val w = CdpWallet("wallet-1", "ethereum-mainnet", "0xabc123")
    assert(w.id == "wallet-1")
    assert(w.network == "ethereum-mainnet")
    assert(w.primaryAddress == "0xabc123")
  }

  test("CdpTransfer fields with txHash") {
    val t = CdpTransfer("transfer-1", "PENDING", Some("0xdeadbeef"))
    assert(t.id == "transfer-1")
    assert(t.status == "PENDING")
    assert(t.txHash.contains("0xdeadbeef"))
  }

  test("CdpTransfer fields without txHash") {
    val t = CdpTransfer("transfer-2", "PENDING", None)
    assert(t.txHash.isEmpty)
  }

  test("Coinbase.connect returns CoinbaseClient") {
    import scala.concurrent.ExecutionContext.Implicits.global
    val client = Coinbase.connect("key", "secret")
    assert(client.isInstanceOf[CoinbaseClient])
    assert(client.trade.isInstanceOf[CoinbaseTrade])
    assert(client.cdp.isInstanceOf[CoinbaseCdp])
    assert(client.x402.isInstanceOf[CoinbaseFacilitator])
  }

  // Live API tests — skip unless credentials provided
  test("trade.listProducts (live)") {
    val key    = sys.env.getOrElse("COINBASE_API_KEY", "")
    val secret = sys.env.getOrElse("COINBASE_API_SECRET", "")
    assume(key.nonEmpty && secret.nonEmpty, "COINBASE_API_KEY / COINBASE_API_SECRET not set")
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val client   = Coinbase.connect(key, secret)
    val products = Await.result(client.trade.listProducts(), 15.seconds)
    assert(products.nonEmpty)
    assert(products.exists(_.id == "BTC-USD"))
  }

  test("trade.getProduct BTC-USD (live)") {
    val key    = sys.env.getOrElse("COINBASE_API_KEY", "")
    val secret = sys.env.getOrElse("COINBASE_API_SECRET", "")
    assume(key.nonEmpty && secret.nonEmpty, "COINBASE_API_KEY / COINBASE_API_SECRET not set")
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val client  = Coinbase.connect(key, secret)
    val product = Await.result(client.trade.getProduct("BTC-USD"), 15.seconds)
    assert(product.id == "BTC-USD")
    assert(product.baseCurrency == "BTC")
    assert(product.quoteCurrency == "USD")
    assert(product.price > 0)
  }
