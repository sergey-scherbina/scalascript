# EVM Client

General-purpose Ethereum/EVM JSON-RPC client for ScalaScript.
Implemented over plain HTTP JSON-RPC — no external Web3 library required.

## Config

```scalascript
case class EvmConfig(
  rpcUrl:  String,
  chainId: Int,
)

object EvmNetworks:
  val Base         = EvmConfig("https://mainnet.base.org",       chainId = 8453)
  val BaseSepolia  = EvmConfig("https://sepolia.base.org",       chainId = 84532)
  val Ethereum     = EvmConfig("https://eth.llamarpc.com",       chainId = 1)
  val Polygon      = EvmConfig("https://polygon-rpc.com",        chainId = 137)
  val Arbitrum     = EvmConfig("https://arb1.arbitrum.io/rpc",   chainId = 42161)
  val Optimism     = EvmConfig("https://mainnet.optimism.io",    chainId = 10)
```

## Types

```scalascript
case class EvmTransaction(
  hash:        String,
  from:        String,
  to:          String,
  value:       BigInt,
  data:        String,
  blockNumber: Option[BigInt],
)

case class EvmReceipt(
  hash:        String,
  status:      Int,           // 1 = success, 0 = revert
  gasUsed:     BigInt,
  logs:        List[EvmLog],
  blockNumber: BigInt,
)

case class EvmLog(address: String, topics: List[String], data: String)
```

## Client

```scalascript
trait EvmClient:
  // Chain state
  def blockNumber(): Async[BigInt]
  def getBalance(address: String): Async[BigInt]           // in wei
  def getCode(address: String): Async[String]              // hex bytecode

  // ERC-20
  def erc20Balance(token: String, address: String): Async[BigInt]
  def erc20Allowance(token: String, owner: String, spender: String): Async[BigInt]
  def erc20Decimals(token: String): Async[Int]
  def erc20Symbol(token: String): Async[String]

  // Transactions
  def getTransaction(hash: String): Async[Option[EvmTransaction]]
  def getReceipt(hash: String): Async[Option[EvmReceipt]]
  def waitForReceipt(hash: String, timeout: Duration = 60.seconds): Async[EvmReceipt]

  // Contract call (read-only, eth_call)
  def call(to: String, data: String): Async[String]

  // Raw JSON-RPC
  def rpc(method: String, params: Any*): Async[Json]

object Evm:
  def connect(config: EvmConfig): EvmClient
  def connect(rpcUrl: String): EvmClient
```

## Usage

```scalascript
val evm = Evm.connect(EvmNetworks.Base)

val balance = evm.erc20Balance("0x833589...", myAddress)
val block   = evm.blockNumber()
val raw     = evm.rpc("eth_getBlockByNumber", "latest", false)

evm.waitForReceipt(txHash).map { receipt =>
  println(s"Confirmed in block ${receipt.blockNumber}, status=${receipt.status}")
}
```

## Used by

- `x402-facilitator-evm` — on-chain payment verification and settlement
