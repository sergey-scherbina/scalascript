package scalascript.imports

/** Resolves Markdown-import link destinations to a local `os.Path`.
 *
 *  Three cases:
 *
 *  1. Absolute URL (`http://…` / `https://…`) → fetch into the user
 *     cache `~/.cache/ssc/<host>/<path>` on first access, then return
 *     the cached path.  Subsequent imports of the same URL hit the
 *     cache without network.
 *  2. Relative path against a *cached* base directory → derive the
 *     corresponding URL by inverting the cache layout (`cache/foo.com/
 *     bar/` → `https://foo.com/bar/`) and fetch the relative dependency
 *     from there.  Lets transitive `./helper.ssc` imports inside a
 *     URL-imported file resolve without the consumer rewriting them.
 *  3. Plain relative path → resolve against `baseDir` (existing
 *     behaviour, no network).
 *
 *  Set the environment variable `SSC_NO_NETWORK=1` to disallow any
 *  outbound fetch — uncached URL imports fail fast with a clear
 *  message, which is what sandboxed / reproducible builds want.
 */
object ImportResolver:
  private val cacheRoot: os.Path = os.home / ".cache" / "ssc"

  def resolve(rawPath: String, baseDir: os.Path): os.Path =
    if isUrl(rawPath) then fetchToCache(rawPath)
    else
      val local = baseDir / os.RelPath(rawPath)
      if os.exists(local) then local
      else cacheBackedRelative(rawPath, baseDir).getOrElse(local)

  private def isUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  /** If `baseDir` lives inside the user cache, the file we're after is
   *  almost certainly a transitive `.ssc` of an earlier URL import.
   *  Reconstruct its URL by inverting the `scheme/authority/path`
   *  layout the cache uses, then fetch. */
  private def cacheBackedRelative(rawPath: String, baseDir: os.Path): Option[os.Path] =
    if !baseDir.startsWith(cacheRoot) then return None
    val rel = baseDir.relativeTo(cacheRoot)
    val segs = rel.segments.toList
    // Need at least `<scheme>/<authority>` before we can derive a URL.
    if segs.length < 2 then return None
    val scheme    = segs.head
    val authority = segs(1)
    val base      = segs.drop(2).mkString("/")
    val combined =
      if base.isEmpty then rawPath.stripPrefix("./")
      else base + "/" + rawPath.stripPrefix("./")
    val normalised = normalisePath(combined)
    if normalised.startsWith("..") then None
    else
      val url = s"$scheme://$authority/$normalised"
      Some(fetchToCache(url))

  private def normalisePath(p: String): String =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    p.split("/").foreach {
      case "" | "." => ()
      case ".."     => if out.nonEmpty && out.last != ".." then out.remove(out.length - 1)
                       else out += ".."
      case seg      => out += seg
    }
    out.mkString("/")

  /** Cache path for a URL.  Layout `~/.cache/ssc/<scheme>/<authority>/<path>`
   *  preserves enough of the URL — protocol + host + port — that a
   *  transitive relative import resolved from a cached file can
   *  reconstruct the original URL by inverting this mapping. */
  private def cachePath(url: String): os.Path =
    val u = java.net.URI.create(url).toURL
    val scheme    = u.getProtocol
    val port      = u.getPort
    val authority = if port < 0 then u.getHost else s"${u.getHost}:$port"
    val path      = u.getPath.stripPrefix("/")
    if path.isEmpty then cacheRoot / scheme / authority / "index.ssc"
    else cacheRoot / scheme / authority / os.SubPath(path)

  private def fetchToCache(url: String): os.Path =
    val out = cachePath(url)
    if os.exists(out) then out
    else
      if sys.env.get("SSC_NO_NETWORK").contains("1") then
        throw new RuntimeException(
          s"URL import not in cache and SSC_NO_NETWORK=1: $url"
        )
      os.makeDir.all(out / os.up)
      val u    = java.net.URI.create(url).toURL
      val conn = u.openConnection
      conn.setConnectTimeout(10_000)
      conn.setReadTimeout(20_000)
      val in   = conn.getInputStream
      try os.write(out, in.readAllBytes())
      finally in.close()
      out
