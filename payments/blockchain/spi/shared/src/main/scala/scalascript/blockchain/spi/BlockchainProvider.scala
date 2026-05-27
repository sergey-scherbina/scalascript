package scalascript.blockchain.spi

import scala.concurrent.ExecutionContext

/** Factory SPI for chain adapter bundles.
 *
 *  Each blockchain module (cosmos, solana, …) may implement this trait
 *  and register it via `META-INF/services/scalascript.blockchain.spi.BlockchainProvider`
 *  to expose multiple `ChainAdapter` instances through a single ServiceLoader entry.
 *
 *  Contrast with registering each `ChainAdapter` directly — a provider
 *  covers a whole chain family (e.g. CosmosHub + Osmosis + Juno) behind
 *  one service file entry. */
trait BlockchainProvider:
  /** Create and return all `ChainAdapter` instances this provider supports. */
  def adapters()(using ExecutionContext): Seq[ChainAdapter]
