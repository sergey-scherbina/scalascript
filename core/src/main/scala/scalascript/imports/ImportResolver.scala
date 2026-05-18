package scalascript.imports

/** Resolves Markdown-import link destinations to a local `os.Path`.
 *
 *  Four cases:
 *
 *  1. `dep:org.example/lib:1.2` → looked up through the dep-sources
 *     chain (`~/.config/scalascript/dep-sources`), cached at
 *     `~/.cache/scalascript/deps/`, integrity-verified via `ssc.lock`.
 *  2. Absolute URL (`http://…` / `https://…`) → fetch into the user
 *     cache `~/.cache/ssc/<host>/<path>` on first access.  If a
 *     `lockPath` is supplied the URL must be in `ssc.lock` and its
 *     SHA-256 must match.
 *  3. Relative path against a *cached* base directory → derive the
 *     corresponding URL and fetch (same lock rules as #2).
 *  4. Plain relative path → resolve against `baseDir` (no network).
 *
 *  Set `SSC_NO_NETWORK=1` to disallow any outbound fetch.
 *  Supply `lockPath` to enforce v1.19 integrity-check semantics.
 */
object ImportResolver:
  private val cacheRoot: os.Path = os.home / ".cache" / "ssc"
  private val depCacheRoot: os.Path = os.home / ".cache" / "scalascript" / "deps"
  private val depSourcesPath: os.Path = os.home / ".config" / "scalascript" / "dep-sources"

  def resolve(rawPath: String, baseDir: os.Path): os.Path =
    resolve(rawPath, baseDir, Map.empty, lockPath = None)

  /** Same as `resolve(rawPath, baseDir)` but with the importing module's
   *  manifest `dependencies:` map in scope and an optional `ssc.lock` path
   *  for v1.19 integrity enforcement. */
  def resolve(
      rawPath:  String,
      baseDir:  os.Path,
      deps:     Map[String, String],
      lockPath: Option[os.Path] = None
  ): os.Path =
    // 1. dep: scheme — always resolved through dep-sources chain
    if rawPath.startsWith("dep:") then
      return resolveDep(rawPath, lockPath)

    val pathThroughDep = applyDeps(rawPath, deps).getOrElse(rawPath)
    val resolved =
      if isUrl(pathThroughDep) then fetchToCache(pathThroughDep, lockPath)
      else
        val local = baseDir / os.RelPath(pathThroughDep)
        if os.exists(local) then local
        else cacheBackedRelative(pathThroughDep, baseDir, lockPath).getOrElse(local)
    // v0.9.1 — directory-as-index: `[Name](./pack)` and the path points to
    // a directory ⇒ look for `<dir>/index.ssc` inside.
    if os.exists(resolved) && os.isDir(resolved) then
      val index = resolved / "index.ssc"
      if os.exists(index) then index else resolved
    else resolved

  /** Rewrite a `<name>://<sub>` path to the URL from the deps map. */
  private def applyDeps(rawPath: String, deps: Map[String, String]): Option[String] =
    val schemeIdx = rawPath.indexOf("://")
    if schemeIdx <= 0 then return None
    val scheme = rawPath.substring(0, schemeIdx)
    if scheme == "http" || scheme == "https" || scheme == "file" then return None
    deps.get(scheme).filter(isUrl).map { base =>
      val sub = rawPath.substring(schemeIdx + 3).stripPrefix("/")
      if base.endsWith("/") then base + sub
      else base + "/" + sub
    }

  private def isUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  // ─── dep: scheme ─────────────────────────────────────────────────

  /** Resolve `dep:org.example/lib:1.2` through the dep-sources chain.
   *
   *  Resolution order:
   *  1. Local dep cache `~/.cache/scalascript/deps/<org.name>/<name>/<version>.ssc`
   *  2. Each line in `~/.config/scalascript/dep-sources` treated as a base URL:
   *     `GET <base>/<org.name>/<lib-name>/<version>.ssc`
   *  3. Fail with a clear message pointing to `dep-sources` config.
   *
   *  v1.19.x: central registry (`registry.scalascript.io`) deferred.
   */
  private def resolveDep(depUri: String, lockPath: Option[os.Path]): os.Path =
    val rest = depUri.stripPrefix("dep:")
    // Parse `org.example/lib:1.2` → (org.example, lib, 1.2)
    val slashIdx = rest.indexOf('/')
    if slashIdx < 0 then throw new RuntimeException(
      s"Invalid dep: URI format '$depUri' — expected dep:<org>/<name>:<version>"
    )
    val org  = rest.substring(0, slashIdx)
    val rest2 = rest.substring(slashIdx + 1)
    val colonIdx = rest2.indexOf(':')
    if colonIdx < 0 then throw new RuntimeException(
      s"Invalid dep: URI format '$depUri' — expected dep:<org>/<name>:<version>"
    )
    val name    = rest2.substring(0, colonIdx)
    val version = rest2.substring(colonIdx + 1)
    val cacheKey = s"$org/$name/$version.ssc"
    val cached = depCacheRoot / os.RelPath(cacheKey)
    if os.exists(cached) then
      // Already cached — verify lock if present
      lockPath.foreach { lp =>
        val content = os.read.bytes(cached)
        LockFile.read(lp).fold(
          err => throw new RuntimeException(err.getMessage),
          lock => lock.check(depUri, content).fold(err => throw new RuntimeException(err), identity)
        )
      }
      return cached
    // Try each dep-source endpoint
    val sources = depSources()
    if sources.isEmpty then
      throw new RuntimeException(
        s"No dep-sources configured for '$depUri'.\n" +
        s"Add an endpoint to ~/.config/scalascript/dep-sources, e.g.:\n" +
        s"  https://packages.example.com/ssc/"
      )
    val tried = sources.map { base =>
      val url = if base.endsWith("/") then s"$base$org/$name/$version.ssc"
                else s"$base/$org/$name/$version.ssc"
      tryFetch(url)
    }
    tried.collectFirst { case Right((url, bytes)) => (url, bytes) } match
      case None =>
        val endpoints = sources.mkString(", ")
        throw new RuntimeException(
          s"dep '$depUri' not found at any configured endpoint ($endpoints)"
        )
      case Some((resolvedUrl, bytes)) =>
        // Write to dep cache
        os.makeDir.all(cached / os.up)
        os.write(cached, bytes)
        // Update ssc.lock
        lockPath.foreach { lp =>
          val lock0 = LockFile.read(lp).getOrElse(LockFile.empty)
          val lock1 = lock0.pin(depUri, bytes, resolvedUrl = Some(resolvedUrl))
          LockFile.write(lock1, lp)
        }
        cached

  private def depSources(): List[String] =
    if !os.exists(depSourcesPath) then Nil
    else os.read.lines(depSourcesPath).filter(l => l.nonEmpty && !l.startsWith("#")).toList

  private def tryFetch(url: String): Either[String, (String, Array[Byte])] =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      return Left(s"SSC_NO_NETWORK=1, skipping $url")
    scala.util.Try {
      val u    = java.net.URI.create(url).toURL
      val conn = u.openConnection
      conn.setConnectTimeout(10_000)
      conn.setReadTimeout(20_000)
      val in = conn.getInputStream
      try in.readAllBytes()
      finally in.close()
    }.toEither.map(bytes => (url, bytes)).left.map(_.getMessage)

  // ─── URL imports + lock check ────────────────────────────────────

  /** If `baseDir` lives inside the user cache, reconstruct the URL
   *  and fetch the relative dependency from the same origin. */
  private def cacheBackedRelative(
      rawPath:  String,
      baseDir:  os.Path,
      lockPath: Option[os.Path]
  ): Option[os.Path] =
    if !baseDir.startsWith(cacheRoot) then return None
    val rel  = baseDir.relativeTo(cacheRoot)
    val segs = rel.segments.toList
    if segs.length < 2 then return None
    val scheme    = segs.head
    val authority = segs(1)
    val base      = segs.drop(2).mkString("/")
    val combined  =
      if base.isEmpty then rawPath.stripPrefix("./")
      else base + "/" + rawPath.stripPrefix("./")
    val normalised = normalisePath(combined)
    if normalised.startsWith("..") then None
    else
      val url = s"$scheme://$authority/$normalised"
      Some(fetchToCache(url, lockPath))

  private def normalisePath(p: String): String =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    p.split("/").foreach {
      case "" | "." => ()
      case ".."     => if out.nonEmpty && out.last != ".." then out.remove(out.length - 1)
                       else out += ".."
      case seg      => out += seg
    }
    out.mkString("/")

  /** Cache path for a URL. */
  private def cachePath(url: String): os.Path =
    val u = java.net.URI.create(url).toURL
    val scheme    = u.getProtocol
    val port      = u.getPort
    val authority = if port < 0 then u.getHost else s"${u.getHost}:$port"
    val path      = u.getPath.stripPrefix("/")
    if path.isEmpty then cacheRoot / scheme / authority / "index.ssc"
    else cacheRoot / scheme / authority / os.SubPath(path)

  /** Fetch `url`, verify against `ssc.lock` when `lockPath` is present,
   *  write to disk cache, and update the lock file on first pin.
   *
   *  v1.19 lock semantics:
   *  - `lockPath = None`  → fetch freely (legacy / no-lock mode)
   *  - `lockPath = Some(p)` and lock file exists and URL not in it
   *                         → build error (run `ssc lock` first)
   *  - `lockPath = Some(p)` and lock file exists and URL in it
   *                         → fetch + verify SHA-256
   *  - `lockPath = Some(p)` and lock file absent
   *                         → fetch + write lock (first-time `ssc lock` call) */
  private[imports] def fetchToCache(url: String, lockPath: Option[os.Path] = None): os.Path =
    val out = cachePath(url)
    lockPath match
      case None =>
        // Legacy mode: fetch without lock enforcement
        if os.exists(out) then out
        else doFetch(url, out, lockPath = None)

      case Some(lp) =>
        LockFile.read(lp) match
          case scala.util.Failure(err) if os.exists(lp) =>
            // Lock file exists but unreadable
            throw new RuntimeException(s"Cannot read ssc.lock at $lp: ${err.getMessage}")
          case scala.util.Failure(_) =>
            // No lock file yet — first-time setup (only reached from `ssc lock`)
            if os.exists(out) then
              // Already cached — add to new lock file
              val content = os.read.bytes(out)
              val lock = LockFile.empty.pin(url, content)
              LockFile.write(lock, lp)
              out
            else doFetch(url, out, lockPath = Some(lp))
          case scala.util.Success(lock) =>
            lock.entries.get(url) match
              case None =>
                // URL not in lock — refuse (per v1.19 hard-no)
                throw new RuntimeException(
                  s"URL not in ssc.lock: $url\nRun `ssc lock` to add it."
                )
              case Some(_) =>
                // URL in lock — fetch (if not cached) then verify
                if !os.exists(out) then doFetch(url, out, lockPath = Some(lp))
                else
                  val content = os.read.bytes(out)
                  lock.check(url, content).fold(
                    err => throw new RuntimeException(err),
                    _   => out
                  )

  /** Perform the actual HTTP fetch, write to cache, optionally update lock. */
  private def doFetch(url: String, out: os.Path, lockPath: Option[os.Path]): os.Path =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      throw new RuntimeException(
        s"URL import not in cache and SSC_NO_NETWORK=1: $url"
      )
    val u    = java.net.URI.create(url).toURL
    val conn = u.openConnection
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(20_000)
    val in      = conn.getInputStream
    val content = try in.readAllBytes() finally in.close()
    os.makeDir.all(out / os.up)
    os.write(out, content)
    // Update / create lock file on fetch
    lockPath.foreach { lp =>
      val lock0 = LockFile.read(lp).getOrElse(LockFile.empty)
      val lock1 = lock0.pin(url, content)
      LockFile.write(lock1, lp)
    }
    out
