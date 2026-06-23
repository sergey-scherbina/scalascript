package scalascript.blockchain.cosmos

import scala.concurrent.ExecutionContext
import scalascript.blockchain.spi.{BlockchainProvider, ChainAdapter}

/** `BlockchainProvider` that registers Cosmos Hub, Osmosis, and Juno adapters
 *  via the Java `ServiceLoader` mechanism.
 *
 *  Registered in:
 *  `META-INF/services/scalascript.blockchain.spi.BlockchainProvider` */
class CosmosBackend extends BlockchainProvider:

  def adapters()(using ExecutionContext): Seq[ChainAdapter] =
    CosmosChainAdapter.all
