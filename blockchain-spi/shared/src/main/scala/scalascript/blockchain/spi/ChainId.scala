package scalascript.blockchain.spi

/** CAIP-2 chain identifier: `<namespace>:<reference>`.
 *  Examples: `eip155:1` (Ethereum mainnet), `eip155:8453` (Base),
 *  `solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w` (Solana mainnet),
 *  `bip122:000000000019d6689c085ae165831e93` (Bitcoin mainnet). */
case class ChainId(caip2: String):
  require(caip2.contains(":"), s"Not a CAIP-2 identifier: $caip2")

  /** Namespace portion, e.g. "eip155" / "solana" / "bip122". */
  def namespace: String = caip2.substring(0, caip2.indexOf(':'))

  /** Reference portion, e.g. "1" / "8453" / "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w". */
  def reference: String = caip2.substring(caip2.indexOf(':') + 1)

  override def toString: String = caip2

object ChainId:
  // Common EVM aliases for convenience; not exhaustive.
  val EthereumMainnet:  ChainId = ChainId("eip155:1")
  val Base:             ChainId = ChainId("eip155:8453")
  val BaseSepolia:      ChainId = ChainId("eip155:84532")
  val Polygon:          ChainId = ChainId("eip155:137")
  val Arbitrum:         ChainId = ChainId("eip155:42161")
  val Optimism:         ChainId = ChainId("eip155:10")

  // Cardano (CAIP-2 namespace "cardano")
  val CardanoMainnet: ChainId = ChainId("cardano:mainnet")
  val CardanoPreprod: ChainId = ChainId("cardano:preprod")
