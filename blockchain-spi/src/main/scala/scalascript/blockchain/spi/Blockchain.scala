package scalascript.blockchain.spi

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Registry of `ChainAdapter` implementations. JVM discovers via
 *  `META-INF/services/scalascript.blockchain.spi.ChainAdapter`;
 *  Scala.js impls call `Blockchain.register(...)` from an init block.
 *
 *  Adapters are looked up by `ChainId` (CAIP-2). One adapter may
 *  register itself for multiple `ChainId`s if it covers a family —
 *  e.g. an `EvmChainAdapter` instance configured for `eip155:8453`
 *  is distinct from one for `eip155:1`, but both are produced by the
 *  same class. */
object Blockchain:

  private val explicit = scala.collection.mutable.LinkedHashMap.empty[ChainId, ChainAdapter]
  @volatile private var discovered: Option[Map[ChainId, ChainAdapter]] = None

  def register(adapter: ChainAdapter): Unit = synchronized:
    explicit(adapter.chainId) = adapter
    discovered = None

  def all: Seq[ChainAdapter] = synchronized:
    discovered match
      case Some(m) => m.values.toSeq
      case None =>
        val byId = scala.collection.mutable.LinkedHashMap.empty[ChainId, ChainAdapter]
        // ServiceLoader first (factories with default config), then
        // explicit registrations override.
        ServiceLoader.load(classOf[ChainAdapter]).asScala.foreach(a => byId(a.chainId) = a)
        explicit.foreach { case (id, a) => byId(id) = a }
        discovered = Some(byId.toMap)
        byId.values.toSeq

  def lookup(id: ChainId): Option[ChainAdapter] =
    all.find(_.chainId == id)

  def lookupOrThrow(id: ChainId): ChainAdapter =
    lookup(id).getOrElse(
      throw new IllegalArgumentException(s"No ChainAdapter registered for $id")
    )

  private[spi] def resetForTests(): Unit = synchronized:
    explicit.clear()
    discovered = None
