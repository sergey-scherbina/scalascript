package scalascript.compiler.plugin.deploy

import java.util.concurrent.ConcurrentHashMap

/** Content-addressed LRU cache for worker bundle artifacts.
 *  Key is `sha256:<hex>`. Evicts least-recently-used entries when `maxEntries` is exceeded. */
class ArtifactCache(val maxEntries: Int = 64):

  private val store = new ConcurrentHashMap[String, Array[Byte]]()
  private val order = new java.util.LinkedHashMap[String, java.lang.Boolean](16, 0.75f, true) {
    override def removeEldestEntry(e: java.util.Map.Entry[String, java.lang.Boolean]): Boolean =
      if super.size() > maxEntries then
        store.remove(e.getKey)
        true
      else false
  }
  private val lock = new Object

  def put(hash: String, bytes: Array[Byte]): Unit =
    store.put(hash, bytes)
    lock.synchronized { order.put(hash, java.lang.Boolean.TRUE) }

  def get(hash: String): Option[Array[Byte]] =
    val v = store.get(hash)
    if v != null then
      lock.synchronized { order.get(hash) }
      Some(v)
    else None

  def contains(hash: String): Boolean = store.containsKey(hash)

  def remove(hash: String): Unit =
    store.remove(hash)
    lock.synchronized { order.remove(hash) }

  def size: Int = store.size()

  def keys: List[String] =
    val buf = List.newBuilder[String]
    store.keySet().forEach(buf += _)
    buf.result()

object ArtifactCache:
  val global: ArtifactCache = new ArtifactCache(128)
