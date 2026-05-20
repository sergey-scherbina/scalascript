package scalascript.blockchain.spi

/** Scala.js-side platform discovery hook used by the shared
 *  `object Blockchain` registry. Scala.js does not have
 *  `ServiceLoader`, so platform discovery is a no-op — impl modules
 *  must call `Blockchain.register(...)` from a top-level initialiser. */
private[spi] object BlockchainDiscovery:
  def discover(): Seq[ChainAdapter] = Nil
