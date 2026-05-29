package scalascript.imports

import scalascript.backend.spi.{DepResolver, DepSpec}

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

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

  /** Root for library (non-relative) imports — the parent of `std/`.
   *  Set by the launcher as `-Dssc.lib.path=<dir>` (e.g. the project root).
   *  Falls back to `SSC_LIB_PATH` env var.  When absent, only relative and
   *  URL imports resolve; bare paths like `std/actors.ssc` fall through to
   *  the usual "file not found" error. */
  val libPath: Option[os.Path] =
    sys.props.get("ssc.lib.path")
      .orElse(sys.env.get("SSC_LIB_PATH"))
      .map(s => os.Path(s, os.pwd))
      .filter(os.exists)

  /** Separate root searched for stdlib imports (`std/foo.ssc`).
   *  Defaults to `libPath` so installed releases need no extra property.
   *  The dev launcher sets `-Dssc.std.path=<root>/runtime` so that
   *  `std/ui/primitives.ssc` resolves as `runtime/std/ui/primitives.ssc`
   *  without affecting the `bin/lib/jars/` lookup that uses `libPath`. */
  val stdPath: Option[os.Path] =
    sys.props.get("ssc.std.path")
      .map(s => os.Path(s, os.pwd))
      .filter(os.exists)
      .orElse(libPath)

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
    val depSpec = parseDepSpec(rawPath)
    if depSpec.raw.startsWith("github:") || depSpec.raw.startsWith("jitpack:") then
      return resolveExternalDep(depSpec)

    // 1. pkg: scheme — installed plugin packages
    if depSpec.raw.startsWith("pkg:") then
      return resolvePkg(depSpec.raw)

    // 2. dep: scheme — always resolved through dep-sources chain
    if depSpec.raw.startsWith("dep:") then
      if MavenDepResolver.isMavenCoordinate(depSpec.raw) then
        return resolveExternalDep(depSpec)
      return resolveDep(depSpec.raw, lockPath)

    val pathThroughDep = applyDeps(depSpec.raw, deps).getOrElse(depSpec.raw)
    val resolved =
      if isUrl(pathThroughDep) then fetchToCache(pathThroughDep, lockPath)
      else
        val local = baseDir / os.RelPath(pathThroughDep)
        if os.exists(local) then local
        else
          // Library fallback: bare paths like `std/actors.ssc` that don't
          // exist relative to the importing file are re-resolved against
          // ssc.std.path (dev: runtime/, installed: same as lib.path).
          // Secondary fallback: libPath/runtime/<path> covers the dev-tree
          // layout (runtime/std/…) when ssc.std.path is not set separately.
          val fromLib =
            stdPath.map(_ / os.RelPath(pathThroughDep)).filter(os.exists)
              .orElse(libPath.map(_ / "runtime" / os.RelPath(pathThroughDep)).filter(os.exists))
          fromLib.getOrElse(
            cacheBackedRelative(pathThroughDep, baseDir, lockPath).getOrElse(local)
          )
    // v0.9.1 — directory-as-index: `[Name](./pack)` and the path points to
    // a directory ⇒ look for `<dir>/index.ssc` inside.
    if os.exists(resolved) && os.isDir(resolved) then
      val index = resolved / "index.ssc"
      if os.exists(index) then index else resolved
    else resolved

  private def parseDepSpec(rawPath: String): DepSpec =
    val marker = " sha256:"
    val idx = rawPath.indexOf(marker)
    if idx < 0 then DepSpec(raw = rawPath)
    else
      val raw = rawPath.substring(0, idx).trim
      val sha = rawPath.substring(idx + marker.length).trim
      if sha.isEmpty then throw new RuntimeException(s"empty sha256 pin in import '$rawPath'")
      DepSpec(raw = raw, sha256 = Some(sha))

  private lazy val depResolvers: List[DepResolver] =
    val loaded = ServiceLoader.load(classOf[DepResolver]).iterator().asScala.toList
    (new GithubReleaseResolver :: new MavenDepResolver :: new JitpackResolver :: loaded)
      .groupBy(_.scheme).values.map(_.head).toList

  private def resolveExternalDep(spec: DepSpec): os.Path =
    val scheme = spec.raw.takeWhile(_ != ':')
    depResolvers.find(_.scheme == scheme) match
      case Some(resolver) => os.Path(resolver.resolve(spec))
      case None => throw new RuntimeException(s"no DepResolver registered for scheme '$scheme'")

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

  // ─── pkg: scheme ─────────────────────────────────────────────────

  /** Default directory where installed `.sscpkg` files live.
   *  Mirrors the `pluginsDir` constant in the CLI. */
  private val pkgPluginsDir: os.Path =
    os.home / ".scalascript" / "compiler" / "plugins"

  /** Resolve `pkg:org/name:version` to the entry-point `.ssc` extracted
   *  from the matching installed `.sscpkg` archive.
   *
   *  Resolution order:
   *  1. Search `~/.scalascript/compiler/plugins/` (and any dirs registered
   *     via `BackendRegistry.addPluginDir`) for a matching `.sscpkg`.
   *  2. If not found locally, look in `LocalRegistry` for a download URL
   *     and call `ssc plugin install` logic to fetch + install first.
   *  3. If still not found, throw a clear "not installed" error.
   *
   *  When a matching archive is found, `BackendRegistry.loadAndExtract`
   *  loads the intrinsics and unpacks the source `.ssc` files to a temp dir.
   *  The entry point is `index.ssc` if present, otherwise the first `.ssc`.
   *
   *  Additive: scripts that don't use `pkg:` are unaffected. */
  private def resolvePkg(pkgUri: String): os.Path =
    val coord = pkgUri.stripPrefix("pkg:")
    import scalascript.compiler.plugin.{BackendRegistry, LocalRegistry}

    // Try to find an already-installed .sscpkg.
    val maybePkg = BackendRegistry.findInstalledPkg(coord)

    val pkgPath = maybePkg.getOrElse {
      // Not installed locally — check the local registry for a URL.
      val entry = LocalRegistry.resolve(coord)
      entry match
        case None =>
          throw new RuntimeException(
            s"plugin '$coord' is not installed.\n" +
            s"Run: ssc install $coord"
          )
        case Some(e) =>
          // Download from the registry URL and install.
          val bytes = downloadBytes(e.url, coord)
          val tmp   = os.temp(bytes, suffix = ".sscpkg")
          val manifest =
            try scalascript.compiler.plugin.SscpkgLoader.load(tmp).manifest
            finally os.remove(tmp)
          os.makeDir.all(pkgPluginsDir)
          val dest = pkgPluginsDir / s"${manifest.id}-${manifest.version}.sscpkg"
          os.write.over(dest, bytes)
          dest
    }

    // Load the plugin (registers intrinsics) and extract sources.
    val srcDir = BackendRegistry.loadAndExtract(pkgPath)

    // Pick the entry-point .ssc file.
    if !os.isDir(srcDir) || os.list(srcDir).isEmpty then
      throw new RuntimeException(
        s"pkg '$coord': archive has no sources (resolved to $pkgPath)"
      )
    val index = srcDir / "index.ssc"
    if os.exists(index) then index
    else os.list(srcDir).filter(_.ext == "ssc").sorted.headOption.getOrElse(
      throw new RuntimeException(
        s"pkg '$coord': no .ssc source found in extracted archive $pkgPath"
      )
    )

  /** Download bytes from a URL, used when auto-installing a plugin on first use. */
  private def downloadBytes(url: String, label: String): Array[Byte] =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      throw new RuntimeException(
        s"Plugin '$label' is not installed and SSC_NO_NETWORK=1 blocks auto-download.\n" +
        s"Run: ssc install $label"
      )
    val req  = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build()
    val resp = java.net.http.HttpClient.newHttpClient()
      .send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
    if resp.statusCode() != 200 then
      throw new RuntimeException(
        s"pkg install: HTTP ${resp.statusCode()} downloading '$label' from $url\n" +
        s"Run: ssc install $label"
      )
    resp.body()

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

  // ─── v2.0 Phase 5 — pre-compiled artifact discovery ──────────────

  /** Look for a pre-compiled artifact next to a resolved source file.
   *
   *  Layout (auto-detected, no manifest schema change):
   *  {{{
   *    <dir>/<basename>.ssc              ← `sscPath`
   *    <dir>/.ssc-artifacts/<basename>.<ext>   ← returned
   *  }}}
   *
   *  Returns `Some(artifactPath)` if the artifact file exists, else `None`.
   *  Used by `compile-jvm` / `compile-js` to short-circuit dep parsing when
   *  a `.sscpkg` was built with `--with-artifacts` and a `.scim` / `.scjvm`
   *  / `.scjs` ships alongside the cached `.ssc`.
   *
   *  v2.0 Phase 5 — pre-compiled artifact distribution in `.sscpkg`. */
  def findArtifactAlongside(sscPath: os.Path, ext: String): Option[os.Path] =
    if !sscPath.last.endsWith(".ssc") then return None
    val baseName = sscPath.last.stripSuffix(".ssc")
    val candidate = sscPath / os.up / ".ssc-artifacts" / s"$baseName.$ext"
    if os.exists(candidate) then Some(candidate) else None

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
