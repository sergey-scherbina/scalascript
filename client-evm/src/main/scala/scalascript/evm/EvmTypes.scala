package scalascript.evm

case class EvmConfig(rpcUrl: String, chainId: Int)

object EvmNetworks:
  val Base        = EvmConfig("https://mainnet.base.org",      chainId = 8453)
  val BaseSepolia = EvmConfig("https://sepolia.base.org",      chainId = 84532)
  val Ethereum    = EvmConfig("https://eth.llamarpc.com",      chainId = 1)
  val Polygon     = EvmConfig("https://polygon-rpc.com",       chainId = 137)
  val Arbitrum    = EvmConfig("https://arb1.arbitrum.io/rpc",  chainId = 42161)
  val Optimism    = EvmConfig("https://mainnet.optimism.io",   chainId = 10)

case class EvmTransaction(
  hash:        String,
  from:        String,
  to:          Option[String],
  value:       BigInt,
  input:       String,
  blockNumber: Option[BigInt],
)

case class EvmLog(
  address: String,
  topics:  List[String],
  data:    String,
)

case class EvmReceipt(
  transactionHash: String,
  status:          Int,
  gasUsed:         BigInt,
  logs:            List[EvmLog],
  blockNumber:     BigInt,
)

case class EvmBlock(
  hash:      String,
  number:    BigInt,
  timestamp: BigInt,
)

case class EvmRpcError(code: Int, message: String)
  extends Exception(s"EVM RPC error $code: $message")
