package scalascript.blockchain.spi

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** JVM-side platform discovery hook used by the shared
 *  `object Blockchain` registry. Loads
 *  `META-INF/services/scalascript.blockchain.spi.ChainAdapter` entries
 *  via the standard `ServiceLoader`. */
private[spi] object BlockchainDiscovery:
  def discover(): Seq[ChainAdapter] =
    ServiceLoader.load(classOf[ChainAdapter]).asScala.toSeq
