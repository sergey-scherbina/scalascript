package scalascript.cli

/** Parse cache for `ssc watch`.
 *
 *  Keeps the last parsed `scalascript.ast.Module` per file path, keyed by
 *  (mtime, content-hash).  On every watch cycle the cache is consulted
 *  before handing the file to the parser:
 *
 *    1. Read mtime cheaply.  If mtime matches the cached entry we skip hashing
 *       and re-use the cached module immediately.
 *    2. If mtime changed, read the file bytes and compute SHA-256.  Only if
 *       the hash also changed do we actually re-parse.  This makes the cache
 *       robust against spurious ENTRY_MODIFY events that editors emit without
 *       actually altering content.
 *
 *  The cache is intentionally a per-JVM-process singleton (object-level
 *  mutable map).  Watch is a single-file, single-thread loop, so no
 *  synchronization is needed.  The cache is thread-safe by convention (only
 *  called from the watch thread or tests that run single-threaded).
 */
object ParseCache:

  private case class Entry(mtime: Long, hash: String, module: scalascript.ast.Module)

  private val cache = scala.collection.mutable.Map.empty[os.Path, Entry]

  /** Return the cached module if (path, mtime, hash) is unchanged, else
   *  re-parse and store the new entry. */
  def getOrParse(path: os.Path): scalascript.ast.Module =
    val mtime = os.mtime(path)
    cache.get(path) match
      case Some(e) if e.mtime == mtime =>
        // mtime matches — trust the cached parse result without re-reading.
        e.module
      case _ =>
        // mtime differs (or no entry yet): read bytes, check hash.
        val bytes = os.read.bytes(path)
        val hash  = sha256hex(bytes)
        cache.get(path) match
          case Some(e) if e.hash == hash =>
            // Content unchanged despite mtime change (e.g. editor metadata touch).
            // Update mtime so the fast path fires next time.
            cache(path) = e.copy(mtime = mtime)
            e.module
          case _ =>
            // Content really changed — re-parse.
            val module = scalascript.parser.Parser.parse(new String(bytes, "UTF-8"))
            cache(path) = Entry(mtime, hash, module)
            module

  /** Remove the cached entry for `path` (useful in tests). */
  def invalidate(path: os.Path): Unit = cache.remove(path)

  /** Clear the entire cache (useful in tests). */
  def clear(): Unit = cache.clear()

  /** Compute a SHA-256 hex digest of `bytes`. */
  def sha256hex(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    toHex(md.digest(bytes))

  private def toHex(bytes: Array[Byte]): String =
    val out = new Array[Char](bytes.length * 2)
    val hex = "0123456789abcdef"
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      out(i * 2) = hex.charAt(b >>> 4)
      out(i * 2 + 1) = hex.charAt(b & 0x0f)
      i += 1
    String(out)
