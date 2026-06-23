package scalascript.blockchain.cosmos

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scalascript.blockchain.spi.BlockchainProvider

/** JVM-only: verifies `CosmosBackend` is wired into `META-INF/services` and discovered via the JDK
 *  `ServiceLoader`. Scala.js has no `ServiceLoader`, so this lives in the jvm source set; the portable
 *  `CosmosBackend.adapters()` behaviour is covered cross-platform in `CosmosTest`. */
class CosmosBackendDiscoveryTest extends AnyFunSuite with Matchers:

  test("BlockchainProvider is discovered via ServiceLoader") {
    val providers = ServiceLoader.load(classOf[BlockchainProvider]).asScala.toSeq
    providers should not be empty
    providers.filter(_.isInstanceOf[CosmosBackend]) should not be empty
  }
