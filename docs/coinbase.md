# Coinbase Client

General-purpose Coinbase API client for ScalaScript.
Covers Advanced Trade API, CDP Wallet API, and the x402 Facilitator API.

## Config

```scalascript
case class CoinbaseConfig(
  apiKey:    String,
  apiSecret: String,
)
```

## Advanced Trade API

```scalascript
case class Product(id: String, baseCurrency: String, quoteCurrency: String, price: BigDecimal)
case class Candle(start: Long, open: BigDecimal, high: BigDecimal, low: BigDecimal, close: BigDecimal, volume: BigDecimal)
case class Account(uuid: String, currency: String, availableBalance: BigDecimal)
case class Order(orderId: String, status: String, side: String, filledSize: BigDecimal)

trait CoinbaseTrade:
  def listProducts(): Async[List[Product]]
  def getProduct(productId: String): Async[Product]
  def getCandles(productId: String, granularity: String, start: Long, end: Long): Async[List[Candle]]
  def listAccounts(): Async[List[Account]]
  def createOrder(productId: String, side: String, size: BigDecimal, price: Option[BigDecimal] = None): Async[Order]
  def cancelOrder(orderId: String): Async[Unit]
  def listOrders(status: String = "OPEN"): Async[List[Order]]
```

## CDP Wallet API

```scalascript
case class CdpWallet(id: String, network: String, primaryAddress: String)
case class CdpTransfer(id: String, status: String, txHash: Option[String])

trait CoinbaseCdp:
  def createWallet(network: String): Async[CdpWallet]
  def getWallet(id: String): Async[CdpWallet]
  def transfer(walletId: String, to: String, amount: BigDecimal, asset: String): Async[CdpTransfer]
  def listBalances(walletId: String): Async[Map[String, BigDecimal]]
```

## x402 Facilitator API

```scalascript
trait CoinbaseFacilitator:
  def verify(payload: Json): Async[Json]
  def settle(payload: Json): Async[Json]
```

## Main client

```scalascript
trait CoinbaseClient:
  def trade: CoinbaseTrade
  def cdp:   CoinbaseCdp
  def x402:  CoinbaseFacilitator

object Coinbase:
  def connect(config: CoinbaseConfig): CoinbaseClient
  def connect(apiKey: String, apiSecret: String): CoinbaseClient
```

## Usage

```scalascript
val cb = Coinbase.connect(env("COINBASE_API_KEY"), env("COINBASE_API_SECRET"))

// Market data
val price = cb.trade.getProduct("BTC-USD").map(_.price)

// CDP wallet
val wallet = cb.cdp.createWallet("base")
val tx     = cb.cdp.transfer(wallet.id, recipientAddress, 10.0, "USDC")

// x402 facilitator (used internally by x402-facilitator-coinbase)
val result = cb.x402.verify(paymentPayloadJson)
```

## Used by

- `x402-facilitator-coinbase` — delegates verify/settle to Coinbase facilitator API
