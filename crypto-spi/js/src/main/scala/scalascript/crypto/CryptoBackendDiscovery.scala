package scalascript.crypto

/** Scala.js-side platform discovery hook used by the shared
 *  `object CryptoBackend` registry. Scala.js does not have
 *  `ServiceLoader`, so platform discovery is a no-op — impl modules
 *  must call `CryptoBackend.register(...)` from a top-level
 *  initialiser. Planned first impl: `crypto-noble-js`. */
private[crypto] object CryptoBackendDiscovery:
  def discover(): Seq[CryptoBackend] = Nil
