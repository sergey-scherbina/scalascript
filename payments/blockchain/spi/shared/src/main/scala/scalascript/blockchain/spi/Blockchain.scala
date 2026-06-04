package scalascript.blockchain.spi

/** Cross-platform registry of `ChainAdapter` implementations.
 *
 *  Two layers of registration:
 *
 *  1. **Explicit** — `Blockchain.register(adapter)` adds an adapter to
 *     the in-memory map. Used directly by Scala.js impl-module
 *     initialisers and by JVM tests that need to inject doubles.
 *  2. **Platform discovery** — on JVM, `BlockchainDiscovery.discover()`
 *     loads `META-INF/services/scalascript.blockchain.spi.ChainAdapter`
 *     via `ServiceLoader`. On Scala.js, `discover()` returns `Nil`.
 *
 *  Adapters are looked up by `ChainId` (CAIP-2). One adapter class may
 *  register itself for multiple `ChainId`s if it covers a family —
 *  e.g. an `EvmChainAdapter` instance configured for `eip155:8453`
 *  is distinct from one for `eip155:1`, but both are produced by the
 *  same class.
 *
 *  See docs/specs/wallet-spi-scalajs.md §3.4 for the cross-platform pattern. */
object Blockchain:

  private val explicit = scala.collection.mutable.LinkedHashMap.empty[ChainId, ChainAdapter]
  @volatile private var cached: Option[Map[ChainId, ChainAdapter]] = None

  def register(adapter: ChainAdapter): Unit = synchronized:
    explicit(adapter.chainId) = adapter
    cached = None

  def all: Seq[ChainAdapter] = synchronized:
    cached match
      case Some(m) => m.values.toSeq
      case None =>
        val byId = scala.collection.mutable.LinkedHashMap.empty[ChainId, ChainAdapter]
        // Platform discovery first (ServiceLoader on JVM, no-op on JS),
        // then explicit overrides.
        BlockchainDiscovery.discover().foreach(a => byId(a.chainId) = a)
        explicit.foreach { case (id, a) => byId(id) = a }
        cached = Some(byId.toMap)
        byId.values.toSeq

  def lookup(id: ChainId): Option[ChainAdapter] =
    all.find(_.chainId == id)

  def lookupOrThrow(id: ChainId): ChainAdapter =
    lookup(id).getOrElse(
      throw new IllegalArgumentException(s"No ChainAdapter registered for $id")
    )

  private[spi] def resetForTests(): Unit = synchronized:
    explicit.clear()
    cached = None
