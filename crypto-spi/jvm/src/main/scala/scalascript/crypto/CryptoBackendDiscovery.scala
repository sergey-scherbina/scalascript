package scalascript.crypto

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** JVM-side platform discovery hook used by the shared
 *  `object CryptoBackend` registry. Loads
 *  `META-INF/services/scalascript.crypto.CryptoBackend` entries via the
 *  standard `ServiceLoader`. */
private[crypto] object CryptoBackendDiscovery:
  def discover(): Seq[CryptoBackend] =
    ServiceLoader.load(classOf[CryptoBackend]).asScala.toSeq
