package scalascript.blockchain.spi

/** Chain-agnostic asset metadata. The `address` field's meaning depends
 *  on the chain namespace:
 *  - `eip155:*` — ERC-20 contract address, or the sentinel
 *    `"native"` / `""` for the chain's native coin (ETH on Ethereum,
 *    MATIC on Polygon, …)
 *  - `solana:*` — SPL token mint address, or `"native"` for SOL
 *  - `bip122:*` — `"native"` (Bitcoin has no native fungible-token
 *    standard beyond BTC itself; BRC-20 / Runes use higher-level
 *    indexers and live in chain-specific extensions)
 *  - other chains define their own conventions
 */
case class Asset(
  chain:    ChainId,
  address:  String,
  symbol:   String,
  decimals: Int,
):
  /** True if this asset represents the chain's native coin. */
  def isNative: Boolean = address == "native" || address.isEmpty
